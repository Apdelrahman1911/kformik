package io.kformik

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultRuleRegistryTest {

    private val reg = ruleRegistry<Map<String, Any?>>()

    // ---------------------------------------------------------------- registry contents

    @Test
    fun defaultRegistry_resolves7BuiltIns() {
        val expected = setOf("required", "minLength", "maxLength", "email", "pattern", "min", "max")
        assertEquals(expected, reg.names())
    }

    @Test
    fun defaultRegistry_doesNotResolveCustom() {
        // A backend-resolvable 'custom' would need a function in its params — violates the
        // "params are plain data" contract. custom stays as a hand-written DSL escape hatch.
        assertFalse(reg.has("custom"))
        assertFalse(reg.has("customValue"))
    }

    // ---------------------------------------------------------------- per-builtin behavior

    @Test
    fun required_resolvesAndValidatesEmptyString() = runTest {
        val schema = formSchema<Map<String, Any?>> {
            field("name") { spec(reg, RuleSpec("required")) }
        }
        assertEquals("Required", schema.validate(mapOf("name" to "")).byPath["name"])
        assertEquals("Required", schema.validate(mapOf("name" to null)).byPath["name"])
        assertNull(schema.validate(mapOf("name" to "ok")).byPath["name"])
    }

    @Test
    fun minLength_resolvesAndUsesValueParam() = runTest {
        val schema = formSchema<Map<String, Any?>> {
            field("nick") { spec(reg, RuleSpec("minLength", mapOf("value" to 3))) }
        }
        assertEquals("Must be at least 3 characters", schema.validate(mapOf("nick" to "ab")).byPath["nick"])
        assertNull(schema.validate(mapOf("nick" to "abc")).byPath["nick"])
    }

    @Test
    fun maxLength_resolvesAndUsesValueParam() = runTest {
        val schema = formSchema<Map<String, Any?>> {
            field("nick") { spec(reg, RuleSpec("maxLength", mapOf("value" to 4))) }
        }
        assertEquals("Must be at most 4 characters", schema.validate(mapOf("nick" to "abcde")).byPath["nick"])
        assertNull(schema.validate(mapOf("nick" to "ok")).byPath["nick"])
    }

    @Test
    fun email_resolvesAndValidates() = runTest {
        val schema = formSchema<Map<String, Any?>> {
            field("e") { spec(reg, RuleSpec("email")) }
        }
        assertEquals("Invalid email", schema.validate(mapOf("e" to "not-an-email")).byPath["e"])
        assertNull(schema.validate(mapOf("e" to "user@example.com")).byPath["e"])
    }

    @Test
    fun pattern_resolvesFromStringValueParam() = runTest {
        val schema = formSchema<Map<String, Any?>> {
            field("digits") { spec(reg, RuleSpec("pattern", mapOf("value" to "^[0-9]+$"))) }
        }
        assertEquals("Does not match pattern", schema.validate(mapOf("digits" to "abc")).byPath["digits"])
        assertNull(schema.validate(mapOf("digits" to "1234")).byPath["digits"])
    }

    @Test
    fun pattern_acceptsRegexValueDirectly() = runTest {
        val schema = formSchema<Map<String, Any?>> {
            field("digits") { spec(reg, RuleSpec("pattern", mapOf("value" to Regex("^[0-9]+$")))) }
        }
        assertEquals("Does not match pattern", schema.validate(mapOf("digits" to "abc")).byPath["digits"])
    }

    @Test
    fun min_resolvesFromNumberValueParam() = runTest {
        val schema = formSchema<Map<String, Any?>> {
            field("age") { spec(reg, RuleSpec("min", mapOf("value" to 18))) }
        }
        // The underlying DSL renders the default message using the original Number's toString.
        val msg = schema.validate(mapOf("age" to 17)).byPath["age"]!!
        assertTrue(msg.startsWith("Must be at least"))
        assertTrue(msg.contains("18"))
        assertNull(schema.validate(mapOf("age" to 18)).byPath["age"])
    }

    @Test
    fun max_resolvesFromNumberValueParam() = runTest {
        val schema = formSchema<Map<String, Any?>> {
            field("age") { spec(reg, RuleSpec("max", mapOf("value" to 60))) }
        }
        val msg = schema.validate(mapOf("age" to 61)).byPath["age"]!!
        assertTrue(msg.startsWith("Must be at most"))
        assertTrue(msg.contains("60"))
        assertNull(schema.validate(mapOf("age" to 60)).byPath["age"])
    }

    @Test
    fun min_acceptsLongFromJsonStyleNumbers() = runTest {
        // kotlinx-serialization decodes JSON integer literals as Long — must work transparently.
        val schema = formSchema<Map<String, Any?>> {
            field("age") { spec(reg, RuleSpec("min", mapOf("value" to 18L))) }
        }
        assertNull(schema.validate(mapOf("age" to 20)).byPath["age"])
        assertTrue(schema.validate(mapOf("age" to 5)).byPath["age"] != null)
    }

    // ---------------------------------------------------------------- per-spec message override

    @Test
    fun messageParam_overridesDefault_forAll7BuiltIns() = runTest {
        // required
        var schema = formSchema<Map<String, Any?>> {
            field("f") { spec(reg, RuleSpec("required", mapOf("message" to "MyRequired"))) }
        }
        assertEquals("MyRequired", schema.validate(mapOf("f" to "")).byPath["f"])

        // minLength
        schema = formSchema<Map<String, Any?>> {
            field("f") { spec(reg, RuleSpec("minLength", mapOf("value" to 3, "message" to "MyMinLen"))) }
        }
        assertEquals("MyMinLen", schema.validate(mapOf("f" to "ab")).byPath["f"])

        // maxLength
        schema = formSchema<Map<String, Any?>> {
            field("f") { spec(reg, RuleSpec("maxLength", mapOf("value" to 2, "message" to "MyMaxLen"))) }
        }
        assertEquals("MyMaxLen", schema.validate(mapOf("f" to "abc")).byPath["f"])

        // email
        schema = formSchema<Map<String, Any?>> {
            field("f") { spec(reg, RuleSpec("email", mapOf("message" to "MyEmail"))) }
        }
        assertEquals("MyEmail", schema.validate(mapOf("f" to "nope")).byPath["f"])

        // pattern
        schema = formSchema<Map<String, Any?>> {
            field("f") { spec(reg, RuleSpec("pattern", mapOf("value" to "^[a-z]+$", "message" to "MyPattern"))) }
        }
        assertEquals("MyPattern", schema.validate(mapOf("f" to "A1")).byPath["f"])

        // min
        schema = formSchema<Map<String, Any?>> {
            field("f") { spec(reg, RuleSpec("min", mapOf("value" to 10, "message" to "MyMin"))) }
        }
        assertEquals("MyMin", schema.validate(mapOf("f" to 5)).byPath["f"])

        // max
        schema = formSchema<Map<String, Any?>> {
            field("f") { spec(reg, RuleSpec("max", mapOf("value" to 10, "message" to "MyMax"))) }
        }
        assertEquals("MyMax", schema.validate(mapOf("f" to 11)).byPath["f"])
    }

    // ---------------------------------------------------------------- param-type-error surfaces at build time

    @Test
    fun min_missingValueParam_throwsAtSpecsInvocation() {
        val ex = kotlin.runCatching {
            formSchema<Map<String, Any?>> {
                field("age") { spec(reg, RuleSpec("min")) }  // missing 'value' param
            }
        }.exceptionOrNull()
        // Throws RuleParamException (not RuleResolutionException — the rule WAS resolved; its
        // params just don't carry what the handler needs).
        assertTrue(ex is RuleParamException, "expected RuleParamException, got ${ex?.let { it::class.simpleName }}")
        assertEquals("min", (ex as RuleParamException).ruleName)
        assertEquals("value", ex.paramKey)
    }
}
