package io.kformik.forms

import androidx.compose.runtime.Stable

/**
 * One option in a [FieldType.Select] or [FieldType.Radio]. The form stores [value] (typed as
 * `Any?`) into the underlying `Map<String, Any?>`; [label] is what the renderer shows.
 *
 * **Nullable value (v1.9.0+):** [value] is `Any?` so a "placeholder" option representing "no
 * selection" can be modeled directly:
 *
 * ```kotlin
 * FieldType.Select(options = listOf(
 *     SelectOption(value = null, label = "— select a country —"),
 *     SelectOption(value = "us",  label = "United States"),
 *     SelectOption(value = "eg",  label = "Egypt"),
 * ))
 * ```
 *
 * Pre-1.9.0, [value] was non-nullable `Any` and "no selection" could only be expressed via
 * `Field(initialValue = null)` plus an out-of-band placeholder. Source-incompatible for
 * downstream code that destructured `SelectOption(v, l)` into a non-nullable `v` (the type
 * widens to `Any?` — recompile to fix).
 *
 * Equality of stored option values goes through `Any.equals`, so prefer immutable types
 * (`String`, `Int`, `enum`) for [value]. Using a mutable user-defined type works but identity
 * vs. equality surprises are on you.
 */
@Stable
public data class SelectOption(
    public val value: Any?,
    public val label: String,
)
