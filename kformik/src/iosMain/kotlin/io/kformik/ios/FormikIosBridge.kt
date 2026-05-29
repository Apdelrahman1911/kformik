package io.kformik.ios

import io.kformik.FormikConfig
import io.kformik.FormikController
import io.kformik.FormikErrors
import io.kformik.FormikState
import io.kformik.FormikSubmitHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

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
class FormikIosBridge private constructor(
    val controller: FormikController<Map<String, Any?>>,
    private val scope: CoroutineScope,
) {

    /** Opaque token returned by [observe]. Hold a reference until you no longer want updates. */
    class Subscription internal constructor(private val job: Job) {
        fun cancel() = job.cancel()
        val isActive: Boolean get() = job.isActive
    }

    /**
     * Subscribe to state changes. [onState] is invoked once immediately with the current snapshot,
     * then again every time the state differs from the previous emission (deep-equal).
     *
     * The returned [Subscription] should be held; `subscription.cancel()` stops further callbacks.
     * If [close] is called on the bridge, all active subscriptions are cancelled.
     */
    fun observe(onState: (StateSnapshot) -> Unit): Subscription {
        val job = scope.launch {
            controller.state.collect { onState(StateSnapshot(controller)) }
        }
        return Subscription(job)
    }

    /** Current snapshot. Safe to call from Swift's main thread. */
    fun snapshot(): StateSnapshot = StateSnapshot(controller)

    // ----------------------------------------------------------------- setters

    fun setFieldValue(name: String, value: Any?, shouldValidate: Boolean? = null) {
        scope.launch { controller.setFieldValue(name, value, shouldValidate) }
    }

    fun setFieldTouched(name: String, isTouched: Boolean, shouldValidate: Boolean? = null) {
        scope.launch { controller.setFieldTouched(name, isTouched, shouldValidate) }
    }

    fun setFieldError(name: String, message: String?) = controller.setFieldError(name, message)
    fun setStatus(status: Any?) = controller.setStatus(status)
    fun setSubmitting(isSubmitting: Boolean) = controller.setSubmitting(isSubmitting)

    /** Fire-and-forget submit. */
    fun submit() = controller.handleSubmit()

    /** Fire-and-forget reset. */
    fun resetForm() = controller.handleReset()

    /**
     * Cancel the bridge's coroutine scope and dispose the controller. Subsequent setter calls
     * become no-ops. Safe to call multiple times.
     */
    fun close() {
        try { scope.cancel(CancellationException("FormikIosBridge.close()")) } catch (_: Throwable) {}
        controller.close()
    }

    companion object {
        /**
         * Factory. Creates a controller for `Map<String, Any?>` values with the given config and
         * wraps it in a bridge. The bridge owns an internal scope on [Dispatchers.Main].
         *
         * The `validate` parameter accepts a synchronous closure returning a `[String: String]`
         * dictionary, mapped to [FormikErrors]. For async validation, supply a `validateAsync`
         * (a Kotlin `suspend` function); from Swift you'd typically prefer the sync overload and
         * dispatch your own async work via `setFieldError`.
         */
        fun create(
            initialValues: Map<String, Any?>,
            validate: ((Map<String, Any?>) -> Map<String, String>)? = null,
            onSubmit: FormikSubmitHandler<Map<String, Any?>>,
            validateOnChange: Boolean = true,
            validateOnBlur: Boolean = true,
            validateOnMount: Boolean = false,
            mainScope: CoroutineScope = MainScope(),
        ): FormikIosBridge {
            val config = FormikConfig(
                initialValues = initialValues,
                validate = validate?.let { fn -> { v -> FormikErrors(fn(v)) } },
                onSubmit = onSubmit,
                validateOnChange = validateOnChange,
                validateOnBlur = validateOnBlur,
                validateOnMount = validateOnMount,
                coroutineScope = mainScope,
            )
            return FormikIosBridge(FormikController(config), mainScope)
        }
    }
}

/**
 * Immutable snapshot of the form state at a point in time. All accessors are non-suspending and
 * return plain Kotlin/Swift-bridge types.
 */
class StateSnapshot internal constructor(controller: FormikController<Map<String, Any?>>) {
    private val state: FormikState<Map<String, Any?>> = controller.state.value

    fun values(): Map<String, Any?> = state.values
    fun value(name: String): Any? = state.values[name]
    fun error(name: String): String? = state.errors[name]
    fun isTouched(name: String): Boolean = state.touched[name]
    fun displayError(name: String): String? = if (isTouched(name)) error(name) else null

    fun errors(): Map<String, String> = state.errors.byPath
    fun touched(): Map<String, Boolean> = state.touched.byPath
    fun status(): Any? = state.status

    fun isSubmitting(): Boolean = state.isSubmitting
    fun isValidating(): Boolean = state.isValidating
    fun submitCount(): Int = state.submitCount
}
