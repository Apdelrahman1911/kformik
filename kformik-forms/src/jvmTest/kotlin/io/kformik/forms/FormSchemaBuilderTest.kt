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
}
