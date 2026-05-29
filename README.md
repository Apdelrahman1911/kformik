# Kformik

A Kotlin Multiplatform port of [Formik](https://github.com/jaredpalmer/formik). UI-independent form state for Android, iOS, JVM, and desktop.

```kotlin
val form = FormikController(
    FormikConfig(
        initialValues = mapOf<String, Any?>("email" to "", "password" to ""),
        validate = { v -> buildErrors {
            if ((v["email"] as String).isBlank()) put("email", "Email is required")
            if ((v["password"] as String).length < 8) put("password", "Password must be at least 8 characters")
        }},
        onSubmit = { values, _ -> /* … */ },
    )
)

form.setFieldValue("email", "user@example.com")
form.setFieldTouched("email", true)
form.submit()
```

## Highlights

- UI-independent core. `commonMain` depends only on `kotlinx-coroutines-core` — no Compose, no Android UI, no SwiftUI.
- Idiomatic Kotlin: `data class` state, `StateFlow` observation, `suspend` validation and submit, no leaking React idioms.
- Optional Compose adapter (`kformik-compose`) and iOS / SwiftUI bridge (`FormikIosBridge`).
- Optional schema DSL (`formSchema { … }`) with the usual rules (required, email, minLength, pattern, cross-field, …).

## Targets

| Target | Notes |
|---|---|
| JVM 17+ | server, desktop, CLI, unit-test host |
| Android (minSdk 21) | |
| iOS Simulator ARM64 | run via `:iosSimulatorArm64Test` |
| iOS Device ARM64 | compiles; not run in CI |

The Compose adapter is Android-only.

## Install

```kotlin
repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation("io.github.apdelrahman1911:kformik:1.4.0")

    // Optional adapters:
    implementation("io.github.apdelrahman1911:kformik-compose:1.4.0")     // Android
    ksp("io.github.apdelrahman1911:kformik-ksp:1.4.0")                    // experimental
}
```

## Usage

### Plain form

```kotlin
val form = FormikController(
    FormikConfig(
        initialValues = mapOf<String, Any?>("email" to "", "password" to ""),
        onSubmit = { values, actions ->
            api.login(values["email"] as String, values["password"] as String)
            actions.setStatus("Welcome ${values["email"]}")
        },
    )
)
```

### Schema validation

```kotlin
val schema = formSchema<Map<String, Any?>> {
    field("email") { required(); email() }
    field("password") { required(); minLength(8) }
}

val form = FormikController(FormikConfig(
    initialValues = mapOf("email" to "", "password" to ""),
    schemaValidator = schema,
    onSubmit = { /* … */ },
))
```

Full DSL: [`docs/SCHEMA_VALIDATION.md`](docs/SCHEMA_VALIDATION.md).

### Typed `data class` values

```kotlin
data class LoginValues(val email: String, val password: String)

object LoginValuesUpdater : ValuesUpdater<LoginValues> {
    override fun getAt(values: LoginValues, path: String) = when (path) {
        "email" -> values.email
        "password" -> values.password
        else -> null
    }
    override fun setAt(values: LoginValues, path: String, value: Any?) = when (path) {
        "email" -> values.copy(email = value as String)
        "password" -> values.copy(password = value as String)
        else -> error(path)
    }
    override fun leafPaths(values: LoginValues) = setOf("email", "password")
}

val form = FormikController(FormikConfig(
    initialValues = LoginValues("", ""),
    valuesUpdater = LoginValuesUpdater,
    onSubmit = { _, _ -> },
))
```

Or use `:kformik-ksp` to generate the updater and path constants from a `@FormValues` annotation. See [`docs/KSP_TYPED_PATHS.md`](docs/KSP_TYPED_PATHS.md).

### Nested paths

The default `MapValuesUpdater` parses dot and bracket paths:

```kotlin
form.setFieldValue("user.address.city", "Lagos")
form.setFieldValue("tags[1]", "gamma")
```

### Field arrays

```kotlin
val friends = form.array("friends")
friends.push("aisha")
friends.unshift("first")
friends.insert(1, "between")
friends.swap(0, 2)
friends.move(2, 0)
friends.remove(0)
friends.pop()
friends.replace(0, "REPLACED")
```

Touched and errors are re-aligned across structural mutations. See [`docs/FIELD_ARRAY.md`](docs/FIELD_ARRAY.md).

### Compose

```kotlin
@Composable
fun LoginScreen() {
    val form = rememberFormik(
        initialValues = mapOf<String, Any?>("email" to "", "password" to ""),
        validate = { v -> buildErrors { /* … */ } },
        onSubmit = { values, actions -> /* … */ },
    )
    val state by form.state

    OutlinedTextField(
        value = state.values["email"] as String,
        onValueChange = { form.setFieldValue("email", it) },
        isError = form.displayError("email") != null,
    )
    Button(onClick = { form.submit() }) { Text("Sign in") }
}
```

A working sample lives in `sample-android-app/`. See [`docs/COMPOSE_USAGE.md`](docs/COMPOSE_USAGE.md).

### SwiftUI / iOS

```swift
final class LoginViewModel: ObservableObject {
    @Published var email: String = ""
    @Published var emailError: String? = nil
    private let bridge: FormikIosBridge

    init() {
        bridge = FormikIosBridge.companion.create(
            initialValues: ["email": "", "password": ""],
            validate: { _ in [:] },
            onSubmit: { _, _ in }
        )
        bridge.observe { [weak self] snap in
            self?.email = snap.value(name: "email") as? String ?? ""
            self?.emailError = snap.displayError(name: "email")
        }
    }

    func onChange(_ v: String) { bridge.setFieldValue(name: "email", value: v, shouldValidate: nil) }
    func submit() { bridge.submit() }
    deinit { bridge.close() }
}
```

See [`docs/IOS_USAGE.md`](docs/IOS_USAGE.md).

## Modules

```
kformik/             core KMP library (commonMain + iosMain)
kformik-compose/     Compose adapter (Android)
kformik-ksp/         experimental KSP processor (typed paths + ValuesUpdater)
sample-android-app/  Compose sample
examples/            10 runnable JVM examples
docs/                topic-by-topic usage notes
```

## Building

```bash
# Core, all targets
./gradlew :kformik:allTests :kformik:iosSimulatorArm64Test

# Compose adapter
./gradlew :kformik-compose:assembleRelease :kformik-compose:testReleaseUnitTest

# Sample Android app
./gradlew :sample-android-app:assembleDebug

# Examples
./gradlew :examples:compileKotlin
./gradlew :examples:run -PrunExample=login   # or nested, async, typed, fieldlevel,
                                              # dependent, debounced, wizard,
                                              # fieldarray, schema
```

## Publishing locally

```bash
./gradlew publishToMavenLocal
# Artifacts at ~/.m2/repository/io/github/apdelrahman1911/<artifact>/<version>/
```

For releasing to Maven Central see [`docs/RELEASE_PROCESS.md`](docs/RELEASE_PROCESS.md).

## License

Apache-2.0. See [`LICENSE`](LICENSE).

## Credit

This is a port of [Formik](https://github.com/jaredpalmer/formik) by Jared Palmer. The Kotlin API mirrors Formik's surface area where it makes sense; differences are documented in `docs/`.
