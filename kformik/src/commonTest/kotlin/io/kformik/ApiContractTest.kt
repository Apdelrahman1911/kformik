package io.kformik

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Documentation-driven contract tests for the public mutator surface on [FormikController] /
 * [FormikActions]. Each test pins the return-shape or observable side-effect promised by the
 * KDoc of the function under test.
 *
 * Coverage already provided by sibling suites — NOT duplicated here:
 *  - `setFieldValue(updater)` read-modify-write form: [FormikControllerTest.setValues_withUpdater_appliesAgainstCurrent]
 *    exercises the same mutex path on the mirror `setValues(updater)` API.
 *  - `setFormikState` escape-hatch whole-state replacement: [FormikControllerTest.setFormikState_isAnEscapeHatch].
 *  - `validateField` returns null for an unknown path: `FormSchemaTest.controller_validateField_returnsNullForUnknownPath`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApiContractTest {

    private fun ctrl(
        scope: TestScope,
        initialValues: Map<String, Any?> = mapOf("name" to "jared", "age" to 30),
        onSubmit: FormikSubmitHandler<Map<String, Any?>> = { _, _ -> },
    ) = FormikController(
        FormikConfig(
            initialValues = initialValues,
            onSubmit = onSubmit,
            coroutineScope = scope,
        )
    )

    /** Setting a field to its current value is a no-op against the public contract — no throw. */
    @Test
    fun setFieldValue_returnsUnit_evenWhenNoChange() = runTest {
        val c = ctrl(this)
        c.setFieldValue("name", "jared", shouldValidate = false)
        c.setFieldValue("name", "jared", shouldValidate = false)
        assertEquals("jared", c.state.value.values["name"])
    }

    /** KDoc: explicit `false` clears the flag (touched is a plain map write, not "set-once"). */
    @Test
    fun setFieldTouched_explicit_false_clearsTheFlag() = runTest {
        val c = ctrl(this)
        c.setFieldTouched("name", true, shouldValidate = false)
        assertTrue(c.state.value.touched["name"])
        c.setFieldTouched("name", false, shouldValidate = false)
        assertFalse(c.state.value.touched["name"])
        assertTrue(c.state.value.touched.contains("name"))
    }

    /** KDoc on `setFieldError`: null clears, non-null stores — including the empty string. */
    @Test
    fun setFieldError_nullClears_emptyStringStores() = runTest {
        val c = ctrl(this)
        c.setFieldError("name", "msg")
        c.setFieldError("name", null)
        assertNull(c.state.value.errors["name"])
        assertFalse(c.state.value.errors.contains("name"))
        c.setFieldError("name", "")
        assertEquals("", c.state.value.errors["name"])
        assertTrue(c.state.value.errors.contains("name"))
    }

    /** KDoc on `setErrors`: replaces the entire errors map, does not merge. */
    @Test
    fun setErrors_overwritesAllErrors() = runTest {
        val c = ctrl(this)
        c.setErrors(buildErrors { put("x", "a") })
        c.setErrors(buildErrors { put("y", "b") })
        assertNull(c.state.value.errors["x"])
        assertEquals("b", c.state.value.errors["y"])
        assertEquals(1, c.state.value.errors.size)
    }

    /** `status` is freeform `Any?` — the controller round-trips whatever the caller hands it. */
    @Test
    fun setStatus_anyTypeAccepted() = runTest {
        val c = ctrl(this)
        c.setStatus(42); assertEquals(42, c.state.value.status)
        c.setStatus("hello"); assertEquals("hello", c.state.value.status)
        c.setStatus(listOf(1, 2, 3)); assertEquals(listOf(1, 2, 3), c.state.value.status)
        data class Custom(val ok: Boolean)
        c.setStatus(Custom(true)); assertEquals(Custom(true), c.state.value.status)
        c.setStatus(null); assertNull(c.state.value.status)
    }

    /**
     * KDoc on `submit`: the documented single-flight gate is the structural `submitMutex`, NOT
     * the `isSubmitting` flag. Flipping `isSubmitting = true` manually does NOT block `submit()`.
     */
    @Test
    fun setSubmitting_doesNotBlockSubmit_gateIsStructural() = runTest {
        var called = 0
        val c = ctrl(this, onSubmit = { _, _ -> called++ })
        c.setSubmitting(true)
        assertTrue(c.state.value.isSubmitting)
        c.submit()
        assertEquals(1, called)
        assertFalse(c.state.value.isSubmitting)
    }

    /** KDoc on `submit`: it is `suspend Unit` — returns normally when validation+onSubmit pass. */
    @Test
    fun submit_returnsUnit_isFireAndForget_atControllerLevel() = runTest {
        val c = ctrl(this)
        val result: Unit = c.submit()
        assertEquals(Unit, result)
        assertEquals(1, c.state.value.submitCount)
    }

    /**
     * KDoc on `resetForm`: clears values/errors/touched/status back to baseline, resets
     * submitCount/isSubmitting/isValidating. One assertion per slice — the documented checklist.
     */
    @Test
    fun resetForm_clearsAllState_andResetsSubmitCount() = runTest {
        val c = ctrl(this)
        c.setFieldValue("name", "ian", shouldValidate = false)
        c.setFieldTouched("name", true, shouldValidate = false)
        c.setFieldError("name", "Required")
        c.setStatus("dirty")
        c.setSubmitting(true)
        c.submit()
        c.resetForm()
        val s = c.state.value
        assertEquals(mapOf("name" to "jared", "age" to 30), s.values)
        assertEquals(FormikErrors.Empty, s.errors)
        assertEquals(FormikTouched.Empty, s.touched)
        assertNull(s.status)
        assertFalse(s.isSubmitting)
        assertFalse(s.isValidating)
        assertEquals(0, s.submitCount)
        assertFalse(c.dirty.value)
    }

    /** KDoc on `state`: it is a [kotlinx.coroutines.flow.StateFlow] — `.first()` returns immediately. */
    @Test
    fun state_isStateFlow_emitsImmediatelyOnSubscribe() = runTest {
        val c = ctrl(this)
        val snapshot = c.state.first()
        assertNotNull(snapshot)
        assertEquals("jared", snapshot.values["name"])
        assertEquals(snapshot, c.state.value)
    }

    /** `dirty` is documented as a [kotlinx.coroutines.flow.StateFlow]; `.first()` returns now. */
    @Test
    fun dirty_isStateFlow() = runTest {
        val c = ctrl(this)
        assertFalse(c.dirty.first())
        c.setFieldValue("name", "ian", shouldValidate = false)
        assertTrue(c.dirty.value)
    }

    /** `isValid` is documented as a [kotlinx.coroutines.flow.StateFlow]; `.first()` returns now. */
    @Test
    fun isValid_isStateFlow() = runTest {
        val c = ctrl(this)
        assertTrue(c.isValid.first())
        c.setErrors(buildErrors { put("name", "Required") })
        assertFalse(c.isValid.value)
    }

    /** `FormikErrors.with` KDoc: "Returns a copy" — original instance is unchanged. */
    @Test
    fun FormikErrors_isImmutable_returnsNew_onWith_call() {
        val empty = FormikErrors.Empty
        val withA = empty.with("a", "x")
        assertTrue(empty.isEmpty)
        assertEquals(0, empty.size)
        assertEquals("x", withA["a"])
        assertEquals(1, withA.size)
        val cleared = withA.with("a", null)
        assertEquals("x", withA["a"])
        assertTrue(cleared.isEmpty)
    }

    /** Same immutability contract as [FormikErrors] — `with` returns a fresh instance. */
    @Test
    fun FormikTouched_isImmutable_returnsNew_onWith_call() {
        val empty = FormikTouched.Empty
        val withA = empty.with("a", true)
        assertTrue(empty.isEmpty)
        assertEquals(0, empty.size)
        assertTrue(withA["a"])
        assertEquals(1, withA.size)
    }

    /** KDoc on `validateForm`: returns the merged errors; non-empty errors means invalid. */
    @Test
    fun validateForm_returnsErrors_andReflectsValidity() = runTest {
        val c = FormikController(
            FormikConfig(
                initialValues = mapOf("name" to ""),
                validate = { v ->
                    buildErrors { if ((v["name"] as String).isBlank()) put("name", "Required") }
                },
                onSubmit = { _, _ -> },
                coroutineScope = this,
            )
        )
        val errs = c.validateForm()
        assertEquals("Required", errs["name"])
        assertTrue(errs.isNotEmpty)
        assertFalse(c.isValid.value)
    }
}
