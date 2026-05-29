package io.kformik.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.kformik.FieldBinding
import io.kformik.FormikActions
import io.kformik.FormikConfig
import io.kformik.FormikController
import io.kformik.FormikErrors
import io.kformik.FormikResetHandler
import io.kformik.FormikState
import io.kformik.FormikSubmitHandler
import io.kformik.SchemaValidator
import io.kformik.ValuesUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Compose-friendly wrapper around [FormikController]. Holds a live [FormikController] tied to the
 * current [androidx.compose.runtime.Composable]'s lifecycle (via [rememberCoroutineScope]).
 *
 * Construct via [rememberFormik]. The returned object exposes:
 *
 *  - `state: State<FormikState<V>>` — observe full form state.
 *  - `dirty`, `isValid`: `State<Boolean>` — derived state for buttons / hints.
 *  - `value(name)`, `error(name)`, `isTouched(name)` — snapshot reads.
 *  - `setFieldValue`, `setFieldTouched`, `setFieldError`, `setStatus`, `submit`, `resetForm` —
 *    convenience wrappers that launch on the remembered scope (no `suspend` ceremony at call sites).
 *
 * Example:
 * ```kotlin
 * @Composable
 * fun LoginScreen() {
 *   val form = rememberFormik(
 *     initialValues = mapOf<String, Any?>("email" to "", "password" to ""),
 *     validate = { v -> buildErrors { … } },
 *     onSubmit = { values, actions -> login(values) },
 *   )
 *
 *   TextField(
 *     value = form.value("email") as String,
 *     onValueChange = { form.setFieldValue("email", it) },
 *     isError = form.isTouched("email") && form.error("email") != null,
 *   )
 *   Button(
 *     enabled = !form.state.value.isSubmitting,
 *     onClick = { form.submit() },
 *   ) { Text("Sign in") }
 * }
 * ```
 *
 * State observation goes through Compose `State<...>` snapshots so recomposition is automatic
 * and field-grained. The underlying coroutines run on the scope returned by
 * [rememberCoroutineScope] and are cancelled when the composable leaves the composition.
 */
class ComposeFormik<V> internal constructor(
    val controller: FormikController<V>,
    private val scope: CoroutineScope,
) {
    /**
     * Test-only factory. Not meant for production use; consumers should construct via
     * [rememberFormik] from a `@Composable` context. Visible so that unit tests can exercise
     * the non-Composable surface (snapshot accessors, fire-and-forget setters) without a
     * Compose runtime host.
     */
    internal companion object {
        internal fun <V> forTesting(
            controller: FormikController<V>,
            scope: CoroutineScope,
        ): ComposeFormik<V> = ComposeFormik(controller, scope)
    }


    /** Live form state — observe with `form.state.value` from a composable. */
    val state: State<FormikState<V>>
        @Composable get() = controller.state.collectAsState()

    /** `true` if the current values differ from the initial baseline. */
    val dirty: State<Boolean>
        @Composable get() = controller.dirty.collectAsState()

    /** `true` if `state.errors` is empty. */
    val isValid: State<Boolean>
        @Composable get() = controller.isValid.collectAsState()

    /**
     * Snapshot-friendly derived state for a single field. Returns a re-computed `FieldBinding` on
     * every state change. Best used inside a composable so recomposition picks up the new value:
     *
     * ```kotlin
     * val email by form.fieldState("email")
     * TextField(email.value as String, onValueChange = { form.setFieldValue("email", it) })
     * ```
     */
    @Composable
    fun fieldState(name: String): State<FieldBinding<Any?>> {
        val state = state.value
        return remember(state, name) {
            derivedStateOf { controller.field(name) }
        }
    }

    // ------------------------------------------------------------------- snapshot accessors

    /** Snapshot read of the value at [name]. Safe to call from a composable. */
    fun value(name: String): Any? = controller.valueAt(name)

    /** Snapshot read of the error at [name]. */
    fun error(name: String): String? = controller.errorAt(name)

    /** Snapshot read of the touched flag at [name]. */
    fun isTouched(name: String): Boolean = controller.touchedAt(name)

    /** Convenience: `error(name)` if `isTouched(name)`, else `null`. Mirrors `<ErrorMessage>`. */
    fun displayError(name: String): String? = if (isTouched(name)) error(name) else null

    // ------------------------------------------------------------------- fire-and-forget setters

    fun setFieldValue(name: String, value: Any?, shouldValidate: Boolean? = null) {
        scope.launch { controller.setFieldValue(name, value, shouldValidate) }
    }

    fun setFieldValue(name: String, updater: (Any?) -> Any?, shouldValidate: Boolean? = null) {
        scope.launch { controller.setFieldValue(name, updater, shouldValidate) }
    }

    fun setFieldTouched(name: String, isTouched: Boolean = true, shouldValidate: Boolean? = null) {
        scope.launch { controller.setFieldTouched(name, isTouched, shouldValidate) }
    }

    fun setFieldError(name: String, message: String?) = controller.setFieldError(name, message)
    fun setStatus(status: Any?) = controller.setStatus(status)
    fun setSubmitting(isSubmitting: Boolean) = controller.setSubmitting(isSubmitting)
    fun setErrors(errors: FormikErrors) = controller.setErrors(errors)

    fun submit() = controller.handleSubmit()
    fun resetForm() = controller.handleReset()

    /**
     * Run a suspend block on the form's scope — handy for `onClick = { form.launch { … } }`
     * patterns that need access to `actions: FormikActions<V>`.
     */
    fun launch(block: suspend FormikActions<V>.() -> Unit) {
        scope.launch { with(controller) { block(controller) } }
    }
}

/**
 * Create-or-remember a Compose-bound [FormikController] for [V].
 *
 * The controller is created on first composition and disposed (via its internal scope) when the
 * composable leaves. Configuration parameters are read on first creation; changing them across
 * recompositions does **not** rebuild the controller (this matches Formik's React semantics — the
 * config snapshot is taken on first render).
 *
 * To force a rebuild, change the [key] argument.
 */
@Composable
fun <V> rememberFormik(
    initialValues: V,
    validate: (suspend (V) -> FormikErrors)? = null,
    schemaValidator: SchemaValidator<V>? = null,
    onSubmit: FormikSubmitHandler<V>,
    onReset: FormikResetHandler<V>? = null,
    validateOnChange: Boolean = true,
    validateOnBlur: Boolean = true,
    validateOnMount: Boolean = false,
    enableReinitialize: Boolean = false,
    valuesUpdater: ValuesUpdater<V>? = null,
    key: Any? = Unit,
): ComposeFormik<V> {
    val scope = rememberCoroutineScope()
    val composeFormik = remember(key) {
        val controller = FormikController(
            FormikConfig(
                initialValues = initialValues,
                validate = validate,
                schemaValidator = schemaValidator,
                onSubmit = onSubmit,
                onReset = onReset,
                validateOnChange = validateOnChange,
                validateOnBlur = validateOnBlur,
                validateOnMount = validateOnMount,
                enableReinitialize = enableReinitialize,
                valuesUpdater = valuesUpdater,
                coroutineScope = scope,
            )
        )
        ComposeFormik(controller, scope)
    }
    DisposableEffect(composeFormik) {
        onDispose { composeFormik.controller.close() }
    }
    return composeFormik
}
