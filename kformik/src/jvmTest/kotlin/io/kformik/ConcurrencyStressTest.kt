package io.kformik

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
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
}
