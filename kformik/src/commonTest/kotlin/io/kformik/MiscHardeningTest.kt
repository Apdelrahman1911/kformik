package io.kformik

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
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

/** A tiny hand-written updater over a typed `data class`, mirroring the KSP-generated shape. */
private data class Profile(val name: String, val age: Int)

private object ProfileUpdater : ValuesUpdater<Profile> {
    override fun getAt(values: Profile, path: String): Any? = when (path) {
        "name" -> values.name
        "age" -> values.age
        else -> null
    }
    override fun setAt(values: Profile, path: String, value: Any?): Profile = when (path) {
        "name" -> values.copy(name = value as String)
        "age" -> values.copy(age = value as Int)
        else -> error("Unknown path: $path")
    }
    override fun leafPaths(values: Profile): Set<String> = setOf("name", "age")
}

/** An opaque typed updater whose [leafPaths] is empty (forces the submit registry-union fallback). */
private object OpaqueProfileUpdater : ValuesUpdater<Profile> {
    override fun getAt(values: Profile, path: String): Any? = ProfileUpdater.getAt(values, path)
    override fun setAt(values: Profile, path: String, value: Any?): Profile = ProfileUpdater.setAt(values, path, value)
    // leafPaths intentionally returns empty (default).
}

/**
 * Misc Phase-10 regression tests: validation precedence, typed updaters through the controller,
 * reinitialize branches, empty-error semantics, blank-handling in email/pattern, schema cancellation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MiscHardeningTest {

    private fun mapCtrl(
        scope: TestScope,
        initialValues: Map<String, Any?> = mapOf("name" to ""),
        validate: (suspend (Map<String, Any?>) -> FormikErrors)? = null,
        schemaValidator: SchemaValidator<Map<String, Any?>>? = null,
        enableReinitialize: Boolean = false,
    ) = FormikController(
        FormikConfig(
            initialValues = initialValues,
            validate = validate,
            schemaValidator = schemaValidator,
            onSubmit = { _, _ -> },
            enableReinitialize = enableReinitialize,
            coroutineScope = scope,
        )
    )

    // ---- three-way merge precedence ([69]) --------------------------------------------------

    @Test
    fun mergePrecedence_topBeatsSchemaBeatsField_samePath() = runTest {
        val schema = SchemaValidator<Map<String, Any?>> { buildErrors { put("name", "schema") } }
        val c = mapCtrl(this, schemaValidator = schema, validate = { buildErrors { put("name", "top") } })
        c.registerField("name") { "field" }
        c.validateForm()
        assertEquals("top", c.state.value.errors["name"])
    }

    @Test
    fun mergePrecedence_schemaBeatsField_whenNoTopLevel() = runTest {
        val schema = SchemaValidator<Map<String, Any?>> { buildErrors { put("name", "schema") } }
        val c = mapCtrl(this, schemaValidator = schema)
        c.registerField("name") { "field" }
        c.validateForm()
        assertEquals("schema", c.state.value.errors["name"])
    }

    // ---- custom/typed ValuesUpdater through the controller ([54]) ---------------------------

    @Test
    fun typedUpdater_routesGetSet_andSubmitTouchesLeafPaths() = runTest {
        val c = FormikController(
            FormikConfig(
                initialValues = Profile("a", 1),
                valuesUpdater = ProfileUpdater,
                onSubmit = { _, _ -> },
                coroutineScope = this,
            )
        )
        c.setFieldValue("name", "b", shouldValidate = false)
        assertEquals("b", c.valueAt("name"))
        c.setFieldValue("age", 30, shouldValidate = false)
        assertEquals(30, c.valueAt("age"))
        c.submit()
        testScheduler.advanceUntilIdle()
        assertTrue(c.state.value.touched["name"])
        assertTrue(c.state.value.touched["age"])
    }

    @Test
    fun typedUpdater_emptyLeafPaths_submitFallsBackToRegistry() = runTest {
        val c = FormikController(
            FormikConfig(
                initialValues = Profile("a", 1),
                valuesUpdater = OpaqueProfileUpdater,
                onSubmit = { _, _ -> },
                coroutineScope = this,
            )
        )
        c.registerField("name")
        c.submit()
        testScheduler.advanceUntilIdle()
        assertTrue(c.state.value.touched["name"], "registry union must touch registered fields when leafPaths is empty")
    }

    @Test
    fun flatTopLevelUpdater_explicitlyPassed_throwsOnAccess() = runTest {
        val c = FormikController(
            FormikConfig(
                initialValues = Profile("a", 1),
                valuesUpdater = FlatTopLevelUpdater(),
                onSubmit = { _, _ -> },
                coroutineScope = this,
            )
        )
        assertFails { c.valueAt("name") }
    }

    /**
     * v1.9.0 regression: a [FormSchema] schema attached to a typed (non-Map) form now reads
     * field values through the controller's configured [ValuesUpdater]. Pre-1.9.0, FormSchema's
     * internal `readValue` fell through to `getIn` for non-Map values, which only walks Map/List
     * trees — every per-field rule received `null` regardless of the actual Profile field, so
     * `required()`-style rules silently misfired and per-field validators on typed forms had no
     * way to inspect their fields.
     */
    @Test
    fun typedSchema_perFieldValidator_seesActualValueViaConfiguredUpdater() = runTest {
        val schema = formSchema<Profile> {
            field("name") {
                custom("must-be-bob") { value, _ ->
                    if (value != "bob") "Got '$value', expected 'bob'" else null
                }
            }
            field("age") {
                custom("must-be-30") { value, _ ->
                    if (value != 30) "Got '$value', expected 30" else null
                }
            }
        }
        val c = FormikController(
            FormikConfig(
                initialValues = Profile(name = "bob", age = 30),
                valuesUpdater = ProfileUpdater,
                schemaValidator = schema,
                onSubmit = { _, _ -> },
                coroutineScope = this,
            )
        )
        // Pre-1.9.0: per-field validators saw null for both fields — `value != "bob"` evaluates
        // true (since null != "bob"), so the schema would report errors EVERYWHERE despite the
        // initial state being correct. Post-fix: validators see the actual Profile values, no
        // false positives on a clean baseline.
        val errs = schema.validate(Profile("bob", 30))
        assertNull(errs["name"], "name validator must see actual 'bob' value, not null")
        assertNull(errs["age"], "age validator must see actual 30 value, not null")

        // And mutation through the controller surfaces fresh errors via the schema:
        c.setFieldValue("name", "alice", shouldValidate = false)
        val mutated = c.validateForm()
        assertEquals("Got 'alice', expected 'bob'", mutated["name"])
        // age still clean (still 30 in mutated Profile).
        assertNull(mutated["age"])
    }

    // ---- reinitialize branches ([75]) -------------------------------------------------------

    @Test
    fun reinitialize_equalBaseline_isNoOp_noValidation() = runTest {
        var calls = 0
        val c = mapCtrl(this, initialValues = mapOf("name" to "old"), validate = { calls++; FormikErrors.Empty }, enableReinitialize = true)
        testScheduler.advanceUntilIdle()
        val before = calls
        c.reinitialize(FormikInitialState(values = mapOf("name" to "old")))
        testScheduler.advanceUntilIdle()
        assertEquals(before, calls, "no-op reinitialize must not run validation")
    }

    @Test
    fun reinitialize_flagFalse_swapsBaseline_withoutValidating_orLosingEdits() = runTest {
        var calls = 0
        val c = mapCtrl(this, initialValues = mapOf("name" to "jared"), validate = { calls++; FormikErrors.Empty }, enableReinitialize = false)
        c.setFieldValue("name", "ian", shouldValidate = false)
        testScheduler.advanceUntilIdle()
        val before = calls
        c.reinitialize(FormikInitialState(values = mapOf("name" to "new-initial")))
        testScheduler.advanceUntilIdle()
        assertEquals("ian", c.valueAt("name"), "user edit preserved")
        assertEquals("new-initial", c.initialState.value.values["name"], "baseline swapped")
        assertEquals(before, calls, "flag-false reinitialize must not validate")
    }

    // ---- validateField cross-field precedence + stale-snapshot guard (2nd-review regressions) ----

    @Test
    fun validateField_crossFieldOverridesPerField_andAgreesWithValidateForm() = runTest {
        val schema = formSchema<Map<String, Any?>> {
            field("a") { custom("perfield") { _, _ -> "per-field" } }
            cross { buildErrors { put("a", "cross-field") } }
        }
        val c = mapCtrl(this, initialValues = mapOf("a" to "x"), schemaValidator = schema)
        c.validateForm()
        assertEquals("cross-field", c.state.value.errors["a"], "validateForm: cross overrides per-field")
        val msg = c.validateField("a")
        assertEquals("cross-field", msg, "validateField must agree (cross overrides per-field)")
        assertEquals("cross-field", c.state.value.errors["a"], "validateField must not downgrade the committed error")
    }

    @Test
    fun validateField_resetDuringValidation_doesNotRepopulate() = runTest {
        val schema = SchemaValidator<Map<String, Any?>> {
            delay(100)
            buildErrors { put("name", "schema-err") }
        }
        val c = mapCtrl(this, schemaValidator = schema)
        val job = backgroundScope.launch { c.validateField("name") } // captures current gen, validates slowly
        testScheduler.advanceTimeBy(10)
        c.resetForm() // bumps generation
        testScheduler.advanceUntilIdle()
        job.join()
        assertNull(c.state.value.errors["name"], "a validateField result computed before a reset must be dropped")
    }

    // ---- empty-string error semantics ([77]/[112]) ------------------------------------------

    @Test
    fun emptyStringError_isInvalid_butDisplayErrorIsNull() = runTest {
        val c = mapCtrl(this)
        c.setFieldError("name", "")
        c.setFieldTouched("name", true, shouldValidate = false)
        assertTrue(c.state.value.errors.contains("name"))
        assertFalse(c.isValid.value, "an empty-string error still counts as invalid (Formik parity)")
        assertNull(c.field("name").displayError, "but a blank error is not surfaced to the UI")
    }

    @Test
    fun setFieldTouched_sameValue_stillRunsValidation() = runTest {
        var calls = 0
        val c = mapCtrl(this, validate = { calls++; FormikErrors.Empty })
        c.setFieldTouched("name", true)
        val after = calls
        c.setFieldTouched("name", true)
        assertTrue(calls > after)
    }

    // ---- blank handling in email/pattern ([51]) ---------------------------------------------

    @Test
    fun optionalEmailAndPattern_passOnBlank() = runTest {
        val schema = formSchema<Map<String, Any?>> {
            field("email") { email() }            // no required()
            field("code") { pattern(Regex("\\d+")) }
        }
        val c = mapCtrl(this, initialValues = mapOf("email" to "", "code" to "   "), schemaValidator = schema)
        c.validateForm()
        assertNull(c.state.value.errors["email"], "optional blank email must pass")
        assertNull(c.state.value.errors["code"], "optional blank pattern must pass")
    }

    // ---- schema cancellation safety ([123]) -------------------------------------------------

    @Test
    fun schemaValidation_cancelledMidFlight_noPartialErrors_andClearsIsValidating() = runTest {
        val schema = SchemaValidator<Map<String, Any?>> {
            delay(10_000)
            buildErrors { put("name", "taken") }
        }
        val c = mapCtrl(this, schemaValidator = schema)
        val job = backgroundScope.launch { c.validateForm() }
        testScheduler.advanceTimeBy(10)
        assertTrue(c.state.value.isValidating, "validation should be in flight")
        job.cancelAndJoin()
        assertNull(c.state.value.errors["name"], "a cancelled run must not commit partial errors")
        assertFalse(c.state.value.isValidating, "isValidating must be cleared even on cancellation")
    }
}
