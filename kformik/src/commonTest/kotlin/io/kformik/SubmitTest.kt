package io.kformik

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SubmitTest {

    private fun ctrl(
        scope: TestScope,
        initialValues: Map<String, Any?> = mapOf("name" to "jared"),
        validate: (suspend (Map<String, Any?>) -> FormikErrors)? = null,
        onSubmit: FormikSubmitHandler<Map<String, Any?>> = { _, _ -> },
    ) = FormikController(
        FormikConfig(
            initialValues = initialValues,
            validate = validate,
            onSubmit = onSubmit,
            coroutineScope = scope,
        )
    )

    @Test
    fun successfulSubmit_callsOnSubmit() = runTest {
        var called = 0
        val c = ctrl(this, onSubmit = { _, _ -> called++ })
        c.submit()
        assertEquals(1, called)
        assertEquals(1, c.state.value.submitCount)
        assertFalse(c.state.value.isSubmitting)
    }

    @Test
    fun submit_doesNotCall_onSubmit_whenValidationFails() = runTest {
        var called = 0
        val c = ctrl(this,
            validate = { buildErrors { put("name", "Required") } },
            onSubmit = { _, _ -> called++ },
        )
        c.submit()
        assertEquals(0, called)
        assertEquals(1, c.state.value.submitCount) // still increments
        assertEquals("Required", c.state.value.errors["name"])
    }

    @Test
    fun submit_touchesAllRegisteredFields() = runTest {
        val c = ctrl(this, initialValues = mapOf("name" to "", "email" to ""))
        c.registerField("name")
        c.registerField("email")
        c.submit()
        assertTrue(c.state.value.touched["name"])
        assertTrue(c.state.value.touched["email"])
    }

    @Test
    fun submitCount_incrementsEachAttempt() = runTest {
        val c = ctrl(this)
        c.submit()
        c.submit()
        c.submit()
        assertEquals(3, c.state.value.submitCount)
    }

    @Test
    fun isSubmitting_flipsAroundOnSubmit() = runTest {
        val c = ctrl(this, onSubmit = { _, _ -> delay(50) })
        assertFalse(c.state.value.isSubmitting)
        val job = backgroundScope.launch { c.submit() }
        testScheduler.advanceTimeBy(10)
        assertTrue(c.state.value.isSubmitting)
        testScheduler.advanceUntilIdle()
        job.join()
        assertFalse(c.state.value.isSubmitting)
    }

    @Test
    fun submit_rethrows_when_onSubmit_throws() = runTest {
        val c = ctrl(this, onSubmit = { _, _ -> error("boom") })
        assertFails { c.submit() }
        // isSubmitting must still be cleared
        assertFalse(c.state.value.isSubmitting)
    }

    @Test
    fun handleSubmit_swallows_exceptions() = runTest {
        val c = ctrl(this, onSubmit = { _, _ -> error("boom") })
        c.handleSubmit()
        // launching on the controller's scope (= this test scope), wait for completion
        testScheduler.advanceUntilIdle()
        assertFalse(c.state.value.isSubmitting)
        // submitCount still incremented
        assertEquals(1, c.state.value.submitCount)
    }

    @Test
    fun submit_with_setStatus_via_actions() = runTest {
        val c = ctrl(this, onSubmit = { _, actions -> actions.setStatus("ok") })
        c.submit()
        assertEquals("ok", c.state.value.status)
    }

    @Test
    fun submit_calls_setFieldError_via_actions() = runTest {
        val c = ctrl(this, onSubmit = { _, actions ->
            actions.setFieldError("server", "Bad credentials")
        })
        c.submit()
        assertEquals("Bad credentials", c.state.value.errors["server"])
    }
}
