package io.kformik

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuleRegistryTest {

    // ---------------------------------------------------------------- ruleRegistry vs emptyRuleRegistry

    @Test
    fun ruleRegistry_isPreseededWith7DeclarativeBuiltins() {
        val reg = ruleRegistry<Map<String, Any?>>()
        val expected = setOf("required", "minLength", "maxLength", "email", "pattern", "min", "max")
        assertEquals(expected, reg.names())
    }

    @Test
    fun emptyRuleRegistry_hasNoHandlers() {
        val reg = emptyRuleRegistry<Map<String, Any?>>()
        assertTrue(reg.names().isEmpty())
        assertFalse(reg.has("min"))
    }

    @Test
    fun register_addsCustomHandler() {
        val reg = ruleRegistry<Map<String, Any?>> {
            register("phone") { _ -> customValue("phone") { v -> if (v is String && v.startsWith("+")) null else "Invalid phone" } }
        }
        assertTrue(reg.has("phone"))
        assertTrue("phone" in reg.names())
    }

    @Test
    fun register_overridesBuiltin_byName() {
        // Override the built-in 'min' with a sentinel handler we can detect from rule introspection.
        val reg = ruleRegistry<Map<String, Any?>> {
            register("min") { _ -> custom("min-overridden") { _, _ -> null } }
        }
        val schema = formSchema<Map<String, Any?>> {
            field("x") { spec(reg, RuleSpec("min", mapOf("value" to 1))) }
        }
        assertEquals(listOf("min-overridden"), schema.fieldInfo("x")?.rules)
    }

    // ---------------------------------------------------------------- alias

    @Test
    fun alias_resolvesTransparently_returnsCanonicalName() {
        val reg = ruleRegistry<Map<String, Any?>> {
            alias(from = "length_min", to = "minLength")
        }
        // The alias is visible via has() and aliases() but does NOT inflate names().
        assertTrue(reg.has("length_min"))
        assertFalse("length_min" in reg.names())
        assertEquals(mapOf("length_min" to "minLength"), reg.aliases())

        // Resolution through the alias produces a FieldRule whose name is the CANONICAL one,
        // not the alias. This keeps fieldInfo / isRequired / requiredFields uniform.
        val schema = formSchema<Map<String, Any?>> {
            field("x") { spec(reg, RuleSpec("length_min", mapOf("value" to 3))) }
        }
        assertEquals(listOf("minLength"), schema.fieldInfo("x")?.rules)
    }

    @Test
    fun alias_chain_resolvesThroughIntermediate() {
        val reg = ruleRegistry<Map<String, Any?>> {
            alias(from = "len_min", to = "length_min")
            alias(from = "length_min", to = "minLength")
        }
        assertEquals("minLength", reg.aliases()["len_min"])
        assertEquals("minLength", reg.aliases()["length_min"])
    }

    @Test
    fun aliasCycle_throwsAtRegistryBuild() {
        val ex = assertFailsWith<RuleResolutionException> {
            ruleRegistry<Map<String, Any?>> {
                alias("a", "b")
                alias("b", "c")
                alias("c", "a")
            }
        }
        assertTrue(ex.message!!.contains("cycle", ignoreCase = true), "expected cycle msg: ${ex.message}")
        // Diagnostic message should also visualize the chain so the user can see which nodes loop.
        assertTrue(ex.message!!.contains("a"), "cycle msg should name node 'a': ${ex.message}")
        assertTrue(ex.message!!.contains("b"), "cycle msg should name node 'b': ${ex.message}")
        assertTrue(ex.message!!.contains("c"), "cycle msg should name node 'c': ${ex.message}")
    }

    @Test
    fun alias_chainOf8Edges_resolvesCleanly_endToEnd() = runTest {
        // Boundary: a chain of exactly 8 alias edges (a1→a2→...→a8→minLength) is supported.
        // Verify both metadata view (canonical name) AND end-to-end validation through the chain.
        val reg = ruleRegistry<Map<String, Any?>> {
            alias("a1", "a2"); alias("a2", "a3"); alias("a3", "a4")
            alias("a4", "a5"); alias("a5", "a6"); alias("a6", "a7")
            alias("a7", "a8"); alias("a8", "minLength")  // 8 edges
        }
        val schema = formSchema<Map<String, Any?>> {
            field("nick") { spec(reg, RuleSpec("a1", mapOf("value" to 3))) }
        }
        assertEquals(listOf("minLength"), schema.fieldInfo("nick")?.rules)
        // End-to-end: validation through the 8-edge chain produces the canonical rule message.
        val err = schema.validate(mapOf("nick" to "ab")).byPath["nick"]
        assertEquals("Must be at least 3 characters", err)
    }

    @Test
    fun alias_chainOf9Edges_throwsAtBuild() {
        // Adding one more edge (9 total) trips the depth cap. Must throw with a depth message,
        // not a generic cycle message — so a user can disambiguate the failure mode.
        val ex = assertFailsWith<RuleResolutionException> {
            ruleRegistry<Map<String, Any?>> {
                alias("a1", "a2"); alias("a2", "a3"); alias("a3", "a4")
                alias("a4", "a5"); alias("a5", "a6"); alias("a6", "a7")
                alias("a7", "a8"); alias("a8", "a9"); alias("a9", "minLength")  // 9 edges
            }
        }
        assertTrue(
            ex.message!!.contains("deep", ignoreCase = true),
            "expected depth-related message, not generic cycle: ${ex.message}",
        )
    }

    @Test
    fun alias_redeclaration_lastWriteWins() {
        // alias("foo", "min") then alias("foo", "max") — second overwrites first.
        val reg = ruleRegistry<Map<String, Any?>> {
            alias("foo", "min")
            alias("foo", "max")
        }
        assertEquals("max", reg.aliases()["foo"])
        // Verify end-to-end via the alias produces a "max" rule, not a "min" rule.
        val schema = formSchema<Map<String, Any?>> {
            field("x") { spec(reg, RuleSpec("foo", mapOf("value" to 10))) }
        }
        assertEquals(listOf("max"), schema.fieldInfo("x")?.rules)
    }

    @Test
    fun alias_toUserRegisteredHandler_resolvesTransparently_canonicalNameWins() = runTest {
        // Symmetry test: aliasing to a CUSTOM (not built-in) handler still produces the canonical
        // name in FieldRule.name, mirroring the alias→built-in case.
        val reg = ruleRegistry<Map<String, Any?>> {
            register("phone") { params ->
                val msg = params.stringOrNull("message") ?: "Bad phone"
                custom("phone") { v, _ ->
                    val s = v as? String ?: return@custom null
                    if (s.startsWith("+")) null else msg
                }
            }
            alias(from = "phoneNumber", to = "phone")
        }
        val schema = formSchema<Map<String, Any?>> {
            field("p") { spec(reg, RuleSpec("phoneNumber")) }
        }
        // FieldRule.name is the canonical 'phone', not the alias 'phoneNumber'.
        assertEquals(listOf("phone"), schema.fieldInfo("p")?.rules)
        // Validates correctly through the alias.
        assertEquals("Bad phone", schema.validate(mapOf("p" to "555-1234")).byPath["p"])
        assertNull(schema.validate(mapOf("p" to "+15551234")).byPath["p"])
    }

    @Test
    fun alias_toUnregisteredTarget_throwsAtBuildTime() {
        val ex = assertFailsWith<RuleResolutionException> {
            emptyRuleRegistry<Map<String, Any?>> {
                alias("foo", "bar")  // 'bar' is not registered
            }
        }
        assertEquals("foo", ex.ruleName)
        assertTrue(ex.message!!.contains("bar"), "expected target name in message: ${ex.message}")
    }

    @Test
    fun alias_selfReference_throwsAtAliasDeclaration() {
        assertFailsWith<IllegalArgumentException> {
            ruleRegistry<Map<String, Any?>> {
                alias(from = "x", to = "x")
            }
        }
    }

    @Test
    fun alias_blankNames_throw() {
        assertFailsWith<IllegalArgumentException> {
            ruleRegistry<Map<String, Any?>> { alias(from = "", to = "min") }
        }
        assertFailsWith<IllegalArgumentException> {
            ruleRegistry<Map<String, Any?>> { alias(from = "x", to = "") }
        }
    }

    @Test
    fun register_blankName_throws() {
        assertFailsWith<IllegalArgumentException> {
            ruleRegistry<Map<String, Any?>> { register("") { _ -> required() } }
        }
    }

    // ---------------------------------------------------------------- unknown-rule policy

    @Test
    fun unknownRuleName_throwsAtSpecsInvocation() {
        val reg = ruleRegistry<Map<String, Any?>>()
        val ex = assertFailsWith<RuleResolutionException> {
            formSchema<Map<String, Any?>> {
                field("x") { spec(reg, RuleSpec("matchesPhone")) }
            }
        }
        assertEquals("matchesPhone", ex.ruleName)
        assertTrue(ex.message!!.contains("Unknown"), "expected 'Unknown' in: ${ex.message}")
    }

    @Test
    fun skip_emitsOnUnknownRuleCallback_andDropsSpec() = runTest {
        val seen = mutableListOf<RuleSpec>()
        val reg = ruleRegistry<Map<String, Any?>> {
            unknownRulePolicy(UnknownRulePolicy.Skip)
            onUnknownRule { spec -> seen += spec }
        }
        val schema = formSchema<Map<String, Any?>> {
            field("x") {
                spec(reg, RuleSpec("matchesPhone"))           // unknown — skipped + reported
                spec(reg, RuleSpec("min", mapOf("value" to 1)))  // known — applied
            }
        }
        // Skipped spec did NOT crash; the known spec was still applied.
        assertEquals(listOf("min"), schema.fieldInfo("x")?.rules)
        // The callback observed the skip exactly once with the right spec.
        assertEquals(1, seen.size)
        assertEquals("matchesPhone", seen[0].name)
    }

    @Test
    fun skip_withNullCallback_isTrulySilent() {
        val reg = ruleRegistry<Map<String, Any?>> {
            unknownRulePolicy(UnknownRulePolicy.Skip)
            // no onUnknownRule wired — null is the default
        }
        // Must not throw. Field ends up with no rules at all.
        val schema = formSchema<Map<String, Any?>> {
            field("x") { spec(reg, RuleSpec("totallyMadeUp")) }
        }
        assertEquals(emptyList(), schema.fieldInfo("x")?.rules)
    }

    @Test
    fun skip_doesNotShortCircuitSubsequentSpecs() {
        val reg = ruleRegistry<Map<String, Any?>> {
            unknownRulePolicy(UnknownRulePolicy.Skip)
        }
        val schema = formSchema<Map<String, Any?>> {
            field("x") {
                specs(reg, listOf(
                    RuleSpec("nope"),                          // skip
                    RuleSpec("min", mapOf("value" to 1)),      // apply
                    RuleSpec("alsoNope"),                      // skip
                    RuleSpec("max", mapOf("value" to 10)),     // apply
                ))
            }
        }
        assertEquals(listOf("min", "max"), schema.fieldInfo("x")?.rules)
    }

    // ---------------------------------------------------------------- has / names / aliases

    @Test
    fun has_returnsTrueForRegisteredAndAliasedNames() {
        val reg = ruleRegistry<Map<String, Any?>> {
            register("foo") { _ -> required() }
            alias("bar", "foo")
        }
        assertTrue(reg.has("foo"))
        assertTrue(reg.has("bar"))
        assertFalse(reg.has("baz"))
    }

    // ---------------------------------------------------------------- specs(...) end-to-end

    @Test
    fun specs_appliesListInOrder() {
        val reg = ruleRegistry<Map<String, Any?>>()
        val schema = formSchema<Map<String, Any?>> {
            field("age") {
                specs(reg, listOf(
                    RuleSpec("required"),
                    RuleSpec("min", mapOf("value" to 18)),
                    RuleSpec("max", mapOf("value" to 60)),
                ))
            }
        }
        assertEquals(listOf("required", "min", "max"), schema.fieldInfo("age")?.rules)
    }

    @Test
    fun spec_singleResolveAppliesOneRule() {
        val reg = ruleRegistry<Map<String, Any?>>()
        val schema = formSchema<Map<String, Any?>> {
            field("email") { spec(reg, RuleSpec("email")) }
        }
        assertEquals(listOf("email"), schema.fieldInfo("email")?.rules)
    }

    @Test
    fun specsAndInlineDsl_appliesBoth_inLambdaOrder() {
        // Specs resolve via the same DSL the inline calls use; everything in the rules lambda
        // applies in source order — no special phasing.
        val reg = ruleRegistry<Map<String, Any?>>()
        val schema = formSchema<Map<String, Any?>> {
            field("name") {
                specs(reg, listOf(RuleSpec("required"), RuleSpec("minLength", mapOf("value" to 2))))
                custom("noDigits") { v, _ ->
                    val s = v as? String ?: return@custom null
                    if (s.any { it.isDigit() }) "no digits" else null
                }
            }
        }
        assertEquals(listOf("required", "minLength", "noDigits"), schema.fieldInfo("name")?.rules)
    }

    @Test
    fun specs_resolveThroughAlias_endToEnd() = runTest {
        val reg = ruleRegistry<Map<String, Any?>> {
            alias("length_min", "minLength")
        }
        val schema = formSchema<Map<String, Any?>> {
            field("nick") {
                spec(reg, RuleSpec("length_min", mapOf("value" to 3)))
            }
        }
        // Rule is registered under the CANONICAL name even when reached via the alias.
        assertEquals(listOf("minLength"), schema.fieldInfo("nick")?.rules)
        // And it actually validates as 'minLength' would.
        val err = schema.validate(mapOf("nick" to "ab"))
        assertEquals("Must be at least 3 characters", err.byPath["nick"])
        val ok = schema.validate(mapOf("nick" to "abcd"))
        assertNull(ok.byPath["nick"])
    }

    @Test
    fun customRule_throughRegistry_isSuspendable_andCallable() = runTest {
        // Demonstrates that a registered handler can compose async work via the existing
        // custom { } DSL — no separate async-rule type is needed.
        val reg = ruleRegistry<Map<String, Any?>> {
            register("evenOnly") { params ->
                val msg = params.stringOrNull("message") ?: "Must be even"
                custom("evenOnly") { v, _ ->
                    val n = v as? Int ?: return@custom null
                    if (n % 2 == 0) null else msg
                }
            }
        }
        val schema = formSchema<Map<String, Any?>> {
            field("n") { spec(reg, RuleSpec("evenOnly", mapOf("message" to "Even please"))) }
        }
        assertEquals("Even please", schema.validate(mapOf("n" to 3)).byPath["n"])
        assertNull(schema.validate(mapOf("n" to 4)).byPath["n"])
    }

    // ---------------------------------------------------------------- edge cases

    @Test
    fun duplicateSpec_sameNameOnOneField_attachesBothRules_firstFailingRuleWins() = runTest {
        // Specs are NOT deduped (only Field.required's auto-inject is). Two specs with the same
        // name on one field produce two FieldRules in the per-field list, each evaluated under
        // the schema's fail-fast contract (loop stops at the first FAILING rule per path).
        val reg = ruleRegistry<Map<String, Any?>>()
        val schema = formSchema<Map<String, Any?>> {
            field("age") {
                specs(reg, listOf(
                    RuleSpec("min", mapOf("value" to 1)),    // permissive
                    RuleSpec("min", mapOf("value" to 18)),   // stricter
                ))
            }
        }
        // Both rules attach — introspection shows the duplicate name, no silent dedupe.
        assertEquals(listOf("min", "min"), schema.fieldInfo("age")?.rules)
        // value=20: passes both rules → no error.
        assertNull(schema.validate(mapOf("age" to 20)).byPath["age"], "20 >= both bounds")
        // value=5: passes min(1), fails min(18) → the second rule's message surfaces.
        assertEquals("Must be at least 18", schema.validate(mapOf("age" to 5)).byPath["age"])
        // value=0: fails min(1) first → loop breaks, first rule's message wins.
        assertEquals("Must be at least 1", schema.validate(mapOf("age" to 0)).byPath["age"])
    }

    @Test
    fun handler_throwingSynchronously_propagatesOutOfSchemaBuild() {
        // Programming errors inside a registered handler must NOT be swallowed — they propagate
        // out of formSchema { ... } at build time, identical to how DSL custom { } throws would.
        val reg = ruleRegistry<Map<String, Any?>> {
            register("brokenHandler") { _ ->
                throw IllegalStateException("handler-boom")
            }
        }
        val ex = assertFailsWith<IllegalStateException> {
            formSchema<Map<String, Any?>> {
                field("x") { spec(reg, RuleSpec("brokenHandler")) }
            }
        }
        assertEquals("handler-boom", ex.message)
    }

    @Test
    fun onUnknownRule_callbackThrowing_propagatesOutOfSchemaBuild() {
        // If the consumer's onUnknownRule observability callback itself throws (e.g. a logger
        // that NPEs), the throw propagates out — the library does NOT swallow callback errors.
        // Pins the contract; users who want defensive logging must catch inside their callback.
        val reg = ruleRegistry<Map<String, Any?>> {
            unknownRulePolicy(UnknownRulePolicy.Skip)
            onUnknownRule { _ -> throw RuntimeException("callback-boom") }
        }
        val ex = assertFailsWith<RuntimeException> {
            formSchema<Map<String, Any?>> {
                field("x") { spec(reg, RuleSpec("unknown")) }
            }
        }
        assertEquals("callback-boom", ex.message)
    }
}
