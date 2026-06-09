package io.kformik

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuleParamsTest {

    private fun params(vararg entries: Pair<String, Any?>): RuleParams =
        RuleParams("testRule", mapOf(*entries))

    // ------------------------------------------------------------------ contains

    @Test
    fun contains_returnsTrue_whenKeyPresent_evenIfValueIsNull() {
        val p = params("value" to null, "other" to 1)
        assertTrue("value" in p)
        assertTrue("other" in p)
        assertTrue("absent" !in p)
    }

    // ------------------------------------------------------------------ int / intOrNull

    @Test
    fun int_acceptsIntDirectly() {
        assertEquals(18, params("value" to 18).int("value"))
    }

    @Test
    fun paramCoercion_intFromLong_succeeds() {
        // kotlinx-serialization decodes JSON integers as Long by default — this is the critical path.
        assertEquals(18, params("value" to 18L).int("value"))
    }

    @Test
    fun int_acceptsIntegerValuedDouble() {
        assertEquals(18, params("value" to 18.0).int("value"))
    }

    @Test
    fun paramCoercion_intFromFractionalDouble_throws() {
        val ex = assertFailsWith<RuleParamException> {
            params("value" to 18.5).int("value")
        }
        assertEquals("testRule", ex.ruleName)
        assertEquals("value", ex.paramKey)
        assertTrue(ex.message!!.contains("fractional"), "expected 'fractional' in: ${ex.message}")
    }

    @Test
    fun int_throwsOnOverflowingLong() {
        val ex = assertFailsWith<RuleParamException> {
            params("value" to (Int.MAX_VALUE.toLong() + 1L)).int("value")
        }
        assertTrue(ex.message!!.contains("overflow"))
    }

    @Test
    fun paramCoercion_intFromString_throws() {
        val ex = assertFailsWith<RuleParamException> {
            params("value" to "18").int("value")
        }
        assertEquals("testRule", ex.ruleName)
        assertEquals("value", ex.paramKey)
        // Message should identify what was expected and what was actually provided.
        assertTrue(ex.message!!.contains("Int"), "expected 'Int' in: ${ex.message}")
        assertTrue(ex.message!!.contains("String"), "expected 'String' in: ${ex.message}")
    }

    @Test
    fun int_throwsOnMissingKey_withRuleNameAndKey() {
        val ex = assertFailsWith<RuleParamException> {
            params().int("value")
        }
        assertEquals("testRule", ex.ruleName)
        assertEquals("value", ex.paramKey)
        assertTrue(ex.message!!.contains("missing or null"))
    }

    @Test
    fun intOrNull_returnsNullForAbsentAndExplicitNull_throwsForMismatch() {
        assertNull(params().intOrNull("value"))
        assertNull(params("value" to null).intOrNull("value"))
        assertEquals(7, params("value" to 7).intOrNull("value"))
        // type mismatch is NOT silenced even by the *OrNull variant
        assertFailsWith<RuleParamException> {
            params("value" to "nope").intOrNull("value")
        }
    }

    // ------------------------------------------------------------------ long

    @Test
    fun long_acceptsLong_Int_andIntegerDouble() {
        assertEquals(5L, params("v" to 5L).long("v"))
        assertEquals(5L, params("v" to 5).long("v"))
        assertEquals(5L, params("v" to 5.0).long("v"))
    }

    @Test
    fun long_rejectsFractionalDouble() {
        assertFailsWith<RuleParamException> {
            params("v" to 5.5).long("v")
        }
    }

    // ------------------------------------------------------------------ double

    @Test
    fun double_acceptsAnyNumber() {
        assertEquals(3.14, params("v" to 3.14).double("v"))
        assertEquals(7.0, params("v" to 7).double("v"))
        assertEquals(7.0, params("v" to 7L).double("v"))
    }

    @Test
    fun double_rejectsString() {
        assertFailsWith<RuleParamException> {
            params("v" to "3.14").double("v")
        }
    }

    // ------------------------------------------------------------------ number

    @Test
    fun number_returnsRawNumber_preservingType() {
        val raw: Number = 18
        val out = params("v" to raw).number("v")
        assertEquals(raw, out)
        assertEquals(18.0, out.toDouble())
    }

    // ------------------------------------------------------------------ string / boolean

    @Test
    fun string_acceptsOnlyString_throwsOnNumber() {
        assertEquals("hi", params("v" to "hi").string("v"))
        assertFailsWith<RuleParamException> {
            params("v" to 5).string("v")
        }
    }

    @Test
    fun boolean_acceptsOnlyBoolean() {
        assertEquals(true, params("v" to true).boolean("v"))
        assertFailsWith<RuleParamException> {
            params("v" to "true").boolean("v")
        }
    }

    // ------------------------------------------------------------------ regex

    @Test
    fun regex_acceptsRegexDirectly() {
        val r = Regex("^[a-z]+$")
        assertEquals(r.pattern, params("v" to r).regex("v").pattern)
    }

    @Test
    fun regex_compilesStringPattern() {
        val out = params("v" to "^[0-9]+$").regex("v")
        assertEquals("^[0-9]+$", out.pattern)
    }

    @Test
    fun regex_throwsWithUsefulMessage_onInvalidPattern() {
        val ex = assertFailsWith<RuleParamException> {
            params("v" to "[invalid").regex("v")
        }
        // We don't pin the exact compiler message (it varies per platform), but the failure should
        // reference the bad pattern and identify the rule/param.
        assertEquals("testRule", ex.ruleName)
        assertEquals("v", ex.paramKey)
        assertTrue(ex.message!!.contains("[invalid"), "expected pattern in message: ${ex.message}")
    }

    @Test
    fun paramTypeMismatch_throwsWithUsefulMessage() {
        // Cross-cutting: every type-mismatch error must identify ruleName + paramKey + expected
        // type + the actual value's toString. The runtime class name is best-effort (KClass.simpleName
        // can be null on Kotlin/Native), so we DO NOT pin it — the helper falls back to "?" there.
        val ex = assertFailsWith<RuleParamException> {
            params("v" to listOf(1, 2)).int("v")
        }
        assertEquals("testRule", ex.ruleName)
        assertEquals("v", ex.paramKey)
        assertTrue(ex.message!!.contains("Int"), "expected 'Int' in message: ${ex.message}")
        // Pin the actual.toString half — this is stable across all platforms.
        assertTrue(ex.message!!.contains("[1, 2]"), "expected actual.toString '[1, 2]' in: ${ex.message}")
        // The KMP-stable contract: the message contains a parenthesized class-name slot after
        // the actual value. On JVM it's ArrayList/SingletonList/List/etc; on Native simpleName
        // may be null and the helper substitutes "?". We assert only the slot's syntactic shape.
        assertTrue(
            Regex("""\([A-Za-z?]""").containsMatchIn(ex.message!!),
            "expected '(ClassName)' or '(?)' slot in: ${ex.message}",
        )
    }

    // ---------------------------------------------------------------- narrowing boundary regressions

    /**
     * Long.MAX_VALUE (2^63 - 1) is NOT exactly representable as Double — its widening rounds UP to
     * 2^63. Pre-fix, an input Double of 9.223372036854776E18 (= 2^63 as Double) silently saturated
     * to Long.MAX_VALUE because (a) `d > Long.MAX_VALUE.toDouble()` compared 2^63 against 2^63 and
     * was false, and (b) the fractional check passed because Long.MAX_VALUE.toDouble() == 2^63 ==
     * d.toLong().toDouble(). The contract on RuleParams promises "narrow exact-only" — this test
     * pins that 2^63 is rejected as overflow, not silently saturated.
     */
    @Test
    fun narrowToLong_doubleAtLongMaxValueBoundary_throwsOverflow() {
        val ex = assertFailsWith<RuleParamException> {
            params("value" to 9.223372036854776E18).long("value")  // 2^63 as Double
        }
        assertTrue(ex.message!!.contains("overflow"), "expected 'overflow' at the 2^63 boundary: ${ex.message}")
    }

    @Test
    fun narrowToLong_doubleSlightlyAboveLongMax_throwsOverflow() {
        // Any Double clearly above 2^63 (e.g. 1e19) was already caught — pin it as a sibling so a
        // refactor that loosens the lower-precision overflow check is also caught.
        val ex = assertFailsWith<RuleParamException> {
            params("value" to 1.0e19).long("value")
        }
        assertTrue(ex.message!!.contains("overflow"))
    }

    @Test
    fun narrowToLong_negativeLongMinValue_isAccepted_becauseExactlyRepresentable() {
        // -Long.MAX_VALUE - 1 == Long.MIN_VALUE = -2^63 IS exactly Double-representable; should pass.
        val out = params("value" to Long.MIN_VALUE.toDouble()).long("value")
        assertEquals(Long.MIN_VALUE, out)
    }

    @Test
    fun narrowToLong_nan_throws_withNanInMessage() {
        val ex = assertFailsWith<RuleParamException> {
            params("value" to Double.NaN).long("value")
        }
        // Pre-fix this was reported as "fractional", which was misleading. NaN gets its own message.
        assertTrue(ex.message!!.contains("NaN"), "expected 'NaN' in message: ${ex.message}")
    }

    @Test
    fun narrowToInt_nan_throws_withNanInMessage() {
        val ex = assertFailsWith<RuleParamException> {
            params("value" to Double.NaN).int("value")
        }
        assertTrue(ex.message!!.contains("NaN"))
    }

    @Test
    fun narrowToInt_largeDouble_throwsOverflow_notFractional() {
        // A Double clearly above Int range should report overflow, not the misleading "fractional"
        // the old code returned for any non-round-trip narrowing.
        val ex = assertFailsWith<RuleParamException> {
            params("value" to 1.0e15).int("value")
        }
        assertTrue(ex.message!!.contains("overflow"), "expected 'overflow' not 'fractional': ${ex.message}")
    }

    @Test
    fun narrowToInt_validIntegerDouble_succeeds() {
        // Sanity: a valid integer Double well inside Int range still narrows cleanly.
        assertEquals(42, params("value" to 42.0).int("value"))
    }

    // ---------------------------------------------------------------- extra params are ignored

    @Test
    fun extraUnusedParamKeys_areIgnored() {
        // Backend forward-compat: handlers read only the keys they expect, so extra keys (e.g. a
        // future "version" field) must not throw.
        val p = params("value" to 18, "version" to "1.0", "extra" to listOf(1, 2, 3))
        assertEquals(18, p.int("value"))
        assertEquals("1.0", p.string("version"))
        // Accessing a key we didn't pass returns null via *OrNull, throws via non-null.
        assertNull(p.intOrNull("missing"))
    }
}
