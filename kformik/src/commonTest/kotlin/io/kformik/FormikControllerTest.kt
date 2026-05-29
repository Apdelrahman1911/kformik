package io.kformik

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Initial state and basic setter tests. Mirrors Formik's `Formik.test.tsx` "should initialize Formik
 * state and pass down props" + the `FormikHelpers` block.
 */
class FormikControllerTest {

    private fun controller(
        initialValues: Map<String, Any?> = mapOf("name" to "jared", "age" to 30),
        validate: (suspend (Map<String, Any?>) -> FormikErrors)? = null,
        onSubmit: FormikSubmitHandler<Map<String, Any?>> = { _, _ -> },
        validateOnChange: Boolean = true,
        validateOnBlur: Boolean = true,
        validateOnMount: Boolean = false,
        scope: TestScope,
    ): FormikController<Map<String, Any?>> {
        return FormikController(
            FormikConfig(
                initialValues = initialValues,
                validate = validate,
                onSubmit = onSubmit,
                validateOnChange = validateOnChange,
                validateOnBlur = validateOnBlur,
                validateOnMount = validateOnMount,
                coroutineScope = scope,
            )
        )
    }

    @Test
    fun initialState_matchesConfig() = runTest {
        val c = controller(scope = this)

        val s = c.state.value
        assertEquals(mapOf("name" to "jared", "age" to 30), s.values)
        assertEquals(FormikErrors.Empty, s.errors)
        assertEquals(FormikTouched.Empty, s.touched)
        assertNull(s.status)
        assertFalse(s.isSubmitting)
        assertFalse(s.isValidating)
        assertEquals(0, s.submitCount)
        assertFalse(c.dirty.value)
        assertTrue(c.isValid.value)
    }

    @Test
    fun setFieldValue_updatesValueAtPath() = runTest {
        val c = controller(scope = this)
        c.setFieldValue("name", "ian")
        assertEquals("ian", c.state.value.values["name"])
    }

    @Test
    fun setFieldValue_supportsNestedPaths() = runTest {
        val c = controller(
            initialValues = mapOf("user" to mapOf("name" to "")),
            scope = this,
        )
        c.setFieldValue("user.name", "ian")
        assertEquals("ian", c.valueAt("user.name"))
    }

    @Test
    fun setValues_updatesEntireMap() = runTest {
        val c = controller(scope = this)
        c.setValues(mapOf("name" to "ian", "age" to 25))
        assertEquals(mapOf("name" to "ian", "age" to 25), c.state.value.values)
    }

    @Test
    fun setValues_withUpdater_appliesAgainstCurrent() = runTest {
        val c = controller(scope = this)
        c.setValues({ cur -> cur + ("age" to 80) })
        assertEquals("jared", c.state.value.values["name"])
        assertEquals(80, c.state.value.values["age"])
    }

    @Test
    fun setFieldTouched_setsTouchedFlag() = runTest {
        val c = controller(scope = this)
        c.setFieldTouched("name", true)
        assertTrue(c.state.value.touched["name"])
    }

    @Test
    fun setTouched_overwritesAllTouched() = runTest {
        val c = controller(scope = this)
        c.setTouched(FormikTouched(mapOf("name" to true, "age" to true)))
        assertTrue(c.state.value.touched["name"])
        assertTrue(c.state.value.touched["age"])
    }

    @Test
    fun setFieldError_setsError() = runTest {
        val c = controller(scope = this)
        c.setFieldError("name", "Required")
        assertEquals("Required", c.state.value.errors["name"])
    }

    @Test
    fun setFieldError_nullClearsError() = runTest {
        val c = controller(scope = this)
        c.setFieldError("name", "Required")
        c.setFieldError("name", null)
        assertNull(c.state.value.errors["name"])
    }

    @Test
    fun setErrors_overwritesAllErrors() = runTest {
        val c = controller(scope = this)
        c.setErrors(FormikErrors(mapOf("name" to "Required")))
        assertEquals("Required", c.state.value.errors["name"])
    }

    @Test
    fun setStatus_setsStatus() = runTest {
        val c = controller(scope = this)
        c.setStatus("submitted")
        assertEquals("submitted", c.state.value.status)
    }

    @Test
    fun setSubmitting_setsFlag() = runTest {
        val c = controller(scope = this)
        c.setSubmitting(true)
        assertTrue(c.state.value.isSubmitting)
    }

    @Test
    fun setFormikState_isAnEscapeHatch() = runTest {
        val c = controller(scope = this)
        c.setFormikState { it.copy(values = mapOf("brand" to "new"), submitCount = 7) }
        assertEquals(mapOf("brand" to "new"), c.state.value.values)
        assertEquals(7, c.state.value.submitCount)
    }

    @Test
    fun dirty_isTrueAfterValueChange() = runTest {
        val c = controller(scope = this)
        assertFalse(c.dirty.value)
        c.setFieldValue("name", "ian", shouldValidate = false)
        assertTrue(c.dirty.value)
    }

    @Test
    fun dirty_stayFalseWhenOnlyTouchedChanges() = runTest {
        val c = controller(scope = this)
        c.setFieldTouched("name", true, shouldValidate = false)
        assertFalse(c.dirty.value)
    }

    @Test
    fun dirty_becomesFalseAfterResetToCurrentValues() = runTest {
        val c = controller(scope = this)
        c.setFieldValue("name", "ian", shouldValidate = false)
        assertTrue(c.dirty.value)
        c.resetForm()
        assertFalse(c.dirty.value)
    }

    @Test
    fun isValid_isFalseWithErrors() = runTest {
        val c = controller(scope = this)
        c.setErrors(FormikErrors(mapOf("name" to "Required")))
        assertFalse(c.isValid.value)
    }
}
