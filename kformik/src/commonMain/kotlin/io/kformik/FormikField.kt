package io.kformik

/**
 * Per-field read/write surface. Mirrors Formik's `FieldInputProps` + `FieldMetaProps` +
 * `FieldHelperProps`, minus the DOM-event-specific bits (`handleChange(e)`, `handleBlur(e)`).
 *
 * The shape is intentionally a plain data-bearing object — UI adapters wrap it for their
 * widget library. A Compose adapter might project it into a `TextFieldState`; a SwiftUI
 * adapter into an `@Published` shim.
 */
data public class FieldBinding<T>(
    /** The field's path / name (`"email"`, `"user.address.street"`, etc.). */
    val name: String,
    /** Current value at this path. */
    val value: T,
    /** Initial value at this path. */
    val initialValue: T?,
    /** Current error at this path, or null. */
    val error: String?,
    /** Initial error at this path, or null. */
    val initialError: String?,
    /** Whether the user has interacted with this field. */
    val touched: Boolean,
    /** Initial touched state. */
    val initialTouched: Boolean,
    /** Convenience: `error` if `touched` else null. Mirrors the `<ErrorMessage>` behavior. */
    val displayError: String?,
    /** Called when the user changes the value (e.g. text input updated). */
    val onValueChange: suspend (T) -> Unit,
    /** Called when focus state changes (true = focused, false = blurred). */
    val onFocusChange: suspend (Boolean) -> Unit,
    /** Imperatively set an error for this field (does not trigger validation). */
    val setError: (String?) -> Unit,
)
