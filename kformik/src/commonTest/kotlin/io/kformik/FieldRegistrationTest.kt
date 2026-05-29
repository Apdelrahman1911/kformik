package io.kformik

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FieldRegistrationTest {

    private fun ctrl(scope: TestScope) = FormikController(
        FormikConfig(
            initialValues = mapOf("name" to "jared", "email" to "x@y.com"),
            onSubmit = { _, _ -> },
            coroutineScope = scope,
        )
    )

    @Test
    fun registerField_addsValidator() = runTest {
        val c = ctrl(this)
        c.registerField("name") { v -> if ((v as String).isBlank()) "Required" else null }
        c.validateForm()
        assertNull(c.state.value.errors["name"])

        c.setFieldValue("name", "", shouldValidate = false)
        c.validateForm()
        assertEquals("Required", c.state.value.errors["name"])
    }

    @Test
    fun unregisterField_removesValidator() = runTest {
        val c = ctrl(this)
        c.registerField("name") { "always fails" }
        c.validateForm()
        assertEquals("always fails", c.state.value.errors["name"])

        c.unregisterField("name")
        c.validateForm()
        assertNull(c.state.value.errors["name"])
    }

    @Test
    fun registerField_overwritesExistingValidator() = runTest {
        val c = ctrl(this)
        c.registerField("name") { "first" }
        c.registerField("name") { "second" }
        c.validateForm()
        assertEquals("second", c.state.value.errors["name"])
    }

    @Test
    fun submit_touchesEveryRegisteredField() = runTest {
        val c = ctrl(this)
        c.registerField("name")
        c.registerField("email")
        c.submit()
        assertTrue(c.state.value.touched["name"])
        assertTrue(c.state.value.touched["email"])
    }

    @Test
    fun fieldBinding_exposesCurrentValueAndMeta() = runTest {
        val c = ctrl(this)
        val name = c.field("name")
        assertEquals("name", name.name)
        assertEquals("jared", name.value)
        assertEquals("jared", name.initialValue)
        assertFalse(name.touched)
        assertNull(name.error)
    }

    @Test
    fun fieldBinding_onValueChange_updatesController() = runTest {
        val c = ctrl(this)
        val name = c.field("name")
        name.onValueChange("ian")
        assertEquals("ian", c.state.value.values["name"])
    }

    @Test
    fun fieldBinding_onFocusChange_setsTouchedOnBlur() = runTest {
        val c = ctrl(this)
        val name = c.field("name")
        name.onFocusChange(true)   // focus
        assertFalse(c.state.value.touched["name"])
        name.onFocusChange(false)  // blur
        assertTrue(c.state.value.touched["name"])
    }

    @Test
    fun fieldBinding_displayError_onlyAfterTouched() = runTest {
        val c = ctrl(this)
        c.setFieldError("name", "Bad")
        val before = c.field("name")
        assertEquals("Bad", before.error)
        assertNull(before.displayError)

        c.setFieldTouched("name", true, shouldValidate = false)
        val after = c.field("name")
        assertEquals("Bad", after.displayError)
    }

    @Test
    fun fieldOf_returnsTyped() = runTest {
        val c = ctrl(this)
        val typed: FieldBinding<String> = c.fieldOf("name")
        assertEquals("jared", typed.value)
    }
}
