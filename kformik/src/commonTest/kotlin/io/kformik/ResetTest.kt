package io.kformik

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResetTest {

    private fun ctrl(
        scope: TestScope,
        initialValues: Map<String, Any?> = mapOf("name" to "jared"),
        onReset: FormikResetHandler<Map<String, Any?>>? = null,
        enableReinitialize: Boolean = false,
    ) = FormikController(
        FormikConfig(
            initialValues = initialValues,
            onSubmit = { _, _ -> },
            onReset = onReset,
            enableReinitialize = enableReinitialize,
            coroutineScope = scope,
        )
    )

    @Test
    fun resetForm_restoresInitialValues() = runTest {
        val c = ctrl(this)
        c.setFieldValue("name", "ian", shouldValidate = false)
        assertEquals("ian", c.state.value.values["name"])
        c.resetForm()
        assertEquals("jared", c.state.value.values["name"])
    }

    @Test
    fun resetForm_clearsTouched_andErrors() = runTest {
        val c = ctrl(this)
        c.setFieldTouched("name", true, shouldValidate = false)
        c.setFieldError("name", "Required")
        assertTrue(c.state.value.touched["name"])
        assertEquals("Required", c.state.value.errors["name"])
        c.resetForm()
        assertFalse(c.state.value.touched["name"])
        assertEquals(null, c.state.value.errors["name"])
    }

    @Test
    fun resetForm_resetsSubmitCount() = runTest {
        val c = ctrl(this)
        c.submit()
        c.submit()
        assertEquals(2, c.state.value.submitCount)
        c.resetForm()
        assertEquals(0, c.state.value.submitCount)
    }

    @Test
    fun resetForm_withNextState_setsNewBaseline() = runTest {
        val c = ctrl(this)
        c.setFieldValue("name", "ian", shouldValidate = false)
        assertTrue(c.dirty.value)
        c.resetForm(FormikState(values = c.state.value.values))
        // dirty should be false now since baseline = current values
        assertFalse(c.dirty.value)
        assertEquals("ian", c.state.value.values["name"])
    }

    @Test
    fun resetForm_invokesOnReset_withCurrentValues() = runTest {
        var seenValues: Map<String, Any?>? = null
        val c = ctrl(this, onReset = { vs, _ -> seenValues = vs })
        c.setFieldValue("name", "ian", shouldValidate = false)
        c.resetForm()
        assertEquals(mapOf("name" to "ian"), seenValues)
    }

    @Test
    fun reinitialize_doesNothing_whenFlagFalse() = runTest {
        val c = ctrl(this, enableReinitialize = false)
        c.setFieldValue("name", "ian", shouldValidate = false)
        c.reinitialize(FormikInitialState(values = mapOf("name" to "new-initial")))
        // form values unchanged (only baseline updated)
        assertEquals("ian", c.state.value.values["name"])
        // baseline updated
        assertEquals("new-initial", c.initialState.value.values["name"])
    }

    @Test
    fun reinitialize_resetsForm_whenFlagTrue() = runTest {
        val c = ctrl(this, enableReinitialize = true)
        c.setFieldValue("name", "ian", shouldValidate = false)
        c.reinitialize(FormikInitialState(values = mapOf("name" to "new-initial")))
        assertEquals("new-initial", c.state.value.values["name"])
        assertEquals("new-initial", c.initialState.value.values["name"])
        assertFalse(c.dirty.value)
    }

    @Test
    fun handleReset_fireAndForget() = runTest {
        val c = ctrl(this)
        c.setFieldValue("name", "ian", shouldValidate = false)
        c.handleReset()
        testScheduler.advanceUntilIdle()
        assertEquals("jared", c.state.value.values["name"])
    }
}
