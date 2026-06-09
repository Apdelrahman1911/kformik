package io.kformik

/**
 * Cap on alias-walk node count. An alias chain of N edges visits N+1 names (intermediate aliases
 * plus the terminal canonical handler), so this constant supports up to 8 chained alias edges
 * before [RuleRegistryBuilder.build] throws to break runaway loops.
 *
 * File-private so it stays out of the public `.api` baseline (a `const val` inside a `private
 * companion object` would still leak as a JVM `public static final` on the enclosing class).
 */
private const val MAX_ALIAS_DEPTH = 9

/**
 * A rule descriptor — a registered rule [name] plus its dynamic [params]. Typically constructed
 * by mapping a backend payload onto this shape:
 *
 * ```kotlin
 * // Backend: {"name": "min", "params": {"value": 18, "message": "Must be 18+"}}
 * RuleSpec("min", mapOf("value" to 18, "message" to "Must be 18+"))
 * ```
 *
 * Not `@Serializable` on purpose — the library does not ship a JSON layer. Consumers map from their
 * wire format (kotlinx-serialization `JsonObject`, Moshi, Gson, …) to [RuleSpec] at the boundary.
 *
 * **Extension point.** The [params] `Map` is the documented extension point — future per-spec
 * concerns (e.g. a `__version` discriminator, a `__kind` tag, telemetry id) go in as reserved
 * `params` keys, NOT as new fields on this class. The data-class shape (constructor params,
 * `copy(...)`, `componentN`) is frozen at `(name, params)` to keep binary compatibility.
 *
 * Resolve via [FieldRulesBuilder.spec] / [FieldRulesBuilder.specs] inside a `formSchema` block or a
 * `Field.rules` lambda.
 */
public data class RuleSpec(
    public val name: String,
    public val params: Map<String, Any?> = emptyMap(),
)

/**
 * Thrown at **schema-build time** when a [RuleSpec] cannot be resolved (unknown rule name when
 * policy is [UnknownRulePolicy.Throw], alias cycle detected at [RuleRegistryBuilder.build]).
 * Programming error — intentionally **not** routed through `FormikConfig.onError`, because a
 * misconfigured registry should crash loudly in dev/staging before any user interacts with the form.
 */
public class RuleResolutionException internal constructor(
    public val ruleName: String,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/**
 * A registry handler: receives parsed [RuleParams], contributes rules to a [FieldRulesBuilder] via
 * the existing DSL (`required`, `min`, `max`, `email`, `pattern`, `minLength`, `maxLength`,
 * `custom`, …).
 *
 * Async rules need no separate type — the DSL's `custom { ... }` lambda is already `suspend`, so a
 * handler that calls a suspending API works transparently:
 *
 * ```kotlin
 * register("serverUniqueCheck") { params ->
 *     val msg = params.stringOrNull("message") ?: "Already taken"
 *     custom("serverUniqueCheck") { value, _ ->
 *         val s = value as? String ?: return@custom null
 *         if (userApi.isTaken(s)) msg else null
 *     }
 * }
 * ```
 *
 * **Cancellation contract.** Rules contributed by a handler are invoked under the controller's
 * validation coroutine. Any suspending call inside them must respect cancellation (Ktor, retrofit
 * with `CallAdapter`, `suspendCancellableCoroutine`, etc.). Non-cancellable bridges will block the
 * validation pipeline — that's a caller bug, not a library concern.
 *
 * **State-capture contract.** Handlers should be pure or capture only thread-safe services (Api
 * clients, repositories). Do NOT capture mutable controller state, mutable maps, or anything that
 * other coroutines may modify while a validation is in flight.
 *
 * **Lambda shape is binary-frozen for v1.x.** Future per-handler context (registry handle, field
 * path, cancellation hooks) will arrive via [RuleParams] extension keys or a new `*WithContext`
 * typealias — NOT by widening this receiver / parameter list. Typealiases expand at compile time,
 * so changing the underlying lambda shape would break every compiled consumer's `register("x") { … }`
 * call site; this typealias is committed to additive-only evolution within v1.x.
 */
public typealias RuleHandler<V> = FieldRulesBuilder<V>.(RuleParams) -> Unit

/**
 * What to do when [FieldRulesBuilder.spec] resolves a [RuleSpec] whose `name` is not registered.
 *  - [Throw] (default): throw [RuleResolutionException] at schema-build time. Catches client/server
 *    skew immediately in dev/staging.
 *  - [Skip]: drop the spec silently. Recommended for prod rollouts where the backend may ship new
 *    rule names ahead of the client. **Wire [RuleRegistryBuilder.onUnknownRule]** when opting into
 *    Skip — silent skipping is otherwise debugging-hostile.
 *
 * **v1.x scope:** only `Throw` and `Skip` are supported today. Future modes (e.g. a `Warn`-style
 * "log-but-treat-as-valid" or a configured fallback handler) will arrive as additional builder
 * knobs on [RuleRegistryBuilder], NOT as new enum entries — keeping consumer `when (policy)`
 * statements exhaustive across v1.x minor releases.
 */
public enum class UnknownRulePolicy { Throw, Skip }

/**
 * Immutable registry of named [RuleHandler]s plus aliases. Build via the [ruleRegistry] /
 * [emptyRuleRegistry] top-level functions.
 *
 * Resolution is transparent: looking up an alias (e.g. `length_min`) returns the handler registered
 * under the canonical name (`minLength`); the resulting [FieldRule]'s `name` is always the canonical
 * one so existing introspection (`FormSchema.fieldInfo`, `isRequired`, `requiredFields`) keeps
 * working regardless of which alias the consumer used at the call site.
 */
public class RuleRegistry<V> internal constructor(
    private val handlers: Map<String, RuleHandler<V>>,
    private val resolvedAliases: Map<String, String>,
    internal val unknownRulePolicy: UnknownRulePolicy,
    internal val onUnknownRule: ((RuleSpec) -> Unit)?,
) {
    /** True if [name] is a registered handler OR a known alias. */
    public fun has(name: String): Boolean = name in handlers || name in resolvedAliases

    /** Canonical handler names registered in this registry (aliases excluded). */
    public fun names(): Set<String> = handlers.keys

    /** Declared aliases, flattened to their canonical destinations (cycles already rejected at build). */
    public fun aliases(): Map<String, String> = resolvedAliases

    internal fun resolve(name: String): RuleHandler<V>? {
        val canonical = resolvedAliases[name] ?: name
        return handlers[canonical]
    }
}

/**
 * Builder for [RuleRegistry]. Build via [ruleRegistry] (pre-seeded with the 7 declarative
 * built-ins) or [emptyRuleRegistry] (blank slate).
 *
 * Not thread-safe; intended for thread-confined construction (typically a top-level `val` or inside
 * a DI module init block).
 */
public class RuleRegistryBuilder<V> internal constructor() {
    private val handlers = mutableMapOf<String, RuleHandler<V>>()
    private val declaredAliases = mutableMapOf<String, String>()
    private var unknownRulePolicy: UnknownRulePolicy = UnknownRulePolicy.Throw
    private var onUnknownRule: ((RuleSpec) -> Unit)? = null

    /**
     * Register a [handler] under [name]. Overwrites any prior registration for the same name —
     * including built-ins seeded by [ruleRegistry]. Use this to override `min`/`max`/etc. with
     * project-specific semantics.
     */
    public fun register(name: String, handler: RuleHandler<V>): RuleRegistryBuilder<V> = apply {
        require(name.isNotBlank()) { "Rule name must not be blank." }
        handlers[name] = handler
    }

    /**
     * Declare that lookups for [from] should resolve to [to]. Useful for backend/client naming
     * mismatches (e.g. backend ships `length_min`, registry has `minLength`).
     *
     * Aliases are checked **before** direct handler lookup, so an alias overrides a registered
     * handler of the same name. Cycles (`alias("a", "b"); alias("b", "a")`) and chains longer
     * than 8 alias edges are detected at [build] time.
     *
     * Re-declaring the same `from` overwrites the previous mapping — last call wins, mirroring
     * [register]'s overwrite semantics.
     */
    public fun alias(from: String, to: String): RuleRegistryBuilder<V> = apply {
        require(from.isNotBlank()) { "Alias 'from' name must not be blank." }
        require(to.isNotBlank()) { "Alias 'to' name must not be blank." }
        require(from != to) { "Alias '$from' must not point to itself." }
        declaredAliases[from] = to
    }

    /** What to do for unknown rule names. Default: [UnknownRulePolicy.Throw]. */
    public fun unknownRulePolicy(policy: UnknownRulePolicy): RuleRegistryBuilder<V> = apply {
        this.unknownRulePolicy = policy
    }

    /**
     * Observability hook invoked once per spec when policy is [UnknownRulePolicy.Skip] and a spec's
     * name is unknown. `null` (the default) makes Skip truly silent — wire a logger when opting
     * into Skip to retain visibility into client/server rule-name drift.
     *
     * No effect under [UnknownRulePolicy.Throw]. If the callback itself throws, that exception
     * propagates out of [FieldRulesBuilder.spec] — the library does not catch callback errors, so
     * callers needing defensive logging must put a try/catch inside their callback.
     */
    public fun onUnknownRule(callback: ((RuleSpec) -> Unit)?): RuleRegistryBuilder<V> = apply {
        this.onUnknownRule = callback
    }

    /**
     * Build the immutable [RuleRegistry]. Throws [RuleResolutionException] if any alias chain
     * cycles or any alias eventually resolves to a name with no registered handler.
     */
    public fun build(): RuleRegistry<V> {
        val resolved = mutableMapOf<String, String>()
        for ((from, _) in declaredAliases) {
            val seen = LinkedHashSet<String>()
            var cur = from
            while (true) {
                if (!seen.add(cur)) {
                    throw RuleResolutionException(
                        from,
                        "Alias cycle detected: ${seen.joinToString(" → ")} → $cur",
                    )
                }
                if (seen.size > MAX_ALIAS_DEPTH) {
                    throw RuleResolutionException(
                        from,
                        "Alias chain too deep (>$MAX_ALIAS_DEPTH hops) starting at '$from': ${seen.joinToString(" → ")}",
                    )
                }
                val next = declaredAliases[cur] ?: break
                cur = next
            }
            if (cur !in handlers) {
                throw RuleResolutionException(
                    from,
                    "Alias '$from' resolves to '$cur', which is not registered. Registered: ${handlers.keys}.",
                )
            }
            resolved[from] = cur
        }
        return RuleRegistry(
            handlers = handlers.toMap(),
            resolvedAliases = resolved.toMap(),
            unknownRulePolicy = unknownRulePolicy,
            onUnknownRule = onUnknownRule,
        )
    }
}

/**
 * Build a [RuleRegistry] pre-seeded with the 7 declarative built-ins: `required`, `minLength`,
 * `maxLength`, `email`, `pattern`, `min`, `max`. The block extends or overrides via
 * [RuleRegistryBuilder.register] / [RuleRegistryBuilder.alias].
 *
 * The built-ins are V-agnostic — they only inspect `value: Any?` from each path, never the full
 * `allValues: V` — so this single typed factory serves any form value type.
 *
 * `custom` and `customValue` from [FieldRulesBuilder] are intentionally **not** registry citizens:
 * a backend-resolvable `custom` would have to carry a Kotlin function in its params, violating the
 * "params are plain data" contract. Use [RuleRegistryBuilder.register] to attach project-specific
 * rules by name.
 *
 * ```kotlin
 * val registry = ruleRegistry<Map<String, Any?>> {
 *     register("serverUniqueCheck") { params ->
 *         val msg = params.stringOrNull("message") ?: "Already taken"
 *         custom("serverUniqueCheck") { v, _ ->
 *             val s = v as? String ?: return@custom null
 *             if (userApi.isTaken(s)) msg else null
 *         }
 *     }
 *     alias(from = "length_min", to = "minLength")
 * }
 * ```
 */
public fun <V> ruleRegistry(block: RuleRegistryBuilder<V>.() -> Unit = {}): RuleRegistry<V> {
    val builder = RuleRegistryBuilder<V>()
    seedDefaults(builder)
    builder.block()
    return builder.build()
}

/**
 * Build a [RuleRegistry] with no built-ins. Use this when you want a strict allowlist — only the
 * rules you explicitly register are valid; everything else falls through to your [UnknownRulePolicy].
 */
public fun <V> emptyRuleRegistry(block: RuleRegistryBuilder<V>.() -> Unit = {}): RuleRegistry<V> {
    val builder = RuleRegistryBuilder<V>()
    builder.block()
    return builder.build()
}

/**
 * Resolve a single [spec] through [registry] and append the resulting rule(s) to this builder.
 *
 * If the spec's name isn't registered, the registry's [UnknownRulePolicy] decides: [Throw] raises
 * [RuleResolutionException] here at schema-build time; [Skip] drops the spec and invokes the
 * registry's optional `onUnknownRule` callback.
 *
 * **Ordering contract.** Rules contributed by the resolved handler append to the field's rule list
 * in source order — interleaving `spec(...)` with hand-written DSL calls (`required()`, `min(...)`,
 * `custom(...) { … }`) inside the same `field { … }` lambda runs them strictly in the order they
 * appear in source. Under the default `failFast = true`, the first failing rule wins.
 */
public fun <V> FieldRulesBuilder<V>.spec(registry: RuleRegistry<V>, spec: RuleSpec) {
    val handler = registry.resolve(spec.name)
    if (handler == null) {
        when (registry.unknownRulePolicy) {
            UnknownRulePolicy.Throw -> throw RuleResolutionException(
                spec.name,
                "Unknown rule '${spec.name}'. Registered: ${registry.names()}.",
            )
            UnknownRulePolicy.Skip -> registry.onUnknownRule?.invoke(spec)
        }
        return
    }
    handler(RuleParams(spec.name, spec.params))
}

/**
 * Convenience for resolving a list of specs in order. Each spec is processed independently — a
 * throw from one short-circuits the rest under [UnknownRulePolicy.Throw], but [Skip]'d specs do
 * not affect subsequent specs.
 */
public fun <V> FieldRulesBuilder<V>.specs(registry: RuleRegistry<V>, specs: List<RuleSpec>) {
    for (s in specs) spec(registry, s)
}
