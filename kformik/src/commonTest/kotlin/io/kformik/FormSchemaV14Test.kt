package io.kformik

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v1.4 schema-API additions:
 *  - Item 4: requiredness introspection (`isRequired`, `requiredFields`, `fieldInfo`).
 *  - Item 5: `failFast=false` / multi-error per field (`validateAll`, `validateAllField`).
 *
 * Both additions are backward-compatible — the existing `validate()` and `validateField()`
 * keep their first-failing-rule semantics.
 */
class FormSchemaV14Test {

    // ─────────────────────────────────────────── Item 4: requiredness introspection

    @Test
    fun isRequired_isTrue_whenRuleIsPresent() {
        val s = formSchema<Map<String, Any?>> {
            field("email") { required() }
            field("nickname") { minLength(3) }
        }
        assertTrue(s.isRequired("email"))
        assertFalse(s.isRequired("nickname"))
        assertFalse(s.isRequired("unknown"))
    }

    @Test
    fun requiredFields_listsOnlyRequiredOnes() {
        val s = formSchema<Map<String, Any?>> {
            field("a") { required() }
            field("b") { minLength(3) }
            field("c") { required(); email() }
        }
        assertEquals(setOf("a", "c"), s.requiredFields())
    }

    @Test
    fun fieldInfo_returnsRuleNames() {
        val s = formSchema<Map<String, Any?>> {
            field("email") { required(); email() }
        }
        val info = s.fieldInfo("email")
        assertNotNull(info)
        assertTrue("required" in info.rules)
        assertTrue("email" in info.rules)
        assertTrue(info.isRequired)
        assertNull(s.fieldInfo("does-not-exist"))
    }

    @Test
    fun fieldInfo_capturesCustomRuleName() {
        val s = formSchema<Map<String, Any?>> {
            field("password") {
                required()
                custom(name = "matchesConfirm") { _, _ -> null }
            }
        }
        val info = s.fieldInfo("password")!!
        assertTrue("matchesConfirm" in info.rules)
        assertTrue(info.isRequired)
    }

    @Test
    fun requiredness_workForNestedAndBracketPaths() {
        val s = formSchema<Map<String, Any?>> {
            field("user.email") { required() }
            field("tags[0]") { required() }
        }
        assertTrue(s.isRequired("user.email"))
        assertTrue(s.isRequired("tags[0]"))
        assertEquals(setOf("user.email", "tags[0]"), s.requiredFields())
    }

    // ─────────────────────────────────────────── Item 5: failFast=false / multi-error

    @Test
    fun validateAll_returnsFirstFailingRule_byDefault() = runTest {
        // Default schema-level failFast=true → at most one error per path.
        val s = formSchema<Map<String, Any?>> {
            field("password") {
                required("Required")
                minLength(8, "Min 8")
                pattern(Regex(".*\\d.*"), "Need a digit")
            }
        }
        val errs = s.validateAll(mapOf("password" to ""))
        assertEquals(listOf("Required"), errs["password"])
    }

    @Test
    fun validateAll_returnsAllErrors_whenFailFastFalse_atSchemaLevel() = runTest {
        val s = formSchema<Map<String, Any?>>(failFast = false) {
            field("password") {
                required("Required")
                minLength(8, "Min 8")
                pattern(Regex(".*\\d.*"), "Need a digit")
            }
        }
        val errs = s.validateAll(mapOf("password" to "abc"))
        // 'required' passes (non-empty), 'minLength' fails, 'pattern' also fails — both reported.
        assertEquals(listOf("Min 8", "Need a digit"), errs["password"])
    }

    @Test
    fun validateAll_perFieldFailFast_overridesSchemaDefault() = runTest {
        // Schema is fail-fast, but the password field opts out.
        val s = formSchema<Map<String, Any?>> {
            field("password", failFast = false) {
                required("Required")
                minLength(8, "Min 8")
                pattern(Regex(".*\\d.*"), "Need a digit")
            }
            field("email") {
                required("Required")
                email("Invalid email")
            }
        }
        val errs = s.validateAll(mapOf("password" to "abc", "email" to "notanemail"))
        assertEquals(listOf("Min 8", "Need a digit"), errs["password"])
        // 'email' field stays fail-fast → only 'Invalid email' (required already passed)
        assertEquals(listOf("Invalid email"), errs["email"])
    }

    @Test
    fun validateAllField_returnsAllErrors_for_failFastFalse() = runTest {
        val s = formSchema<Map<String, Any?>> {
            field("password", failFast = false) {
                minLength(8, "Min 8")
                pattern(Regex(".*\\d.*"), "Need a digit")
            }
        }
        val msgs = s.validateAllField(mapOf("password" to "abc"), "password")
        assertEquals(listOf("Min 8", "Need a digit"), msgs)
    }

    @Test
    fun validate_keepsLegacyFirstFailingContract_unchanged() = runTest {
        // The existing `validate()` API MUST keep returning at most one error per path, even
        // when `failFast=false` — to preserve backward compatibility with `FormikErrors`.
        val s = formSchema<Map<String, Any?>>(failFast = false) {
            field("password") {
                required("Required")
                minLength(8, "Min 8")
            }
        }
        val errs = s.validate(mapOf("password" to ""))
        assertEquals("Required", errs["password"])
    }

    @Test
    fun validateAll_includes_crossFieldErrors() = runTest {
        val s = formSchema<Map<String, Any?>>(failFast = false) {
            field("password") { minLength(8, "Min 8") }
            cross { v ->
                val p = v["password"] as? String
                val c = v["confirm"] as? String
                if (p != c) buildErrors { put("confirm", "Passwords must match") } else FormikErrors.Empty
            }
        }
        val errs = s.validateAll(mapOf("password" to "abc", "confirm" to "other"))
        // Per-field collected:
        assertEquals(listOf("Min 8"), errs["password"])
        // Cross-field appended:
        assertEquals(listOf("Passwords must match"), errs["confirm"])
    }

    @Test
    fun validateAll_emptyMap_whenAllRulesPass() = runTest {
        val s = formSchema<Map<String, Any?>>(failFast = false) {
            field("name") { required(); minLength(2) }
        }
        val errs = s.validateAll(mapOf("name" to "Aisha"))
        assertTrue(errs.isEmpty())
    }
}
