package io.kformik.forms

import io.kformik.FormSchema
import io.kformik.MapValuesUpdater
import io.kformik.formSchema

/**
 * Assemble a [FormSchema] from a declarative `Map<String, Field>`.
 *
 * For each entry, the field's [Field.rules] block is registered under that path in the schema.
 *
 * **Required-flag auto-injection.** When a [Field] sets `required = true`, this builder injects a
 * synthetic `required()` rule at the **front** of that path's rule list — *unless* the field's own
 * `rules` block already declares one. The prepend matters for fail-fast semantics: an empty email
 * field should surface `"Required"` before `"Invalid email"`.
 *
 * Done in two passes via the public DSL: pass 1 captures only the user's rules so we can
 * introspect which paths already declare `required` via [FormSchema.fieldInfo]; pass 2 builds the
 * final schema, prepending the auto-required where appropriate. Two passes are cheap (no
 * validation runs, just rule registration), and we avoid the duplicate-`required` rule that a
 * single-pass implementation would emit when both `Field.required = true` and a user-supplied
 * `rules { required("custom") }` are present.
 */
internal fun buildSchemaFrom(fields: Map<String, Field>): FormSchema<Map<String, Any?>> {
    // Pass 1 — schema with the user's rules only, used purely for introspection.
    // `rules` is an extension lambda `FieldRulesBuilder<…>.() -> Unit`, so we invoke it explicitly
    // passing `this` (the FieldRulesBuilder opened by `field(path)`) as the receiver.
    val userOnlySchema = formSchema<Map<String, Any?>> {
        fields.forEach { (path, fieldDef) ->
            field(path) { fieldDef.rules(this) }
        }
    }

    // Pass 2 — the real schema. For each field, prepend the auto-required rule only when the
    // user's own block didn't already declare one.
    return formSchema {
        fields.forEach { (path, fieldDef) ->
            val userDeclaredRequired =
                userOnlySchema.fieldInfo(path)?.rules?.any { it == "required" } == true
            field(path) {
                if (fieldDef.required && !userDeclaredRequired) required()
                fieldDef.rules(this)
            }
        }
    }
}

/**
 * Resolve the initial values map for a declarative field set. The user's [Field.initialValue]
 * wins unless it's the unset sentinel ([FieldDefaultValue], the default when the parameter is
 * omitted) — in that case, [defaultValueFor] picks a type-appropriate baseline.
 *
 * Explicit `null` is preserved (`Field(initialValue = null)` stores `null`), which is the
 * documented "no selection" path for [FieldType.Select] / [FieldType.Radio]. Pre-1.9.0 the code
 * used `?:` and so couldn't distinguish "omitted" from "explicit null" — the documented null
 * escape hatch silently fell back to the first option's value.
 *
 * **Nested-path keys** (e.g. `"user.email"`, `"items[0]"`): routed through [MapValuesUpdater] so
 * the resulting initial-values map is properly nested:
 *
 * ```kotlin
 * mapOf(
 *     "user.name" to Field(...),
 *     "user.email" to Field(...),
 * )
 * // → { "user" → { "name" → "", "email" → "" } }   (v1.9.0+)
 * // pre-1.9.0: { "user.name" → "", "user.email" → "" }  — literal flat keys, never resolved
 * //                                                       by the controller's MapValuesUpdater
 * ```
 *
 * Pre-1.9.0 the function used `.mapValues` which inserted the path string as a literal map key —
 * so `KformikForm` with nested-path fields never resolved its own initial values, breaking the
 * documented nested-path use case.
 */
internal fun buildInitialValuesFrom(fields: Map<String, Field>): Map<String, Any?> {
    var result: Map<String, Any?> = emptyMap()
    for ((path, f) in fields) {
        val value = if (f.initialValue === FieldDefaultValue) defaultValueFor(f.type) else f.initialValue
        result = MapValuesUpdater.setAt(result, path, value)
    }
    return result
}
