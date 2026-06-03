package io.kformik

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests for behaviors that are implemented but were uncovered by the per-area test files
 * (FormikControllerTest, ValidationTest, etc.). They target specific edge cases — duplicate
 * setFieldValue calls, dirty toggling, deep-merge of validate errors, blur ordering, etc.
 */
class CoverageGapsTest {

    private fun ctrl(
        scope: TestScope,
        initialValues: Map<String, Any?> = mapOf("name" to "jared", "age" to 30),
        validate: (suspend (Map<String, Any?>) -> FormikErrors)? = null,
        schemaValidator: SchemaValidator<Map<String, Any?>>? = null,
        onSubmit: FormikSubmitHandler<Map<String, Any?>> = { _, _ -> },
    ) = FormikController(
        FormikConfig(
            initialValues = initialValues,
            validate = validate,
            schemaValidator = schemaValidator,
            onSubmit = onSubmit,
            coroutineScope = scope,
        )
    )

    // ----------------------------------------------------------- P2-1: submit-touch-leaves
    // Formik marks every leaf of `values` touched on submit, regardless of registration.

    @Test
    fun submit_touchesAllLeafPaths_includingUnregisteredFields() = runTest {
        val c = ctrl(
            this,
            initialValues = mapOf(
                "email" to "",
                "password" to "",
                "user" to mapOf("name" to "", "address" to mapOf("city" to "", "country" to "")),
                "tags" to listOf("a", "b"),
            ),
        )
        // Note: no registerField calls. Submit must still touch every leaf.
        c.submit()
        val touched = c.state.value.touched.byPath
        assertTrue(touched["email"] == true, "email should be touched")
        assertTrue(touched["password"] == true, "password should be touched")
        assertTrue(touched["user.name"] == true, "user.name should be touched")
        assertTrue(touched["user.address.city"] == true, "user.address.city should be touched")
        assertTrue(touched["user.address.country"] == true, "user.address.country should be touched")
        assertTrue(touched["tags[0]"] == true, "tags[0] should be touched")
        assertTrue(touched["tags[1]"] == true, "tags[1] should be touched")
    }

    @Test
    fun submit_unionsLeafPathsAndRegistryKeys() = runTest {
        // Typed-updater consumers may have empty leafPaths but still want registered fields touched.
        val c = ctrl(this, initialValues = mapOf("name" to ""))
        c.registerField("synthetic.path") // not in values
        c.submit()
        val touched = c.state.value.touched.byPath
        assertTrue(touched["name"] == true)
        assertTrue(touched["synthetic.path"] == true)
    }

    // ---------------------------------------------- P2-2: setErrors no-op when content equal

    @Test
    fun setErrors_withEqualContent_doesNotChangeStateIdentity() = runTest {
        val c = ctrl(this)
        c.setErrors(FormikErrors(mapOf("name" to "Required")))
        val first = c.state.value
        c.setErrors(FormikErrors(mapOf("name" to "Required")))
        val second = c.state.value
        assertSame(first, second, "Equivalent setErrors must not produce a new state object")
    }

    @Test
    fun setErrors_withDifferentContent_changesStateIdentity() = runTest {
        val c = ctrl(this)
        c.setErrors(FormikErrors(mapOf("name" to "Required")))
        val first = c.state.value
        c.setErrors(FormikErrors(mapOf("name" to "Different")))
        val second = c.state.value
        assertNotSame(first, second)
    }

    // ---------------------------------- P2-3: setFieldValue validates against post-set values

    @Test
    fun setFieldValue_runs_validation_against_resolvedValue_notPriorState() = runTest {
        var seenName: String? = null
        val c = ctrl(this, validate = { v ->
            seenName = v["name"] as String?
            FormikErrors.Empty
        })
        c.setFieldValue("name", "POST")
        assertEquals("POST", seenName)
    }

    // -------------------------------------- P2-3 (variant): updater overload validates resolved

    @Test
    fun setFieldValue_updater_form_validatesAgainstResolvedValue() = runTest {
        var seen: String? = null
        val c = ctrl(this, validate = { v ->
            seen = v["name"] as String?
            FormikErrors.Empty
        })
        c.setFieldValue("name", updater = { prev -> "$prev-suffix" })
        assertEquals("jared-suffix", seen)
        assertEquals("jared-suffix", c.state.value.values["name"])
    }

    // ------------------------------------- P2-4: validate throws → submit rethrows + clears

    @Test
    fun validate_throws_submit_rethrows_and_clearsIsSubmitting() = runTest {
        val c = ctrl(this, validate = { error("validator boom") })
        val ex = assertFails { c.submit() }
        assertContains(ex.message ?: "", "validator boom")
        assertFalse(c.state.value.isSubmitting)
        assertFalse(c.state.value.isValidating)
    }

    // -------------------------- P2-5: schemaValidator throws → submit rethrows + clears

    @Test
    fun schemaValidator_throws_submit_rethrows_and_clearsIsSubmitting() = runTest {
        val schema = SchemaValidator<Map<String, Any?>> { error("schema boom") }
        val c = ctrl(this, schemaValidator = schema)
        val ex = assertFails { c.submit() }
        assertContains(ex.message ?: "", "schema boom")
        assertFalse(c.state.value.isSubmitting)
        assertFalse(c.state.value.isValidating)
    }

    // -------------------------------------- P2-6: deep merge of validate + schema errors

    @Test
    fun mergedValidation_combinesNestedErrors_byPath() = runTest {
        val schema = SchemaValidator<Map<String, Any?>> { _ ->
            buildErrors {
                put("users[0].firstName", "schema-firstName")
                put("users[0].lastName", "schema-lastName")
            }
        }
        val c = ctrl(
            this,
            initialValues = mapOf("users" to listOf(mapOf("firstName" to "", "lastName" to ""))),
            schemaValidator = schema,
            validate = { _ -> buildErrors { put("users[0].firstName", "top-firstName") } },
        )
        c.validateForm()
        // top-level wins for overlapping path; schema covers the non-overlapping path
        assertEquals("top-firstName", c.state.value.errors["users[0].firstName"])
        assertEquals("schema-lastName", c.state.value.errors["users[0].lastName"])
    }

    // -------------------------------- P2-7: setAt with null removes key (nested map)

    @Test
    fun mapValuesUpdater_setAt_null_removesNestedKey() {
        val before = mapOf<String, Any?>("user" to mapOf("name" to "x", "age" to 30))
        val after = MapValuesUpdater.setAt(before, "user.name", null)
        @Suppress("UNCHECKED_CAST")
        val userAfter = after["user"] as Map<String, Any?>
        assertFalse(userAfter.containsKey("name"))
        assertTrue(userAfter.containsKey("age"))
    }

    @Test
    fun mapValuesUpdater_setAt_null_writesNullIntoListSlot() {
        val before = mapOf<String, Any?>("tags" to listOf("a", "b", "c"))
        val after = MapValuesUpdater.setAt(before, "tags[1]", null)
        assertEquals(listOf("a", null, "c"), after["tags"])
    }

    // -------------------------------------- P2-8: structural sharing

    @Test
    fun mapValuesUpdater_setAt_preservesSiblingIdentity() {
        val sibling = mapOf("name" to "sib")
        val before = mapOf<String, Any?>("user" to mapOf("name" to "x"), "other" to sibling)
        val after = MapValuesUpdater.setAt(before, "user.name", "y")
        assertSame(sibling, after["other"], "Sibling subtree must keep reference identity")
        assertNotSame(before["user"], after["user"], "Updated subtree must be a new object")
    }

    // -------------------------- P2-9: initialState.values not aliased to state.values

    @Test
    fun initialValues_and_currentValues_are_separateMaps_butStartEqual() = runTest {
        val c = ctrl(this)
        // Trigger a mutation; initialState should not move.
        c.setFieldValue("name", "ian")
        assertEquals("jared", c.initialState.value.values["name"])
        assertEquals("ian", c.state.value.values["name"])
    }

    // -------------------------------- P2-10: resetForm clears isSubmitting / isValidating

    @Test
    fun resetForm_clearsIsSubmitting_andIsValidating() = runTest {
        val c = ctrl(this)
        c.setSubmitting(true)
        c.setFormikState { it.copy(isValidating = true) }
        c.resetForm()
        assertFalse(c.state.value.isSubmitting)
        assertFalse(c.state.value.isValidating)
    }

    // -------------------------- P2-11: reinitialize + validateOnMount triggers re-validation

    @Test
    fun reinitialize_withValidateOnMount_runsValidationOnNewBaseline() = runTest {
        var calls = 0
        val seenValues = mutableListOf<Map<String, Any?>>()
        val c = FormikController(
            FormikConfig(
                initialValues = mapOf("name" to "old"),
                validate = { v -> calls++; seenValues += v; FormikErrors.Empty },
                onSubmit = { _, _ -> },
                validateOnMount = true,
                enableReinitialize = true,
                coroutineScope = this,
            )
        )
        // validateOnMount ran once at construction.
        testScheduler.advanceUntilIdle()
        val callsAfterInit = calls
        assertTrue(callsAfterInit >= 1)

        c.reinitialize(FormikInitialState(values = mapOf("name" to "new")))
        testScheduler.advanceUntilIdle()
        assertTrue(calls > callsAfterInit, "reinitialize should re-run validation when validateOnMount=true")
        assertEquals("new", seenValues.last()["name"])
    }

    // ------------------------- P2-12: close() cancels in-flight validate; mutations no-op

    @Test
    fun close_cancels_inFlightValidate_andSilencesSubsequentMutations() = runTest {
        // Use a freshly-created controller with its OWN scope (not the test scope) so we can
        // close it without killing the test runner.
        val gate = CompletableDeferred<Unit>()
        val ownScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val c = FormikController(
                FormikConfig(
                    initialValues = mapOf("name" to ""),
                    validate = {
                        gate.complete(Unit)
                        delay(10_000) // never returns within the test
                        FormikErrors.Empty
                    },
                    onSubmit = { _, _ -> },
                    coroutineScope = ownScope,
                )
            )
            val job = ownScope.launch { c.validateForm() }
            gate.await()
            ownScope.cancel()
            // After cancellation, mutations should no-op (silently).
            c.setFieldValue("name", "should-be-dropped")
            // Best-effort assertion: cancellation propagated, value did not change.
            assertEquals("", c.state.value.values["name"])
            assertTrue(job.isCancelled || job.isCompleted)
        } finally {
            ownScope.cancel()
        }
    }

    // -------------------------- P2-13: PathParser handles longer bracket segments

    @Test
    fun pathParser_handlesLongerAlphanumericBracketSegments() {
        assertEquals(
            listOf("user", "superPowers", "0"),
            io.kformik.internal.PathParser.parse("user[superPowers][0]"),
        )
        assertEquals(
            listOf("user", "superPowers", "1"),
            io.kformik.internal.PathParser.parse("user['superPowers'].1"),
        )
    }

    // ----------------------------------------- Bonus: getIn / setIn top-level helpers

    @Test
    fun getIn_returnsDefault_whenPathDoesNotResolve() {
        val tree: Any? = mapOf("a" to mapOf("b" to 1))
        assertEquals(1, getIn(tree, "a.b"))
        assertNull(getIn(tree, "a.c"))
        assertEquals("DEF", getIn(tree, "a.c", "DEF"))
        assertEquals("DEF", getIn(null, "a.b", "DEF"))
    }

    /**
     * v1.9.0: `getIn` preserves an explicitly-stored `null` leaf instead of falling back to the
     * default. Pre-1.9.0 the function conflated "key missing" with "key present with null value"
     * via `current ?: default`, so any consumer reading a nullable field via getIn lost the
     * ability to distinguish "user cleared this" from "user never set this".
     */
    @Test
    fun getIn_distinguishesExplicitNull_fromMissingKey() {
        val tree: Any? = mapOf("a" to mapOf("explicit" to null, "missingTwin" to 1))
        // Explicit null at leaf is preserved — not coerced to default.
        assertNull(getIn(tree, "a.explicit", "DEF"))
        // Missing key still falls back to default.
        assertEquals("DEF", getIn(tree, "a.absent", "DEF"))
        // Descending THROUGH a null intermediate still fails to default — can't keep walking.
        assertEquals("DEF", getIn(tree, "a.explicit.further", "DEF"))
    }

    /**
     * v1.9.0: same null-vs-missing discrimination for list indexes — present-but-null preserved,
     * out-of-range falls back to default.
     */
    @Test
    fun getIn_list_distinguishesExplicitNullElement_fromOutOfRange() {
        val tree: Any? = mapOf("xs" to listOf<Any?>("a", null, "c"))
        assertEquals("a", getIn(tree, "xs[0]", "DEF"))
        assertNull(getIn(tree, "xs[1]", "DEF"))          // explicit null preserved
        assertEquals("c", getIn(tree, "xs[2]", "DEF"))
        assertEquals("DEF", getIn(tree, "xs[5]", "DEF")) // out-of-range falls back
        assertEquals("DEF", getIn(tree, "xs[-1]", "DEF"))// negative falls back
    }

    @Test
    fun setIn_returnsSameMapWhenValueUnchanged() {
        val before = mapOf<String, Any?>("a" to 1)
        assertSame(before, setIn(before, "a", 1))
    }

    @Test
    fun setIn_createsNestedStructure() {
        val before = mapOf<String, Any?>()
        val after = setIn(before, "a.b.c", "x")
        assertEquals(mapOf("a" to mapOf("b" to mapOf("c" to "x"))), after)
    }

    // ------------------------------------------ Bonus: blank field name rejected

    @Test
    fun field_blankName_throws() = runTest {
        val c = ctrl(this)
        assertFails { c.field("") }
        assertFails { c.field("   ") }
    }

    @Test
    fun setFieldValue_blankName_throws() = runTest {
        val c = ctrl(this)
        assertFails { c.setFieldValue("", "x") }
    }

    @Test
    fun registerField_blankName_throws() = runTest {
        val c = ctrl(this)
        assertFails { c.registerField("") }
    }
}
