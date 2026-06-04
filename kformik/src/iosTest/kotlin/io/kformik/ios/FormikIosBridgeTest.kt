package io.kformik.ios

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct iOS-target tests for [FormikIosBridge].
 *
 * These run on `iosX64` / `iosArm64` / `iosSimulatorArm64` via the `iosTest` source set. They
 * exercise the bridge's Swift-facing API surface using Kotlin (because we don't have a Swift test
 * runner in this repo; the Swift-side `SwiftUI` consumer would call these same methods).
 *
 * Notes:
 *  - The bridge defaults to `MainScope()` on iOS. In tests we pass an explicit scope on
 *    [Dispatchers.Unconfined] so callbacks fire synchronously and we don't need a main-thread
 *    runloop.
 *  - `runTest` uses a virtual scheduler; bridge work runs on the unconfined scope, so we yield()
 *    or `delay()` to let observers fire.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FormikIosBridgeTest {

    private val scopes = mutableListOf<CoroutineScope>()
    private val bridges = mutableListOf<FormikIosBridge>()

    private fun newBridge(
        initial: Map<String, Any?> = mapOf("email" to "", "password" to ""),
        validate: ((Map<String, Any?>) -> Map<String, String>)? = null,
        onSubmit: suspend (Map<String, Any?>, io.kformik.FormikActions<Map<String, Any?>>) -> Unit = { _, _ -> },
    ): FormikIosBridge {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scopes += scope
        val bridge = FormikIosBridge.create(
            initialValues = initial,
            validate = validate,
            onSubmit = onSubmit,
            mainScope = scope,
            // No main runloop in the test target; deliver observe callbacks synchronously.
            callbackDispatcher = Dispatchers.Unconfined,
        )
        bridges += bridge
        return bridge
    }

    @AfterTest
    fun cleanup() {
        bridges.forEach { runCatching { it.close() } }
        scopes.forEach { runCatching { (it.coroutineContext[Job])?.cancel() } }
        bridges.clear()
        scopes.clear()
    }

    // ---------------------------------------------------------------------- create + snapshot

    @Test
    fun create_buildsBridge_withInitialState() = runTest {
        val b = newBridge()
        val s = b.snapshot()
        assertEquals("", s.value("email"))
        assertEquals("", s.value("password"))
        assertFalse(s.isSubmitting())
        assertFalse(s.isValidating())
        assertEquals(0, s.submitCount())
        assertNull(s.status())
        assertTrue(s.errors().isEmpty())
        assertTrue(s.touched().isEmpty())
    }

    @Test
    fun snapshot_isImmutableSliceAtCallTime() = runTest {
        val b = newBridge()
        val s1 = b.snapshot()
        b.setFieldValue("email", "ian@example.com")
        yield()
        val s2 = b.snapshot()
        assertEquals("", s1.value("email"), "old snapshot unchanged")
        assertEquals("ian@example.com", s2.value("email"))
    }

    @Test
    fun values_errors_touched_mapsAreExposed() = runTest {
        val b = newBridge(initial = mapOf("email" to "x"))
        // shouldValidate=false to avoid the empty-validator pipeline overwriting our manual error
        b.setFieldTouched("email", true, shouldValidate = false)
        b.setFieldError("email", "bad")
        yield()
        val s = b.snapshot()
        assertEquals("x", s.values()["email"])
        assertEquals("bad", s.errors()["email"])
        assertEquals(true, s.touched()["email"])
    }

    // ---------------------------------------------------------------------- accessors

    @Test
    fun value_error_touched_displayError_singleField() = runTest {
        val b = newBridge()
        // Touch first (with no validation, to keep the manual error in place),
        // then set the error. Order matters because validate-on-blur would otherwise wipe it.
        b.setFieldTouched("email", true, shouldValidate = false)
        b.setFieldError("email", "Required")
        yield()
        val s = b.snapshot()
        assertEquals("Required", s.error("email"))
        assertTrue(s.isTouched("email"))
        assertEquals("Required", s.displayError("email"))
    }

    @Test
    fun displayError_isNullUntilTouched() = runTest {
        val b = newBridge()
        b.setFieldError("email", "Required")
        yield()
        assertNull(b.snapshot().displayError("email"))
    }

    // ---------------------------------------------------------------------- setters

    @Test
    fun setFieldValue_updatesValue() = runTest {
        val b = newBridge()
        b.setFieldValue("email", "ian@example.com")
        yield()
        assertEquals("ian@example.com", b.snapshot().value("email"))
    }

    @Test
    fun setFieldTouched_setsTouched() = runTest {
        val b = newBridge()
        b.setFieldTouched("email", true)
        yield()
        assertTrue(b.snapshot().isTouched("email"))
    }

    @Test
    fun setFieldError_setError_then_clear() = runTest {
        val b = newBridge()
        b.setFieldError("email", "boom")
        yield()
        assertEquals("boom", b.snapshot().error("email"))
        b.setFieldError("email", null)
        yield()
        assertNull(b.snapshot().error("email"))
    }

    @Test
    fun setStatus_setsStatus() = runTest {
        val b = newBridge()
        b.setStatus("welcome")
        yield()
        assertEquals("welcome", b.snapshot().status())
    }

    @Test
    fun setSubmitting_setsFlag() = runTest {
        val b = newBridge()
        b.setSubmitting(true)
        yield()
        assertTrue(b.snapshot().isSubmitting())
    }

    // ---------------------------------------------------------------------- observe

    @Test
    fun observe_firesInitialSnapshot_andOnEachChange() = runTest {
        val b = newBridge()
        val seen = mutableListOf<String?>()
        val sub = b.observe { snap -> seen += snap.value("email") as? String }

        // initial emission
        yield()
        assertTrue(seen.isNotEmpty())
        assertEquals("", seen.last())

        b.setFieldValue("email", "a")
        yield()
        b.setFieldValue("email", "ab")
        yield()
        b.setFieldValue("email", "abc")
        yield()

        assertTrue(seen.contains("a"))
        assertTrue(seen.contains("ab"))
        assertTrue(seen.contains("abc"))
        sub.cancel()
    }

    @Test
    fun observe_cancel_stopsCallbacks() = runTest {
        val b = newBridge()
        var calls = 0
        val sub = b.observe { calls++ }
        yield()
        val baseline = calls
        sub.cancel()
        // Wait for cancellation to propagate
        yield()
        assertFalse(sub.isActive)

        b.setFieldValue("email", "after-cancel")
        yield()
        yield()
        // No new callbacks should fire (calls stays at baseline).
        assertEquals(baseline, calls)
    }

    @Test
    fun close_cancelsAllObservers_andDisposesController() = runTest {
        val b = newBridge()
        val sub = b.observe { /* no-op */ }
        yield()
        assertTrue(sub.isActive)
        b.close()
        yield()
        assertFalse(sub.isActive)
        // Subsequent setters are no-ops (the bridge's internal scope is cancelled)
        b.setFieldValue("email", "should-be-dropped")
        yield()
        assertEquals("", b.snapshot().value("email"))
    }

    /**
     * v1.8.1 regression: `close()` cancels bridge-launched coroutines (observers, setter
     * launches) but DOES NOT cancel a caller-provided `mainScope` — caller-owned scopes follow
     * the caller's lifecycle. Pre-1.8.1 the bridge unconditionally called `scope.cancel()` in
     * `close()`, tearing down whatever else the caller had launched on the same scope.
     */
    @Test
    fun close_doesNotCancelCallerProvidedScope_butDoesCancelObserver() = runTest {
        val callerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scopes += callerScope
        val b = FormikIosBridge.create(
            initialValues = mapOf("email" to ""),
            onSubmit = { _, _ -> },
            mainScope = callerScope,
            callbackDispatcher = Dispatchers.Unconfined,
        )
        bridges += b
        val sub = b.observe { /* no-op */ }
        yield()
        assertTrue(sub.isActive)

        b.close()
        yield()

        // Observer subscription IS cancelled (bridge-launched work).
        assertFalse(sub.isActive, "bridge.close() must cancel observer subscriptions")
        // Caller's scope is NOT cancelled — they own it.
        assertTrue(callerScope.coroutineContext[Job]!!.isActive, "bridge.close() must NOT cancel a caller-provided scope")
    }

    /**
     * v1.8.1 regression: `StateSnapshot.value(name)` routes through `MapValuesUpdater.getAt` so
     * nested-path lookups (`"user.address.city"`) resolve correctly. Pre-1.8.1 the snapshot did
     * a flat `state.values[name]` map lookup, silently returning `null` for any nested key — the
     * documented Swift `"user.email"` usage pattern was broken.
     */
    @Test
    fun snapshot_value_resolvesNestedPaths() = runTest {
        val b = newBridge(
            initial = mapOf<String, Any?>(
                "user" to mapOf<String, Any?>(
                    "name" to "Aisha",
                    "address" to mapOf<String, Any?>("city" to "Cairo"),
                ),
                "items" to listOf("a", "b", "c"),
            ),
        )
        val s = b.snapshot()
        assertEquals("Aisha", s.value("user.name"), "flat-nested path")
        assertEquals("Cairo", s.value("user.address.city"), "double-nested path")
        assertEquals("b", s.value("items[1]"), "list-index path")
        assertEquals("Aisha", s.value("user.name"), "repeat call returns same value")
    }

    @Test
    fun snapshot_value_emptyPath_returnsNull_doesNotCrash() = runTest {
        // Swift consumers binding @State var name = "" to bridge.snapshot().value(name) would
        // crash the iOS process before this fix because MapValuesUpdater.getAt rejects empty
        // paths with IllegalArgumentException. Regression coverage for the v1.9.0 final-review
        // finding "value-empty-path-crash".
        val b = newBridge(initial = mapOf<String, Any?>("email" to "x@y.com"))
        val s = b.snapshot()
        assertNull(s.value(""), "empty path resolves to null instead of throwing")
        assertNull(s.value("."), "single-dot path resolves to null instead of throwing")
        assertNull(s.value("[]"), "empty bracket path resolves to null instead of throwing")
        // Non-empty unknown paths still resolve to null (no regression for the happy path).
        assertNull(s.value("missing"), "unknown flat path still resolves to null")
        // The valid lookup still works.
        assertEquals("x@y.com", s.value("email"))
    }

    // ---------------------------------------------------------------------- validation

    @Test
    fun validate_runsOnChange_andSurfacesErrors() = runTest {
        val b = newBridge(validate = { v ->
            val errs = mutableMapOf<String, String>()
            if ((v["email"] as String).isBlank()) errs["email"] = "Email required"
            errs
        })

        b.setFieldValue("email", "")
        yield()
        assertEquals("Email required", b.snapshot().error("email"))

        b.setFieldValue("email", "x@y.com")
        yield()
        assertNull(b.snapshot().error("email"))
    }

    // ---------------------------------------------------------------------- submit

    @Test
    fun submit_callsOnSubmit_andClearsIsSubmitting_whenValid() = runTest {
        var called = 0
        val b = newBridge(onSubmit = { _, _ -> called++ })
        b.submit()
        yield()
        // submit() is fire-and-forget via handleSubmit; let the scheduler drain.
        testScheduler.advanceUntilIdle()
        assertEquals(1, called)
        assertEquals(1, b.snapshot().submitCount())
        assertFalse(b.snapshot().isSubmitting())
    }

    @Test
    fun submit_blockedByValidation_doesNotCallOnSubmit_butIncrementsCount() = runTest {
        var called = 0
        val b = newBridge(
            validate = { mapOf("email" to "Required") },
            onSubmit = { _, _ -> called++ },
        )
        b.submit()
        yield()
        testScheduler.advanceUntilIdle()
        assertEquals(0, called)
        assertEquals(1, b.snapshot().submitCount())
        assertEquals("Required", b.snapshot().error("email"))
    }

    @Test
    fun submit_setsTouchedOnEveryLeaf() = runTest {
        val b = newBridge(initial = mapOf("email" to "", "password" to ""))
        b.submit()
        yield()
        testScheduler.advanceUntilIdle()
        val touched = b.snapshot().touched()
        assertTrue(touched["email"] == true)
        assertTrue(touched["password"] == true)
    }

    // ---------------------------------------------------------------------- reset

    @Test
    fun resetForm_restoresInitial() = runTest {
        val b = newBridge()
        b.setFieldValue("email", "x@y.com")
        b.setFieldTouched("email", true)
        b.setFieldError("email", "bad")
        b.setStatus("oops")
        yield()

        b.resetForm()
        yield()
        testScheduler.advanceUntilIdle()

        val s = b.snapshot()
        assertEquals("", s.value("email"))
        assertFalse(s.isTouched("email"))
        assertNull(s.error("email"))
        assertNull(s.status())
        assertEquals(0, s.submitCount())
    }

    // ---------------------------------------------------------------------- async observer

    @Test
    fun multipleObservers_allReceiveUpdates() = runTest {
        val b = newBridge()
        val seenA = mutableListOf<String?>()
        val seenB = mutableListOf<String?>()
        b.observe { seenA += it.value("email") as? String }
        b.observe { seenB += it.value("email") as? String }
        yield()

        b.setFieldValue("email", "hello")
        yield()

        assertTrue(seenA.contains("hello"))
        assertTrue(seenB.contains("hello"))
    }

    // ---------------------------------------------------------------------- dirty / valid / createSimple

    @Test
    fun snapshot_exposesDirtyAndValid() = runTest {
        val b = newBridge(
            initial = mapOf("email" to "x@y.com"),
            validate = { v -> if ((v["email"] as String).isBlank()) mapOf("email" to "Required") else emptyMap() },
        )
        // pristine + no errors
        assertFalse(b.snapshot().isDirty())
        assertTrue(b.snapshot().isValid())

        b.setFieldValue("email", "")
        yield()
        // changed from baseline, and now invalid
        assertTrue(b.snapshot().isDirty())
        assertFalse(b.snapshot().isValid())
    }

    @Test
    fun createSimple_nonSuspendOnSubmit_works() = runTest {
        var submitted: Map<String, Any?>? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scopes += scope
        val b = FormikIosBridge.createSimple(
            initialValues = mapOf("email" to "a@b.com"),
            onSubmit = { values -> submitted = values },
            mainScope = scope,
            callbackDispatcher = Dispatchers.Unconfined,
        )
        bridges += b
        b.submit()
        yield()
        testScheduler.advanceUntilIdle()
        assertEquals("a@b.com", submitted?.get("email"))
    }

    @Test
    fun observe_handlesDelayedValidation() = runTest {
        val b = newBridge(validate = { v ->
            // Synchronous in this signature, but observers still see the validating flip.
            if ((v["email"] as String).isBlank()) mapOf("email" to "Required") else emptyMap()
        })
        var lastEmailError: String? = null
        b.observe { lastEmailError = it.error("email") }
        b.setFieldValue("email", "")
        yield()
        delay(1)
        assertEquals("Required", lastEmailError)
    }
}
