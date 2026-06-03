package io.kformik.forms

import io.kformik.FieldRulesBuilder

/**
 * A declarative description of one form field. Consumers build a `Map<String, Field>` (the key is
 * the field's *name* — used as the path into the form's `Map<String, Any?>` values) and hand it to
 * [KformikForm] or [KformikFields].
 *
 * Example:
 *
 * ```kotlin
 * val fields = mapOf(
 *     "email" to Field(
 *         type = FieldType.Email,
 *         label = "Email",
 *         required = true,
 *         rules = { email() },
 *     ),
 *     "password" to Field(
 *         type = FieldType.Password,
 *         label = "Password",
 *         required = true,
 *         rules = { minLength(8) },
 *     ),
 * )
 *
 * KformikForm(fields = fields, onSubmit = { values -> api.login(values) })
 * ```
 *
 * **Render order**: the order of entries in the `fields` map is the render order. Kotlin's
 * `mapOf` returns a `LinkedHashMap` (insertion-ordered) so this is stable for the canonical
 * builder; using `HashMap` will give nondeterministic order.
 *
 * @property type one of [FieldType] — picks the default Material 3 renderer.
 * @property label human-readable label shown beside or above the widget. A trailing asterisk (`*`)
 *  is appended by the renderer when [required] is true.
 * @property placeholder hint text shown inside an empty text input (no effect for non-text types).
 * @property helperText optional muted text under the widget. Distinct from validation errors,
 *  which replace it when present.
 * @property initialValue the value to seed when the form is first composed. When `null`, a
 *  default-for-type is used: `""` for text-likes, `0` / `0.0` for [FieldType.Number], `false` for
 *  [FieldType.Checkbox] / [FieldType.Switch], the first option's value for [FieldType.Select] /
 *  [FieldType.Radio], `null` for [FieldType.Date].
 * @property required when true, a `required()` rule is auto-prepended into the schema for this
 *  path **unless** [rules] already declares one (the convenience flag and explicit rule don't
 *  duplicate). Renderer also marks the label with a trailing `*`.
 * @property disabled when true, the widget renders as non-interactive. Form state for this path is
 *  preserved; it just can't be edited via the UI.
 * @property rules schema-DSL block applied to this field's path. Same builder as the existing
 *  `formSchema { field("…") { … } }` API: `required()`, `email()`, `minLength(n)`, `maxLength(n)`,
 *  `pattern(regex)`, `min(n)`, `max(n)`, `custom(name) { v, allValues -> … }`. Cross-field rules
 *  receive `allValues: Map<String, Any?>`.
 */
public data class Field(
    public val type: FieldType,
    public val label: String? = null,
    public val placeholder: String? = null,
    public val helperText: String? = null,
    public val initialValue: Any? = null,
    public val required: Boolean = false,
    public val disabled: Boolean = false,
    public val rules: FieldRulesBuilder<Map<String, Any?>>.() -> Unit = {},
)
