package io.kformik.forms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.kformik.FormikErrors
import io.kformik.FormikTouched
import io.kformik.compose.ComposeFormik
import io.kformik.compose.rememberFormik

/**
 * Top-level declarative form composable. Hands you a working form from a `Map<String, Field>`
 * plus an `onSubmit` callback — that's the entire required surface for most forms.
 *
 * Internally:
 *  1. Resolves initial values from each field's `initialValue` (falling back to a type-appropriate
 *     default — see [defaultValueFor]).
 *  2. Assembles a `FormSchema<Map<String, Any?>>` from each field's `rules` block, auto-prepending
 *     a `required()` rule when `field.required = true` and the user didn't already declare one
 *     (see [buildSchemaFrom]).
 *  3. Calls [rememberFormik] with the values + schema + onSubmit, plus the validation flags
 *     forwarded from this function's parameters (`validateOnChange`, `validateOnBlur`,
 *     `validateOnMount`, `enableReinitialize`, `validateDebounceMs`, `validateAsync`).
 *  4. Renders the field set via [KformikFields] and the submit button via [submitButton].
 *
 * Example:
 *
 * ```kotlin
 * KformikForm(
 *     fields = mapOf(
 *         "email" to Field(type = FieldType.Email, label = "Email", required = true, rules = { email() }),
 *         "password" to Field(type = FieldType.Password, label = "Password", required = true, rules = { minLength(8) }),
 *     ),
 *     onSubmit = { values -> api.login(values) },
 * )
 * ```
 *
 * @param fields the declarative field map. Insertion order = render order.
 * @param onSubmit invoked with the current `Map<String, Any?>` values *only* if validation passes.
 *  Receives a `suspend` lambda so it can call your `suspend` repo/API directly.
 * @param submitButton slot for the form's primary action. The default renders a Material 3
 *  [Button] enabled when `isValid && !isSubmitting`. Pass a different `@Composable` to replace
 *  it (e.g. a `Row` with submit + reset).
 * @param renderOverride per-field render escape hatch — see [KformikFields].
 * @param spacing vertical gap between fields. Default `12.dp`.
 * @param extraValidate optional secondary validate callback passed to `rememberFormik(validate = …)`.
 *  Runs alongside the auto-built schema; useful for cross-form rules that don't fit per-field
 *  schema declarations.
 * @param validateDebounceMs / @param validateAsync forwarded to [rememberFormik].
 * @param onError invoked when the user's [onSubmit] throws (or when any fire-and-forget setter
 *  raised by the form fails). Without an [onError], `onSubmit` exceptions are silently swallowed
 *  by the underlying scope. Pass a real handler (logger / Snackbar trigger / error reporter) to
 *  surface failures.
 * @param initialErrors optional pre-populated error map (server-side validation hydration). The
 *  errors are visible to `displayError(name)` only after the corresponding fields are touched —
 *  pair with [initialTouched] to surface them on first render.
 * @param initialTouched optional pre-populated touched-flag map (same hydration use case).
 * @param initialStatus optional pre-populated form-level status (`Any?`). Free-form, mirrors
 *  Formik's `status` field.
 */
@Composable
public fun KformikForm(
    fields: Map<String, Field>,
    onSubmit: suspend (values: Map<String, Any?>) -> Unit,
    modifier: Modifier = Modifier,
    spacing: Dp = 12.dp,
    submitButton: @Composable (
        onSubmit: () -> Unit,
        isValid: Boolean,
        isSubmitting: Boolean,
    ) -> Unit = { onSubmit, isValid, isSubmitting ->
        DefaultSubmitButton(onSubmit = onSubmit, isValid = isValid, isSubmitting = isSubmitting)
    },
    renderOverride: (@Composable (
        name: String,
        field: Field,
        form: ComposeFormik<Map<String, Any?>>,
    ) -> Boolean)? = null,
    validateOnChange: Boolean = true,
    validateOnBlur: Boolean = true,
    validateOnMount: Boolean = false,
    enableReinitialize: Boolean = false,
    validateDebounceMs: Long? = null,
    validateAsync: (suspend (Map<String, Any?>) -> FormikErrors)? = null,
    extraValidate: (suspend (Map<String, Any?>) -> FormikErrors)? = null,
    onError: ((Throwable) -> Unit)? = null,
    initialErrors: FormikErrors = FormikErrors.Empty,
    initialTouched: FormikTouched = FormikTouched.Empty,
    initialStatus: Any? = null,
) {
    val schema = remember(fields) { buildSchemaFrom(fields) }
    val initialValues = remember(fields) { buildInitialValuesFrom(fields) }

    // Stable controller key. `Field` is a data class whose `rules: () -> Unit` member is folded into
    // structural `equals`, so a closure-capturing `rules = { minLength(minVar) }` produces a fresh
    // `Field` per parent recomposition — using `fields` as the key would rebuild the controller and
    // wipe user input on every recompose. We derive a key from the *shape* (name, type, required,
    // disabled, initialValue) so identical shapes share a controller across recompositions, and only
    // a real structural change (adding a field, swapping a type) forces a rebuild.
    val controllerKey = fields.entries.joinToString("|") { (name, f) ->
        "$name:${f.type}:${f.required}:${f.disabled}:${f.initialValue}"
    }

    val form = rememberFormik(
        initialValues = initialValues,
        validate = extraValidate,
        schemaValidator = schema,
        onSubmit = { values, _ -> onSubmit(values) },
        validateOnChange = validateOnChange,
        validateOnBlur = validateOnBlur,
        validateOnMount = validateOnMount,
        enableReinitialize = enableReinitialize,
        onError = onError,
        key = controllerKey,
        validateDebounceMs = validateDebounceMs,
        validateAsync = validateAsync,
        initialErrors = initialErrors,
        initialTouched = initialTouched,
        initialStatus = initialStatus,
    )

    val state by form.state
    val isValid by form.isValid

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        KformikFields(
            fields = fields,
            form = form,
            spacing = spacing,
            renderOverride = renderOverride,
        )
        submitButton(form::submit, isValid, state.isSubmitting)
    }
}

/**
 * Default submit button — Material 3 [Button] with label "Submit", disabled while invalid or
 * mid-submission. Public so consumers can wrap/decorate without having to copy the gating logic.
 */
@Composable
public fun DefaultSubmitButton(
    onSubmit: () -> Unit,
    isValid: Boolean,
    isSubmitting: Boolean,
) {
    Button(
        onClick = onSubmit,
        enabled = isValid && !isSubmitting,
    ) {
        Text(if (isSubmitting) "Submitting…" else "Submit")
    }
}
