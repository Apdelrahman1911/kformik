package io.kformik.forms

/**
 * The fallback initial value used when [Field.initialValue] is the unset sentinel
 * ([FieldDefaultValue], the default when the parameter is omitted). Picked per [FieldType] so the
 * form starts in a sane state without forcing every consumer to spell out an `initialValue`.
 *
 * Note: an *explicit* `null` (`Field(initialValue = null)`) is preserved verbatim and does NOT
 * trigger this fallback — see [FieldDefaultValue]'s docs for the distinction.
 *
 * - Text-like ([FieldType.Text] / [Email] / [Password] / [Multiline]) → empty string. Pairs with
 *   `required()`'s blank-string check naturally.
 * - [FieldType.Number] → `null`. Pairs with `required()` for a "must enter a number" workflow.
 *   (Before v1.8.1 this defaulted to `0` / `0.0`, which silently passed `required()` since `0` is
 *   a value, not absence. Consumers who want a starting numeric value pass an explicit
 *   `initialValue` such as `18` or `0.0`.)
 * - [FieldType.Checkbox] / [FieldType.Switch] → `false`. Note: `required()` does not enforce
 *   "must be checked" on a Boolean (false is a value, not absence) — use a `custom` rule for
 *   ToS-style mandatory checkboxes.
 * - [FieldType.Select] / [FieldType.Radio] → the **first option's value**, or `null` when the
 *   option list is empty. Picks a valid choice by default; consumers who want "no selection
 *   selected" should provide an explicit `initialValue = null`.
 * - [FieldType.Date] → `null`. Pairs with `required()` for a "must pick a date" workflow; the
 *   renderer treats `null` as "no date chosen".
 */
internal fun defaultValueFor(type: FieldType): Any? = when (type) {
    FieldType.Text, FieldType.Email, FieldType.Password, FieldType.Multiline -> ""
    is FieldType.Number -> null
    FieldType.Checkbox, FieldType.Switch -> false
    is FieldType.Select -> type.options.firstOrNull()?.value
    is FieldType.Radio -> type.options.firstOrNull()?.value
    FieldType.Date -> null
}
