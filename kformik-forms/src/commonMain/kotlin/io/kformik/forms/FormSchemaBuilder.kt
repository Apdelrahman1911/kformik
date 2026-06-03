package io.kformik.forms

import io.kformik.FormSchema
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
 * Resolve the initial values map for a declarative field set. [Field.initialValue] wins when
 * non-null; otherwise [defaultValueFor] picks a type-appropriate baseline.
 */
internal fun buildInitialValuesFrom(fields: Map<String, Field>): Map<String, Any?> =
    fields.mapValues { (_, f) -> f.initialValue ?: defaultValueFor(f.type) }
