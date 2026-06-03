package io.kformik.ios

import io.kformik.FormikConfig
import io.kformik.FormikController
import io.kformik.FormikErrors
import io.kformik.FormikState
import io.kformik.FormikSubmitHandler
import io.kformik.MapValuesUpdater
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * Swift/SwiftUI-friendly façade over [FormikController].
 *
 * Why a separate class? Several Kotlin idioms are awkward from Swift:
 *
 *  - `suspend` functions become `async`/`await` *or* completion-handler functions depending on
 *    the consumer's setup (SKIE vs vanilla Kotlin/Native). Most consumers prefer plain
 *    fire-and-forget setters that run on an internal scope.
 *  - `StateFlow<T>` doesn't bridge to `Combine.Publisher` automatically. We expose a callback-based
 *    [observe] method that fires on every distinct state change.
 *  - `Map<String, Any?>` arrives in Swift as `[String: Any]?` — usable but typed loosely. For
 *    consumers who want stronger typing on the Swift side, write a thin Swift wrapper that
 *    converts to a `struct` per snapshot.
 *
 * The bridge is hard-coded to `Map<String, Any?>` because that's what bridges cleanly to
 * `NSDictionary` from Swift. If you need a typed `data class` form, build a `FormikController`
 * directly and write your own Swift bridge.
 *
 * Construction is via the [FormikIosBridge] companion factories (see [create]).
 *
 * Lifecycle:
 *  - The bridge owns an internal [CoroutineScope] on [Dispatchers.Main] (override via
 *    [create]).
 *  - Call [close] when the SwiftUI view dismisses to cancel observers and the controller.
 *
 * Swift usage (SwiftUI):
 * ```swift
 * class LoginViewModel: ObservableObject {
 *   @Published var email: String = ""
 *   @Published var password: String = ""
 *   @Published var emailError: String? = nil
 *   @Published var passwordError: String? = nil
 *   @Published var submitting: Bool = false
 *
 *   private let bridge: FormikIosBridge
 *   private var token: AnyObject?
 *
 *   init() {
 *     bridge = FormikIosBridge.companion.create(
 *       initialValues: ["email": "", "password": ""] as NSDictionary,
 *       validate: { v in /* ... */ },
 *       onSubmit: { values, actions in /* ... */ }
 *     )
 *     token = bridge.observe { [weak self] state in
 *       guard let self = self else { return }
 *       self.email = state.value("email") as? String ?? ""
 *       self.emailError = state.displayError("email")
 *       self.submitting = state.isSubmitting()
 *     }
 *   }
 *
 *   func emailChanged(_ v: String)    { bridge.setFieldValue(name: "email", value: v) }
 *   func passwordChanged(_ v: String) { bridge.setFieldValue(name: "password", value: v) }
 *   func emailBlurred()               { bridge.setFieldTouched(name: "email", isTouched: true) }
 *   func submit()                     { bridge.submit() }
 *
 *   deinit { bridge.close() }
 * }
 * ```
 */
@OptIn(ExperimentalObjCName::class)
public class FormikIosBridge private constructor(
    public val controller: FormikController<Map<String, Any?>>,
    private val outerScope: CoroutineScope,
    private val ownsOuterScope: Boolean,
    private val callbackDispatcher: CoroutineDispatcher,
) {
    /**
     * Bridge-owned job, child of [outerScope]'s job. All bridge-launched coroutines (observer
     * collectors, fire-and-forget setter launches) run on a derived [scope] tied to this Job.
     * [close] cancels [bridgeJob], which deterministically cancels all bridge-launched work
     * regardless of whether the [outerScope] is owned by the caller or by the bridge.
     */
    private val bridgeJob: Job = SupervisorJob(parent = outerScope.coroutineContext[Job])
    private val scope: CoroutineScope = CoroutineScope(outerScope.coroutineContext + bridgeJob)

    /** Opaque token returned by [observe]. Hold a reference until you no longer want updates. */
    @ObjCName("FormikSubscription")
    public class Subscription internal constructor(private val job: Job) {
        public fun cancel(): Unit = job.cancel()
        public val isActive: Boolean get() = job.isActive
    }

    /**
     * Subscribe to state changes. [onState] is invoked once immediately with the current snapshot,
     * then again every time the state differs from the previous emission (deep-equal).
     *
     * The callback is dispatched on [callbackDispatcher] (default [Dispatchers.Main]), so it is safe
     * to update `@Published`/UIKit from inside it regardless of which scope the bridge runs work on.
     *
     * The returned [Subscription] should be held; `subscription.cancel()` stops further callbacks.
     * If [close] is called on the bridge, all active subscriptions are cancelled.
     */
    public fun observe(onState: (StateSnapshot) -> Unit): Subscription {
        val job = scope.launch {
            controller.state.collect {
                val snap = StateSnapshot(controller)
                withContext(callbackDispatcher) { onState(snap) }
            }
        }
        return Subscription(job)
    }

    /** Current snapshot. Safe to call from Swift's main thread. */
    public fun snapshot(): StateSnapshot = StateSnapshot(controller)

    // ----------------------------------------------------------------- setters

    public fun setFieldValue(name: String, value: Any?, shouldValidate: Boolean? = null) {
        scope.launch { controller.setFieldValue(name, value, shouldValidate) }
    }

    public fun setFieldTouched(name: String, isTouched: Boolean, shouldValidate: Boolean? = null) {
        scope.launch { controller.setFieldTouched(name, isTouched, shouldValidate) }
    }

    // Routed through the scope (like the value/touched setters) so a Swift caller's call order is
    // preserved: e.g. setFieldValue("email", v) then setFieldError("email", serverErr) commits the
    // error AFTER the value's revalidation, instead of having it cleared by the later async write.
    public fun setFieldError(name: String, message: String?) {
        scope.launch { controller.setFieldError(name, message) }
    }

    public fun setStatus(status: Any?) {
        scope.launch { controller.setStatus(status) }
    }

    public fun setSubmitting(isSubmitting: Boolean) {
        scope.launch { controller.setSubmitting(isSubmitting) }
    }

    /** Fire-and-forget submit. */
    public fun submit(): Unit = controller.handleSubmit()

    /** Fire-and-forget reset. */
    public fun resetForm(): Unit = controller.handleReset()

    /**
     * Dispose the bridge. Always cancels bridge-launched work (observer subscriptions and any
     * fire-and-forget setter launches) via [bridgeJob]. Additionally cancels the [outerScope]
     * itself only when the bridge created it (i.e. the caller did NOT pass an explicit
     * `mainScope` to [create]) — caller-provided scopes follow the caller's lifecycle, mirroring
     * [FormikController.close]'s "caller owns the scope" rule. Without this distinction, a
     * bridge sharing its parent scope with other coroutines would tear them all down on dismiss.
     *
     * Safe to call multiple times.
     */
    public fun close() {
        bridgeJob.cancel(CancellationException("FormikIosBridge.close()"))
        if (ownsOuterScope) {
            try { outerScope.cancel(CancellationException("FormikIosBridge.close()")) } catch (_: Throwable) {}
        }
        controller.close()
    }

    companion public object {
        /**
         * Factory. Creates a controller for `Map<String, Any?>` values with the given config and
         * wraps it in a bridge. The bridge owns an internal scope on [Dispatchers.Main].
         *
         * The `validate` parameter accepts a synchronous closure returning a `[String: String]`
         * dictionary, mapped to [FormikErrors]. For async validation, supply a `validateAsync`
         * (a Kotlin `suspend` function); from Swift you'd typically prefer the sync overload and
         * dispatch your own async work via `setFieldError`.
         */
        public fun create(
            initialValues: Map<String, Any?>,
            validate: ((Map<String, Any?>) -> Map<String, String>)? = null,
            onSubmit: FormikSubmitHandler<Map<String, Any?>>,
            validateOnChange: Boolean = true,
            validateOnBlur: Boolean = true,
            validateOnMount: Boolean = false,
            mainScope: CoroutineScope? = null,
            callbackDispatcher: CoroutineDispatcher = Dispatchers.Main,
        ): FormikIosBridge {
            // null sentinel lets us distinguish "caller didn't supply a scope" (we create + own +
            // cancel on close) from "caller supplied their own" (caller owns the scope, close()
            // does not cancel it). The pre-1.8.1 default of `MainScope()` evaluated at the call
            // site couldn't be distinguished from a caller-supplied scope, so close() always
            // cancelled it — which tore down the caller's scope if they had passed one. See the
            // [close] KDoc for the resulting policy.
            val ownsOuterScope = mainScope == null
            val effectiveScope = mainScope ?: MainScope()
            val config = FormikConfig(
                initialValues = initialValues,
                validate = validate?.let { fn -> { v -> FormikErrors(fn(v)) } },
                onSubmit = onSubmit,
                validateOnChange = validateOnChange,
                validateOnBlur = validateOnBlur,
                validateOnMount = validateOnMount,
                coroutineScope = effectiveScope,
            )
            return FormikIosBridge(FormikController(config), effectiveScope, ownsOuterScope, callbackDispatcher)
        }

        /**
         * Swift-friendly overload taking a **non-suspending** `onSubmit` (`(values) -> Unit`). This
         * keeps the bridge's "no async/await ceremony" promise: a vanilla Kotlin/Native consumer can
         * pass a plain closure `{ values in … }` without SKIE. The closure runs on [mainScope].
         */
        public fun createSimple(
            initialValues: Map<String, Any?>,
            validate: ((Map<String, Any?>) -> Map<String, String>)? = null,
            onSubmit: (Map<String, Any?>) -> Unit,
            validateOnChange: Boolean = true,
            validateOnBlur: Boolean = true,
            validateOnMount: Boolean = false,
            mainScope: CoroutineScope? = null,
            callbackDispatcher: CoroutineDispatcher = Dispatchers.Main,
        ): FormikIosBridge = create(
            initialValues = initialValues,
            validate = validate,
            onSubmit = { v, _ -> onSubmit(v) },
            validateOnChange = validateOnChange,
            validateOnBlur = validateOnBlur,
            validateOnMount = validateOnMount,
            mainScope = mainScope,
            callbackDispatcher = callbackDispatcher,
        )
    }
}

/**
 * Immutable snapshot of the form state at a point in time. All accessors are non-suspending and
 * return plain Kotlin/Swift-bridge types. The [isDirty]/[isValid] flags are captured at construction
 * so the snapshot stays self-consistent.
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName("FormikStateSnapshot")
public class StateSnapshot internal constructor(controller: FormikController<Map<String, Any?>>) {
    private val state: FormikState<Map<String, Any?>> = controller.state.value
    private val dirty: Boolean = controller.dirty.value
    private val valid: Boolean = controller.isValid.value

    public fun values(): Map<String, Any?> = state.values
    /**
     * Read the value at [name]. Supports nested paths (`"user.address.city"`, `"items[2]"`) via
     * the same [MapValuesUpdater] the controller uses internally; flat keys resolve as direct map
     * lookups, so `value("email")` behaves identically to the pre-1.8.1 implementation. Returns
     * `null` for unknown paths.
     */
    public fun value(name: String): Any? = MapValuesUpdater.getAt(state.values, name)
    public fun error(name: String): String? = state.errors[name]
    public fun isTouched(name: String): Boolean = state.touched[name]
    // Mirror the controller/Compose displayError: a blank-but-present error is not surfaced.
    public fun displayError(name: String): String? = if (isTouched(name)) error(name)?.takeIf { it.isNotEmpty() } else null

    public fun errors(): Map<String, String> = state.errors.byPath
    public fun touched(): Map<String, Boolean> = state.touched.byPath
    public fun status(): Any? = state.status

    public fun isSubmitting(): Boolean = state.isSubmitting
    public fun isValidating(): Boolean = state.isValidating
    public fun submitCount(): Int = state.submitCount

    /** Whether current values differ from the initial-values baseline (Formik `dirty`). */
    public fun isDirty(): Boolean = dirty

    /** Whether the form currently has no errors (Formik `isValid`). */
    public fun isValid(): Boolean = valid
}
