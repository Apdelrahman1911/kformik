package io.kformik.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import io.kformik.FieldBinding
import io.kformik.FormikActions
import io.kformik.FormikConfig
import io.kformik.FormikController
import io.kformik.FormikErrors
import io.kformik.FormikInitialState
import io.kformik.FormikResetHandler
import io.kformik.FormikState
import io.kformik.FormikSubmitHandler
import io.kformik.FormikTouched
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
 * State observation goes through Compose `State<...>` snapshots. Reading [state] is whole-form:
 * any change to any field (or `isValidating`/`isSubmitting`/`submitCount`) recomposes every reader
 * of `form.state.value`. For field-grained recomposition — a reader that only recomposes when *its*
 * field changes — use [fieldState], which is backed by a per-field deduplicated flow. The
 * underlying coroutines run on the scope returned by [rememberCoroutineScope] and are cancelled when
 * the composable leaves the composition (which is also what stops accepting mutations — see
 * [rememberFormik]).
 */
@Stable
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
     * Field-grained observable [FieldBinding] for [name]. Backed by [FormikController.fieldFlow],
     * which only emits when *this field's* value/error/touched (or their initial counterparts)
     * change — so a composable reading only this state does not recompose on keystrokes in other
     * fields or on `isValidating` toggles.
     *
     * The binding's `value` is `Any?`; its `onValueChange`/`onFocusChange` are `suspend` and so
     * cannot be wired directly into Compose UI callbacks — route writes through
     * [setFieldValue]/[setFieldTouched] (or [launch]) instead:
     *
     * ```kotlin
     * val email by form.fieldState("email")
     * TextField(
     *     value = email.value as String,
     *     onValueChange = { form.setFieldValue("email", it) },
     *     isError = email.displayError != null,
     * )
     * ```
     */
    @Composable
    fun fieldState(name: String): State<FieldBinding<Any?>> {
        val flow = remember(name) { controller.fieldFlow(name) }
        return flow.collectAsState()
    }

    /** Typed snapshot read of the value at [name] (delegates to [FormikController.fieldOf]). */
    inline fun <reified T> valueOf(name: String): T = controller.fieldOf<T>(name).value

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
 * The controller is created on first composition (or when [key] changes) and bound to the
 * composable's [rememberCoroutineScope]. When the composable leaves the composition Compose cancels
 * that scope, which tears down in-flight work and causes subsequent mutations to be silently
 * dropped — so no explicit disposal call is needed (and `controller.close()` is intentionally a
 * no-op here, because the scope is caller-owned).
 *
 * The `onSubmit` / `validate` / `onReset` / `onError` callbacks are tracked with
 * [rememberUpdatedState], so they always reflect the latest composition even though the controller
 * itself is built once — they may freely close over changing props without going stale.
 *
 * When [enableReinitialize] is true, changing [initialValues] across recompositions re-syncs the
 * baseline (via [FormikController.reinitialize]); the first composition is skipped so the initial
 * snapshot is preserved. Changing [key] forces a full rebuild (discarding user edits).
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
    onError: ((Throwable) -> Unit)? = null,
    key: Any? = Unit,
    /**
     * Optional debounce window (ms) applied to change-triggered validation. Mirrors
     * [FormikConfig.validateDebounceMs]; appended at the end of the parameter list so older
     * positional callers stay binary-compatible.
     */
    validateDebounceMs: Long? = null,
    /**
     * Optional async validator that runs only when the cheap sync layer is clean. Mirrors
     * [FormikConfig.validateAsync]; appended at the end of the parameter list so older positional
     * callers stay binary-compatible. Tracked with [rememberUpdatedState] like [validate].
     */
    validateAsync: (suspend (V) -> FormikErrors)? = null,
    /**
     * Optional initial error map. Useful for server-side validation hydration ("this form was
     * rendered with these errors already attached") — pre-1.9.0 the only path was constructing a
     * raw [FormikController] yourself. Mirrors [FormikConfig.initialErrors]. Captured at first
     * composition; subsequent changes are ignored unless [enableReinitialize] is true (which
     * triggers a `reinitialize(FormikInitialState(…))` re-seed).
     */
    initialErrors: FormikErrors = FormikErrors.Empty,
    /**
     * Optional initial touched-flag map. Same hydration use case as [initialErrors]. Mirrors
     * [FormikConfig.initialTouched].
     */
    initialTouched: FormikTouched = FormikTouched.Empty,
    /**
     * Optional initial form-level status (`Any?`). Same hydration use case. Mirrors
     * [FormikConfig.initialStatus].
     */
    initialStatus: Any? = null,
): ComposeFormik<V> {
    val scope = rememberCoroutineScope()

    // Always-fresh references so callbacks captured on first composition don't go stale.
    val onSubmitState = rememberUpdatedState(onSubmit)
    val validateState = rememberUpdatedState(validate)
    val validateAsyncState = rememberUpdatedState(validateAsync)
    // schemaValidator is also wrapped so an inline schema that closes over changing state (e.g.
    // `formSchema { field("email") { minLength(minLenVar) } }` where minLenVar is a State) picks
    // up the latest captures across recompositions. Without this the controller silently keeps the
    // first-composition schema instance — asymmetric with validate / validateAsync, which already
    // route through rememberUpdatedState.
    val schemaValidatorState = rememberUpdatedState(schemaValidator)
    val onResetState = rememberUpdatedState(onReset)
    val onErrorState = rememberUpdatedState(onError)

    val composeFormik = remember(key) {
        val controller = FormikController(
            FormikConfig(
                initialValues = initialValues,
                initialErrors = initialErrors,
                initialTouched = initialTouched,
                initialStatus = initialStatus,
                validate = { v -> validateState.value?.invoke(v) ?: FormikErrors.Empty },
                validateAsync = { v -> validateAsyncState.value?.invoke(v) ?: FormikErrors.Empty },
                schemaValidator = SchemaValidator { v ->
                    schemaValidatorState.value?.validate(v) ?: FormikErrors.Empty
                },
                onSubmit = { v, actions -> onSubmitState.value(v, actions) },
                onReset = { v, actions -> onResetState.value?.invoke(v, actions) },
                validateOnChange = validateOnChange,
                validateOnBlur = validateOnBlur,
                validateOnMount = validateOnMount,
                validateDebounceMs = validateDebounceMs,
                enableReinitialize = enableReinitialize,
                valuesUpdater = valuesUpdater,
                onError = { t -> onErrorState.value?.invoke(t) },
                coroutineScope = scope,
            )
        )
        ComposeFormik(controller, scope)
    }

    if (enableReinitialize) {
        val firstPass = remember(composeFormik) { mutableStateOf(true) }
        LaunchedEffect(composeFormik, initialValues) {
            if (firstPass.value) {
                firstPass.value = false
                return@LaunchedEffect
            }
            // reinitialize() deep-equals the new baseline and early-returns when unchanged, so
            // re-running this effect is cheap/idempotent.
            composeFormik.controller.reinitialize(FormikInitialState(values = initialValues))
        }
    }

    return composeFormik
}
