package io.kformik.forms

import io.kformik.buildErrors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract for [buildSchemaFrom] — the bridge between the declarative `Map<String, Field>` shape
 * and the existing `FormSchema<Map<String, Any?>>` validator.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FormSchemaBuilderTest {

    @Test
    fun rules_block_isAppliedToCorrectPath() = runTest {
        val fields = mapOf(
            "email" to Field(type = FieldType.Email, rules = { email() }),
            "password" to Field(type = FieldType.Password, rules = { minLength(8) }),
        )
        val schema = buildSchemaFrom(fields)

        val errs = schema.validate(mapOf("email" to "not-an-email", "password" to "short"))
        assertNotNull(errs["email"], "email rule should attach to 'email' path")
        assertNotNull(errs["password"], "minLength rule should attach to 'password' path")
        assertNull(errs["nonexistent"], "no rule should leak to other paths")
    }

    @Test
    fun required_auto_injectsWhen_field_required_isTrue() = runTest {
        val fields = mapOf("name" to Field(type = FieldType.Text, required = true))
        val schema = buildSchemaFrom(fields)

        val errs = schema.validate(mapOf("name" to ""))
        assertEquals("Required", errs["name"], "Field.required=true should auto-inject a required() rule")
    }

    @Test
    fun required_isNotDuplicated_whenUserAlreadyDeclaredOne() = runTest {
        val fields = mapOf(
            "name" to Field(
                type = FieldType.Text,
                required = true,
                rules = { required("Name is mandatory") },   // user-supplied custom message
            ),
        )
        val schema = buildSchemaFrom(fields)

        // The auto-inject path is suppressed because the user already declared required().
        // So the user's custom message wins.
        val info = schema.fieldInfo("name")
        assertNotNull(info, "fieldInfo should be present")
        assertEquals(
            1,
            info.rules.count { it == "required" },
            "required rule should appear exactly once, not duplicated",
        )

        val errs = schema.validate(mapOf("name" to ""))
        assertEquals("Name is mandatory", errs["name"], "user's custom required() message should win")
    }

    @Test
    fun required_autoInject_prependsBeforeOtherRules_forFailFastOrdering() = runTest {
        // Empty email should surface "Required" (auto-inject) before "Invalid email" (user's rule).
        val fields = mapOf(
            "email" to Field(
                type = FieldType.Email,
                required = true,
                rules = { email() },
            ),
        )
        val schema = buildSchemaFrom(fields)
        val errs = schema.validate(mapOf("email" to ""))
        assertEquals("Required", errs["email"], "auto-required should run before email() — fail-fast")
    }

    @Test
    fun field_required_isFalse_doesNotInjectRequired() = runTest {
        val fields = mapOf("name" to Field(type = FieldType.Text, required = false))
        val schema = buildSchemaFrom(fields)
        val errs = schema.validate(mapOf("name" to ""))
        assertNull(errs["name"], "no required flag and no rules → no error on empty value")
        val info = schema.fieldInfo("name")
        // Either no fieldInfo at all (path never registered) or rules list doesn't include required.
        if (info != null) {
            assertTrue("required" !in info.rules, "should NOT auto-inject required when field.required=false")
        }
    }

    @Test
    fun crossField_rule_inUserBlock_works() = runTest {
        val fields = mapOf(
            "password" to Field(type = FieldType.Password, rules = { minLength(8) }),
            "confirm" to Field(
                type = FieldType.Password,
                rules = {
                    custom(name = "matches-password") { v, allValues ->
                        if (v != allValues["password"]) "Doesn't match" else null
                    }
                },
            ),
        )
        val schema = buildSchemaFrom(fields)
        val errs = schema.validate(mapOf("password" to "hunter22", "confirm" to "wrong"))
        assertEquals("Doesn't match", errs["confirm"])
    }

    @Test
    fun fields_with_no_rules_andNotRequired_produceNoErrors() = runTest {
        val fields = mapOf(
            "free" to Field(type = FieldType.Text),
            "empty" to Field(type = FieldType.Number(asInt = true)),
        )
        val schema = buildSchemaFrom(fields)
        val errs = schema.validate(mapOf("free" to "", "empty" to 0))
        assertTrue(errs.byPath.isEmpty(), "unrestricted fields should never produce errors")
    }

    @Test
    fun emptyFieldMap_buildsEmptySchema() = runTest {
        val schema = buildSchemaFrom(emptyMap())
        val errs = schema.validate(emptyMap())
        assertTrue(errs.byPath.isEmpty())
    }

    @Test
    fun integration_initialValues_andSchema_together() = runTest {
        // Smoke test: the same field set produces consistent initialValues + schema. Equivalent
        // to what KformikForm wires together at composition time.
        val fields = mapOf(
            "email" to Field(type = FieldType.Email, required = true, rules = { email() }),
            "age" to Field(type = FieldType.Number(asInt = true), initialValue = 21, rules = { min(18) }),
            "accept" to Field(
                type = FieldType.Checkbox,
                // "must check this" semantics require a custom rule — see [required_onCheckbox_…] below.
                rules = { custom("Must accept") { v, _ -> if (v != true) "Must accept" else null } },
            ),
        )
        val initial = buildInitialValuesFrom(fields)
        val schema = buildSchemaFrom(fields)

        assertEquals("", initial["email"])
        assertEquals(21, initial["age"])
        assertEquals(false, initial["accept"])

        val errs = schema.validate(initial)
        assertNotNull(errs["email"], "empty email → required")
        assertNull(errs["age"], "21 >= 18 → no min violation")
        assertEquals("Must accept", errs["accept"], "custom 'must be true' rule fires on unchecked checkbox")
    }

    /**
     * The core `required()` rule from `:kformik` treats a missing value as:
     *  - `null`
     *  - blank String
     *  - empty Collection / Map
     *
     * Booleans (including `false`) are NOT treated as missing — `false` *is* a value, just not
     * the truthy one. So `Field(type = Checkbox, required = true)` ensures the value is non-null
     * (it always is for a Compose Checkbox), but does NOT enforce "must be checked". For the
     * "must accept TOS" pattern, use a `custom` rule comparing to `true`. This test pins that
     * semantic so future contributors don't accidentally change it.
     */
    @Test
    fun required_onCheckbox_doesNotEnforceMustBeChecked() = runTest {
        val fields = mapOf("accept" to Field(type = FieldType.Checkbox, required = true))
        val schema = buildSchemaFrom(fields)
        val errs = schema.validate(mapOf("accept" to false))
        assertNull(errs["accept"], "required() doesn't fire on Boolean false; use a custom rule for 'must be true'")
    }

    /**
     * Schema's `validate` reads through `MapValuesUpdater.getAt` for `Map<*, *>` values, which
     * parses `"user.email"` into segments `[user, email]` and walks the nested map. So a nested
     * input shape (matching what [buildInitialValuesFrom] produces for nested-path keys) resolves
     * correctly and the per-field error attaches at the full dotted path.
     */
    @Test
    fun nested_paths_in_Field_name_validate_correctly() = runTest {
        val fields = mapOf("user.email" to Field(type = FieldType.Email, rules = { email() }))
        val schema = buildSchemaFrom(fields)

        val errs = schema.validate(mapOf("user" to mapOf("email" to "bad")))
        assertEquals(
            "Invalid email",
            errs["user.email"],
            "nested-path rule should resolve via MapValuesUpdater and attach at 'user.email'",
        )

        val ok = schema.validate(mapOf("user" to mapOf("email" to "a@b.co")))
        assertNull(ok["user.email"], "valid nested email should pass")
    }

    @Test
    fun field_with_no_rules_block_passes_validation_when_required_false() = runTest {
        val fields = mapOf("name" to Field(type = FieldType.Text)) // required defaults to false
        val schema = buildSchemaFrom(fields)
        val errs = schema.validate(mapOf("name" to ""))
        assertNull(errs["name"], "no rules + required=false → empty string is fine")
        assertTrue(errs.byPath.isEmpty(), "no errors anywhere on the form")
    }

    @Test
    fun field_with_required_true_and_no_rules_block_still_injects_required() = runTest {
        val fields = mapOf("name" to Field(type = FieldType.Text, required = true))
        val schema = buildSchemaFrom(fields)
        val errs = schema.validate(mapOf("name" to ""))
        assertEquals("Required", errs["name"], "Field.required=true with no rules block still injects required()")
    }

    @Test
    fun field_with_required_true_and_custom_message_overrides_default() = runTest {
        val fields = mapOf(
            "name" to Field(
                type = FieldType.Text,
                required = true,
                rules = { required("Name is mandatory") },
            ),
        )
        val schema = buildSchemaFrom(fields)
        val errs = schema.validate(mapOf("name" to ""))
        assertEquals(
            "Name is mandatory",
            errs["name"],
            "user's custom required() message should override the default 'Required'",
        )
    }

    /**
     * Rules registered in a field's block run in declaration order; the schema's first-failing-rule
     * contract means the earlier-declared rule's message wins when both fail. Documenting this
     * order-sensitive behavior keeps refactors from accidentally reordering the rule list.
     */
    @Test
    fun field_with_email_chained_with_minLength_appliesBoth_inOrder() = runTest {
        val fields = mapOf(
            "addr" to Field(
                type = FieldType.Email,
                rules = {
                    email()
                    minLength(5)
                },
            ),
        )
        val schema = buildSchemaFrom(fields)

        // "a@b.c" — passes email (one @, dot in domain), length 5 → no error
        val okErrs = schema.validate(mapOf("addr" to "a@b.c"))
        assertNull(okErrs["addr"], "valid email of exactly minLength should pass both rules")

        // "x" — fails email AND fails minLength; email is declared first → its message wins
        val badErrs = schema.validate(mapOf("addr" to "x"))
        assertEquals(
            "Invalid email",
            badErrs["addr"],
            "first-declared rule (email) wins fail-fast over later-declared minLength",
        )
    }

    @Test
    fun field_with_min_max_numeric_rules_apply_correctly() = runTest {
        val fields = mapOf(
            "score" to Field(
                type = FieldType.Number(asInt = true),
                rules = {
                    min(10)
                    max(100)
                },
            ),
        )
        val schema = buildSchemaFrom(fields)

        val tooLow = schema.validate(mapOf("score" to 5))
        assertEquals("Must be at least 10", tooLow["score"], "5 < 10 should fail min")

        val inRange = schema.validate(mapOf("score" to 50))
        assertNull(inRange["score"], "50 is in [10, 100], should pass")

        val tooHigh = schema.validate(mapOf("score" to 150))
        assertEquals("Must be at most 100", tooHigh["score"], "150 > 100 should fail max")
    }

    @Test
    fun field_with_customValue_accessing_value_works() = runTest {
        val fields = mapOf(
            "n" to Field(
                type = FieldType.Number(asInt = true),
                rules = {
                    customValue("even") { v -> if ((v as? Int)?.rem(2) == 0) null else "Must be even" }
                },
            ),
        )
        val schema = buildSchemaFrom(fields)

        assertNull(schema.validate(mapOf("n" to 4))["n"], "even value passes")
        assertEquals("Must be even", schema.validate(mapOf("n" to 5))["n"], "odd value fails")
    }

    @Test
    fun field_with_custom_accessing_allValues_works() = runTest {
        val fields = mapOf(
            "pwd" to Field(type = FieldType.Password),
            "cnf" to Field(
                type = FieldType.Password,
                rules = {
                    custom("matchPwd") { v, allValues ->
                        if (v == allValues["pwd"]) null else "Mismatch"
                    }
                },
            ),
        )
        val schema = buildSchemaFrom(fields)

        val mismatch = schema.validate(mapOf("pwd" to "secret", "cnf" to "different"))
        assertEquals("Mismatch", mismatch["cnf"], "custom rule should see both fields via allValues")

        val ok = schema.validate(mapOf("pwd" to "secret", "cnf" to "secret"))
        assertNull(ok["cnf"], "matching passwords should pass the cross-field custom rule")
    }

    @Test
    fun field_with_pattern_rule_appliesRegex() = runTest {
        val fields = mapOf(
            "code" to Field(
                type = FieldType.Text,
                rules = { pattern(Regex("^[A-Z]+$")) },
            ),
        )
        val schema = buildSchemaFrom(fields)

        assertNull(schema.validate(mapOf("code" to "ABC"))["code"], "uppercase only string matches")
        assertEquals(
            "Does not match pattern",
            schema.validate(mapOf("code" to "abc"))["code"],
            "lowercase string does not match ^[A-Z]+$",
        )
    }

    /**
     * Pins the documented default-value contract for [FieldType.Select]: omitting [Field.initialValue]
     * picks the first option's value; passing `initialValue = null` explicitly preserves null
     * (the "no selection" escape hatch). Pre-1.9.0 used `?:` and so explicit-null silently fell
     * back to the first option — this test guards against that regression.
     */
    @Test
    fun select_default_is_firstOption_unless_explicitNull() = runTest {
        val opts = listOf(SelectOption("a", "A"), SelectOption("b", "B"))

        val implicit = buildInitialValuesFrom(mapOf("pick" to Field(type = FieldType.Select(opts))))
        assertEquals("a", implicit["pick"], "Select with no initialValue defaults to first option's value")

        val explicit = buildInitialValuesFrom(
            mapOf("pick" to Field(type = FieldType.Select(opts), initialValue = null)),
        )
        assertNull(explicit["pick"], "explicit initialValue=null is preserved verbatim (no first-option fallback)")
    }

    @Test
    fun radio_default_same_contract() = runTest {
        val opts = listOf(SelectOption("x", "X"), SelectOption("y", "Y"))

        val implicit = buildInitialValuesFrom(mapOf("choice" to Field(type = FieldType.Radio(opts))))
        assertEquals("x", implicit["choice"], "Radio with no initialValue defaults to first option's value")

        val explicit = buildInitialValuesFrom(
            mapOf("choice" to Field(type = FieldType.Radio(opts), initialValue = null)),
        )
        assertNull(explicit["choice"], "explicit initialValue=null is preserved verbatim for Radio too")
    }
}
