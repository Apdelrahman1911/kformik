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
 */
/**
 * Field metadata returned by [FormSchema.fieldInfo] and friends. Lists the rule names attached
 * to a path. Each builder rule registers a stable [FieldRule.name] (`"required"`, `"email"`,
 * `"minLength"`, …) which makes introspection deterministic.
 */
data class FormFieldInfo(
    val path: String,
    val rules: List<String>,
) {
    /** True if the field has a `required` rule. */
    val isRequired: Boolean get() = "required" in rules
}

class FormSchema<V> internal constructor(
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
    suspend fun validateAll(values: V): Map<String, List<String>> {
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
    suspend fun validateField(values: V, path: String): String? {
        val rules = perField[path] ?: return null
        val value = readValue(values, path)
        for (rule in rules) {
            val msg = rule.check(value, values)
            if (msg != null) return msg
        }
        return null
    }

    /**
     * Multi-error single-field validation. Returns every failing rule's message for [path], or an
     * empty list if all pass / the path is unknown. Honors the per-field `failFast` flag.
     */
    suspend fun validateAllField(values: V, path: String): List<String> {
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
    fun hasField(path: String): Boolean = perField.containsKey(path)

    /** Returns the configured paths. Useful for introspection / test-driven schemas. */
    fun fields(): Set<String> = perField.keys

    /**
     * Returns metadata for [path] (the registered rule names) or `null` if the path has no
     * configured rules. Use [FormFieldInfo.isRequired] to check for a `required` rule.
     */
    fun fieldInfo(path: String): FormFieldInfo? = perField[path]?.let { rules ->
        FormFieldInfo(path = path, rules = rules.map { it.name })
    }

    /** Returns true if [path] has a `required` rule. */
    fun isRequired(path: String): Boolean = perField[path]?.any { it.name == "required" } == true

    /** Returns the set of paths that have a `required` rule. */
    fun requiredFields(): Set<String> = perField.entries
        .asSequence()
        .filter { (_, rules) -> rules.any { it.name == "required" } }
        .map { it.key }
        .toSet()

    private fun readValue(values: V, path: String): Any? = when {
        values is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            MapValuesUpdater.getAt(values as Map<String, Any?>, path)
        }
        else -> getIn(values, path)
    }
}

/** A single validation rule attached to a path. */
class FieldRule<V> internal constructor(
    val name: String,
    val check: suspend (value: Any?, allValues: V) -> String?,
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
inline fun <V> formSchema(
    failFast: Boolean = true,
    block: FormSchemaBuilder<V>.() -> Unit,
): FormSchema<V> {
    val builder = FormSchemaBuilder<V>(schemaFailFast = failFast)
    builder.block()
    return builder.build()
}

class FormSchemaBuilder<V> @PublishedApi internal constructor(
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
    fun field(
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
    fun cross(rule: suspend (V) -> FormikErrors) {
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

class FieldRulesBuilder<V>(private val path: String) {
    private val rules: MutableList<FieldRule<V>> = ArrayList()

    /** Reject `null`, empty strings, and empty lists. Custom [message] defaults to "Required". */
    fun required(message: String = "Required") {
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
    fun minLength(min: Int, message: String = "Must be at least $min characters") {
        rules += FieldRule(name = "minLength") { value, _ ->
            val len = lengthOf(value)
            if (len != null && len < min) message else null
        }
    }

    /** Maximum length for `String` / `Collection` / `Map`. */
    fun maxLength(max: Int, message: String = "Must be at most $max characters") {
        rules += FieldRule(name = "maxLength") { value, _ ->
            val len = lengthOf(value)
            if (len != null && len > max) message else null
        }
    }

    /** Matches a (sufficient) email pattern: non-empty, contains a single `@`, has a dot in the domain. */
    fun email(message: String = "Invalid email") {
        rules += FieldRule(name = "email") { value, _ ->
            val s = value as? String ?: return@FieldRule null
            if (s.isEmpty()) return@FieldRule null
            if (EMAIL_REGEX.matches(s)) null else message
        }
    }

    /** Custom regex match. The [pattern] must be a valid [Regex]. */
    fun pattern(pattern: Regex, message: String = "Does not match pattern") {
        rules += FieldRule(name = "pattern") { value, _ ->
            val s = value as? String ?: return@FieldRule null
            if (pattern.matches(s)) null else message
        }
    }

    /** Numeric minimum. Numbers compared as `Double`. */
    fun min(minValue: Number, message: String = "Must be at least $minValue") {
        rules += FieldRule(name = "min") { value, _ ->
            val n = (value as? Number)?.toDouble() ?: return@FieldRule null
            if (n < minValue.toDouble()) message else null
        }
    }

    /** Numeric maximum. */
    fun max(maxValue: Number, message: String = "Must be at most $maxValue") {
        rules += FieldRule(name = "max") { value, _ ->
            val n = (value as? Number)?.toDouble() ?: return@FieldRule null
            if (n > maxValue.toDouble()) message else null
        }
    }

    /**
     * Custom rule. Return a non-null message to fail, or null to pass. The full values object is
     * passed as the second argument for cross-field comparisons.
     */
    fun custom(name: String = "custom", rule: suspend (value: Any?, allValues: V) -> String?) {
        rules += FieldRule(name = name, check = rule)
    }

    /** Short-form custom rule that only inspects the value. */
    fun customValue(name: String = "custom", rule: suspend (value: Any?) -> String?) {
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

    companion object {
        // Pragmatic email regex: one '@', one '.' in the domain, no whitespace.
        private val EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    }
}
