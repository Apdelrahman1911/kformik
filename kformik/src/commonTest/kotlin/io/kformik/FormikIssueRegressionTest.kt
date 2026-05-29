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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests that pin KFormik's behavior against specific bug patterns from the original
 * [Formik issue tracker](https://github.com/jaredpalmer/formik/issues).
 *
 * Each test names the upstream issue number it covers. The intent is twofold:
 *
 *  1. Document — via running tests — that the same class of bug does not exist in KFormik.
 *  2. Catch regressions if a future refactor reintroduces one of these behaviors.
 *
 * Coverage rules:
 *  - Issues that are purely React/TS/Yup/DOM-specific are skipped (they couldn't manifest here).
 *  - Issues that target behavior shared with KFormik (validation timing, async submit,
 *    consecutive mutations, validateOnMount + isValid, etc.) get one test each.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FormikIssueRegressionTest {

    private fun ctrl(
        scope: TestScope,
        initialValues: Map<String, Any?> = mapOf("name" to ""),
        validate: (suspend (Map<String, Any?>) -> FormikErrors)? = null,
        onSubmit: FormikSubmitHandler<Map<String, Any?>> = { _, _ -> },
        validateOnChange: Boolean = true,
        validateOnBlur: Boolean = true,
        validateOnMount: Boolean = false,
    ) = FormikController(
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

    // ─────────────────────────────────────────────────────────────── Formik #1950 / #2172
    // "isValid=true on mount, even though initialValues are invalid and validateOnMount=true"
    //
    // Formik's bug: isValid was computed once at construction (before validateOnMount finished)
    // and stayed `true` until the first user action. In KFormik, `isValid` is a `DerivedStateFlow`
    // that reads `state.errors.isEmpty` lazily on every access — so once validateOnMount commits
    // its errors, isValid flips correctly.

    @Test
    fun formik_issue_1950_isValid_reflectsValidateOnMount_result() = runTest {
        val c = ctrl(
            this,
            initialValues = mapOf("name" to ""),
            validateOnMount = true,
            validate = { v ->
                buildErrors { if ((v["name"] as String).isBlank()) put("name", "Required") }
            },
        )
        testScheduler.advanceUntilIdle()
        assertEquals("Required", c.state.value.errors["name"], "validateOnMount should commit errors")
        assertFalse(c.isValid.value, "isValid must reflect committed errors after validateOnMount")
    }

    // ─────────────────────────────────────────────────────────────── Formik #2083
    // "Validation runs on old values after setFieldTouched"
    //
    // Formik's bug: setFieldTouched re-validated against a stale closure of `values`.
    // KFormik runs `runAllValidationsAndCommit(_state.value.values)` — which reads the latest
    // values atomically AFTER the touched mutation commits. The latest value is what the
    // validator sees.

    @Test
    fun formik_issue_2083_setFieldTouched_validatesAgainstLatestValues() = runTest {
        val seen = mutableListOf<String>()
        val c = ctrl(
            this,
            validate = { v ->
                seen += v["name"] as String
                FormikErrors.Empty
            },
        )
        c.setFieldValue("name", "fresh", shouldValidate = false)
        c.setFieldTouched("name", true)  // triggers validation
        testScheduler.advanceUntilIdle()
        assertEquals("fresh", seen.last(), "setFieldTouched must validate against the latest values, not a stale snapshot")
    }

    // ─────────────────────────────────────────────────────────────── Formik #2266
    // "Validation run on obsolete data when calling setFieldValue multiple times consecutively"
    //
    // KFormik's `setFieldValue` holds the controller mutex while writing the value and runs the
    // validator against the post-set values (the `newValues` returned from `updater.setAt`).
    // Three rapid setFieldValue calls produce three validations, each seeing the right values.

    @Test
    fun formik_issue_2266_consecutive_setFieldValue_validatesEachOnLatest() = runTest {
        val seen = mutableListOf<String>()
        val c = ctrl(
            this,
            validate = { v ->
                seen += v["name"] as String
                FormikErrors.Empty
            },
        )
        c.setFieldValue("name", "a")
        c.setFieldValue("name", "ab")
        c.setFieldValue("name", "abc")
        testScheduler.advanceUntilIdle()
        // Each validation must have seen the value at the time it was triggered.
        assertEquals(listOf("a", "ab", "abc"), seen)
    }

    // ─────────────────────────────────────────────────────────────── Formik #2457
    // "Incorrect validation if setFieldTouched is executed immediately after setFieldValue"
    //
    // The pattern: set the value AND mark the field touched in the same tick. KFormik's mutex
    // serializes them; the final validator call sees the new value AND the new touched state.

    @Test
    fun formik_issue_2457_setFieldTouched_after_setFieldValue_isCoherent() = runTest {
        val seen = mutableListOf<Pair<String, Boolean>>()
        val c = ctrl(
            this,
            validate = { v ->
                seen += (v["name"] as String) to true  // touched is checked indirectly
                FormikErrors.Empty
            },
        )
        c.setFieldValue("name", "new")
        c.setFieldTouched("name", true)
        testScheduler.advanceUntilIdle()
        // Validator must have seen the new value at least once
        assertTrue(seen.any { it.first == "new" })
        // And the final state has both value+touched committed
        assertEquals("new", c.state.value.values["name"])
        assertTrue(c.state.value.touched["name"])
    }

    // ─────────────────────────────────────────────────────────────── Formik #1580
    // "Promise returned from submitForm not rejected when form is invalid"
    //
    // Formik's behavior: submitForm() resolves (does NOT reject) when validation fails — onSubmit
    // is simply not called. KFormik mirrors this: `submit()` returns normally; the caller learns
    // about validation failure via `state.errors` and `state.submitCount`.
    //
    // This test pins the contract so callers can rely on it.

    @Test
    fun formik_issue_1580_submit_resolvesNormally_whenValidationFails() = runTest {
        var onSubmitCalled = false
        val c = ctrl(
            this,
            onSubmit = { _, _ -> onSubmitCalled = true },
            validate = { _ -> buildErrors { put("name", "Required") } },
        )
        // Does NOT throw — the contract is "resolve normally; surface failure via state".
        c.submit()
        testScheduler.advanceUntilIdle()
        assertFalse(onSubmitCalled, "onSubmit must not be called when validation fails")
        assertEquals("Required", c.state.value.errors["name"])
        assertEquals(1, c.state.value.submitCount)
    }

    // ─────────────────────────────────────────────────────────────── Formik #1730
    // "isSubmitting resets to false too early"
    //
    // In Formik v2, isSubmitting would clear before async onSubmit finished. KFormik clears
    // isSubmitting only AFTER the suspend onSubmit returns (success or failure).

    @Test
    fun formik_issue_1730_isSubmitting_clearsOnlyAfterAsyncOnSubmitReturns() = runTest {
        val c = ctrl(
            this,
            onSubmit = { _, _ -> delay(100) },
        )
        val job = backgroundScope.launch { c.submit() }
        testScheduler.advanceTimeBy(10)   // submit started, onSubmit suspended
        assertTrue(c.state.value.isSubmitting, "isSubmitting must remain true while async onSubmit is still running")
        testScheduler.advanceUntilIdle()  // onSubmit returns
        job.join()
        assertFalse(c.state.value.isSubmitting, "isSubmitting must clear once async onSubmit returns")
    }

    // ─────────────────────────────────────────────────────────────── Formik #1329
    // "`validate` swallows Exceptions"
    //
    // Formik's bug: a thrown error inside `validate` was sometimes swallowed and turned into
    // an empty errors map. KFormik's pipeline rethrows any non-`FormikErrors` throwable up to
    // the `submit()` caller and clears isValidating/isSubmitting.

    @Test
    fun formik_issue_1329_validate_throws_propagatesToCaller() = runTest {
        val c = ctrl(
            this,
            validate = { error("synthetic-validator-failure") },
        )
        val ex = assertFails { c.submit() }
        assertTrue("synthetic-validator-failure" in (ex.message ?: ""))
        assertFalse(c.state.value.isSubmitting)
        assertFalse(c.state.value.isValidating)
    }

    // ─────────────────────────────────────────────────────────────── Formik #512
    // "Validate only one field at a time"
    //
    // KFormik exposes `validateField(name)` that runs only the field-level validator (or the
    // focused schema path) and writes only that field's error. No re-validation of siblings.

    @Test
    fun formik_issue_512_validateField_isFocused() = runTest {
        val c = ctrl(this, initialValues = mapOf("email" to "", "password" to ""))
        var emailCalls = 0
        var passwordCalls = 0
        c.registerField("email") { emailCalls++; "Email required" }
        c.registerField("password") { passwordCalls++; "Password required" }

        c.validateField("email")
        assertEquals(1, emailCalls)
        assertEquals(0, passwordCalls, "validateField('email') must not touch the password validator")
        assertEquals("Email required", c.state.value.errors["email"])
        assertNull(c.state.value.errors["password"])
    }

    // ─────────────────────────────────────────────────────────────── Formik #797
    // "Add validationSchema to FormikProps"
    //
    // KFormik exposes the schema validator as `config.schemaValidator`. The controller's `config`
    // is `val`-accessible. Consumers can read it back if needed.

    @Test
    fun formik_issue_797_schemaValidator_isExposedOnConfig() = runTest {
        val schema = formSchema<Map<String, Any?>> {
            field("name") { required("Required") }
        }
        val c = ctrl(this, validate = null)  // no top-level validate
        val c2 = FormikController(
            FormikConfig(
                initialValues = mapOf("name" to ""),
                schemaValidator = schema,
                onSubmit = { _, _ -> },
                coroutineScope = this,
            )
        )
        assertEquals(schema, c2.config.schemaValidator)
    }

    // ─────────────────────────────────────────────────────────────── Formik #2675
    // "Please make onSubmit/handleSubmit optional for use cases that don't involve submitting"
    //
    // KFormik decision: `onSubmit` is required (the Kotlin type system makes it impossible to
    // omit). Consumers who don't need it pass a no-op. This test documents the decision via a
    // tiny no-op submit.

    @Test
    fun formik_issue_2675_noOpOnSubmit_isLegal() = runTest {
        val c = ctrl(this, onSubmit = { _, _ -> /* intentional no-op */ })
        c.submit()
        assertEquals(1, c.state.value.submitCount)
        assertFalse(c.state.value.isSubmitting)
    }

    // ─────────────────────────────────────────────────────────────── Formik #2347
    // "Incorrect type inference for members of FormikErrors which are elements of an array of objects"
    //
    // Pure TypeScript issue in Formik — KFormik uses a flat `Map<String, String>` keyed by path
    // string. The "type inference" failure cannot occur. This test asserts that nested-array
    // errors work via path keys, which is the KFormik-equivalent contract.

    @Test
    fun formik_issue_2347_nestedArrayErrors_useFlatPathKeys() = runTest {
        val c = ctrl(this, initialValues = mapOf("users" to listOf(mapOf("name" to ""))))
        c.setFieldError("users[0].name", "Required")
        assertEquals("Required", c.state.value.errors["users[0].name"])
    }

    // ─────────────────────────────────────────────────────────────── Formik #150
    // "A way to keep backend errors with client validation"
    //
    // In KFormik, `setFieldError(name, msg)` writes a manual error. A subsequent change-driven
    // validation will overwrite it (because the validator's view is authoritative). Consumers
    // who want sticky backend errors should keep them in `status` or in a field-level error
    // that the validator preserves. This test pins the trade-off.

    @Test
    fun formik_issue_150_manualFieldError_isOverwritten_byNextValidation() = runTest {
        val c = ctrl(
            this,
            validate = { _ -> FormikErrors.Empty },  // validator always says "no errors"
        )
        c.setFieldError("server", "Backend says no")
        assertEquals("Backend says no", c.state.value.errors["server"])
        c.setFieldValue("name", "x")  // triggers validation
        testScheduler.advanceUntilIdle()
        // Empty-validator wins.
        assertNull(c.state.value.errors["server"])
        // To preserve backend errors, callers should store them outside the validator's keyspace
        // (e.g. via setStatus) — see docs/SCHEMA_VALIDATION.md.
    }
}
