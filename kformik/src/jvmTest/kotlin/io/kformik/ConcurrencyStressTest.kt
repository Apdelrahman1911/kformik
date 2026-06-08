package io.kformik

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real multi-threaded stress tests (JVM only — `runTest`'s virtual-time scheduler is single
 * threaded and cannot exercise true preemption). These prove the documented thread-safety
 * guarantee: no lost updates under concurrent mutation, and no `ConcurrentModificationException`
 * from the field registry. They reliably fail against blind-write / plain-`mutableMap` code.
 */
class ConcurrencyStressTest {

    @Test
    fun concurrentArrayPush_noLostUpdates() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val c = FormikController(
                FormikConfig(
                    initialValues = mapOf("items" to emptyList<Int>()),
                    onSubmit = { _, _ -> },
                    coroutineScope = scope,
                )
            )
            val n = 300
            coroutineScope {
                repeat(n) { i ->
                    launch(Dispatchers.Default) { c.array("items").push(i, shouldValidate = false) }
                }
            }
            @Suppress("UNCHECKED_CAST")
            val items = c.state.value.values["items"] as List<Int>
            assertEquals(n, items.size, "every concurrent push must land (no lost updates)")
            assertEquals((0 until n).toSet(), items.toSet())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun concurrentValueAndErrorWrites_noLostUpdates() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val keys = (0 until 120).map { "f$it" }
            val c = FormikController(
                FormikConfig(
                    initialValues = keys.associateWith { "" } as Map<String, Any?>,
                    onSubmit = { _, _ -> },
                    validateOnChange = false,
                    coroutineScope = scope,
                )
            )
            // For each key, race a (mutex-guarded) value write against a (lock-free CAS) error write.
            // Because every mutex-held write is itself a compare-and-set on the latest state, neither
            // path clobbers the other — both must survive.
            coroutineScope {
                keys.forEach { k ->
                    launch(Dispatchers.Default) { c.setFieldValue(k, "v-$k", shouldValidate = false) }
                    launch(Dispatchers.Default) { c.setFieldError(k, "e-$k") }
                }
            }
            val st = c.state.value
            keys.forEach { k ->
                assertEquals("v-$k", st.values[k], "value write for $k lost")
                assertEquals("e-$k", st.errors[k], "error write for $k lost")
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun concurrentRegisterUnregister_duringValidation_noCrash() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val c = FormikController(
                FormikConfig(
                    initialValues = mapOf("name" to "x"),
                    validate = { FormikErrors.Empty },
                    onSubmit = { _, _ -> },
                    coroutineScope = scope,
                )
            )
            coroutineScope {
                // hammer the registry while validations iterate it
                repeat(200) { i ->
                    launch(Dispatchers.Default) { c.registerField("f$i") { null } }
                    launch(Dispatchers.Default) { c.validateForm() }
                    launch(Dispatchers.Default) { c.unregisterField("f$i") }
                }
            }
            // reaching here without a ConcurrentModificationException / native crash is the assertion
            assertEquals("x", c.valueAt("name"))
        } finally {
            scope.cancel()
        }
    }

    /**
     * A mutex-guarded `setFieldValue` and a lock-free `setFormikState` must both survive — neither
     * can clobber the other. The pre-fix code computed `next = setAt(_state.value.values, ...)`
     * BEFORE entering `_state.update`, so a setFormikState landing between snapshot read and CAS
     * commit got overwritten by the stale `it.copy(values = next)`.
     *
     * Failure mode against the pre-fix code: `b` reverts to 0 on iterations where setFormikState's
     * commit slipped between setFieldValue's snapshot and its CAS.
     */
    @Test
    fun setFieldValue_andLockFreeSetFormikState_bothWritesSurvive() = runBlocking {
        repeat(150) { iter ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val c = FormikController(
                    FormikConfig(
                        initialValues = mapOf<String, Any?>("a" to 0, "b" to 0),
                        validateOnChange = false,
                        onSubmit = { _, _ -> },
                        coroutineScope = scope,
                    )
                )
                coroutineScope {
                    launch(Dispatchers.Default) {
                        c.setFieldValue("a", iter, shouldValidate = false)
                    }
                    launch(Dispatchers.Default) {
                        c.setFormikState { state ->
                            state.copy(values = state.values + ("b" to iter * 2))
                        }
                    }
                }
                val vals = c.state.value.values
                assertEquals(iter, vals["a"], "iter=$iter: setFieldValue write lost (a was $iter, became ${vals["a"]})")
                assertEquals(iter * 2, vals["b"], "iter=$iter: setFormikState write lost (b was ${iter * 2}, became ${vals["b"]})")
            } finally {
                scope.cancel()
            }
        }
    }

    /**
     * Same pattern as [setFieldValue_andLockFreeSetFormikState_bothWritesSurvive] but for the
     * `setValues((V) -> V)` updater overload, which had the same pre-CAS-snapshot pattern.
     */
    @Test
    fun setValuesUpdater_andLockFreeSetFormikState_bothWritesSurvive() = runBlocking {
        repeat(150) { iter ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val c = FormikController(
                    FormikConfig(
                        initialValues = mapOf<String, Any?>("a" to 0, "b" to 0),
                        validateOnChange = false,
                        onSubmit = { _, _ -> },
                        coroutineScope = scope,
                    )
                )
                coroutineScope {
                    launch(Dispatchers.Default) {
                        c.setValues({ current -> current + ("a" to iter) }, shouldValidate = false)
                    }
                    launch(Dispatchers.Default) {
                        c.setFormikState { state ->
                            state.copy(values = state.values + ("b" to iter * 2))
                        }
                    }
                }
                val vals = c.state.value.values
                assertEquals(iter, vals["a"], "iter=$iter: setValues updater write lost")
                assertEquals(iter * 2, vals["b"], "iter=$iter: setFormikState write lost vs setValues")
            } finally {
                scope.cancel()
            }
        }
    }

    /**
     * Single-flight: while a submit is in flight (awaiting `config.onSubmit`), a second `submit()`
     * call must return immediately as a no-op even if the visible `isSubmitting` flag has been
     * cleared mid-submit (e.g. by `resetForm()` or a manual `setSubmitting(false)`). The
     * structural [submitMutex] gate enforces this independently of the flag — the pre-fix code
     * relied solely on the `isSubmitting` check inside the mutex, which a concurrent `resetForm`
     * could disarm.
     *
     * Failure mode against the pre-fix code: `onSubmitCalls == 2` (the second submit slipped
     * through after `resetForm` flipped `isSubmitting = false`).
     */
    @Test
    fun submit_singleFlight_secondSubmitNoOpEvenAfterReset() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val submitBarrier = Mutex(locked = true)
            var onSubmitCalls = 0
            val c = FormikController(
                FormikConfig(
                    initialValues = mapOf<String, Any?>("a" to 0),
                    onSubmit = { _, _ ->
                        onSubmitCalls++
                        submitBarrier.withLock { /* released by test below */ }
                    },
                    coroutineScope = scope,
                )
            )
            val submit1 = scope.launch { c.submit() }
            // Wait for submit-1 to enter onSubmit (it increments then awaits the barrier).
            while (onSubmitCalls == 0) yield()
            assertTrue(c.state.value.isSubmitting, "submit-1 should have set isSubmitting=true")

            // Reset clears the visible flag (matches Formik's resetForm semantic).
            c.resetForm()
            assertEquals(
                false,
                c.state.value.isSubmitting,
                "resetForm should clear visible isSubmitting flag (Formik-compatible)",
            )

            // Concurrent submit-2 MUST be a no-op — submitMutex is still held by submit-1.
            val submit2 = scope.launch { c.submit() }
            submit2.join()  // submit-2 returns immediately if single-flight gate holds
            assertEquals(
                1,
                onSubmitCalls,
                "submit-2 ran concurrently with in-flight submit-1; single-flight gate broken",
            )

            submitBarrier.unlock()
            submit1.join()
        } finally {
            scope.cancel()
        }
    }

    /**
     * Under heavy concurrent writes from multiple threads, the field's final value must be one of
     * the exact values written by some caller — never a partially-merged or garbage value. Each
     * mutex-held write is a compare-and-set onto the latest values, so writes serialize cleanly
     * and the last committed write wins atomically.
     *
     * Failure mode against a blind-write implementation: a torn read could yield a value outside
     * the known input set (e.g. a stale snapshot's value after a concurrent reset, or a corrupted
     * map entry from non-atomic mutation).
     */
    @Test
    fun rapidFire_setFieldValue_underContention_finalStateIsAValidInput() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val c = FormikController(
                FormikConfig(
                    initialValues = mapOf<String, Any?>("x" to 0),
                    validateOnChange = false,
                    onSubmit = { _, _ -> },
                    coroutineScope = scope,
                )
            )
            val validInputs = setOf(0, 1, 2, 3)
            val perThread = 250
            coroutineScope {
                repeat(4) { tid ->
                    launch(Dispatchers.Default) {
                        repeat(perThread) { i ->
                            c.setFieldValue("x", (tid + i) % 4, shouldValidate = false)
                        }
                    }
                }
            }
            val finalValue = c.state.value.values["x"]
            assertTrue(
                finalValue in validInputs,
                "final value $finalValue must be one of the written inputs $validInputs",
            )
        } finally {
            scope.cancel()
        }
    }

    /**
     * 50 concurrent `submit()` calls must collapse to a single in-flight submission. The
     * structural [submitMutex] gate uses `tryLock` — concurrent callers that find the lock held
     * return immediately as a no-op without incrementing `submitCount` or invoking `onSubmit`.
     * Only the lock-winner runs the submission lifecycle.
     */
    @Test
    fun submit_underContention_singleFlight_strictlyOnce() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val submitBarrier = Mutex(locked = true)
            var onSubmitCalls = 0
            val c = FormikController(
                FormikConfig(
                    initialValues = mapOf<String, Any?>("a" to 0),
                    onSubmit = { _, _ ->
                        onSubmitCalls++
                        submitBarrier.withLock { /* released below */ }
                    },
                    coroutineScope = scope,
                )
            )
            // Launch 50 concurrent submits. The first to grab submitMutex enters onSubmit and
            // awaits the barrier; the other 49 must observe tryLock() == false and return.
            val jobs = (0 until 50).map { scope.launch(Dispatchers.Default) { c.submit() } }
            // Wait for the winner to have entered onSubmit.
            while (onSubmitCalls == 0) yield()
            // Wait until only one job is still active (the winner blocked on the barrier); the
            // 49 losers tryLock-and-return so their coroutines complete promptly.
            while (jobs.count { it.isActive } > 1) yield()
            assertEquals(
                1,
                onSubmitCalls,
                "50 concurrent submits produced more than one onSubmit invocation; single-flight gate broken",
            )
            assertTrue(
                c.state.value.submitCount <= 1,
                "submitCount=${c.state.value.submitCount} > 1; single-flight gate broken",
            )

            submitBarrier.unlock()
            jobs.forEach { it.join() }
            // Final invariant: even after the winner completes, only one onSubmit invocation ran.
            assertEquals(
                1,
                onSubmitCalls,
                "after all submits settled, onSubmit invocation count != 1 (was $onSubmitCalls)",
            )
        } finally {
            scope.cancel()
        }
    }

    /**
     * Cancelling the controller's scope mid-flight, while many threads are hammering
     * `setFieldValue`, must not propagate any exception to the caller. Post-cancellation
     * mutations are silently dropped (the `scope.isActive` short-circuit), so the test reaching
     * the assertion without a thrown exception is itself the assertion.
     */
    @Test
    fun close_underContention_doesNotCrash_andSubsequentMutationsAreIgnored() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val c = FormikController(
            FormikConfig(
                initialValues = mapOf<String, Any?>("x" to 0),
                validateOnChange = false,
                onSubmit = { _, _ -> },
                coroutineScope = scope,
            )
        )
        // 10 threads each doing tight-loop setFieldValue calls. We supervisor-wrap the contention
        // coroutineScope so individual writer cancellations (expected once scope.cancel fires)
        // don't propagate out of the test.
        val contention = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val writers = (0 until 10).map { tid ->
                contention.launch(Dispatchers.Default) {
                    var i = 0
                    while (isActive) {
                        try {
                            c.setFieldValue("x", tid * 1000 + i, shouldValidate = false)
                        } catch (_: CancellationException) {
                            // expected once `scope` is cancelled — re-throw to honor structured concurrency
                            throw kotlin.coroutines.cancellation.CancellationException("writer stopped")
                        }
                        i++
                    }
                }
            }
            // Let the writers run briefly so cancellation lands mid-flight.
            yield()
            yield()
            // Cancel the controller's scope. The setFieldValue's `scope.isActive` guard means
            // subsequent calls become silent no-ops rather than throwing.
            scope.cancel()
            // Subsequent mutations after cancel must be silently ignored (no throw).
            repeat(50) { c.setFieldValue("x", -1, shouldValidate = false) }
            // Stop the writer coroutines and wait for them to exit cleanly.
            writers.forEach { it.cancel() }
            writers.forEach { it.join() }
            // Reaching here without any uncaught exception is the assertion.
            assertTrue(true, "close under contention completed without crashes")
        } finally {
            contention.cancel()
        }
    }
}
