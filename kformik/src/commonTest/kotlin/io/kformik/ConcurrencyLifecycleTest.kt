package io.kformik

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for the Phase-1 concurrency / state-model hardening:
 *  - stale async validation must not overwrite a fresher result
 *  - `isValidating` must stay true until the last overlapping run finishes
 *  - `submit()` is single-flight and validates the exact snapshot it submits
 *  - a value mutation must not clobber a concurrently-set error (lost update)
 *  - `validateField` must preserve a cross-field error
 *  - a throwing validator propagates and still clears `isValidating`
 *  - a validation launched before `resetForm` must not repopulate cleared errors
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConcurrencyLifecycleTest {

    private fun ctrl(
        scope: TestScope,
        initialValues: Map<String, Any?> = mapOf("name" to ""),
        validate: (suspend (Map<String, Any?>) -> FormikErrors)? = null,
        schemaValidator: SchemaValidator<Map<String, Any?>>? = null,
        onSubmit: FormikSubmitHandler<Map<String, Any?>> = { _, _ -> },
        validateOnChange: Boolean = true,
        validateOnBlur: Boolean = true,
    ): FormikController<Map<String, Any?>> = FormikController(
        FormikConfig(
            initialValues = initialValues,
            validate = validate,
            schemaValidator = schemaValidator,
            onSubmit = onSubmit,
            validateOnChange = validateOnChange,
            validateOnBlur = validateOnBlur,
            coroutineScope = scope,
        )
    )

    // ---- stale async validation (generation guard) -------------------------------------------

    @Test
    fun staleSlowValidation_doesNotOverwrite_fresherResult() = runTest {
        // "slow" produces an error after 100ms; "ab"/"fast" produces no error after 10ms.
        val c = ctrl(this, validate = { v ->
            val name = v["name"] as String
            if (name == "slow") {
                delay(100); buildErrors { put("name", "slow-error") }
            } else {
                delay(10); FormikErrors.Empty
            }
        })

        val j1 = backgroundScope.launch { c.setFieldValue("name", "slow") } // gen 1, slow
        testScheduler.advanceTimeBy(5)
        val j2 = backgroundScope.launch { c.setFieldValue("name", "fast") } // gen 2, fast
        testScheduler.advanceUntilIdle()
        j1.join(); j2.join()

        // The fast (latest) run committed empty errors; the slow stale run was dropped.
        assertEquals("fast", c.valueAt("name"))
        assertNull(c.state.value.errors["name"])
    }

    // ---- isValidating reflects in-flight count ----------------------------------------------

    @Test
    fun isValidating_staysTrue_untilLastOverlappingRunCompletes() = runTest {
        val c = ctrl(this, validate = { v ->
            val n = v["name"] as String
            delay(if (n == "a") 100 else 50)
            FormikErrors.Empty
        })

        val j1 = backgroundScope.launch { c.setFieldValue("name", "a") } // delay 100
        testScheduler.advanceTimeBy(10)
        assertTrue(c.state.value.isValidating, "first run should be validating")

        val j2 = backgroundScope.launch { c.setFieldValue("name", "b") } // delay 50
        testScheduler.advanceTimeBy(60) // b's run (started ~t=10) finished at ~t=60; a still running
        assertTrue(c.state.value.isValidating, "second run finished but first is still in flight")

        testScheduler.advanceUntilIdle()
        j1.join(); j2.join()
        assertFalse(c.state.value.isValidating, "all runs finished")
    }

    // ---- double submit ----------------------------------------------------------------------

    @Test
    fun concurrentSubmits_singleFlight_callsOnSubmitOnce() = runTest {
        var calls = 0
        val c = ctrl(this, onSubmit = { _, _ -> delay(100); calls++ })

        val j1 = backgroundScope.launch { c.submit() }
        val j2 = backgroundScope.launch { c.submit() }
        testScheduler.advanceUntilIdle()
        j1.join(); j2.join()

        assertEquals(1, calls, "onSubmit must fire exactly once")
        assertEquals(1, c.state.value.submitCount, "submitCount must not double")
        assertFalse(c.state.value.isSubmitting)
    }

    // ---- submit snapshot stability ----------------------------------------------------------

    @Test
    fun submit_usesTheSnapshotItValidated_evenIfValuesChangeDuringSubmit() = runTest {
        var submitted: String? = null
        val c = ctrl(this, onSubmit = { v, _ -> delay(50); submitted = v["name"] as String })

        c.setFieldValue("name", "first", shouldValidate = false)
        val j = backgroundScope.launch { c.submit() }
        testScheduler.advanceTimeBy(10) // submit captured "first" and is inside onSubmit's delay
        backgroundScope.launch { c.setFieldValue("name", "second", shouldValidate = false) }
        testScheduler.advanceUntilIdle()
        j.join()

        assertEquals("first", submitted, "onSubmit must receive the values it validated")
    }

    // ---- lost update (CAS-merge of disjoint slices) -----------------------------------------

    @Test
    fun valueMutation_preservesConcurrentlySetError_onAnotherSlice() = runTest {
        val c = ctrl(this, initialValues = mapOf("name" to "", "other" to ""), validateOnChange = false)
        c.setFieldError("other", "boom")
        c.setFieldValue("name", "x", shouldValidate = false)
        assertEquals("x", c.valueAt("name"))
        assertEquals("boom", c.state.value.errors["other"], "error on a disjoint slice must survive")
    }

    // ---- validateField + cross-field --------------------------------------------------------

    @Test
    fun validateField_preservesCrossFieldError() = runTest {
        val schema = formSchema<Map<String, Any?>> {
            field("password") { required() }
            cross { v ->
                val p = v["password"]
                val confirm = v["confirm"]
                if (p != confirm) buildErrors { put("confirm", "Passwords must match") } else FormikErrors.Empty
            }
        }
        val c = ctrl(
            this,
            initialValues = mapOf("password" to "abc", "confirm" to "xyz"),
            schemaValidator = schema,
        )
        val msg = c.validateField("confirm")
        assertEquals("Passwords must match", msg)
        assertEquals("Passwords must match", c.state.value.errors["confirm"])
    }

    // ---- throwing validator -----------------------------------------------------------------

    @Test
    fun validateForm_throwingValidator_propagates_andClearsIsValidating() = runTest {
        val c = ctrl(this, validate = { error("boom") })
        assertFailsWith<IllegalStateException> { c.validateForm() }
        assertFalse(c.state.value.isValidating)
    }

    @Test
    fun validateField_throwingValidator_propagates_andClearsIsValidating() = runTest {
        val c = ctrl(this)
        c.registerField("name") { error("field-boom") }
        assertFailsWith<IllegalStateException> { c.validateField("name") }
        assertFalse(c.state.value.isValidating)
    }

    // ---- reset cancels stale validation -----------------------------------------------------

    @Test
    fun resetForm_dropsValidationLaunchedBeforeIt() = runTest {
        val c = ctrl(this, validate = { v ->
            val n = v["name"] as String
            if (n == "dirty") { delay(100); buildErrors { put("name", "err") } } else FormikErrors.Empty
        })
        val j = backgroundScope.launch { c.setFieldValue("name", "dirty") } // gen 1, will error at t=100
        testScheduler.advanceTimeBy(10)
        c.resetForm() // gen 2, clears state back to baseline (name = "")
        testScheduler.advanceUntilIdle()
        j.join()

        assertEquals("", c.valueAt("name"))
        assertNull(c.state.value.errors["name"], "stale validation must not repopulate reset errors")
    }

    // ---- setValues / setTouched validation triggers -----------------------------------------

    @Test
    fun setValues_and_setTouched_triggerValidation() = runTest {
        var called = 0
        val c = ctrl(this, validate = { called++; FormikErrors.Empty })
        c.setValues(mapOf("name" to "x"))
        assertEquals(1, called)
        c.setValues({ it + ("name" to "y") })
        assertEquals(2, called)
        c.setTouched(FormikTouched(mapOf("name" to true)))
        assertEquals(3, called)
    }
}
