@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.kformik

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
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
     * Single-flight gate for [submit]. Held for the entire submit lifecycle — including the
     * suspending `config.onSubmit` call — so a second `submit()` issued while the first is still
     * awaiting the user's submission handler returns immediately as a no-op (`tryLock` fails).
     *
     * Separated from the `isSubmitting` flag intentionally: [resetForm] (and direct user calls to
     * [setSubmitting]) can freely flip `isSubmitting = false` without disarming the structural
     * single-flight guard. Without this, a `resetForm()` landing mid-submit would clear the flag
     * and let a second `submit()` race past the in-mutex `isSubmitting` check, breaking the
     * documented single-flight guarantee.
     */
    private val submitMutex = Mutex()

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
        var newValues: Any? = null
        mutex.withLock {
            gen = ++validationGeneration
            // Run the (pure) transform INSIDE the compare-and-set, so its values/touched/errors
            // realignment is computed against the latest state on each retry — a concurrent
            // lock-free setter (setFieldError/setErrors/...) is therefore not clobbered.
            _state.update { current ->
                val next = transform(current)
                newValues = next.values
                next
            }
        }
        @Suppress("UNCHECKED_CAST")
        if (validate) runAllValidationsAndCommit(newValues as V, gen)
    }

    /**
     * Debounced change-validation pipeline. Non-null only when [FormikConfig.validateDebounceMs]
     * is a positive value. Carries (values, generation) pairs — the generation lets
     * [runAllValidationsAndCommit] drop stale results when newer mutations have superseded the
     * pair we just debounced.
     *
     * `replay = 1` matters: change requests can be emitted (via [setFieldValue] etc.) before the
     * collector coroutine launched in [init] has had a chance to subscribe. With `replay = 0`
     * those pre-subscription emissions would be lost; `replay = 1` means a late subscriber sees
     * the most recent intent, which is exactly what debounce semantics want anyway.
     *
     * `DROP_OLDEST` on overflow is also safe here: every emission is a complete (values, gen)
     * pair, so dropping a stale one in favor of a newer one is the correct debounce behavior.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private val _changeValidationRequests: MutableSharedFlow<Pair<V, Long>>? =
        config.validateDebounceMs?.takeIf { it > 0L }?.let {
            MutableSharedFlow(
                replay = 1,
                extraBufferCapacity = 63,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        }

    /**
     * Job for the debounced-validation collector. Tracked explicitly so [close] can cancel it
     * even when the user supplied their own [CoroutineScope] (in which case [close] does NOT
     * cancel the scope itself — but the Job we created is still our lifecycle to clean up).
     */
    private var _debounceCollectorJob: Job? = null

    /**
     * The most-recent debounced-validation run launched by the collector. When the collector
     * picks up a newer emission, it cancels this Job before launching the replacement — so a
     * slow `validateAsync` (typically a network round-trip) is interrupted instead of running
     * to completion only to have its result discarded at the generation-guarded commit step.
     * Requires the consumer's `validateAsync` to be cancel-cooperative (check `isActive` or
     * await a suspending call that propagates cancellation).
     */
    private var _inFlightDebouncedValidation: Job? = null

    init {
        if (config.validateOnMount) {
            scope.launch {
                revalidateCurrent()
            }
        }
        // Wire the debounced collector exactly once, only when configured. The collect body's
        // try/catch is load-bearing: without it, a throwing `validate` / `validateAsync` would
        // propagate out of .collect, terminate the launched coroutine, and silently kill all
        // subsequent change-validation requests for the controller's lifetime. Mirrors the
        // exception-routing strategy of handleSubmit/handleReset.
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        if (_changeValidationRequests != null) {
            val debounceMs = config.validateDebounceMs!!
            _debounceCollectorJob = scope.launch {
                _changeValidationRequests
                    .debounce(debounceMs)
                    .collect { (values, gen) ->
                        // Skip if a newer mutation has already bumped the generation past us —
                        // a blur or another change-validation kicked off in the foreground while
                        // we were debouncing, and its result will commit instead. The read is
                        // unprotected by the mutex — benign race, the worst case is a false-pass
                        // that drops at commit-time exactly as before.
                        if (gen != validationGeneration) return@collect
                        // Cancel any previously-running debounced run that is still in-flight.
                        // Without this, a slow validateAsync (network call) keeps running until
                        // completion only to have its result dropped at the gen-guarded commit;
                        // cancelling proactively gives the user's cooperative-cancellation code
                        // a chance to abort the network request and avoid the wasted round-trip.
                        _inFlightDebouncedValidation?.cancel(
                            CancellationException("superseded by newer change/blur (gen=$gen)")
                        )
                        _inFlightDebouncedValidation = scope.launch {
                            try {
                                runAllValidationsAndCommit(values, gen)
                            } catch (c: CancellationException) {
                                throw c
                            } catch (t: Throwable) {
                                config.onError?.invoke(t)
                            }
                        }
                    }
            }
        }
    }

    /**
     * Route a change-triggered validation request. If the controller is configured with
     * [FormikConfig.validateDebounceMs], the request is emitted to the debounced pipeline and
     * returns immediately; otherwise the validator runs synchronously (current behavior).
     * Generation tracking inside [runAllValidationsAndCommit] guarantees stale results from the
     * debounced path are dropped if newer mutations have already superseded them.
     */
    private suspend fun scheduleChangeValidation(values: V, gen: Long) {
        val pipeline = _changeValidationRequests
        if (pipeline != null) {
            // tryEmit succeeds without suspension because the buffer is configured with
            // extraBufferCapacity + DROP_OLDEST — overflow drops the previous (now-stale)
            // request, which is the correct semantics for a debounce.
            pipeline.tryEmit(values to gen)
        } else {
            runAllValidationsAndCommit(values, gen)
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
     *
     * **Parameterized-type caveat (JVM erasure).** `reified T` preserves the outer type at
     * runtime but NOT its type arguments. So `fieldOf<List<String>>("tags")` will accept a
     * stored `List<Int>` without throwing — the `is T` check erases to `is List<*>`. The
     * mismatch only surfaces when an individual element is read (an inserted-cast
     * `ClassCastException`). If you need element-level validation, prefer
     * `fieldOf<List<Any?>>(...)` and validate each element yourself, or write a typed
     * `ValuesUpdater` and use [field] for untyped access. (This is a fundamental limitation of
     * Kotlin generics on JVM/Native — fixable only with `kotlin-reflect` or a per-element walk,
     * neither of which we want in the core dependency.)
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
        var newValues: Any? = null
        mutex.withLock {
            gen = ++validationGeneration
            // Compute setAt INSIDE the compare-and-set so each retry recomputes against the latest
            // committed values — a concurrent lock-free setFormikState (which can mutate values
            // without holding the mutex) is therefore merged with, not clobbered by, this write.
            // Mirrors applyArrayMutation's pattern.
            _state.update { current ->
                val next = updater.setAt(current.values, name, value)
                newValues = next
                current.copy(values = next)
            }
        }
        @Suppress("UNCHECKED_CAST")
        if (willValidate) scheduleChangeValidation(newValues as V, gen)
    }

    override suspend fun setFieldValue(name: String, updater: (Any?) -> Any?, shouldValidate: Boolean?) {
        require(name.isNotBlank()) { "Field name must not be blank" }
        if (!scope.isActive) return
        val willValidate = shouldValidate ?: config.validateOnChange
        var gen = 0L
        var newValues: Any? = null
        mutex.withLock {
            gen = ++validationGeneration
            _state.update { current ->
                val prev = this.updater.getAt(current.values, name)
                val next = this.updater.setAt(current.values, name, updater(prev))
                newValues = next
                current.copy(values = next)
            }
        }
        @Suppress("UNCHECKED_CAST")
        if (willValidate) scheduleChangeValidation(newValues as V, gen)
    }

    override suspend fun setValues(values: V, shouldValidate: Boolean?) {
        if (!scope.isActive) return
        val willValidate = shouldValidate ?: config.validateOnChange
        val gen = mutex.withLock {
            // Wholesale replacement: `values` is the caller's intent verbatim. CAS via update still
            // forces a concurrent lock-free state mutation to retry rather than be interleaved.
            _state.update { it.copy(values = values) }
            ++validationGeneration
        }
        if (willValidate) scheduleChangeValidation(values, gen)
    }

    override suspend fun setValues(updater: (V) -> V, shouldValidate: Boolean?) {
        if (!scope.isActive) return
        val willValidate = shouldValidate ?: config.validateOnChange
        var gen = 0L
        var resolved: Any? = null
        mutex.withLock {
            gen = ++validationGeneration
            _state.update { current ->
                val next = updater(current.values)
                resolved = next
                current.copy(values = next)
            }
        }
        @Suppress("UNCHECKED_CAST")
        if (willValidate) scheduleChangeValidation(resolved as V, gen)
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

    /**
     * Atomic, lambda-based state update — the documented escape hatch for tests and rare cases
     * where the typed setters (`setFieldValue`, `setErrors`, etc.) don't fit.
     *
     * **Generation tracking caveat.** This setter is non-suspending and therefore cannot acquire
     * the controller's mutex; it does NOT bump `validationGeneration`. Practical consequence: an
     * in-flight validator launched BEFORE a `setFormikState` call commits its errors against the
     * pre-setFormikState values, because its gen check still matches at commit time. For typical
     * uses (test fixtures, applying a server-side error map, toggling status) this is fine — but
     * if you mutate `values` here and have an async validator in flight, the validator's stale
     * errors will overwrite your update's would-be-clean state.
     *
     * Mitigations:
     *  - For value mutations, prefer the mutex-protected setters (`setFieldValue`, `setValues`)
     *    which DO bump the generation atomically.
     *  - If you must mutate values via `setFormikState`, follow up with `setErrors(FormikErrors
     *    .Empty)` (or `validateForm()`) to overwrite any stale errors a previously-running
     *    validator may commit.
     *
     * A future major version may migrate `validationGeneration` to `atomicfu` to close this gap
     * without changing the escape hatch's non-suspending signature.
     */
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
        val syncErrors = fieldErrors.overlay(schemaErrors).overlay(topLevelErrors)

        // Circuit breaker: skip the optional async / expensive validator entirely if any cheap
        // rule already invalidated the form. This is the documented [FormikConfig.validateAsync]
        // contract — see its KDoc for the rationale.
        if (syncErrors.byPath.isNotEmpty()) return syncErrors

        val asyncErrors = config.validateAsync?.invoke(values) ?: FormikErrors.Empty
        // Async path overlays onto the (empty) sync layer; keeps the merge order consistent.
        return syncErrors.overlay(asyncErrors)
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
        // Capture the values snapshot and the current generation atomically. validateField is NOT a
        // mutation, so it observes the current intent rather than claiming a new one.
        val captured: Pair<V, Long> = mutex.withLock { _state.value.values to validationGeneration }
        val values = captured.first
        val gen = captured.second
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
            // Commit the per-field error under the mutex (compare-and-set), but only if no newer
            // mutation/reset has superseded the snapshot we validated — so a slow validateField
            // cannot overwrite a fresher full-validation result for this field.
            if (scope.isActive) mutex.withLock {
                if (gen == validationGeneration) {
                    _state.update { it.copy(errors = it.errors.with(name, msg)) }
                }
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
        // Structural single-flight gate: while a submit is in flight (submitMutex held, including
        // during the suspending `config.onSubmit`), any concurrent `submit()` returns immediately
        // as a no-op. This holds even if `resetForm()` or `setSubmitting(false)` clears the
        // `isSubmitting` flag mid-submit — the gate is independent of the flag.
        if (!submitMutex.tryLock()) return
        try {
            // SUBMIT_ATTEMPT: touch every leaf of `values` AND every registered field, capture the
            // submit snapshot, and claim a validation generation — all atomically under the mutex.
            var gen = 0L
            var submitValues: Any? = null
            mutex.withLock {
                gen = ++validationGeneration
                // Capture the submit snapshot INSIDE the CAS lambda so we see the same values the
                // committed state holds. Reading a pre-CAS snapshot ("`cur = _state.value`" then
                // returning `cur.values` later) would let a concurrent lock-free setFormikState
                // land between snapshot read and CAS commit, so `onSubmit` would receive the OLD
                // values while published state showed the NEW ones. Building touchedAll inside the
                // lambda also keeps it aligned with `current.values` on each CAS retry.
                _state.update { current ->
                    submitValues = current.values
                    val touchedAll = FormikTouched(
                        buildMap {
                            putAll(current.touched.byPath)
                            updater.leafPaths(current.values).forEach { put(it, true) }
                            _fieldRegistry.value.keys.forEach { put(it, true) }
                        }
                    )
                    current.copy(
                        touched = touchedAll,
                        isSubmitting = true,
                        submitCount = current.submitCount + 1,
                    )
                }
            }
            @Suppress("UNCHECKED_CAST")
            val resolvedValues = submitValues as V

            val errors: FormikErrors = try {
                runAllValidationsAndCommit(resolvedValues, gen)
            } catch (t: Throwable) {
                _state.update { it.copy(isSubmitting = false) }
                throw t
            }

            if (errors.isNotEmpty) {
                _state.update { it.copy(isSubmitting = false) }
                return
            }

            try {
                config.onSubmit(resolvedValues, this)
                if (scope.isActive) _state.update { it.copy(isSubmitting = false) }
            } catch (t: Throwable) {
                if (scope.isActive) _state.update { it.copy(isSubmitting = false) }
                throw t
            }
        } finally {
            submitMutex.unlock()
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
            // Reset is a full-state replacement; still commit via compare-and-set (not a blind
            // assignment) so a concurrent lock-free setter that lands mid-reset forces a retry
            // rather than being interleaved — honoring the class-wide CAS invariant.
            //
            // Note: `isSubmitting = false` here is the visible-flag reset (matches Formik). The
            // *single-flight* guarantee for submit() — that a second submit cannot start while a
            // first is awaiting `onSubmit` — is enforced structurally by [submitMutex], not by the
            // `isSubmitting` flag. So clearing isSubmitting here does NOT let a concurrent submit
            // race past the single-flight gate.
            _state.update {
                FormikState(
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
        // Always cancel any controller-owned Jobs (debounced collector etc.) so they don't
        // outlive the controller even when the user retains the underlying scope.
        _debounceCollectorJob?.cancel()
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
