package io.kformik

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Validation behaviors. Mirrors Formik's `Formik.test.tsx` validation block plus the field-level
 * validation tests from `Field.test.tsx`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ValidationTest {

    private fun ctrl(
        scope: TestScope,
        initialValues: Map<String, Any?> = mapOf("name" to ""),
        validate: (suspend (Map<String, Any?>) -> FormikErrors)? = null,
        schemaValidator: SchemaValidator<Map<String, Any?>>? = null,
        validateOnChange: Boolean = true,
        validateOnBlur: Boolean = true,
        validateOnMount: Boolean = false,
    ): FormikController<Map<String, Any?>> = FormikController(
        FormikConfig(
            initialValues = initialValues,
            validate = validate,
            schemaValidator = schemaValidator,
            onSubmit = { _, _ -> },
            validateOnChange = validateOnChange,
            validateOnBlur = validateOnBlur,
            validateOnMount = validateOnMount,
            coroutineScope = scope,
        )
    )

    @Test
    fun validate_runsOnSetFieldValue_byDefault() = runTest {
        var called = 0
        val c = ctrl(this, validate = { v ->
            called++
            buildErrors {
                if ((v["name"] as String).isBlank()) put("name", "Required")
            }
        })

        c.setFieldValue("name", "")
        assertEquals(1, called)
        assertEquals("Required", c.state.value.errors["name"])

        c.setFieldValue("name", "ian")
        assertEquals(2, called)
        assertNull(c.state.value.errors["name"])
    }

    @Test
    fun validate_doesNotRunIfValidateOnChangeFalse() = runTest {
        var called = 0
        val c = ctrl(this, validateOnChange = false, validate = {
            called++; FormikErrors.Empty
        })
        c.setFieldValue("name", "x")
        assertEquals(0, called)
    }

    @Test
    fun validate_runsOnSetFieldTouched_byDefault() = runTest {
        var called = 0
        val c = ctrl(this, validate = { called++; FormikErrors.Empty })
        c.setFieldTouched("name", true)
        assertEquals(1, called)
    }

    @Test
    fun validate_doesNotRunOnTouchedIfValidateOnBlurFalse() = runTest {
        var called = 0
        val c = ctrl(this, validateOnBlur = false, validate = { called++; FormikErrors.Empty })
        c.setFieldTouched("name", true)
        assertEquals(0, called)
    }

    @Test
    fun validate_runsOnMount_whenEnabled() = runTest {
        var called = 0
        val c = ctrl(this, validateOnMount = true, validate = {
            called++; FormikErrors.Empty
        })
        // Flush the launched job.
        testScheduler.advanceUntilIdle()
        assertEquals(1, called)
        // ensure we leave the controller in a clean state
        assertFalse(c.state.value.isValidating)
    }

    @Test
    fun async_validate_isAwaited() = runTest {
        val c = ctrl(this, validate = { v ->
            delay(50)
            buildErrors {
                if ((v["name"] as String).isBlank()) put("name", "Required")
            }
        })
        c.setFieldValue("name", "")
        assertEquals("Required", c.state.value.errors["name"])
    }

    @Test
    fun fieldLevel_validator_runs_and_setsError() = runTest {
        val c = ctrl(this)
        c.registerField("name") { v ->
            if ((v as String).isBlank()) "Required" else null
        }
        c.validateForm()
        assertEquals("Required", c.state.value.errors["name"])
    }

    @Test
    fun fieldLevel_overridden_by_topLevel_validate_for_same_path() = runTest {
        val c = ctrl(this, validate = { _ ->
            buildErrors { put("name", "Top-level says no") }
        })
        c.registerField("name") { "Field-level says nope" }
        c.validateForm()
        // Top-level merges last in our deepmerge equivalent → wins.
        assertEquals("Top-level says no", c.state.value.errors["name"])
    }

    @Test
    fun fieldLevel_and_topLevel_merge_when_different_paths() = runTest {
        val c = ctrl(
            this,
            initialValues = mapOf("name" to "", "age" to 0),
            validate = { _ -> buildErrors { put("age", "Min 18") } },
        )
        c.registerField("name") { "Required" }
        c.validateForm()
        assertEquals("Required", c.state.value.errors["name"])
        assertEquals("Min 18", c.state.value.errors["age"])
    }

    @Test
    fun schemaValidator_runs_and_setsErrors() = runTest {
        val schema = SchemaValidator<Map<String, Any?>> { v ->
            buildErrors {
                if ((v["name"] as String).isBlank()) put("name", "Required (schema)")
            }
        }
        val c = ctrl(this, schemaValidator = schema)
        c.validateForm()
        assertEquals("Required (schema)", c.state.value.errors["name"])
    }

    @Test
    fun schema_and_topLevel_validate_merge() = runTest {
        val schema = SchemaValidator<Map<String, Any?>> { v ->
            buildErrors {
                if ((v["name"] as String).isBlank()) put("name", "Required")
            }
        }
        val c = ctrl(
            this,
            initialValues = mapOf("name" to "", "age" to 0),
            schemaValidator = schema,
            validate = { _ -> buildErrors { put("age", "Min 18") } },
        )
        c.validateForm()
        assertEquals("Required", c.state.value.errors["name"])
        assertEquals("Min 18", c.state.value.errors["age"])
    }

    @Test
    fun validateField_runsFieldLevelOnly() = runTest {
        var topLevelCalls = 0
        val c = ctrl(this, validate = { topLevelCalls++; FormikErrors.Empty })
        c.registerField("name") { v -> if ((v as String).isBlank()) "Required" else null }
        c.validateField("name")
        assertEquals(0, topLevelCalls)
        assertEquals("Required", c.state.value.errors["name"])
    }

    @Test
    fun validateField_fallsBackToSchema() = runTest {
        val schema = SchemaValidator<Map<String, Any?>> { v ->
            buildErrors {
                if ((v["name"] as String).isBlank()) put("name", "Schema required")
            }
        }
        val c = ctrl(this, schemaValidator = schema)
        val msg = c.validateField("name")
        assertEquals("Schema required", msg)
        assertEquals("Schema required", c.state.value.errors["name"])
    }

    @Test
    fun isValidating_flipsAroundValidate() = runTest {
        val c = ctrl(this, validate = { delay(50); FormikErrors.Empty })
        assertFalse(c.state.value.isValidating)
        val job = backgroundScope.launch { c.validateForm() }
        testScheduler.advanceTimeBy(10)
        assertTrue(c.state.value.isValidating)
        testScheduler.advanceUntilIdle()
        job.join()
        assertFalse(c.state.value.isValidating)
    }

    @Test
    fun shouldValidate_argumentOverridesDefault() = runTest {
        var called = 0
        val c = ctrl(this, validateOnChange = false, validate = { called++; FormikErrors.Empty })
        c.setFieldValue("name", "x", shouldValidate = true)
        assertEquals(1, called)
    }
}

