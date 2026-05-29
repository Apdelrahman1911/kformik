# Kformik

[![Maven Central](https://img.shields.io/maven-central/v/io.github.apdelrahman1911/kformik.svg?label=Maven%20Central&color=blue)](https://central.sonatype.com/artifact/io.github.apdelrahman1911/kformik)
[![License](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF.svg?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![CI](https://github.com/Apdelrahman1911/kformik/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Apdelrahman1911/kformik/actions/workflows/ci.yml)

Kotlin Multiplatform port of [Formik](https://github.com/jaredpalmer/formik). Same form-state engine, written in Kotlin, with no opinion about your UI layer. Drop it into a coroutine, a Jetpack Compose screen, or a SwiftUI view and read state from a `StateFlow`.

```kotlin
val form = FormikController(
    FormikConfig(
        initialValues = mapOf<String, Any?>("email" to "", "password" to ""),
        validate = { v -> buildErrors {
            if ((v["email"] as String).isBlank())          put("email", "Required")
            if ((v["password"] as String).length < 8)      put("password", "Too short")
        }},
        onSubmit = { values, _ ->
            api.login(values["email"] as String, values["password"] as String)
        },
    )
)

form.setFieldValue("email", "user@example.com")
form.setFieldTouched("email", true)
form.submit()
```

## Install

```kotlin
repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation("io.github.apdelrahman1911:kformik:1.4.0")

    // Optional
    implementation("io.github.apdelrahman1911:kformik-compose:1.4.0")  // Android Compose adapter
    ksp("io.github.apdelrahman1911:kformik-ksp:1.4.0")                 // typed paths + updater (experimental)
}
```

Targets: JVM 17+, Android (`minSdk 21`), iOS (`iosX64`, `iosArm64`, `iosSimulatorArm64`).

## What's in it

Plain `Map<String, Any?>` forms or typed `data class` forms (via a `ValuesUpdater`, hand-rolled or KSP-generated).

Three validation flavors, mix and match:

- a synchronous `validate: (V) -> FormikErrors` callback
- an async one (`suspend (V) -> FormikErrors`)
- a schema DSL

```kotlin
val schema = formSchema<Map<String, Any?>> {
    field("email")    { required(); email() }
    field("password") { required(); minLength(8) }
    field("confirm")  { required(); custom("Doesn't match") { v -> v == values["password"] } }
}

val form = FormikController(FormikConfig(
    initialValues = mapOf("email" to "", "password" to "", "confirm" to ""),
    schemaValidator = schema,
    onSubmit = { /* … */ },
))
```

By default the schema stops at the first failing rule per field. Pass `failFast = false` to collect every failure:

```kotlin
val schema = formSchema<Map<String, Any?>>(failFast = false) {
    field("password") {
        required()
        minLength(8)
        custom("Must contain a digit") { it.toString().any(Char::isDigit) }
    }
}
schema.validateAllField(values, "password")
// ["Required", "Too short", "Must contain a digit"]
```

The schema is also introspectable, so you can render required-field markers without running validation:

```kotlin
schema.isRequired("email")        // true
schema.requiredFields()           // {"email", "password", "confirm"}
schema.fieldInfo("password")      // FormFieldInfo(path, rules, isRequired)
```

Nested paths and array paths work everywhere a path string does:

```kotlin
form.setFieldValue("user.address.city", "Lagos")
form.setFieldValue("tags[1]", "gamma")
form.setFieldError("friends[0].name", "Required")
```

Field arrays handle structural mutations and keep `touched` / `errors` aligned with the rows:

```kotlin
val friends = form.array("friends")
friends.push("aisha")             // append, doesn't touch
friends.insert(1, "between")
friends.swap(0, 2)
friends.move(2, 0)
friends.remove(0)
friends.pop()
friends.replace(0, "REPLACED")    // doesn't touch
```

The rest of Formik's surface is here too: `validateOnChange` / `validateOnBlur` / `validateOnMount`, `dirty`, `isValid`, `submitCount`, `setStatus`, submit-touches-all, async submit (suspending), per-field error overrides, and `resetForm`.

## Typed values with KSP

Annotate a `data class`:

```kotlin
@FormValues
data class LoginValues(val email: String, val password: String)
```

The processor generates two siblings:

```kotlin
object LoginValuesPaths {
    const val email    = "email"
    const val password = "password"
}

object LoginValuesUpdater : ValuesUpdater<LoginValues> { /* generated get/set/leafPaths */ }
```

Which means no stringly-typed paths and no hand-rolled `when (path) { … }` boilerplate:

```kotlin
val form = FormikController(FormikConfig(
    initialValues = LoginValues("", ""),
    valuesUpdater = LoginValuesUpdater,
    onSubmit = { v, _ -> api.login(v.email, v.password) },
))

form.setFieldValue(LoginValuesPaths.email, "user@example.com")
form.setFieldError(LoginValuesPaths.password, "Too short")
```

Nested `@FormValues data class`es nest the path scope (`UserValuesPaths.address.city`). Lists, maps, sealed types, and generics aren't generated yet; for those, fall back to string paths and either hand-roll the `ValuesUpdater` or stay with `Map<String, Any?>`. Full walkthrough in [`docs/KSP_TYPED_PATHS.md`](docs/KSP_TYPED_PATHS.md).

## Compose (Android)

```kotlin
@Composable
fun LoginScreen() {
    val form = rememberFormik(
        initialValues = mapOf<String, Any?>("email" to "", "password" to ""),
        validate = { v -> buildErrors { /* … */ } },
        onSubmit = { v, _ -> /* … */ },
    )
    val state by form.state

    OutlinedTextField(
        value = state.values["email"] as String,
        onValueChange = { form.setFieldValue("email", it) },
        isError = form.displayError("email") != null,
        supportingText = { form.displayError("email")?.let { Text(it) } },
    )
    Button(onClick = { form.submit() }, enabled = !state.isSubmitting) {
        Text("Sign in")
    }
}
```

Working sample in [`sample-android-app/`](sample-android-app/). More patterns in [`docs/COMPOSE_USAGE.md`](docs/COMPOSE_USAGE.md).

## SwiftUI / iOS

`FormikIosBridge` is a Swift-friendly facade around the same controller. Wrap it in an `ObservableObject`:

```swift
final class LoginViewModel: ObservableObject {
    @Published var email = ""
    @Published var emailError: String?
    private let bridge: FormikIosBridge

    init() {
        bridge = FormikIosBridge.companion.create(
            initialValues: ["email": "", "password": ""],
            validate: { _ in [:] },
            onSubmit: { _, _ in }
        )
        bridge.observe { [weak self] snap in
            self?.email      = snap.value(name: "email") as? String ?? ""
            self?.emailError = snap.displayError(name: "email")
        }
    }

    func onChange(_ v: String) { bridge.setFieldValue(name: "email", value: v, shouldValidate: nil) }
    func submit() { bridge.submit() }
    deinit { bridge.close() }
}
```

More in [`docs/IOS_USAGE.md`](docs/IOS_USAGE.md).

## Project layout

```
kformik/             core KMP library
kformik-compose/     Compose adapter (Android)
kformik-ksp/         KSP processor for typed paths + ValuesUpdater (experimental)
sample-android-app/  Compose sample
examples/            10 runnable JVM examples
docs/                topic-by-topic usage notes
```

## Examples

```bash
./gradlew :examples:run -PrunExample=login
./gradlew :examples:run -PrunExample=schema
./gradlew :examples:run -PrunExample=fieldarray
./gradlew :examples:run -PrunExample=wizard
```

Other example names: `nested`, `async`, `typed`, `fieldlevel`, `dependent`, `debounced`.

## Build

```bash
./gradlew :kformik:allTests :kformik:iosSimulatorArm64Test
./gradlew :kformik-compose:assembleRelease
./gradlew :sample-android-app:assembleDebug
./gradlew publishToMavenLocal
```

Maven Central release process: [`docs/RELEASE_PROCESS.md`](docs/RELEASE_PROCESS.md).

## What isn't done

- The Compose adapter is Android-only. No Compose Multiplatform target.
- iOS device target compiles but isn't exercised in CI; the iOS simulator target is.
- KSP `@FormValues` generation handles flat and nested `data class`es. Lists, maps, sealed types, and generics aren't covered yet.
- No CI workflow shipped — releases run from a local machine.

## Credit

Port of [Formik](https://github.com/jaredpalmer/formik) by Jared Palmer. Where Kformik diverges from upstream behavior, the difference is documented in `docs/`.

## License

Apache-2.0. See [`LICENSE`](LICENSE).
