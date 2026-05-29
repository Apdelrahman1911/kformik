@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.kformik

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.isActive
import kotlin.jvm.JvmName

/**
 * The brain of Kformik. Owns the [FormikState] and every mutator. Equivalent to Formik's
 * `useFormik(config)` returned bag — except as a long-lived object rather than a function-call
 * result.
 *
 * Usage:
 * ```kotlin
 * val controller = FormikController(
 *     config = FormikConfig(
 *         initialValues = mapOf("email" to "", "password" to ""),
 *         validate = { v ->
 *             buildErrors {
 *                 if ((v["email"] as String).isBlank()) put("email", "Required")
 *                 if ((v["password"] as String).length < 8) put("password", "Too short")
 *             }
 *         },
 *         onSubmit = { values, _ -> login(values) },
 *     )
 * )
 *
 * controller.setFieldValue("email", "user@example.com")
 * controller.submit()
 * ```
 *
 * The controller exposes its state as a [StateFlow] — compose `collectAsState`, SwiftUI bridges,
 * or plain `state.value` reads all work.
 *
 * Thread-safety: every public mutation goes through an internal [Mutex] so reducer steps are
 * atomic. The controller is safe to use from multiple coroutines on multiple threads.
 *
 * Lifecycle: when [config.coroutineScope][FormikConfig.coroutineScope] is provided, the controller
 * inherits its lifecycle. Otherwise [close] cancels the controller's internal scope. After
 * cancellation, mutations are silently dropped (mirroring Formik's `isMounted` check).
 */
class FormikController<V>(
    val config: FormikConfig<V>,
) : FormikActions<V> {

    private val scope: CoroutineScope = config.coroutineScope
        ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Snapshot of the "initial" state. Mutates on reset/reinitialize. */
    private val _initialState = MutableStateFlow(
        FormikInitialState(
            values = config.initialValues,
            errors = config.initialErrors,
            touched = config.initialTouched,
            status = config.initialStatus,
        )
    )
    val initialState: StateFlow<FormikInitialState<V>> = _initialState.asStateFlow()

    /** Live state. */
    private val _state = MutableStateFlow(
        FormikState(
            values = config.initialValues,
            errors = config.initialErrors,
            touched = config.initialTouched,
            status = config.initialStatus,
            isSubmitting = false,
            isValidating = false,
            submitCount = 0,
        )
    )
    val state: StateFlow<FormikState<V>> = _state.asStateFlow()

    /**
     * Derived: are current values different from the (moving) initial-values baseline?
     * Implemented as a non-launching [StateFlow] facade — `.value` recomputes from the underlying
     * state, and `.collect` observes via [combine]+[distinctUntilChanged] on the source flows.
     * This avoids holding a long-lived collector job that would prevent [kotlinx.coroutines.test.runTest]
     * from completing.
     */
    val dirty: StateFlow<Boolean> = DerivedStateFlow(
        valueFn = { !deepEquals(_state.value.values, _initialState.value.values) },
        flowFn = { combine(_state, _initialState) { s, i -> !deepEquals(s.values, i.values) }.distinctUntilChanged() },
    )

    /** Derived: is the form valid (no errors)? */
    val isValid: StateFlow<Boolean> = DerivedStateFlow(
        valueFn = { _state.value.errors.isEmpty },
        flowFn = { _state.map { it.errors.isEmpty }.distinctUntilChanged() },
    )

    /** Reducer mutex. Held during state transitions but **not** during user-provided callbacks. */
    private val mutex = Mutex()

    /** Field registry. Key = path, value = optional per-field validator. */
    private val fieldRegistry: MutableMap<String, FieldValidator?> = mutableMapOf()

    /** Picks a default updater if the user didn't supply one. */
    @Suppress("UNCHECKED_CAST")
    private val updater: ValuesUpdater<V> = config.valuesUpdater
        ?: if (config.initialValues is Map<*, *>) MapValuesUpdater as ValuesUpdater<V>
        else FlatTopLevelUpdater()

    /** Internal accessor for the resolved updater. Used by [FieldArrayController]. */
    internal val updaterValue: ValuesUpdater<V> get() = updater

    /**
     * Atomic state mutation under the controller's reducer mutex. Used by [FieldArrayController]
     * to batch a values/touched/errors update so the three slices commit together. Returns the
     * new `values` for downstream validation.
     *
     * Not part of the public API — its contract may change between releases.
     */
    internal suspend fun applyAtomic(updater: (FormikState<V>) -> FormikState<V>): V {
        if (!scope.isActive) return _state.value.values
        return mutex.withLock {
            val next = updater(_state.value)
            _state.value = next
            next.values
        }
    }

    /**
     * Run the full validation pipeline against [values] and commit the merged errors. Exposed
     * `internal` for [FieldArrayController]; equivalent to the private `runAllValidationsAndCommit`.
     */
    internal suspend fun runValidationsFromArray(values: V): FormikErrors =
        runAllValidationsAndCommit(values)

    init {
        if (config.validateOnMount) {
            scope.launch {
                runAllValidationsAndCommit(_state.value.values)
            }
        }
    }

    // -------------------------------------------------------------------------- field registry

    /**
     * Register a field by path. The optional [validator] is called as part of each validation
     * run with the value at that path. Registering an existing name overwrites the validator.
     *
     * The [name] must be non-blank.
     */
    fun registerField(name: String, validator: FieldValidator? = null) {
        require(name.isNotBlank()) { "Field name must not be blank" }
        fieldRegistry[name] = validator
    }

    /** Unregister a field by path. No-op if not registered. */
    fun unregisterField(name: String) {
        require(name.isNotBlank()) { "Field name must not be blank" }
        fieldRegistry.remove(name)
    }

    /** Read the current value at [path] (any type). */
    fun valueAt(path: String): Any? {
        require(path.isNotBlank()) { "Field path must not be blank" }
        return updater.getAt(_state.value.values, path)
    }

    /** Read the current error at [path]. */
    fun errorAt(path: String): String? {
        require(path.isNotBlank()) { "Field path must not be blank" }
        return _state.value.errors[path]
    }

    /** Read the current touched flag at [path]. */
    fun touchedAt(path: String): Boolean {
        require(path.isNotBlank()) { "Field path must not be blank" }
        return _state.value.touched[path]
    }

    /** Construct an untyped [FieldBinding] for [name]. */
    fun field(name: String): FieldBinding<Any?> {
        require(name.isNotBlank()) { "Field name must not be blank" }
        return makeBinding(name)
    }

    /** Construct a typed [FieldBinding] for [name]. */
    inline fun <reified T> fieldOf(name: String): FieldBinding<T> {
        @Suppress("UNCHECKED_CAST")
        val b = field(name) as FieldBinding<Any?>
        return FieldBinding(
            name = b.name,
            value = b.value as T,
            initialValue = b.initialValue as T?,
            error = b.error,
            initialError = b.initialError,
            touched = b.touched,
            initialTouched = b.initialTouched,
            displayError = b.displayError,
            onValueChange = { v -> b.onValueChange(v) },
            onFocusChange = { f -> b.onFocusChange(f) },
            setError = b.setError,
        )
    }

    private fun makeBinding(name: String): FieldBinding<Any?> {
        val s = _state.value
        val init = _initialState.value
        val value = updater.getAt(s.values, name)
        val initialValue = updater.getAt(init.values, name)
        val error = s.errors[name]
        val initialError = init.errors[name]
        val touched = s.touched[name]
        val initialTouched = init.touched[name]
        return FieldBinding(
            name = name,
            value = value,
            initialValue = initialValue,
            error = error,
            initialError = initialError,
            touched = touched,
            initialTouched = initialTouched,
            displayError = if (touched) error else null,
            onValueChange = { newValue -> setFieldValue(name, newValue) },
            onFocusChange = { focused -> if (!focused) setFieldTouched(name, true) },
            setError = { msg -> setFieldError(name, msg) },
        )
    }

    // ----------------------------------------------------------------------------- mutations

    override suspend fun setFieldValue(name: String, value: Any?, shouldValidate: Boolean?) {
        require(name.isNotBlank()) { "Field name must not be blank" }
        if (!scope.isActive) return
        val willValidate = shouldValidate ?: config.validateOnChange
        val newValues: V = mutex.withLock {
            val current = _state.value
            val next = updater.setAt(current.values, name, value)
            _state.value = current.copy(values = next)
            next
        }
        if (willValidate) runAllValidationsAndCommit(newValues)
    }

    override suspend fun setFieldValue(name: String, updater: (Any?) -> Any?, shouldValidate: Boolean?) {
        require(name.isNotBlank()) { "Field name must not be blank" }
        if (!scope.isActive) return
        val willValidate = shouldValidate ?: config.validateOnChange
        val newValues: V = mutex.withLock {
            val current = _state.value
            val prev = this.updater.getAt(current.values, name)
            val next = this.updater.setAt(current.values, name, updater(prev))
            _state.value = current.copy(values = next)
            next
        }
        if (willValidate) runAllValidationsAndCommit(newValues)
    }

    override suspend fun setValues(values: V, shouldValidate: Boolean?) {
        if (!scope.isActive) return
        val willValidate = shouldValidate ?: config.validateOnChange
        mutex.withLock {
            _state.value = _state.value.copy(values = values)
        }
        if (willValidate) runAllValidationsAndCommit(values)
    }

    override suspend fun setValues(updater: (V) -> V, shouldValidate: Boolean?) {
        if (!scope.isActive) return
        val willValidate = shouldValidate ?: config.validateOnChange
        val resolved: V = mutex.withLock {
            val cur = _state.value
            val next = updater(cur.values)
            _state.value = cur.copy(values = next)
            next
        }
        if (willValidate) runAllValidationsAndCommit(resolved)
    }

    override suspend fun setFieldTouched(name: String, isTouched: Boolean, shouldValidate: Boolean?) {
        require(name.isNotBlank()) { "Field name must not be blank" }
        if (!scope.isActive) return
        val willValidate = shouldValidate ?: config.validateOnBlur
        mutex.withLock {
            val cur = _state.value
            _state.value = cur.copy(touched = cur.touched.with(name, isTouched))
        }
        if (willValidate) runAllValidationsAndCommit(_state.value.values)
    }

    override suspend fun setTouched(touched: FormikTouched, shouldValidate: Boolean?) {
        if (!scope.isActive) return
        val willValidate = shouldValidate ?: config.validateOnBlur
        mutex.withLock {
            _state.value = _state.value.copy(touched = touched)
        }
        if (willValidate) runAllValidationsAndCommit(_state.value.values)
    }

    override fun setFieldError(name: String, message: String?) {
        require(name.isNotBlank()) { "Field name must not be blank" }
        if (!scope.isActive) return
        _state.update { it.copy(errors = it.errors.with(name, message)) }
    }

    override fun setErrors(errors: FormikErrors) {
        if (!scope.isActive) return
        _state.update {
            if (deepEquals(it.errors.byPath, errors.byPath)) it else it.copy(errors = errors)
        }
    }

    override fun setStatus(status: Any?) {
        if (!scope.isActive) return
        _state.update { it.copy(status = status) }
    }

    override fun setSubmitting(isSubmitting: Boolean) {
        if (!scope.isActive) return
        _state.update { it.copy(isSubmitting = isSubmitting) }
    }

    /** Atomic, lambda-based state update (escape hatch). */
    override fun setFormikState(updater: (FormikState<V>) -> FormikState<V>) {
        if (!scope.isActive) return
        _state.update(updater)
    }

    // --------------------------------------------------------------------------- validation

    /** Run all configured validators, merge their results, write into [_state.errors]. */
    private suspend fun runAllValidationsAndCommit(values: V): FormikErrors {
        if (!scope.isActive) return FormikErrors.Empty
        _state.update { it.copy(isValidating = true) }
        val merged = try {
            runAllValidations(values)
        } catch (t: Throwable) {
            _state.update { it.copy(isValidating = false) }
            throw t
        }
        if (!scope.isActive) return merged
        mutex.withLock {
            val current = _state.value
            val newErrors = if (deepEquals(current.errors.byPath, merged.byPath)) current.errors else merged
            _state.value = current.copy(isValidating = false, errors = newErrors)
        }
        return merged
    }

    private suspend fun runAllValidations(values: V): FormikErrors {
        val fieldErrors = runFieldLevelValidations(values)
        val schemaErrors = config.schemaValidator?.validate(values) ?: FormikErrors.Empty
        val topLevelErrors = config.validate?.invoke(values) ?: FormikErrors.Empty

        // merge order: field → schema → top-level (later writers win, matching Formik's deepmerge.all order)
        return fieldErrors.overlay(schemaErrors).overlay(topLevelErrors)
    }

    private suspend fun runFieldLevelValidations(values: V): FormikErrors {
        if (fieldRegistry.isEmpty()) return FormikErrors.Empty
        val result = mutableMapOf<String, String>()
        // Snapshot to avoid mutation during iteration
        val entries = fieldRegistry.toMap()
        for ((name, validator) in entries) {
            if (validator == null) continue
            val v = updater.getAt(values, name)
            val msg = try { validator(v) } catch (t: Throwable) { throw t }
            if (msg != null) result[name] = msg
        }
        return FormikErrors(result.toMap())
    }

    override suspend fun validateForm(values: V?): FormikErrors {
        return runAllValidationsAndCommit(values ?: _state.value.values)
    }

    override suspend fun validateField(name: String): String? {
        require(name.isNotBlank()) { "Field name must not be blank" }
        if (!scope.isActive) return null
        val validator = fieldRegistry[name]
        if (validator != null) {
            val v = updater.getAt(_state.value.values, name)
            _state.update { it.copy(isValidating = true) }
            val msg = try { validator(v) } finally {
                _state.update { it.copy(isValidating = false) }
            }
            setFieldError(name, msg)
            return msg
        }
        // Fall back to schema-only field validation. If the schema is a FormSchema, use its
        // focused validateField() — it only runs rules for this path, which is cheap. Otherwise
        // run the full schema and pluck the path.
        val schema = config.schemaValidator ?: return null
        _state.update { it.copy(isValidating = true) }
        val msg = try {
            if (schema is FormSchema<*>) {
                @Suppress("UNCHECKED_CAST")
                (schema as FormSchema<V>).validateField(_state.value.values, name)
            } else {
                schema.validate(_state.value.values)[name]
            }
        } finally {
            _state.update { it.copy(isValidating = false) }
        }
        setFieldError(name, msg)
        return msg
    }

    // ------------------------------------------------------------------------------- submit

    /**
     * Imperatively submit the form. Touches every registered field, runs validation, and
     * (only if validation passes) calls [config.onSubmit]. Returns when `onSubmit` returns.
     *
     * Throws if the user's `onSubmit` throws (mirroring Formik's `submitForm` rejection behavior).
     */
    override suspend fun submit() {
        if (!scope.isActive) return
        // SUBMIT_ATTEMPT: touch every leaf of `values` AND every registered field.
        // Matches Formik's `setNestedObjectValues(state.values, true)` which marks every leaf
        // path touched, regardless of whether the field was explicitly registered. The registry
        // keys are unioned in for typed `data class` updaters whose `leafPaths` may return empty.
        mutex.withLock {
            val cur = _state.value
            val leafTouched: Map<String, Boolean> =
                updater.leafPaths(cur.values).associateWith { true }
            val registryTouched: Map<String, Boolean> =
                fieldRegistry.keys.associateWith { true }
            val touchedAll = FormikTouched(
                cur.touched.byPath + leafTouched + registryTouched
            )
            _state.value = cur.copy(
                touched = touchedAll,
                isSubmitting = true,
                submitCount = cur.submitCount + 1,
            )
        }

        val errors: FormikErrors = try {
            runAllValidationsAndCommit(_state.value.values)
        } catch (t: Throwable) {
            _state.update { it.copy(isSubmitting = false) }
            throw t
        }

        if (errors.isNotEmpty) {
            _state.update { it.copy(isSubmitting = false) }
            return
        }

        try {
            config.onSubmit(_state.value.values, this)
            if (scope.isActive) _state.update { it.copy(isSubmitting = false) }
        } catch (t: Throwable) {
            if (scope.isActive) _state.update { it.copy(isSubmitting = false) }
            throw t
        }
    }

    /** Fire-and-forget submit. Launches on the controller's scope; logs (does not rethrow) errors. */
    fun handleSubmit() {
        scope.launch {
            runCatching { submit() }
        }
    }

    // -------------------------------------------------------------------------------- reset

    /**
     * Reset the form. If [nextState] is provided, its non-null fields become the new baseline;
     * otherwise the existing baseline is restored.
     *
     * Updates the internal "initial state" snapshot so [dirty] re-baselines.
     *
     * If [config.onReset] is set, it is awaited before the reset is committed.
     */
    override suspend fun resetForm(nextState: FormikState<V>?) {
        if (!scope.isActive) return

        // Call onReset (if any) BEFORE committing — Formik calls it with the current values
        // before dispatching RESET_FORM.
        config.onReset?.invoke(_state.value.values, this)

        val baseline = _initialState.value
        val values = nextState?.values ?: baseline.values
        val errors = nextState?.errors ?: baseline.errors
        val touched = nextState?.touched ?: baseline.touched
        val status = nextState?.status ?: baseline.status

        mutex.withLock {
            _initialState.value = FormikInitialState(values, errors, touched, status)
            _state.value = FormikState(
                values = values,
                errors = errors,
                touched = touched,
                status = status,
                isSubmitting = nextState?.isSubmitting ?: false,
                isValidating = nextState?.isValidating ?: false,
                submitCount = nextState?.submitCount ?: 0,
            )
        }
    }

    /** Fire-and-forget reset. */
    fun handleReset() {
        scope.launch { runCatching { resetForm() } }
    }

    // ----------------------------------------------------------------------- reinitialize

    /**
     * Update the initial-values baseline. If [config.enableReinitialize] is true, this also
     * resets the form (matching Formik's `enableReinitialize` effect). Otherwise it only
     * updates the snapshot — useful if you want a new baseline without losing user changes.
     *
     * @param newInitial the new baseline.
     */
    suspend fun reinitialize(newInitial: FormikInitialState<V>) {
        if (!scope.isActive) return
        if (deepEquals(newInitial.values, _initialState.value.values) &&
            deepEquals(newInitial.errors.byPath, _initialState.value.errors.byPath) &&
            deepEquals(newInitial.touched.byPath, _initialState.value.touched.byPath) &&
            deepEquals(newInitial.status, _initialState.value.status)
        ) {
            return
        }

        if (config.enableReinitialize) {
            resetForm(
                FormikState(
                    values = newInitial.values,
                    errors = newInitial.errors,
                    touched = newInitial.touched,
                    status = newInitial.status,
                )
            )
            if (config.validateOnMount) {
                runAllValidationsAndCommit(newInitial.values)
            }
        } else {
            mutex.withLock {
                _initialState.value = newInitial
            }
        }
    }

    // ------------------------------------------------------------------------------- close

    /**
     * Cancel the controller. If `config.coroutineScope` was supplied, this is a no-op (the
     * caller owns the scope). Otherwise, cancels the internal scope and stops accepting
     * mutations.
     */
    fun close() {
        if (config.coroutineScope == null) {
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------------- expose flags

    val validateOnChange: Boolean get() = config.validateOnChange
    val validateOnBlur: Boolean get() = config.validateOnBlur
    val validateOnMount: Boolean get() = config.validateOnMount
    val enableReinitialize: Boolean get() = config.enableReinitialize
}

// =================================================================================== helpers

/** Update-with-lambda extension. Mirrors `MutableStateFlow.update` from kotlinx.coroutines 1.6+. */
@JvmName("updateStateFlow")
private inline fun <T> MutableStateFlow<T>.update(updater: (T) -> T) {
    while (true) {
        val cur = value
        val next = updater(cur)
        if (compareAndSet(cur, next)) return
    }
}

/**
 * A [StateFlow] facade whose `.value` is computed lazily from a function and whose `.collect`
 * delegates to a transformed source [kotlinx.coroutines.flow.Flow]. Avoids spawning long-lived
 * collector jobs at construction time. Acceptable inheritance because Kformik fully owns this class
 * (it's `private`, never re-exported).
 *
 * The compiler emits an "Inheriting from this kotlinx.coroutines API is unstable" warning here
 * because [StateFlow] is annotated `@SubclassOptInRequired`. The file-level `@OptIn` opts in;
 * Kotlin 2.0.21 still emits the warning, but the inheritance contract is sealed within this file.
 * Suppressed explicitly to keep the build log clean.
 */
@Suppress("OPT_IN_USAGE", "OPT_IN_OVERRIDE")
private class DerivedStateFlow<T>(
    private val valueFn: () -> T,
    private val flowFn: () -> kotlinx.coroutines.flow.Flow<T>,
) : StateFlow<T> {
    override val replayCache: List<T> get() = listOf(valueFn())
    override val value: T get() = valueFn()
    override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<T>): Nothing {
        flowFn().collect(collector)
        error("unreachable: StateFlow.collect never returns normally")
    }
}
