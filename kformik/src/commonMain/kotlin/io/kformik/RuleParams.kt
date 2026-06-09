package io.kformik

/**
 * Thrown by [RuleParams] accessors when a param is missing or has the wrong type. Carries the
 * rule name and param key so error messages identify the failing rule unambiguously.
 *
 * Thrown at **schema-build time** (when a [RuleHandler] runs inside `FieldRulesBuilder.specs(...)`),
 * NOT at validation time. Misconfigured params are programming errors that should crash loudly in
 * dev/staging — they are intentionally **not** routed through `FormikConfig.onError`.
 */
public class RuleParamException internal constructor(
    public val ruleName: String,
    public val paramKey: String,
    message: String,
) : IllegalArgumentException(message)

/**
 * Typed accessor over a [RuleSpec]'s `params` with clear type-mismatch diagnostics.
 *
 * Numeric accessors accept any [Number] and narrow exact-only:
 *  - `params.int("value")` succeeds for `18`, `18L`, `18.0`, throws on `18.5` (fractional) or
 *    `Int.MAX_VALUE + 1L` (overflow).
 *  - `params.long("value")` likewise; double-valued integers within `Long` range narrow cleanly.
 *  - `params.double("value")` accepts any Number.
 *
 * String/Boolean accessors require the exact type. `regex(key)` accepts either a [Regex] or a
 * `String` that compiles to one (the common case for backend-shipped patterns).
 *
 * The `*OrNull` variants return `null` when the key is absent OR present with a `null` value
 * (matches `Map<K, V?>.get` semantics); use [contains] to distinguish. The non-null variants
 * throw [RuleParamException] for missing/null AND for type mismatches.
 *
 * @property ruleName the name of the rule that constructed this params view; used in error messages.
 * @property raw the underlying immutable params map.
 */
public class RuleParams internal constructor(
    public val ruleName: String,
    public val raw: Map<String, Any?>,
) {
    public operator fun contains(key: String): Boolean = key in raw

    public fun int(key: String): Int = intOrNull(key) ?: throw missing(key, "Int")
    public fun intOrNull(key: String): Int? {
        val v = raw[key] ?: return null
        return narrowToInt(key, v)
    }

    public fun long(key: String): Long = longOrNull(key) ?: throw missing(key, "Long")
    public fun longOrNull(key: String): Long? {
        val v = raw[key] ?: return null
        return narrowToLong(key, v)
    }

    public fun double(key: String): Double = doubleOrNull(key) ?: throw missing(key, "Double")
    public fun doubleOrNull(key: String): Double? {
        val v = raw[key] ?: return null
        return when (v) {
            is Number -> v.toDouble()
            else -> throw mismatch(key, "Double", v)
        }
    }

    public fun number(key: String): Number = numberOrNull(key) ?: throw missing(key, "Number")
    public fun numberOrNull(key: String): Number? {
        val v = raw[key] ?: return null
        return v as? Number ?: throw mismatch(key, "Number", v)
    }

    public fun string(key: String): String = stringOrNull(key) ?: throw missing(key, "String")
    public fun stringOrNull(key: String): String? {
        val v = raw[key] ?: return null
        return v as? String ?: throw mismatch(key, "String", v)
    }

    public fun boolean(key: String): Boolean = booleanOrNull(key) ?: throw missing(key, "Boolean")
    public fun booleanOrNull(key: String): Boolean? {
        val v = raw[key] ?: return null
        return v as? Boolean ?: throw mismatch(key, "Boolean", v)
    }

    public fun regex(key: String): Regex = regexOrNull(key) ?: throw missing(key, "Regex")
    public fun regexOrNull(key: String): Regex? {
        val v = raw[key] ?: return null
        return when (v) {
            is Regex -> v
            is String -> try {
                Regex(v)
            } catch (e: IllegalArgumentException) {
                // e.message can be null on some platforms; e::class.simpleName is the fallback.
                val reason = e.message ?: e::class.simpleName ?: "compile error"
                throw RuleParamException(
                    ruleName, key,
                    "Rule '$ruleName' param '$key': failed to compile Regex from pattern '$v' ($reason).",
                )
            }
            else -> throw mismatch(key, "Regex or String pattern", v)
        }
    }

    private fun narrowToInt(key: String, v: Any?): Int = when (v) {
        is Int -> v
        is Number -> {
            val d = v.toDouble()
            if (d.isNaN()) throw mismatch(key, "Int (NaN)", v)
            // Range check first so overflow surfaces as "overflow", not "fractional". Both Int
            // bounds are exactly representable as Double.
            if (d > Int.MAX_VALUE.toDouble() || d < Int.MIN_VALUE.toDouble()) {
                throw mismatch(key, "Int (overflow)", v)
            }
            val asLong = d.toLong()
            if (asLong.toDouble() != d) throw mismatch(key, "Int (fractional)", v)
            asLong.toInt()
        }
        else -> throw mismatch(key, "Int", v)
    }

    private fun narrowToLong(key: String, v: Any?): Long = when (v) {
        is Long -> v
        is Int -> v.toLong()
        is Number -> {
            val d = v.toDouble()
            if (d.isNaN()) throw mismatch(key, "Long (NaN)", v)
            // Range check uses >= for the upper bound because Long.MAX_VALUE (2^63 - 1) is NOT
            // exactly representable as Double — Long.MAX_VALUE.toDouble() rounds UP to 2^63, so any
            // Double at that value is already out of Long range. Long.MIN_VALUE (-2^63) IS exactly
            // representable, so the lower bound uses strict <.
            if (d >= Long.MAX_VALUE.toDouble() || d < Long.MIN_VALUE.toDouble()) {
                throw mismatch(key, "Long (overflow)", v)
            }
            val asLong = d.toLong()
            if (asLong.toDouble() != d) throw mismatch(key, "Long (fractional)", v)
            asLong
        }
        else -> throw mismatch(key, "Long", v)
    }

    private fun missing(key: String, expected: String): RuleParamException = RuleParamException(
        ruleName, key,
        "Rule '$ruleName' requires param '$key' of type $expected, but it was missing or null.",
    )

    private fun mismatch(key: String, expected: String, actual: Any?): RuleParamException {
        val actualRepr = if (actual == null) {
            "null"
        } else {
            // Kotlin/Native may return null for KClass.simpleName on some runtime classes — fall back
            // to "?" so the message stays useful across platforms without leaking the literal "null".
            val cls = actual::class.simpleName ?: "?"
            "$actual ($cls)"
        }
        return RuleParamException(
            ruleName, key,
            "Rule '$ruleName' param '$key': expected $expected, got $actualRepr.",
        )
    }
}
