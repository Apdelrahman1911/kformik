package io.kformik.forms

/**
 * The fallback initial value used when [Field.initialValue] is `null`. Picked per [FieldType] so
 * the form starts in a sane state without forcing every consumer to spell out an `initialValue`.
 *
 * - Text-like ([FieldType.Text] / [Email] / [Password] / [Multiline]) → empty string. Pairs with
 *   `required()`'s blank-string check naturally.
 * - [FieldType.Number] → `0` if `asInt`, else `0.0`. Matches what the renderer's numeric parser
 *   produces on first commit.
 * - [FieldType.Checkbox] / [FieldType.Switch] → `false`.
 * - [FieldType.Select] / [FieldType.Radio] → the **first option's value**, or `null` when the
 *   option list is empty. Picks a valid choice by default; consumers who want "no selection
 *   selected" should provide an explicit `initialValue = null`.
 * - [FieldType.Date] → `null`. Pairs with `required()` for a "must pick a date" workflow; the
 *   renderer treats `null` as "no date chosen".
 */
internal fun defaultValueFor(type: FieldType): Any? = when (type) {
    FieldType.Text, FieldType.Email, FieldType.Password, FieldType.Multiline -> ""
    is FieldType.Number -> if (type.asInt) 0 else 0.0
    FieldType.Checkbox, FieldType.Switch -> false
    is FieldType.Select -> type.options.firstOrNull()?.value
    is FieldType.Radio -> type.options.firstOrNull()?.value
    FieldType.Date -> null
}
