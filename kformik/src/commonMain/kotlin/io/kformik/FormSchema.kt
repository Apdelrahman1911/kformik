package io.kformik

/**
 * Builder-style schema validation. The Kotlin equivalent of Formik's Yup integration.
 *
 * Build a schema once and reuse it across forms:
 *
 * ```kotlin
 * val schema = formSchema {
 *     field("email") {
 *         required("Email is required")
 *         email("Invalid email")
 *     }
 *     field("password") {
 *         required("Password is required")
 *         minLength(8, "Min 8 characters")
 *     }
 *     field("user.age") {
 *         custom { v ->
 *             val n = v as? Int ?: return@custom "Must be a number"
 *             if (n < 18) "Must be 18+" else null
 *         }
 *     }
 * }
 *
 * val form = FormikController(FormikConfig(
 *     initialValues = mapOf("email" to "", "password" to "", "user" to mapOf("age" to 0)),
 *     schemaValidator = schema,
 *     onSubmit = ...,
 * ))
 * ```
 *
 * The schema is a [SchemaValidator] — drop it into [FormikConfig.schemaValidator]. Field-level
 * validation via [FormikController.validateField] runs only the rules registered for that field.
 *
 * Differences from Yup:
 *
 *  - **No type coercion.** Yup coerces numbers/strings/booleans aggressively. Kotlin has real
 *    types; rules see the raw value and decide what to do.
 *  - **Path strings, not nested schemas.** `field("user.address.city") { ... }` is a single rule
 *    list keyed by a dot/bracket path. Mirrors the Kformik error-shape decision (flat
 *    `Map<String, String>` rather than a recursive mirror type).
 *  - **No `when`/`oneOf`/`shape` operators.** Use [custom] with the surrounding `values` to model
 *    cross-field constraints.
 *  - **Suspending.** Validators are `suspend`, so an API check ("is this email already used?")
 *    can be expressed inline.
 *
 * Limitations to be aware of:
 *  - **Typed-form path resolution requires controller wiring.** Schema path lookup uses
 *    [MapValuesUpdater] / [getIn] by default; v1.9.0 added internal wiring via
 *    [configureValuesUpdater] so a schema attached to a typed `data class` form (with a custom
 *    [ValuesUpdater]) reads through the controller's updater instead of returning `null` for
 *    every field. Standalone schema use (no [FormikController]) on a non-`Map` value type still
 *    falls back to `getIn`, which only walks Map / List trees.
 *  - **Multi-error mode is direct-call only.** [FormikController] always uses the single-error
 *    [validate]/[validateField] path; the `failFast = false` flag and [validateAll]/[validateAllField]
 *    only take effect when you call them on the schema directly.
 */
/**
 * Field metadata returned by [FormSchema.fieldInfo] and friends. Lists the rule names attached
 * to a path. Each builder rule registers a stable [FieldRule.name] (`"required"`, `"email"`,
 * `"minLength"`, …) which makes introspection deterministic.
 */
data public class FormFieldInfo(
    val path: String,
    val rules: List<String>,
) {
    /** True if the field has a `required` rule. */
    val isRequired: Boolean get() = "required" in rules
}

public class FormSchema<V> internal constructor(
    private val perField: Map<String, List<FieldRule<V>>>,
    private val crossField: List<suspend (V) -> FormikErrors>,
    /**
     * Default fail-fast behavior. When `true` (the default and Formik-compatible mode), only the
     * first failing rule per path is recorded in [validate]'s result. When `false`, every rule
     * runs and [validateAll] returns the full list per path. The per-field `failFast` flag in
     * [FormSchemaBuilder.field] overrides this.
     */
    private val failFast: Boolean = true,
    private val perFieldFailFast: Map<String, Boolean> = emptyMap(),
) : SchemaValidator<V> {

    private fun fieldFailFast(path: String): Boolean = perFieldFailFast[path] ?: failFast

    /**
     * Run every field-rule and every cross-field rule. Returns the first-failing-rule message
     * per path (legacy Formik behavior), which keeps [FormikErrors] flat. Cross-field rules
     * merge last (so a cross-field error overrides a per-field one — matches Formik's deepmerge
     * order).
     *
     * For multi-error display, call [validateAll] instead.
     */
    override suspend fun validate(values: V): FormikErrors {
        val out = HashMap<String, String>()
        for ((path, rules) in perField) {
            val value = readValue(values, path)
            for (rule in rules) {
                val msg = rule.check(value, values)
                if (msg != null) {
                    out[path] = msg
                    break // first-failing wins (legacy contract — unchanged by failFast flag)
                }
            }
        }
        for (rule in crossField) {
            val errs = rule(values)
            if (errs.isNotEmpty) out.putAll(errs.byPath)
        }
        return FormikErrors(out.toMap())
    }

    /**
     * Multi-error variant of [validate]. Returns a map from path → list of error messages from
     * every failing rule.
     *
     *  - Schema-level `failFast=true` (the default, set via [formSchema] / [FormSchemaBuilder])
     *    short-circuits at the first failure per path → list size is 0 or 1.
     *  - `failFast=false` runs every rule and returns all failures per path.
     *  - Per-field `failFast` (set on `field(path, failFast = …)`) overrides the schema default.
     *
     * Cross-field rules append into the result map's path entries.
     */
    suspend public fun validateAll(values: V): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        for ((path, rules) in perField) {
            val value = readValue(values, path)
            val short = fieldFailFast(path)
            val msgs = mutableListOf<String>()
            for (rule in rules) {
                val m = rule.check(value, values) ?: continue
                msgs += m
                if (short) break
            }
            if (msgs.isNotEmpty()) out[path] = msgs
        }
        for (rule in crossField) {
            val errs = rule(values)
            if (errs.isNotEmpty) {
                for ((k, v) in errs.byPath) {
                    out.getOrPut(k) { mutableListOf() }.add(v)
                }
            }
        }
        return out.mapValues { it.value.toList() }
    }

    /**
     * Validate just one path. Used by [FormikController.validateField]. Returns the first error
     * (or null if all rules pass). For multi-error per-field validation, call [validateAllField].
     */
    suspend public fun validateField(values: V, path: String): String? {
        val rules = perField[path] ?: return null
        val value = readValue(values, path)
        for (rule in rules) {
            val msg = rule.check(value, values)
            if (msg != null) return msg
        }
        return null
    }

    /**
     * Like [validateField], but also consults cross-field rules ([cross]) that produce an error
     * keyed at [path]. Used by [FormikController.validateField] so a field whose only error comes
     * from a cross-field constraint (e.g. a confirm-password mismatch) is not erroneously cleared
     * when the field itself has no per-field rule. A cross-field error **overrides** the per-field
     * first-failing message on the same path, matching the cross-merges-last precedence of the full
     * [validate] (so `validateField` and `validateForm` agree on the committed error for a path).
     */
    suspend public fun validateFieldIncludingCross(values: V, path: String): String? {
        var msg: String? = perField[path]?.let { rules ->
            val value = readValue(values, path)
            var found: String? = null
            for (rule in rules) {
                val m = rule.check(value, values)
                if (m != null) { found = m; break }
            }
            found
        }
        for (rule in crossField) {
            val errs = rule(values)
            errs.byPath[path]?.let { msg = it } // cross overrides per-field, mirroring validate()'s putAll-last
        }
        return msg
    }

    /**
     * Multi-error single-field validation. Returns every failing rule's message for [path], or an
     * empty list if all pass / the path is unknown. Honors the per-field `failFast` flag.
     */
    suspend public fun validateAllField(values: V, path: String): List<String> {
        val rules = perField[path] ?: return emptyList()
        val value = readValue(values, path)
        val short = fieldFailFast(path)
        val msgs = mutableListOf<String>()
        for (rule in rules) {
            val m = rule.check(value, values) ?: continue
            msgs += m
            if (short) break
        }
        return msgs
    }

    /** Returns true if any rules are configured for [path]. */
    public fun hasField(path: String): Boolean = perField.containsKey(path)

    /** Returns the configured paths. Useful for introspection / test-driven schemas. */
    public fun fields(): Set<String> = perField.keys

    /**
     * Returns metadata for [path] (the registered rule names) or `null` if the path has no
     * configured rules. Use [FormFieldInfo.isRequired] to check for a `required` rule.
     */
    public fun fieldInfo(path: String): FormFieldInfo? = perField[path]?.let { rules ->
        FormFieldInfo(path = path, rules = rules.map { it.name })
    }

    /** Returns true if [path] has a `required` rule. */
    public fun isRequired(path: String): Boolean = perField[path]?.any { it.name == "required" } == true

    /** Returns the set of paths that have a `required` rule. */
    public fun requiredFields(): Set<String> = perField.entries
        .asSequence()
        .filter { (_, rules) -> rules.any { it.name == "required" } }
        .map { it.key }
        .toSet()

    /**
     * Optional values reader configured by the [FormikController]. When the form's value type is
     * NOT `Map<String, Any?>` (a typed `data class` or similar), the controller wires its
     * configured [ValuesUpdater] here so that schema-level per-field validators can actually read
     * the field's value via path. Pre-1.9.0 this wasn't routed — non-Map values fell through to
     * `getIn` which only handles Map / List trees, so every per-field validator received `null`
     * regardless of what the form held. Typed-data-class forms with a schema therefore silently
     * "passed" every per-field rule that returned null-on-null.
     *
     * Set via [configureValuesUpdater] during controller construction; read by `validate`
     * thereafter. `@Volatile` covers the cross-controller-sharing case: if a single FormSchema
     * instance is reused across multiple FormikController constructions (an unusual but legal
     * pattern), each controller's `init {}` writes its own updater here. Without volatile, a
     * happens-before edge isn't guaranteed across the unsynchronized writes; the last writer wins
     * regardless, but volatile ensures readers see SOMEONE's commit rather than a torn value.
     * (Schemas shared across controllers that need DIFFERENT updaters is a user error — they
     * should construct a separate schema per controller.)
     */
    @kotlin.concurrent.Volatile
    private var configuredUpdater: ValuesUpdater<V>? = null

    /**
     * Wire this schema to the form's [ValuesUpdater]. Called once by [FormikController] during
     * construction when both a custom `valuesUpdater` and a `FormSchema` schemaValidator are
     * configured. Consumers building a schema standalone (no controller) can set this themselves
     * for typed values; for `Map<String, Any?>` forms the default works fine without it.
     */
    @InternalKformikApi
    public fun configureValuesUpdater(updater: ValuesUpdater<V>): FormSchema<V> = apply {
        this.configuredUpdater = updater
    }

    private fun readValue(values: V, path: String): Any? {
        // Prefer the controller-supplied updater when present — it knows how to walk typed data
        // class values that getIn / MapValuesUpdater cannot.
        configuredUpdater?.let { return it.getAt(values, path) }
        return when {
            values is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                MapValuesUpdater.getAt(values as Map<String, Any?>, path)
            }
            else -> getIn(values, path)
        }
    }
}

/**
 * Marker for APIs that are part of the public surface but only meant to be consumed by other
 * Kformik internals (e.g. `FormikController` wiring `FormSchema.configureValuesUpdater`). Source
 * stability is best-effort across releases; we use a [RequiresOptIn] annotation so accidental use
 * from external code surfaces as a compile-time opt-in nag.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Kformik internal API — exposed only for cross-module wiring inside the library. Not subject to source-compat guarantees.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class InternalKformikApi

/** A single validation rule attached to a path. */
public class FieldRule<V> internal constructor(
    public val name: String,
    public val check: suspend (value: Any?, allValues: V) -> String?,
)

// ====================================================================== DSL builders

/**
 * Top-level entrypoint to the schema DSL.
 *
 * ```kotlin
 * val schema = formSchema<Map<String, Any?>> {
 *     field("email") { required(); email() }
 *     field("password") { required(); minLength(8) }
 *     cross { values ->
 *         val pwd = values["password"] as? String
 *         val confirm = values["confirmPassword"] as? String
 *         if (pwd != confirm) buildErrors { put("confirmPassword", "Passwords must match") }
 *         else FormikErrors.Empty
 *     }
 * }
 * ```
 */
inline public fun <V> formSchema(
    failFast: Boolean = true,
    block: FormSchemaBuilder<V>.() -> Unit,
): FormSchema<V> {
    val builder = FormSchemaBuilder<V>(schemaFailFast = failFast)
    builder.block()
    return builder.build()
}

public class FormSchemaBuilder<V> @PublishedApi internal constructor(
    @PublishedApi internal val schemaFailFast: Boolean = true,
) {
    private val fields: MutableMap<String, MutableList<FieldRule<V>>> = LinkedHashMap()
    private val crossField: MutableList<suspend (V) -> FormikErrors> = ArrayList()
    private val perFieldFailFast: MutableMap<String, Boolean> = LinkedHashMap()

    /**
     * Configure rules for a single field by path. Paths follow Formik's dot/bracket convention:
     * `email`, `user.address.city`, `friends[0]`, `users[2].name`.
     *
     * @param failFast When non-null, overrides the schema-level `failFast` for this field. Pass
     * `false` to collect every failing rule (see [FormSchema.validateAll] / [validateAllField]).
     */
    public fun field(
        path: String,
        failFast: Boolean? = null,
        block: FieldRulesBuilder<V>.() -> Unit,
    ) {
        require(path.isNotBlank()) { "Schema field path must not be blank" }
        val rb = FieldRulesBuilder<V>(path)
        rb.block()
        fields.getOrPut(path) { ArrayList() }.addAll(rb.build())
        if (failFast != null) perFieldFailFast[path] = failFast
    }

    /**
     * Register a cross-field rule. The lambda receives the full values object and returns errors
     * to merge in. Empty errors means "no cross-field issue."
     */
    public fun cross(rule: suspend (V) -> FormikErrors) {
        crossField.add(rule)
    }

    @PublishedApi
    internal fun build(): FormSchema<V> = FormSchema(
        perField = fields.mapValues { it.value.toList() },
        crossField = crossField.toList(),
        failFast = schemaFailFast,
        perFieldFailFast = perFieldFailFast.toMap(),
    )
}

public class FieldRulesBuilder<V>(private val path: String) {
    private val rules: MutableList<FieldRule<V>> = ArrayList()

    /** Reject `null`, empty strings, and empty lists. Custom [message] defaults to "Required". */
    public fun required(message: String = "Required") {
        rules += FieldRule(name = "required") { value, _ ->
            val isMissing = when (value) {
                null -> true
                is String -> value.isBlank()
                is Collection<*> -> value.isEmpty()
                is Map<*, *> -> value.isEmpty()
                else -> false
            }
            if (isMissing) message else null
        }
    }

    /** Minimum length for `String` / `Collection` / `Map`. */
    public fun minLength(min: Int, message: String = "Must be at least $min characters") {
        rules += FieldRule(name = "minLength") { value, _ ->
            val len = lengthOf(value)
            if (len != null && len < min) message else null
        }
    }

    /** Maximum length for `String` / `Collection` / `Map`. */
    public fun maxLength(max: Int, message: String = "Must be at most $max characters") {
        rules += FieldRule(name = "maxLength") { value, _ ->
            val len = lengthOf(value)
            if (len != null && len > max) message else null
        }
    }

    /**
     * Matches a (sufficient) email pattern: contains a single `@`, has a dot in the domain.
     * A blank/absent value passes (combine with [required] to forbid it), so an optional email
     * field left empty is not flagged — matching Yup's skip-on-empty and [required]'s isBlank rule.
     */
    public fun email(message: String = "Invalid email") {
        rules += FieldRule(name = "email") { value, _ ->
            val s = value as? String ?: return@FieldRule null
            if (s.isBlank()) return@FieldRule null
            if (EMAIL_REGEX.matches(s)) null else message
        }
    }

    /**
     * Custom regex match. The [pattern] must be a valid [Regex]. A blank/absent value passes
     * (combine with [required] to forbid it), consistent with [email].
     */
    public fun pattern(pattern: Regex, message: String = "Does not match pattern") {
        rules += FieldRule(name = "pattern") { value, _ ->
            val s = value as? String ?: return@FieldRule null
            if (s.isBlank()) return@FieldRule null
            if (pattern.matches(s)) null else message
        }
    }

    /**
     * Numeric minimum. Values are compared as `Double`. Non-finite inputs (`NaN`, `±Infinity`)
     * fail the rule — `NaN < x` always returns `false` regardless of `x`, which would silently
     * pass the check otherwise. Schema-declaration-time guard: [minValue] must be finite (catches
     * `min(Double.NaN)` and `min(Double.POSITIVE_INFINITY)` mistakes at build time).
     */
    public fun min(minValue: Number, message: String = "Must be at least $minValue") {
        val bound = minValue.toDouble()
        require(bound.isFinite()) { "min's bound must be a finite Number (got $minValue)" }
        rules += FieldRule(name = "min") { value, _ ->
            val n = (value as? Number)?.toDouble() ?: return@FieldRule null
            if (!n.isFinite()) return@FieldRule message  // NaN / ±Infinity → out-of-range
            if (n < bound) message else null
        }
    }

    /**
     * Numeric maximum. Same finite-only semantics as [min]: a non-finite input fails the rule;
     * a non-finite [maxValue] throws at schema-declaration time.
     */
    public fun max(maxValue: Number, message: String = "Must be at most $maxValue") {
        val bound = maxValue.toDouble()
        require(bound.isFinite()) { "max's bound must be a finite Number (got $maxValue)" }
        rules += FieldRule(name = "max") { value, _ ->
            val n = (value as? Number)?.toDouble() ?: return@FieldRule null
            if (!n.isFinite()) return@FieldRule message
            if (n > bound) message else null
        }
    }

    /**
     * Custom rule. Return a non-null message to fail, or null to pass. The full values object is
     * passed as the second argument for cross-field comparisons.
     */
    public fun custom(name: String = "custom", rule: suspend (value: Any?, allValues: V) -> String?) {
        rules += FieldRule(name = name, check = rule)
    }

    /** Short-form custom rule that only inspects the value. */
    public fun customValue(name: String = "custom", rule: suspend (value: Any?) -> String?) {
        rules += FieldRule(name = name) { value, _ -> rule(value) }
    }

    @PublishedApi
    internal fun build(): List<FieldRule<V>> = rules.toList()

    private fun lengthOf(value: Any?): Int? = when (value) {
        is String -> value.length
        is Collection<*> -> value.size
        is Map<*, *> -> value.size
        else -> null
    }

    companion public object {
        // Pragmatic email regex: one '@', one '.' in the domain, no whitespace.
        private val EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    }
}
