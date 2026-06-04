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

// setFieldValue / setFieldTouched / submit are suspend — call them from a coroutine
// (or use the fire-and-forget form.handleSubmit() outside one):
scope.launch {
    form.setFieldValue("email", "user@example.com")
    form.setFieldTouched("email", true)
    form.submit()
}
```

## Install

The plugins you'll need depend on what you're building. Apply only what's relevant — core users don't need anything beyond the Kotlin plugin.

```kotlin
plugins {
    kotlin("jvm") version "2.0.21"                              // or kotlin("multiplatform") / kotlin("android")

    // Only if you use @FormValues — the ksp(...) dep below requires this plugin.
    id("com.google.devtools.ksp") version "2.0.21-1.0.27"

    // Only if you use the Compose adapter. For pure Android Compose, the Android Compose
    // plugin works too; the JetBrains one is what enables KMP shared composables.
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation("io.github.apdelrahman1911:kformik:1.9.0")

    // Optional
    implementation("io.github.apdelrahman1911:kformik-compose:1.9.0")  // Compose Multiplatform adapter
    implementation("io.github.apdelrahman1911:kformik-forms:1.9.0")    // Declarative Map<String, Field> form layer

    // KSP processor — needs BOTH compileOnly (for @FormValues import) and ksp (to run the processor)
    compileOnly("io.github.apdelrahman1911:kformik-ksp:1.9.0")
    ksp("io.github.apdelrahman1911:kformik-ksp:1.9.0")
}
```

Targets: JVM 17+, Android (`minSdk 21` for core / `24` for the Compose adapter), iOS (`iosX64`, `iosArm64`, `iosSimulatorArm64`).

The `kformik-compose` adapter is a Compose Multiplatform module — works in shared `commonMain` code on Android, Desktop JVM, and iOS. See the Compose section below.

## What's in it

Plain `Map<String, Any?>` forms or typed `data class` forms (via a `ValuesUpdater`, hand-rolled or KSP-generated).

Three validation flavors, mix and match:

- a synchronous `validate: (V) -> FormikErrors` callback
- an async one (`suspend (V) -> FormikErrors`)
- a schema DSL

#### Same form, two ways

```kotlin
// Style A — plain validate callback. Just Kotlin. Good when the logic doesn't fit a rule shape
// or you want fine-grained control over conditionals.
val formA = FormikController(FormikConfig(
    initialValues = mapOf("email" to "", "password" to ""),
    validate = { v -> buildErrors {
        if ((v["email"] as String).isBlank())     put("email", "Required")
        if ((v["password"] as String).length < 8) put("password", "Too short")
    }},
    onSubmit = { /* … */ },
))

// Style B — schema DSL. Same rules, declared field by field. Introspectable; rules are data.
val schema = formSchema<Map<String, Any?>> {
    field("email") {
        required()
    }
    field("password") {
        required()
        minLength(8)
    }
}
val formB = FormikController(FormikConfig(
    initialValues = mapOf("email" to "", "password" to ""),
    schemaValidator = schema,
    onSubmit = { /* … */ },
))
```

The schema is a regular Kotlin DSL — `formSchema<V> { … }` is a normal function with a lambda receiver, like `buildString { }` or `apply { }`. Inside it, `field("name") { … }` opens a builder for one path's rules. No code generation or compiler magic — every block is just a method call with a trailing lambda.

#### Built-in rules

`required`, `minLength`, `maxLength`, `email`, `pattern` (regex), `min`, `max` (numeric), and `custom` for anything else. Full reference: [`docs/SCHEMA_VALIDATION.md`](docs/SCHEMA_VALIDATION.md).

#### Cross-field rules

Use `custom`. The rule lambda takes two arguments — the value at the field being declared (`v`) and
the whole form snapshot (`all`) — and returns a **nullable error message** (return `null` to pass,
a non-null `String` to fail):

```kotlin
val schema = formSchema<Map<String, Any?>> {
    field("password") {
        required()
        minLength(8)
    }
    field("confirm") {
        required()
        // `v` is the current value at "confirm"; `all` is the whole form snapshot, so you can
        // compare to any other path. Return null to pass, a message to fail.
        custom("Doesn't match") { v, all -> if (v == all["password"]) null else "Doesn't match" }
    }
}
```

#### Nested fields and array indices

Field paths in the schema accept the same dot / bracket syntax as `setFieldValue`:

```kotlin
val schema = formSchema<Map<String, Any?>> {
    field("user.email")          { required(); email() }
    field("user.address.city")   { required() }
    field("tags[0]")             { minLength(3) }
}
```

#### When to use which

| | Plain `validate = { … }` | Schema DSL |
|---|---|---|
| Best for | one-off forms; logic that doesn't fit a rule shape; complex conditional branching | reusable validation; multiple forms sharing rules; validation that needs to be inspected |
| Multi-error per field | hand-rolled with `buildErrors` | built-in via `failFast = false` |
| Render required-field markers without running validation | manual bookkeeping | `schema.isRequired("email")` directly |
| Cross-field checks | any Kotlin you want | `custom { v, all -> … all["…"] }` |

You can also combine them — set a `schemaValidator` AND a `validate` callback on the same `FormikConfig`; both run and their errors merge.

#### Multi-error collection

By default the schema stops at the first failing rule per field. Pass `failFast = false` to collect every failure:

```kotlin
val schema = formSchema<Map<String, Any?>>(failFast = false) {
    field("password") {
        required()
        minLength(8)
        custom("Must contain a digit") { v, _ -> if (v.toString().any(Char::isDigit)) null else "Must contain a digit" }
    }
}
// validateAllField suspends — call it from a coroutine or suspend function:
val errors = schema.validateAllField(values, "password")
// ["Required", "Too short", "Must contain a digit"]
```

#### Introspection

The schema is data — you can ask it questions without running validation:

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

#### Debounced + async validation (v1.7.0+)

Two `FormikConfig` knobs let you sidestep "validate fires on every keystroke" for expensive checks:

```kotlin
val form = FormikController(FormikConfig(
    initialValues = mapOf("username" to ""),
    validate = { v -> buildErrors {                       // cheap sync — runs every change
        if ((v["username"] as String).isBlank()) put("username", "Required")
    }},
    validateAsync = { v -> buildErrors {                  // expensive — only runs if sync is clean
        if (api.isUsernameTaken(v["username"] as String)) put("username", "Already taken")
    }},
    validateDebounceMs = 300L,                            // coalesce rapid changes into 1 run
    onSubmit = { /* … */ },
))
```

`validateAsync` is sync-then-async **circuit-broken**: a failure in the cheap sync layer skips the async pass entirely, so an empty username doesn't hit the network. Debounce only applies to change-triggered validation — blur, submit, and explicit `validateForm()` always run immediately.

#### Hydration: server-side errors / pre-touched fields

Constructor params on both `FormikConfig` and `rememberFormik` let you seed initial state — useful when re-rendering a form with server-returned errors after a failed submit:

```kotlin
val form = rememberFormik(
    initialValues = serverPayload.values,
    initialErrors = serverPayload.errors,        // FormikErrors(mapOf("email" to "Already taken"))
    initialTouched = serverPayload.touched,      // FormikTouched(mapOf("email" to true))
    initialStatus = serverPayload.banner,
    onSubmit = { /* … */ },
)
```

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

### Generating typed paths on demand

KSP runs automatically whenever the Kotlin compiler runs — e.g. `./gradlew build`, an Android Studio "Build → Make Project", or any save when **Build project automatically** is enabled in IntelliJ. The `Paths` / `Updater` files appear in `build/generated/ksp/.../kotlin/`, which the IDE already indexes as a source root.

If you'd rather have a single named task that regenerates the `@FormValues` outputs without running a full project build, paste this snippet into your `build.gradle.kts`:

```kotlin
tasks.register("generateKFormikTypedPaths") {
    group = "kformik"
    description = "Run KSP to generate @FormValues typed paths and ValuesUpdater objects (no full build)."

    // tasks.matching is lazy and project-shape-agnostic — picks up whatever KSP tasks the active
    // Kotlin / Android / KMP configuration registered: kspKotlin (JVM), kspDebugKotlin (Android),
    // kspCommonMainKotlinMetadata + kspKotlinJvm/IosX64/… (KMP).
    dependsOn(tasks.matching { it.name.startsWith("ksp") && it.name.contains("Kotlin") })
}
```

The task shows up in IntelliJ / Android Studio's Gradle tool window under a **kformik** group. Run it from the IDE or `./gradlew generateKFormikTypedPaths` to refresh only the generated outputs, skipping the rest of the build.

## Compose

`kformik-compose` is a **Compose Multiplatform** module. The same `rememberFormik(…)` API works in shared `commonMain` code on:

| Target | Supported | Notes |
|---|:---:|---|
| Android (Jetpack Compose) | ✅ | uses AndroidX Compose runtime under the hood |
| Desktop JVM (Compose Multiplatform) | ✅ | works with `compose-jb` desktop projects |
| iOS (Compose Multiplatform) | ✅ | `iosX64`, `iosArm64`, `iosSimulatorArm64` |
| Web / WASM | ⏸ | not exposed yet (no `wasmJs`/`js` target on this module) |

Use it from any of those, including from `commonMain`:

```kotlin
// shared commonMain code:
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

The form-state code above compiles unchanged on Android, Desktop, and iOS. The only platform-specific layer is the choice of `OutlinedTextField` / `Button` widgets (Material 3 on Android + Desktop; Compose Multiplatform Material on iOS).

#### Field-grained recomposition

The example above subscribes to whole-form state via `form.state` — fine for small forms, but every keystroke in any field recomposes every reader. For non-trivial forms, prefer `fieldState(name)`: a per-field `State<FieldBinding<Any?>>` backed by a deduplicated flow that only emits when *that field's* value / error / touched changes.

```kotlin
@Composable
fun EmailRow(form: ComposeFormik<Map<String, Any?>>) {
    val email by form.fieldState("email")    // recomposes ONLY on email changes
    OutlinedTextField(
        value = (email.value as? String).orEmpty(),
        onValueChange = { form.setFieldValue("email", it) },
        isError = email.displayError != null,
        supportingText = { email.displayError?.let { Text(it) } },
    )
}
```

For typed forms, `valueOf<T>(name)` and `fieldOfOrNull<T>(name)` give you a typed read without the `as? String` ceremony.

#### Fire-and-forget submit + `onError`

`ComposeFormik.submit()` / `resetForm()` are deliberately non-suspending so they can be wired into `onClick = { form.submit() }` without ceremony. Under the hood they call `controller.handleSubmit()` / `handleReset()`, which launch on the controller's scope. **Anything `onSubmit` throws on that path is silently swallowed** unless you opt into an `onError` sink:

```kotlin
val form = rememberFormik(
    initialValues = mapOf<String, Any?>("email" to ""),
    onSubmit = { v, _ -> api.login(v["email"] as String) },
    onError = { t -> snackbar.show("Sign in failed: ${t.message}") },   // <- recommended
)
```

Same shape on `FormikConfig.onError` for non-Compose callers, and on `KformikForm.onError` for the declarative form layer. If you stay inside a `scope.launch { form.submit() }` coroutine instead of the fire-and-forget path, exceptions propagate naturally and `onError` is optional.

Working Android sample in [`sample-android-app/`](sample-android-app/). More patterns in [`docs/COMPOSE_USAGE.md`](docs/COMPOSE_USAGE.md).

`:kformik-compose` runs a JVM-host Compose UI test rig (`runComposeUiTest`) covering `state` / `dirty` / `isValid` / `fieldState` / `enableReinitialize` — same `:kformik-compose:jvmTest` task already wired into CI; no emulator required.

## Declarative forms

A higher-level layer in `kformik-forms` lets you describe the form as `Map<String, Field>` instead of writing each `OutlinedTextField` by hand. Same engine underneath; just less code at the call site:

```kotlin
KformikForm(
    fields = mapOf(
        "email"    to Field(type = FieldType.Email,    label = "Email",    required = true, rules = { email() }),
        "password" to Field(type = FieldType.Password, label = "Password", required = true, rules = { minLength(8) }),
        "country"  to Field(
            type = FieldType.Select(listOf(
                SelectOption("us", "United States"),
                SelectOption("eg", "Egypt"),
            )),
            label = "Country",
        ),
    ),
    onSubmit = { values -> api.register(values) },
)
```

Renders Material 3 widgets, wires up validation, gates the submit button on `isValid && !isSubmitting`. Ten field types ship: `Text`, `Email`, `Password`, `Multiline`, `Number`, `Checkbox`, `Switch`, `Select`, `Radio`, `Date`. Escape hatches: per-field `renderOverride`, custom `submitButton` slot, `footerSlot` for form-level error summaries, `onError` hook, server-side hydration via `initialErrors` / `initialTouched` / `initialStatus`, and pass-through for `validateDebounceMs` + `validateAsync`. Full reference in [`docs/FORMS_USAGE.md`](docs/FORMS_USAGE.md).

Need finer-grained layout (custom containers, dividers between sections, manual submit button)? Drop down to `KformikFields(fields, form)` — same renderers, but it leaves the surrounding `Column` and submit-button slot to you. Useful when the default vertical-stack `KformikForm` layout doesn't fit.

`Field.initialValue` defaults to the public sentinel `FieldDefaultValue` (meaning "no explicit value — use the type default"). Pass any value to override; pass `null` to store an explicit null (useful for `Select` / `Radio` to model a "— pick one —" placeholder with `SelectOption(value = null, label = "…")`, since `SelectOption.value` is `Any?`).

The default renderers ship with **accessibility baked in**: `Modifier.toggleable` / `selectable` / `selectableGroup` for proper role + group announcements; an `error` text marked as a `liveRegion = LiveRegionMode.Polite` so screen readers announce newly-appearing validation errors when they land; a programmatic `stateDescription = "Required"` so TalkBack / VoiceOver announce required fields without depending on the visual `*` suffix.

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
kformik-compose/     Compose Multiplatform adapter (Android / Desktop / iOS)
kformik-forms/       Declarative Map<String, Field> form layer on top of :kformik-compose
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
./gradlew :kformik-compose:jvmTest :kformik-forms:jvmTest :kformik-ksp:test
./gradlew :kformik-compose:assembleRelease :kformik-forms:assembleRelease
./gradlew :sample-android-app:assembleDebug
./gradlew apiCheck                # binary-compat baselines for every module
./gradlew publishToMavenLocal     # signed artifacts under ~/.m2
```

All four published modules use `kotlin.explicitApi()` strict mode — every public declaration must carry an explicit `public` / `internal` / `private` modifier and explicit return type.

Maven Central release process: [`docs/RELEASE_PROCESS.md`](docs/RELEASE_PROCESS.md).

## What's done

- **Core controller** with reactive `StateFlow<FormikState<V>>`, structured-concurrency suspend setters, single-flight submit (independent of the `isSubmitting` flag), CAS-based lock-free escape hatch (`setFormikState`), and a monotonic validation-generation guard so stale async validators can't overwrite a fresher result.
- **Validation**: synchronous `validate`, schema DSL, async `validateAsync` with sync-then-async circuit-breaking, debounce window (`validateDebounceMs`), cross-field rules. All paths skip-on-supersede via the generation guard.
- **Field arrays** (`form.array(path)`): `push` / `pop` / `unshift` / `insert` / `remove` / `replace` / `swap` / `move`; touched + errors stay aligned across structural mutations; throws on present-but-non-list paths; `current()` / `size()` symmetric with the mutating helpers.
- **Compose Multiplatform adapter** (`:kformik-compose`): `rememberFormik(…)`, `ComposeFormik<V>` with per-field `fieldState(name)`, `@Composable` accessors for `state` / `dirty` / `isValid`, `enableReinitialize` baseline re-sync (all four hydration slots watched). `rememberUpdatedState` keeps `onSubmit` / `validate` / `validateAsync` / `schemaValidator` / `onReset` / `onError` fresh across recompositions. JVM-host UI test rig (`runComposeUiTest`) ships in `:kformik-compose:jvmTest`.
- **Declarative forms layer** (`:kformik-forms`): `Map<String, Field>` → fully wired Material 3 form via `KformikForm(fields, onSubmit, …)`. Ten field types, a11y baked in (`toggleable` / `selectable` / `selectableGroup`, `liveRegion` errors, `stateDescription = "Required"`), escape hatches: `renderOverride`, `submitButton` slot, `footerSlot`, `onError`, server-side `initialErrors` / `initialTouched` / `initialStatus`, debounced + async validation pass-through.
- **iOS / SwiftUI bridge** (`io.kformik.ios.FormikIosBridge`): Swift-friendly facade with `observe` / `snapshot` / setters / `submit` / `resetForm` / `close`. `StateSnapshot.value("user.address.city")` resolves nested paths via `MapValuesUpdater.getAt`. Caller-owned scope respected on `close()`.
- **KSP processor** (`:kformik-ksp`, experimental): `@FormValues` → `<Name>Paths` + `<Name>Updater : ValuesUpdater<Name>`. Flat and nested `data class`es. Incremental per-file dependencies (KSP1 + KSP2).
- **CI** (`.github/workflows/ci.yml`): every push / PR to `main` runs JVM + Android + KSP tests, full Compose UI test rig, iOS-simulator tests, `iosArm64` + `iosX64` cross-compile for all three KMP modules, `apiCheck` baselines, and verifies publication wiring via `publishToMavenLocal`.
- **Automated release** (`.github/workflows/release.yml`): pushing a `v*` tag runs the signed pre-publish verification → Sonatype staging → `bulk/close` + state poll → `bulk/promote` to Central → `gh release create`. The promote step is gated behind a `release` GitHub environment with a required-reviewer approval (recommend self-review). Failures drop the staging repo via `bulk/drop`. `workflow_dispatch` with `dry_run = true` exercises the pipeline without publishing. See [`docs/RELEASE_PROCESS.md`](docs/RELEASE_PROCESS.md).
- **1153 tests / 0 failures** across the four modules (kformik 268 JVM + 262 Android debug + 262 Android release + 286 iOS sim incl. v1.9.0 hardening regressions, kformik-compose 20 JVM incl. 5 Compose UI tests, kformik-forms 26 JVM, kformik-ksp 24). All four published modules use `explicitApi()` strict mode. `apiCheck` baselines committed for every module/variant pair, including `@Deprecated(HIDDEN)` overloads that keep v1.8.0-compiled bytecode linking against the v1.9.0 jars.

## What isn't done

- **Web / WASM targets** for `:kformik-compose` aren't built yet (`wasmJs`/`js` not exposed).
- **iOS on-device test execution**. `iosArm64` + `iosX64` compile in CI; only `iosSimulatorArm64Test` actually runs (macos-14 runner). On-device execution would require a self-hosted iOS runner.
- **KSP per-index typed `List<...>` accessors**. `List`/`Map` properties are handled by full-value replacement; per-index access still uses string concatenation (`"${LoginValuesPaths.friends.\`$path\`}[0]"`). Future enhancement.
- **`gradle/libs.versions.toml` + buildSrc convention plugin**: POM + signing config is currently duplicated across the four published modules. Working but redundant; refactor planned for a future cycle.
- **Sealed / generic / abstract `@FormValues` targets**: rejected with a clear `KSPLogger` error rather than miscompiled. Documented as out-of-scope.
- **`fieldOf<T>` parameterised-T element-type validation**: fundamentally limited by JVM / Native generics erasure. Documented in the KDoc with workarounds.

## Credit

Port of [Formik](https://github.com/jaredpalmer/formik) by Jared Palmer. Where Kformik diverges from upstream behavior, the difference is documented in `docs/`.

## License

Apache-2.0. See [`LICENSE`](LICENSE).
