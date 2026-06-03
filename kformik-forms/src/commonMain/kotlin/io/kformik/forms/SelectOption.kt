package io.kformik.forms

import androidx.compose.runtime.Stable

/**
 * One option in a [FieldType.Select] or [FieldType.Radio]. The form stores [value] (typed as
 * `Any`) into the underlying `Map<String, Any?>`; [label] is what the renderer shows.
 *
 * Equality of stored option values goes through `Any.equals`, so prefer immutable types
 * (`String`, `Int`, `enum`) for [value]. Using a mutable user-defined type works but identity
 * vs. equality surprises are on you.
 */
@Stable
public data class SelectOption(
    public val value: Any,
    public val label: String,
)
