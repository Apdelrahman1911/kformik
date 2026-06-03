package io.kformik.forms

import androidx.compose.runtime.Stable

/**
 * The set of field shapes the v1 declarative layer can render with its built-in [DefaultRenderers].
 *
 * Modelled as a `sealed class` rather than an `enum` because [Select] and [Radio] carry option
 * lists, and [Number] carries a configuration flag. A sealed hierarchy also lets consumers `when`
 * over the full set; **adding a new subtype in a later Kformik version is therefore an ABI break**
 * for any consumer with their own exhaustive dispatch. The supported set is intentionally fixed in
 * 1.x to keep that surface small.
 *
 * For field shapes outside this set, use `KformikFields(renderOverride = …)` or `KformikForm(
 * renderOverride = …)` to plug in your own composable per field name.
 */
@Stable
public sealed class FieldType {

    /** Single-line plain text input. */
    public object Text : FieldType()

    /** Single-line text with an email-keyboard hint. Pair with `rules { email() }` for validation. */
    public object Email : FieldType()

    /**
     * Single-line text with [androidx.compose.ui.text.input.PasswordVisualTransformation]. Default
     * is masked; pair with the renderer's show/hide toggle for reveal.
     */
    public object Password : FieldType()

    /** Multi-line text input. Renders as an [androidx.compose.material3.OutlinedTextField] with `singleLine = false`. */
    public object Multiline : FieldType()

    /**
     * Numeric input.
     *
     * @param asInt if `true`, the renderer parses input as `Int`; otherwise `Double`. Choose based
     *  on what your `validate` / `onSubmit` expect to receive — there is no automatic coercion at
     *  submit time.
     *
     * Default stored value is `null` (as of v1.8.1+; was `0` / `0.0` pre-1.8.1). Pair with
     * `required()` for "must enter a value" enforcement; pass `Field(initialValue = 0)` to seed a
     * zero start explicitly.
     */
    public data class Number(val asInt: Boolean = false) : FieldType()

    /** Boolean checkbox with the field label rendered to its right. */
    public object Checkbox : FieldType()

    /** Boolean switch — visual alternative to [Checkbox]. */
    public object Switch : FieldType()

    /**
     * Dropdown selector. Renders as an [androidx.compose.material3.ExposedDropdownMenuBox]. The
     * stored value is the chosen option's [SelectOption.value] (typed as `Any?` — see
     * [SelectOption] for the v1.9.0 widening that lets a "no selection" placeholder option be
     * modeled directly). The default stored value is the first option's value when [options] is
     * non-empty, `null` when empty — pass an explicit `Field(initialValue = null)` to start
     * with no selection regardless of the option list.
     */
    public data class Select(val options: List<SelectOption>) : FieldType()

    /**
     * One-of-N radio button group. Stored value is the chosen option's [SelectOption.value].
     * Same `null`-when-empty default as [Select]; same explicit-`null` override.
     */
    public data class Radio(val options: List<SelectOption>) : FieldType()

    /**
     * Date picker. Stored value is an ISO-8601 `"yyyy-MM-dd"` `String` (or `null` when empty), to
     * keep `kotlinx-datetime` types out of the public API. The module DOES depend on
     * `kotlinx-datetime` internally for parsing — consumers who want a typed date downstream
     * pass the stored String to `LocalDate.parse(…)`.
     *
     * Renderer pops a Material 3 [androidx.compose.material3.DatePickerDialog] triggered by
     * tapping the read-only [androidx.compose.material3.OutlinedTextField] (or its trailing
     * "Pick" text button — the renderer avoids the `material-icons-extended` dependency).
     */
    public object Date : FieldType()
}
