package io.kformik

import kotlinx.coroutines.CoroutineScope

/**
 * The full configuration for a [FormikController]. Equivalent to Formik's `FormikConfig<Values>`,
 * with React-specific bits stripped out (render slots, `innerRef`, etc.).
 */
data class FormikConfig<V>(
    /** Required: the initial values. Snapshot is taken on construction. */
    val initialValues: V,

    /** Initial errors. Defaults to empty. */
    val initialErrors: FormikErrors = FormikErrors.Empty,

    /** Initial touched state. Defaults to empty. */
    val initialTouched: FormikTouched = FormikTouched.Empty,

    /** Initial status. Defaults to null. */
    val initialStatus: Any? = null,

    /** Top-level validator. Returns the full error map (or empty if valid). */
    val validate: (suspend (V) -> FormikErrors)? = null,

    /** Schema-style validator. Composed with [validate] and per-field validators. */
    val schemaValidator: SchemaValidator<V>? = null,

    /** Required: submit handler. Always suspending. */
    val onSubmit: FormikSubmitHandler<V>,

    /** Optional reset handler. Awaited before the reset is committed. */
    val onReset: FormikResetHandler<V>? = null,

    /** Whether changes (`setFieldValue`, `setValues`) trigger validation. Default `true`. */
    val validateOnChange: Boolean = true,

    /** Whether blur (`setFieldTouched`, `setTouched`) triggers validation. Default `true`. */
    val validateOnBlur: Boolean = true,

    /** Whether to validate on construction. Default `false`. */
    val validateOnMount: Boolean = false,

    /**
     * Optional debounce window (in milliseconds) applied to change-triggered validation. When set,
     * rapid mutations via [FormikController.setFieldValue] / [FormikController.setValues] coalesce
     * into a single validation run after the debounce window — useful for forms whose [validate]
     * makes expensive checks (network lookups, regex scans over long input, etc.).
     *
     * The debounce applies **only** to validation triggered by [validateOnChange]. Validation
     * triggered by blur ([validateOnBlur]), explicit [FormikController.validateForm] /
     * [FormikController.validateField] calls, or submit always runs immediately — those are
     * explicit triggers where users expect instant feedback.
     *
     * `null` (default) preserves current behavior: every change validates synchronously.
     * `0` is treated as `null`. Negative values are coerced to `null`.
     */
    val validateDebounceMs: Long? = null,

    /** Whether [FormikController.reinitialize] should auto-reset and re-snapshot. Default `false`. */
    val enableReinitialize: Boolean = false,

    /**
     * Optional [CoroutineScope] that owns all background work (validation, submit, etc.).
     * If null, the controller creates its own scope using [kotlinx.coroutines.SupervisorJob]
     * and [kotlinx.coroutines.Dispatchers.Default]. Closing the controller cancels this scope.
     *
     * Pass a `ViewModelScope` on Android or a custom scope on iOS/JVM to let the lifecycle
     * cancel in-flight work automatically.
     */
    val coroutineScope: CoroutineScope? = null,

    /**
     * Strategy for reading/writing nested fields inside [V]. If null, the controller picks a
     * sensible default: [MapValuesUpdater] when [initialValues] is a [Map]. For a non-[Map] [V]
     * with no updater supplied the controller fails fast at construction (see [FormikController]),
     * since it has no way to read or write fields.
     *
     * For typed `data class` values, provide a hand-written or codegen'd updater.
     */
    val valuesUpdater: ValuesUpdater<V>? = null,

    /**
     * Optional error sink for the fire-and-forget [FormikController.handleSubmit] /
     * [FormikController.handleReset] entry points. When `onSubmit`/`onReset` throws on those
     * non-awaited paths, the throwable is delivered here instead of being silently swallowed.
     * [kotlinx.coroutines.CancellationException] is never delivered — it propagates so structured
     * concurrency keeps working. The awaitable [FormikController.submit] / [FormikController.resetForm]
     * still throw to their caller regardless of this hook.
     */
    val onError: ((Throwable) -> Unit)? = null,
)
