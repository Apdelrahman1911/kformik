# iOS / SwiftUI bridge (`FormikIosBridge`)

A Swift-friendly façade around `FormikController`, shipped inside Kformik's `iosMain` source set so it's available in the shared framework you expose to Xcode.

## Why a separate bridge?

Several Kotlin idioms are awkward from Swift:

- `suspend` functions become `async`/`await` (with SKIE/Touchlab) or completion-handler functions (without). Most consumers prefer plain fire-and-forget setters that run on an internal scope.
- `StateFlow<T>` doesn't bridge to `Combine.Publisher` automatically.
- `Map<String, Any?>` arrives in Swift as `[String: Any]?` — usable but loosely typed.

`FormikIosBridge` exposes a *non-suspending* API surface: callback-based observation, fire-and-forget setters, and `submit()` / `resetForm()` that don't require `await` at the call site.

## Build setup

The bridge is in `kformik/src/iosMain/kotlin/io/kformik/ios/`. Build the shared framework as you normally would:

```
./gradlew :kformik:linkReleaseFrameworkIosArm64
./gradlew :kformik:linkReleaseFrameworkIosSimulatorArm64
```

Then add the resulting `.framework` to your Xcode project (drag-and-drop or via `cocoapods`/`SPM` integration).

## Public API

```kotlin
class FormikIosBridge {
    companion object {
        fun create(
            initialValues: Map<String, Any?>,
            validate: ((Map<String, Any?>) -> Map<String, String>)? = null,
            onSubmit: FormikSubmitHandler<Map<String, Any?>>,
            validateOnChange: Boolean = true,
            validateOnBlur: Boolean = true,
            validateOnMount: Boolean = false,
            mainScope: CoroutineScope = MainScope(),
        ): FormikIosBridge
    }

    fun observe(onState: (StateSnapshot) -> Unit): Subscription
    fun snapshot(): StateSnapshot

    fun setFieldValue(name: String, value: Any?, shouldValidate: Boolean? = null)
    fun setFieldTouched(name: String, isTouched: Boolean, shouldValidate: Boolean? = null)
    fun setFieldError(name: String, message: String?)
    fun setStatus(status: Any?)
    fun setSubmitting(isSubmitting: Boolean)
    fun submit()
    fun resetForm()
    fun close()
}

class StateSnapshot {
    fun values(): Map<String, Any?>
    fun value(name: String): Any?
    fun error(name: String): String?
    fun isTouched(name: String): Boolean
    fun displayError(name: String): String?
    fun errors(): Map<String, String>
    fun touched(): Map<String, Boolean>
    fun status(): Any?
    fun isSubmitting(): Boolean
    fun isValidating(): Boolean
    fun submitCount(): Int
}
```

## SwiftUI usage

```swift
import shared  // your KMP framework

final class LoginViewModel: ObservableObject {
    @Published var email: String = ""
    @Published var password: String = ""
    @Published var emailError: String? = nil
    @Published var passwordError: String? = nil
    @Published var submitting: Bool = false
    @Published var status: String? = nil

    private let bridge: FormikIosBridge
    private var subscription: FormikIosBridge.Subscription?

    init() {
        bridge = FormikIosBridge.companion.create(
            initialValues: ["email": "", "password": ""] as [String: Any],
            validate: { values in
                var errors: [String: String] = [:]
                let email = values["email"] as? String ?? ""
                let password = values["password"] as? String ?? ""
                if email.isEmpty {
                    errors["email"] = "Email is required"
                } else if !email.contains("@") {
                    errors["email"] = "Invalid email"
                }
                if password.count < 8 {
                    errors["password"] = "Password must be at least 8 characters"
                }
                return errors as NSDictionary as! [String: String]
            },
            onSubmit: { values, _ in
                // call your async service here using SKIE/Touchlab
            }
        )

        subscription = bridge.observe { [weak self] snapshot in
            guard let self else { return }
            self.email = snapshot.value(name: "email") as? String ?? ""
            self.password = snapshot.value(name: "password") as? String ?? ""
            self.emailError = snapshot.displayError(name: "email")
            self.passwordError = snapshot.displayError(name: "password")
            self.submitting = snapshot.isSubmitting()
            self.status = snapshot.status() as? String
        }
    }

    func onEmailChange(_ v: String)    { bridge.setFieldValue(name: "email", value: v, shouldValidate: nil) }
    func onPasswordChange(_ v: String) { bridge.setFieldValue(name: "password", value: v, shouldValidate: nil) }
    func onEmailBlur()                 { bridge.setFieldTouched(name: "email", isTouched: true, shouldValidate: nil) }
    func onPasswordBlur()              { bridge.setFieldTouched(name: "password", isTouched: true, shouldValidate: nil) }
    func submit()                      { bridge.submit() }

    deinit {
        subscription?.cancel()
        bridge.close()
    }
}

struct LoginView: View {
    @StateObject var vm = LoginViewModel()

    var body: some View {
        VStack(spacing: 8) {
            TextField("Email", text: Binding(get: { vm.email }, set: { vm.onEmailChange($0) }))
                .onSubmit(vm.onEmailBlur)
                .textContentType(.emailAddress)
            if let e = vm.emailError { Text(e).foregroundColor(.red) }

            SecureField("Password", text: Binding(get: { vm.password }, set: { vm.onPasswordChange($0) }))
                .onSubmit(vm.onPasswordBlur)
            if let e = vm.passwordError { Text(e).foregroundColor(.red) }

            Button("Sign in", action: vm.submit).disabled(vm.submitting)
            if let s = vm.status { Text(s) }
        }
        .padding()
    }
}
```

## Limitations and notes

- **Strong typing**: `Map<String, Any?>` is the only supported `Values` shape for the bridge. For typed `data class` values, build a `FormikController` directly and write a thin Swift wrapper.
- **Threading**: the bridge creates a `MainScope()` by default, so state callbacks fire on the iOS main thread. Pass your own `CoroutineScope` to override.
- **Async validation**: the `validate` parameter exposed via `companion.create` is synchronous (Swift can't easily express a `suspend` closure). For async checks, use `setFieldError` from inside `onSubmit` or write a Kotlin-side helper that returns `suspend`.
- **Build environment**: the bridge has been compiled for `iosArm64`, `iosX64`, and `iosSimulatorArm64` in this repo. SwiftUI verification requires Xcode and an iOS simulator/device — not part of the JVM/Android CI loop.

## Tests

The bridge's behavior is exercised through the core controller tests, which run on `iosSimulatorArm64`. No iOS-specific test fixture has been added in this phase; a future round can add a `kotlin/native/test` suite that drives the bridge directly.
