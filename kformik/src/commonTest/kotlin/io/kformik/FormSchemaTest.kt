package io.kformik

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FormSchemaTest {

    private fun ctrl(
        scope: TestScope,
        initialValues: Map<String, Any?>,
        schema: SchemaValidator<Map<String, Any?>>,
    ) = FormikController(
        FormikConfig(
            initialValues = initialValues,
            schemaValidator = schema,
            onSubmit = { _, _ -> },
            coroutineScope = scope,
        )
    )

    // -------------------------------------------------------------------- required

    @Test
    fun required_blocksEmptyString() = runTest {
        val schema = formSchema<Map<String, Any?>> {
            field("email") { required("Email is required") }
        }
        val c = ctrl(this, mapOf("email" to ""), schema)
        val errs = schema.validate(c.state.value.values)
        assertEquals("Email is required", errs["email"])
    }

    @Test
    fun required_blocksNull() = runTest {
        val schema = formSchema<Map<String, Any?>> {
            field("name") { required() }
        }
        val errs = schema.validate(mapOf("name" to null))
        assertEquals("Required", errs["name"])
    }

    @Test
    fun required_blocksEmptyList() = runTest {
        val schema = formSchema<Map<String, Any?>> {
            field("tags") { required("Need tags") }
        }
        val errs = schema.validate(mapOf("tags" to emptyList<String>()))
        assertEquals("Need tags", errs["tags"])
    }

    @Test
    fun required_passesWhenPresent() = runTest {
        val schema = formSchema<Map<String, Any?>> {
            field("email") { required() }
        }
        val errs = schema.validate(mapOf("email" to "x@y.com"))
        assertTrue(errs.isEmpty)
    }

    // -------------------------------------------------------------------- email

    @Test
    fun email_acceptsValid() = runTest {
        val schema = formSchema<Map<String, Any?>> { field("email") { email() } }
        assertTrue(schema.validate(mapOf("email" to "alice@example.com")).isEmpty)
    }

    @Test
    fun email_rejectsInvalid() = runTest {
        val schema = formSchema<Map<String, Any?>> { field("email") { email("nope") } }
        assertEquals("nope", schema.validate(mapOf("email" to "not-an-email"))["email"])
    }

    @Test
    fun email_skipsEmptyByDesign() = runTest {
        // Pair with `required()` if you need empty-strings to fail.
        val schema = formSchema<Map<String, Any?>> { field("email") { email() } }
        assertTrue(schema.validate(mapOf("email" to "")).isEmpty)
    }

    // -------------------------------------------------------------- min/maxLength

    @Test
    fun minLength_failsBelow() = runTest {
        val schema = formSchema<Map<String, Any?>> {
            field("password") { minLength(8, "Min 8") }
        }
        assertEquals("Min 8", schema.validate(mapOf("password" to "short"))["password"])
    }

    @Test
    fun maxLength_failsAbove() = runTest {
        val schema = formSchema<Map<String, Any?>> {
            field("bio") { maxLength(5, "Too long") }
        }
        assertEquals("Too long", schema.validate(mapOf("bio" to "this is way too long"))["bio"])
    }

    @Test
    fun firstFailingRule_wins() = runTest {
        // required is evaluated before minLength
        val schema = formSchema<Map<String, Any?>> {
            field("password") { required("Required"); minLength(8, "Min 8") }
        }
        assertEquals("Required", schema.validate(mapOf("password" to ""))["password"])
    }

    // -------------------------------------------------------------------- pattern

    @Test
    fun pattern_matches() = runTest {
        val schema = formSchema<Map<String, Any?>> {
            field("zip") { pattern(Regex("^[0-9]{5}$"), "5 digits required") }
        }
        assertTrue(schema.validate(mapOf("zip" to "94105")).isEmpty)
        assertEquals("5 digits required", schema.validate(mapOf("zip" to "abc"))["zip"])
    }

    // -------------------------------------------------------------------- min / max

    @Test
    fun min_max_numericRules() = runTest {
        val schema = formSchema<Map<String, Any?>> {
            field("age") { min(18, "Too young"); max(120, "Too old") }
        }
        assertEquals("Too young", schema.validate(mapOf("age" to 12))["age"])
        assertEquals("Too old", schema.validate(mapOf("age" to 200))["age"])
        assertTrue(schema.validate(mapOf("age" to 30)).isEmpty)
    }

    // ────────── min/max non-finite input guards (v1.8.1 regression: NaN passed both)

    /**
     * `NaN < 18` and `NaN > 120` both evaluate to `false` in IEEE 754, so the pre-1.8.1 min/max
     * implementations let `NaN` slip through silently. The fix rejects non-finite values
     * (`NaN`, `±Infinity`) as out-of-range.
     */
    @Test
    fun min_rejectsNaNInputAsOutOfRange() = runTest {
        val schema = formSchema<Map<String, Any?>> {
            field("score") { min(0, "out of range") }
        }
        assertEquals("out of range", schema.validate(mapOf<String, Any?>("score" to Double.NaN))["score"])
    }

    @Test
    fun max_rejectsNaNInputAsOutOfRange() = runTest {
        val schema = formSchema<Map<String, Any?>> {
            field("score") { max(100, "out of range") }
        }
        assertEquals("out of range", schema.validate(mapOf<String, Any?>("score" to Double.NaN))["score"])
    }

    @Test
    fun min_rejectsPositiveInfinityAtInput() = runTest {
        val schema = formSchema<Map<String, Any?>> {
            field("score") { min(0, "out of range") }
        }
        // +Infinity technically satisfies min(0) mathematically, but is rarely a valid form
        // value — treat non-finite inputs as invalid for finite-range rules.
        assertEquals("out of range", schema.validate(mapOf<String, Any?>("score" to Double.POSITIVE_INFINITY))["score"])
    }

    @Test
    fun min_throwsAtSchemaDeclaration_whenBoundIsNonFinite() {
        assertFailsWith<IllegalArgumentException> {
            formSchema<Map<String, Any?>> { field("score") { min(Double.NaN) } }
        }
    }

    @Test
    fun max_throwsAtSchemaDeclaration_whenBoundIsNonFinite() {
        assertFailsWith<IllegalArgumentException> {
            formSchema<Map<String, Any?>> { field("score") { max(Double.NEGATIVE_INFINITY) } }
        }
    }

    // -------------------------------------------------------------------- custom

    @Test
    fun custom_canAccessAllValues() = runTest {
        val schema = formSchema<Map<String, Any?>> {
            field("confirmPassword") {
                custom("matchesPassword") { v, all ->
                    if (v != all["password"]) "Passwords must match" else null
                }
            }
        }
        val mismatch = schema.validate(mapOf("password" to "abc", "confirmPassword" to "xyz"))
        assertEquals("Passwords must match", mismatch["confirmPassword"])
        val match = schema.validate(mapOf("password" to "abc", "confirmPassword" to "abc"))
        assertTrue(match.isEmpty)
    }

    @Test
    fun customValue_skipsAllValuesArg() = runTest {
        val schema = formSchema<Map<String, Any?>> {
            field("nickname") {
                customValue("nopalindrome") { v ->
                    val s = v as? String ?: return@customValue null
                    if (s.isNotEmpty() && s == s.reversed()) "Palindromes not allowed" else null
                }
            }
        }
        assertEquals("Palindromes not allowed", schema.validate(mapOf("nickname" to "anna"))["nickname"])
    }

    // -------------------------------------------------------------------- nested paths

    @Test
    fun nestedPath_isValidated() = runTest {
        val schema = formSchema<Map<String, Any?>> {
            field("user.address.city") { required("City required") }
        }
        val errs = schema.validate(
            mapOf("user" to mapOf("address" to mapOf("city" to "")))
        )
        assertEquals("City required", errs["user.address.city"])
    }

    @Test
    fun bracketPath_isValidated() = runTest {
        val schema = formSchema<Map<String, Any?>> {
            field("tags[1]") { minLength(3, "Tag must be 3+ chars") }
        }
        val errs = schema.validate(mapOf("tags" to listOf("alpha", "bb", "gamma")))
        assertEquals("Tag must be 3+ chars", errs["tags[1]"])
    }

    // ----------------------------------------------------------------- cross-field

    @Test
    fun crossField_addsErrors() = runTest {
        val schema = formSchema<Map<String, Any?>> {
            field("password") { required(); minLength(8) }
            cross { v ->
                val p = v["password"] as? String ?: return@cross FormikErrors.Empty
                val c = v["confirmPassword"] as? String ?: return@cross FormikErrors.Empty
                if (p != c) buildErrors { put("confirmPassword", "Mismatch") }
                else FormikErrors.Empty
            }
        }
        val errs = schema.validate(mapOf("password" to "long-enough", "confirmPassword" to "other"))
        assertEquals("Mismatch", errs["confirmPassword"])
    }

    @Test
    fun crossField_overridesPerFieldErrorOnSamePath() = runTest {
        val schema = formSchema<Map<String, Any?>> {
            field("a") { custom { _, _ -> "per-field" } }
            cross { _ -> buildErrors { put("a", "cross-field") } }
        }
        val errs = schema.validate(mapOf("a" to "x"))
        assertEquals("cross-field", errs["a"])
    }

    // ----------------------------------------------------------------- async rules

    @Test
    fun asyncCustomRule_isSupported() = runTest {
        val schema = formSchema<Map<String, Any?>> {
            field("username") {
                customValue("apiCheck") { v ->
                    delay(50)
                    if ((v as String) == "taken") "Already taken" else null
                }
            }
        }
        assertEquals("Already taken", schema.validate(mapOf("username" to "taken"))["username"])
        assertTrue(schema.validate(mapOf("username" to "free")).isEmpty)
    }

    // ----------------------------------------------------- integration with controller

    @Test
    fun controller_validateForm_runsSchema() = runTest {
        val schema = formSchema<Map<String, Any?>> {
            field("email") { required("Required") }
            field("password") { required("Required"); minLength(8, "Min 8") }
        }
        val c = ctrl(this, mapOf("email" to "", "password" to "short"), schema)
        c.validateForm()
        assertEquals("Required", c.state.value.errors["email"])
        assertEquals("Min 8", c.state.value.errors["password"])
    }

    @Test
    fun controller_validateField_usesFocusedSchemaPath() = runTest {
        val schema = formSchema<Map<String, Any?>> {
            field("email") { required("Email required") }
            field("password") { required(); minLength(8) }
        }
        val c = ctrl(this, mapOf("email" to "", "password" to "x"), schema)
        val msg = c.validateField("email")
        assertEquals("Email required", msg)
        // password field's error should not be set, because focused validate only ran 'email'.
        assertNull(c.state.value.errors["password"])
    }

    @Test
    fun controller_validateField_returnsNullForUnknownPath() = runTest {
        val schema = formSchema<Map<String, Any?>> {
            field("known") { required() }
        }
        val c = ctrl(this, mapOf("known" to "x"), schema)
        val msg = c.validateField("does-not-exist")
        assertNull(msg)
    }

    // ----------------------------------------------------- input validation / introspection

    @Test
    fun field_blankPath_throws() {
        assertFails {
            formSchema<Map<String, Any?>> {
                field("") { required() }
            }
        }
    }

    @Test
    fun fields_introspection() {
        val schema = formSchema<Map<String, Any?>> {
            field("a") { required() }
            field("b") { required() }
        }
        assertEquals(setOf("a", "b"), schema.fields())
        assertTrue(schema.hasField("a"))
        assertTrue(!schema.hasField("c"))
    }
}
