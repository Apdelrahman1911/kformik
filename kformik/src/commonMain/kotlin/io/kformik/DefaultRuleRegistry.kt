package io.kformik

/**
 * Seed a [RuleRegistryBuilder] with the 7 declarative built-ins. Each handler reads the standard
 * `"value"` param (where applicable) and an optional `"message"` override, then delegates to the
 * corresponding existing [FieldRulesBuilder] DSL method — so default error-message formatting is
 * identical to a hand-written `field(p) { min(18) }` and any future change to the DSL's defaults
 * propagates without per-handler edits.
 *
 * Convention for built-in spec params:
 *  - `value`: the rule's primary argument (`min`/`max` → Number, `minLength`/`maxLength` → Int,
 *    `pattern` → Regex or String compilable to one). Required where the rule has one.
 *  - `message`: optional override for the default error message.
 *
 * `custom` and `customValue` are intentionally NOT seeded — a registry-resolvable `custom` would
 * require carrying a Kotlin function in its params, which violates the "params are plain data"
 * contract that makes the registry usable from backend metadata.
 */
internal fun <V> seedDefaults(builder: RuleRegistryBuilder<V>) {
    builder.register("required") { params ->
        val msg = params.stringOrNull("message")
        if (msg != null) required(msg) else required()
    }
    builder.register("minLength") { params ->
        val n = params.int("value")
        val msg = params.stringOrNull("message")
        if (msg != null) minLength(n, msg) else minLength(n)
    }
    builder.register("maxLength") { params ->
        val n = params.int("value")
        val msg = params.stringOrNull("message")
        if (msg != null) maxLength(n, msg) else maxLength(n)
    }
    builder.register("email") { params ->
        val msg = params.stringOrNull("message")
        if (msg != null) email(msg) else email()
    }
    builder.register("pattern") { params ->
        val regex = params.regex("value")
        val msg = params.stringOrNull("message")
        if (msg != null) pattern(regex, msg) else pattern(regex)
    }
    builder.register("min") { params ->
        val n = params.number("value")
        val msg = params.stringOrNull("message")
        if (msg != null) min(n, msg) else min(n)
    }
    builder.register("max") { params ->
        val n = params.number("value")
        val msg = params.stringOrNull("message")
        if (msg != null) max(n, msg) else max(n)
    }
}
