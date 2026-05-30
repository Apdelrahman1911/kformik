@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.kformik

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.isActive
import kotlin.coroutines.cancellation.CancellationException
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
 * Thread-safety: the controller is safe to use from multiple coroutines on multiple threads.
 *  - Value/touched mutations and the validation/submit/reset transitions serialize through an
 *    internal [Mutex] so multi-step reducer steps are atomic.
 *  - The non-suspend setters ([setFieldError], [setErrors], [setStatus], [setSubmitting],
 *    [setFormikState]) commit lock-free via compare-and-set; because every mutex-held write is
 *    *also* a compare-and-set onto the latest state (never a blind assignment), the two paths
 *    compose without clobbering each other.
 *  - The field registry is held in an atomic immutable-map snapshot, safe to mutate and iterate
 *    from any thread.
 *
 * Validation correctness: validators run outside the mutex (they may be slow/async), but each run
 * captures a monotonic *validation generation* taken atomically with the mutation that triggered
 * it. A run only commits its errors if no newer mutation/reset has appeared in the meantime, so a
 * slow stale run can never overwrite a fresher result, and a run launched before a [resetForm]
 * cannot repopulate the cleared errors. `isValidating` is published from an in-flight-run counter,
 * so it stays true until the *last* overlapping run completes.
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

    /**
     * Monotonic validation-intent counter, mutated only under [mutex]. Bumped by every mutation
     * that may trigger validation and by reset/reinitialize. A validation run captures it at the
     * moment it is launched and refuses to commit its errors if a newer intent has since appeared.
     */
    private var validationGeneration: Long = 0L

    /**
     * Number of validation runs currently in flight, mutated only under [mutex]. `isValidating` is
     * published as `activeValidations > 0`, so an early-finishing overlapping run cannot prematurely
     * clear the flag.
     */
    private var activeValidations: Int = 0

    /**
     * Field registry as an atomic immutable-map snapshot. Key = path, value = optional per-field
     * validator. Reads return a stable snapshot (safe to iterate from any thread); writes are
     * compare-and-set, so the non-suspend [registerField]/[unregisterField] are thread-safe without
     * a lock (the reducer [mutex] is suspend-only and cannot guard non-suspend public API).
     */
    private val _fieldRegistry = MutableStateFlow<Map<String, FieldValidator?>>(emptyMap())

    /** Picks a default updater if the user didn't supply one. */
    @Suppress("UNCHECKED_CAST")
    private val updater: ValuesUpdater<V> = config.valuesUpdater
        ?: if (config.initialValues is Map<*, *>) MapValuesUpdater as ValuesUpdater<V>
        else throw IllegalArgumentException(
            "No ValuesUpdater for a non-Map values type" +
                (config.initialValues?.let { " (${it::class.simpleName})" } ?: "") +
                ". Pass FormikConfig(valuesUpdater = …) for typed values, or use a Map<String, Any?>."
        )

    /** Internal accessor for the resolved updater. Used by [FieldArrayController]. */
    internal val updaterValue: ValuesUpdater<V> get() = updater

    /**
     * Atomic array mutation under the controller's reducer mutex, used by [FieldArrayController].
     * [transform] receives the locked state snapshot and returns the new state; only its
     * values/touched/errors slices are committed (via compare-and-set onto the latest state, so a
     * concurrent lock-free setter is not clobbered). Bumps the validation generation atomically with
     * the write and (if [validate]) runs validation against the new values under that generation.
     *
     * Not part of the public API — its contract may change between releases.
     */
    internal suspend fun applyArrayMutation(
        validate: Boolean,
        transform: (FormikState<V>) -> FormikState<V>,
    ) {
        if (!scope.isActive) return
        var gen = 0L
        val newValues: V = mutex.withLock {
            val base = _state.value
            val next = transform(base)
            gen = ++validationGeneration
            _state.update { it.copy(values = next.values, touched = next.touched, errors = next.errors) }
            next.values
        }
        if (validate) runAllValidationsAndCommit(newValues, gen)
    }

    init {
        if (config.validateOnMount) {
            scope.launch {
                revalidateCurrent()
            }
        }
    }

    // -------------------------------------------------------------------------- field registry

    /**
     * Register a field by path. The optional [validator] is called as part of each validation
     * run with the value at that path. Registering an existing name overwrites the validator.
     *
     * The [name] must be non-blank. Thread-safe.
     */
    fun registerField(name: String, validator: FieldValidator? = null) {
        require(name.isNotBlank()) { "Field name must not be blank" }
        _fieldRegistry.update { it + (name to validator) }
    }

    /** Unregister a field by path. No-op if not registered. Thread-safe. */
    fun unregisterField(name: String) {
        require(name.isNotBlank()) { "Field name must not be blank" }
        _fieldRegistry.update { it - name }
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

    /**
     * Construct a typed [FieldBinding] for [name].
     *
     * The value at [name] (and its initial value) must be assignable to [T]. When [T] is
     * **non-nullable** and the path is absent/unresolved (or holds a value of a different type),
     * this throws [IllegalStateException] with an actionable message rather than a raw
     * `ClassCastException`/NPE. For an optional or possibly-absent field, use [fieldOfOrNull] or a
     * nullable type argument (`fieldOf<String?>(...)`).
     */
    inline fun <reified T> fieldOf(name: String): FieldBinding<T> {
        @Suppress("UNCHECKED_CAST")
        val b = field(name) as FieldBinding<Any?>
        val raw = b.value
        // The only trap is a non-nullable T paired with a null/absent value; nullable T passes through.
        if (raw == null && null !is T) {
            error(
                "Field '$name' is null or absent, but fieldOf<${T::class.simpleName}>() requires a " +
                    "non-null value. Use field(\"$name\"), fieldOfOrNull<${T::class.simpleName}>(\"$name\"), " +
                    "or fieldOf<${T::class.simpleName}?>(\"$name\")."
            )
        }
        if (raw != null && raw !is T) {
            error(
                "Field '$name' holds a ${raw::class.simpleName} but fieldOf<${T::class.simpleName}>() was " +
                    "requested. Use field(\"$name\") for untyped access, or request the correct type."
            )
        }
        return FieldBinding(
            name = b.name,
            value = raw as T,
            initialValue = b.initialValue as? T,
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

    /**
     * Nullable-safe typed [FieldBinding] for [name]. Returns a `FieldBinding<T?>` whose [value] is
     * `null` when the path is absent/unresolved or holds a value not assignable to [T] — never
     * throws. Prefer this for optional or not-yet-populated fields.
     */
    inline fun <reified T> fieldOfOrNull(name: String): FieldBinding<T?> {
        @Suppress("UNCHECKED_CAST")
        val b = field(name) as FieldBinding<Any?>
        return FieldBinding(
            name = b.name,
            value = b.value as? T,
            initialValue = b.initialValue as? T,
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

    /**
     * A field-grained [StateFlow] of the [FieldBinding] for [name]. Unlike observing the whole
     * [state], a collector of this flow is only notified when *this field's* own data slices
     * (value / error / touched and their initial counterparts) actually change — a keystroke in
     * another field, or `isValidating` toggling, does not emit. This is the building block for
     * field-grained recomposition in the Compose adapter. Implemented as a non-launching facade,
     * so it spawns no long-lived collector job.
     */
    fun fieldFlow(name: String): StateFlow<FieldBinding<Any?>> {
        require(name.isNotBlank()) { "Field name must not be blank" }
        return DerivedStateFlow(
            valueFn = { makeBinding(name) },
            flowFn = {
                combine(_state, _initialState) { _, _ -> makeBinding(name) }
                    .distinctUntilChanged { a, b ->
                        // Compare only the data-bearing slices; ignore the (always-fresh) callback lambdas.
                        a.value == b.value && a.error == b.error && a.touched == b.touched &&
                            a.initialValue == b.initialValue && a.initialError == b.initialError &&
                            a.initialTouched == b.initialTouched
                    }
            },
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
            // Don't surface a blank-but-present error decoration (a validator returning "" still
            // creates an error key); the field is still counted invalid by isValid, matching Formik.
            displayError = if (touched) error?.takeIf { it.isNotEmpty() } else null,
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
        var gen = 0L
        val newValues: V = mutex.withLock {
            val next = updater.setAt(_state.value.values, name, value)
            gen = ++validationGeneration
            _state.update { it.copy(values = next) }
            next
        }
        if (willValidate) runAllValidationsAndCommit(newValues, gen)
    }

    override suspend fun setFieldValue(name: String, updater: (Any?) -> Any?, shouldValidate: Boolean?) {
        require(name.isNotBlank()) { "Field name must not be blank" }
        if (!scope.isActive) return
        val willValidate = shouldValidate ?: config.validateOnChange
        var gen = 0L
        val newValues: V = mutex.withLock {
            val prev = this.updater.getAt(_state.value.values, name)
            val next = this.updater.setAt(_state.value.values, name, updater(prev))
            gen = ++validationGeneration
            _state.update { it.copy(values = next) }
            next
        }
        if (willValidate) runAllValidationsAndCommit(newValues, gen)
    }

    override suspend fun setValues(values: V, shouldValidate: Boolean?) {
        if (!scope.isActive) return
        val willValidate = shouldValidate ?: config.validateOnChange
        val gen = mutex.withLock {
            _state.update { it.copy(values = values) }
            ++validationGeneration
        }
        if (willValidate) runAllValidationsAndCommit(values, gen)
    }

    override suspend fun setValues(updater: (V) -> V, shouldValidate: Boolean?) {
        if (!scope.isActive) return
        val willValidate = shouldValidate ?: config.validateOnChange
        var gen = 0L
        val resolved: V = mutex.withLock {
            val next = updater(_state.value.values)
            gen = ++validationGeneration
            _state.update { it.copy(values = next) }
            next
        }
        if (willValidate) runAllValidationsAndCommit(resolved, gen)
    }

    override suspend fun setFieldTouched(name: String, isTouched: Boolean, shouldValidate: Boolean?) {
        require(name.isNotBlank()) { "Field name must not be blank" }
        if (!scope.isActive) return
        val willValidate = shouldValidate ?: config.validateOnBlur
        var gen = 0L
        val newValues: V = mutex.withLock {
            _state.update { it.copy(touched = it.touched.with(name, isTouched)) }
            gen = ++validationGeneration
            _state.value.values
        }
        if (willValidate) runAllValidationsAndCommit(newValues, gen)
    }

    override suspend fun setTouched(touched: FormikTouched, shouldValidate: Boolean?) {
        if (!scope.isActive) return
        val willValidate = shouldValidate ?: config.validateOnBlur
        var gen = 0L
        val newValues: V = mutex.withLock {
            _state.update { it.copy(touched = touched) }
            gen = ++validationGeneration
            _state.value.values
        }
        if (willValidate) runAllValidationsAndCommit(newValues, gen)
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

    /** Mark a validation run as started: bump the in-flight count and raise `isValidating`. */
    private suspend fun enterValidation() = mutex.withLock {
        activeValidations++
        _state.update { if (it.isValidating) it else it.copy(isValidating = true) }
    }

    /**
     * Mark a validation run as finished: lower the in-flight count and republish `isValidating`.
     * Always invoked inside [NonCancellable] from a `finally`, so the flag is released even when the
     * run was cancelled mid-flight (a suspend `mutex.withLock` would otherwise throw immediately in a
     * cancelled coroutine and leave `isValidating` stuck true).
     */
    private suspend fun exitValidation() = mutex.withLock {
        activeValidations = (activeValidations - 1).coerceAtLeast(0)
        val validating = activeValidations > 0
        _state.update { if (it.isValidating == validating) it else it.copy(isValidating = validating) }
    }

    /**
     * Run all configured validators against [values] and commit the merged errors — but only if
     * [gen] is still the latest validation generation at commit time (otherwise a newer mutation or
     * a reset has superseded this run, or the run was cancelled, and its result is dropped). Returns
     * the computed errors regardless, so awaiting callers ([submit], [validateForm]) see this run's
     * own result. `isValidating` is managed by the enter/exit pair so it survives cancellation.
     */
    private suspend fun runAllValidationsAndCommit(values: V, gen: Long): FormikErrors {
        if (!scope.isActive) return FormikErrors.Empty
        enterValidation()
        try {
            val merged = runAllValidations(values)
            if (scope.isActive) {
                mutex.withLock {
                    if (gen == validationGeneration) {
                        _state.update { current ->
                            if (deepEquals(current.errors.byPath, merged.byPath)) current
                            else current.copy(errors = merged)
                        }
                    }
                }
            }
            return merged
        } finally {
            withContext(NonCancellable) { exitValidation() }
        }
    }

    private suspend fun runAllValidations(values: V): FormikErrors {
        val fieldErrors = runFieldLevelValidations(values)
        val schemaErrors = config.schemaValidator?.validate(values) ?: FormikErrors.Empty
        val topLevelErrors = config.validate?.invoke(values) ?: FormikErrors.Empty

        // merge order: field → schema → top-level (later writers win, matching Formik's deepmerge.all order)
        return fieldErrors.overlay(schemaErrors).overlay(topLevelErrors)
    }

    private suspend fun runFieldLevelValidations(values: V): FormikErrors {
        val registry = _fieldRegistry.value
        if (registry.isEmpty()) return FormikErrors.Empty
        val result = mutableMapOf<String, String>()
        for ((name, validator) in registry) {
            if (validator == null) continue
            val v = updater.getAt(values, name)
            // A validator exception propagates to the caller (Formik #1329 contract), aborting this
            // run; the run's mutex commit is skipped and isValidating is restored on the throw path.
            val msg = validator(v)
            if (msg != null) result[name] = msg
        }
        return FormikErrors(result)
    }

    override suspend fun validateForm(values: V?): FormikErrors {
        if (!scope.isActive) return FormikErrors.Empty
        return if (values != null) revalidate(values) else revalidateCurrent()
    }

    /** Capture a fresh generation atomically and validate the current values. */
    private suspend fun revalidateCurrent(): FormikErrors {
        if (!scope.isActive) return FormikErrors.Empty
        val captured: Pair<V, Long> = mutex.withLock { _state.value.values to ++validationGeneration }
        return runAllValidationsAndCommit(captured.first, captured.second)
    }

    /** Capture a fresh generation atomically and validate the supplied [values]. */
    private suspend fun revalidate(values: V): FormikErrors {
        if (!scope.isActive) return FormikErrors.Empty
        val gen = mutex.withLock { ++validationGeneration }
        return runAllValidationsAndCommit(values, gen)
    }

    override suspend fun validateField(name: String): String? {
        require(name.isNotBlank()) { "Field name must not be blank" }
        if (!scope.isActive) return null
        val validator = _fieldRegistry.value[name]
        val values = _state.value.values
        enterValidation()
        try {
            val msg: String? = when {
                validator != null -> validator(updater.getAt(values, name))
                config.schemaValidator is FormSchema<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    (config.schemaValidator as FormSchema<V>).validateFieldIncludingCross(values, name)
                }
                config.schemaValidator != null -> config.schemaValidator!!.validate(values)[name]
                else -> null
            }
            // Commit the per-field error under the mutex (compare-and-set) so it composes with a
            // concurrent full-validation commit.
            if (scope.isActive) mutex.withLock {
                _state.update { it.copy(errors = it.errors.with(name, msg)) }
            }
            return msg
        } finally {
            withContext(NonCancellable) { exitValidation() }
        }
    }

    // ------------------------------------------------------------------------------- submit

    /**
     * Imperatively submit the form. Touches every registered field, runs validation, and
     * (only if validation passes) calls [config.onSubmit]. Returns when `onSubmit` returns.
     *
     * Single-flight: while a submission is already in flight (`isSubmitting == true`) this is a
     * no-op and returns immediately without incrementing `submitCount` or calling `onSubmit` again.
     * The values validated are the exact values passed to `onSubmit` (captured once at the start of
     * the attempt), so a concurrent edit during submit cannot make them diverge.
     *
     * Throws if the user's `onSubmit` throws (mirroring Formik's `submitForm` rejection behavior).
     */
    override suspend fun submit() {
        if (!scope.isActive) return
        // SUBMIT_ATTEMPT: touch every leaf of `values` AND every registered field, capture the
        // submit snapshot, and claim a validation generation — all atomically under the mutex.
        var gen = 0L
        val submitValues: V = mutex.withLock {
            val cur = _state.value
            if (cur.isSubmitting) return  // single-flight guard (non-local return releases the lock)
            // Build the touched-all map in one pass (rightmost-wins ordering) instead of two
            // intermediate map concatenations.
            val touchedAll = FormikTouched(
                buildMap {
                    putAll(cur.touched.byPath)
                    updater.leafPaths(cur.values).forEach { put(it, true) }
                    _fieldRegistry.value.keys.forEach { put(it, true) }
                }
            )
            gen = ++validationGeneration
            _state.update {
                it.copy(
                    touched = touchedAll,
                    isSubmitting = true,
                    submitCount = it.submitCount + 1,
                )
            }
            cur.values
        }

        val errors: FormikErrors = try {
            runAllValidationsAndCommit(submitValues, gen)
        } catch (t: Throwable) {
            _state.update { it.copy(isSubmitting = false) }
            throw t
        }

        if (errors.isNotEmpty) {
            _state.update { it.copy(isSubmitting = false) }
            return
        }

        try {
            config.onSubmit(submitValues, this)
            if (scope.isActive) _state.update { it.copy(isSubmitting = false) }
        } catch (t: Throwable) {
            if (scope.isActive) _state.update { it.copy(isSubmitting = false) }
            throw t
        }
    }

    /**
     * Fire-and-forget submit. Launches on the controller's scope. [CancellationException] propagates;
     * any other failure is delivered to [FormikConfig.onError] (if set) instead of being silently
     * swallowed. Use the suspend [submit] when you need to await/observe the result directly.
     */
    fun handleSubmit() {
        scope.launch {
            try {
                submit()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                config.onError?.invoke(t)
            }
        }
    }

    // -------------------------------------------------------------------------------- reset

    /**
     * Reset the form. If [nextState] is provided, its non-null fields become the new baseline;
     * otherwise the existing baseline is restored.
     *
     * Updates the internal "initial state" snapshot so [dirty] re-baselines, and bumps the
     * validation generation so any validation launched before the reset cannot repopulate the
     * cleared errors.
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
            // Invalidate any in-flight validation started before this reset.
            validationGeneration++
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

    /**
     * Fire-and-forget reset. [CancellationException] propagates; any other failure is delivered to
     * [FormikConfig.onError] (if set) instead of being silently swallowed.
     */
    fun handleReset() {
        scope.launch {
            try {
                resetForm()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                config.onError?.invoke(t)
            }
        }
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
                revalidate(newInitial.values)
            }
        } else {
            mutex.withLock {
                validationGeneration++
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

    /** Read-only mirror of [FormikConfig.validateOnChange]. */
    val validateOnChange: Boolean get() = config.validateOnChange

    /** Read-only mirror of [FormikConfig.validateOnBlur]. */
    val validateOnBlur: Boolean get() = config.validateOnBlur

    /** Read-only mirror of [FormikConfig.validateOnMount]. */
    val validateOnMount: Boolean get() = config.validateOnMount

    /** Read-only mirror of [FormikConfig.enableReinitialize]. */
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
