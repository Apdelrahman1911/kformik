# Changelog

All notable changes are documented here. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.5.0] — 2026-05-30

### Added

- **Compose Multiplatform support for `:kformik-compose`.** The adapter is now a Kotlin Multiplatform module — `commonMain` source compiles to **Android**, **Desktop JVM**, and **iOS** (`iosX64`, `iosArm64`, `iosSimulatorArm64`). `rememberFormik(…)` and `ComposeFormik<V>` work identically across all targets, so shared Compose UI in `commonMain` can drive form state without target-specific wiring. Web / WASM targets are not yet exposed.
- KSP processor: regression test for the multi-file nested `@FormValues` case (cross-file dependency).

### Fixed

- **KSP incremental performance.** `FormValuesProcessor` now declares accurate per-file `Dependencies` (`aggregating = false, <decl.containingFile + transitively-reachable nested @FormValues files>`) when writing each `<Name>Paths.kt` / `<Name>Updater.kt`. Previously these were created with an empty file set, which on KSP1 fell back to "regenerate everything on every change" and on KSP2 was rejected as a non-isolating output. The fix:
  - Adding/changing one `@FormValues data class` now regenerates **only** that class's `Paths` + `Updater`, plus the outputs of any other class that embeds it as a nested type.
  - Build feedback loop in large projects (50+ `@FormValues` types) drops from linear-in-N to constant in change size.
  - Works under both KSP1 and KSP2.

### Changed

- `:kformik-compose` artifact shape: was a single Android AAR (`kformik-compose-1.4.0.aar`); is now a KMP umbrella publication with per-target variants (`kformik-compose-android`, `kformik-compose-jvm`, `kformik-compose-iosx64`, `kformik-compose-iosarm64`, `kformik-compose-iossimulatorarm64`). Android consumers using `implementation("io.github.apdelrahman1911:kformik-compose:1.5.0")` automatically receive the Android variant via Gradle module metadata — **no consumer code change required**.
- Compose runtime dependency switched from `androidx.compose.runtime:runtime:1.7.5` to `org.jetbrains.compose:runtime` (Compose Multiplatform 1.7.3). On Android this transitively resolves to the same AndroidX runtime.

### Docs

- README + `docs/COMPOSE_USAGE.md` updated to document the Compose Multiplatform target matrix.
- README install snippet updated to add `compileOnly("…kformik-ksp:1.5.0")` alongside `ksp(…)` so `@FormValues` resolves on the consumer's source classpath (fixes the v1.4.0 "missing import" friction).
- Added a `generateKFormikTypedPaths` Gradle task snippet (in both README and `docs/KSP_TYPED_PATHS.md`) — users paste it once into their `build.gradle.kts` and get a named task under a "kformik" group in IntelliJ / Android Studio's Gradle tool window. The task fans out to whatever `kspXxxKotlin` tasks the project's shape registered (JVM / Android / KMP) and refreshes only the `@FormValues` outputs, skipping full build steps. An auto-registering Gradle plugin is planned for v1.6.0.

## [1.4.0] — 2026-05-29

First public release. The library went through several private iterations (1.0.x – 1.3.x, summarised below); 1.4.0 is the first cut published to Maven Central under `io.github.apdelrahman1911`.

### Features

- **Core (KMP)** — UI-independent form-state engine. `commonMain` depends only on `kotlinx-coroutines-core`. Reactive state via `StateFlow<FormikState<V>>`. Targets JVM 17+, Android (`minSdk 21`), iOS (`iosX64`, `iosArm64`, `iosSimulatorArm64`).
- **Schema validation** — `formSchema { field("email") { required(); email() } }` DSL with built-in rules (`required`, `minLength`, `maxLength`, `email`, `pattern`, `min`, `max`, `custom`) and cross-field support. `failFast = false` collects every failing rule per field. Schema is introspectable: `isRequired(path)`, `requiredFields()`, `fieldInfo(path)` — useful for rendering required-field markers without running validation.
- **Async validation + submit** — `validate` and `onSubmit` are both `suspend` functions with standard structured-concurrency semantics.
- **Field arrays** — `form.array(path)` with `push` / `pop` / `unshift` / `insert` / `remove` / `replace` / `swap` / `move`. Touched + errors arrays stay aligned across structural mutations.
- **Compose adapter** — `kformik-compose` for Android (Jetpack Compose). `rememberFormik(...)` returns a `ComposeFormik<V>` with snapshot-friendly `State<...>` derivations.
- **iOS / SwiftUI bridge** — `FormikIosBridge` in `iosMain` exposing Swift-friendly `observe` / `snapshot` / setters / `submit` / `resetForm` / `close`.
- **KSP processor (experimental)** — `kformik-ksp`. Annotate a `data class` with `@FormValues`; the processor generates `<Name>Paths` (compile-checked path constants) and `<Name>Updater : ValuesUpdater<Name>` so you can drop the hand-rolled `when (path)` boilerplate. Flat and nested data classes covered.
- **Nested and bracket paths** — `MapValuesUpdater` parses `user.address.city` and `tags[1]` uniformly.
- **Formik parity** — submit-touches-all, `validateOnChange` / `validateOnBlur` / `validateOnMount`, `dirty`, `isValid`, `submitCount`, `setStatus`, `resetForm`.

### Coordinates

- `io.github.apdelrahman1911:kformik:1.4.0`
- `io.github.apdelrahman1911:kformik-compose:1.4.0`
- `io.github.apdelrahman1911:kformik-ksp:1.4.0`

Apache-2.0 licensed. Sources jar, Dokka HTML javadoc jar, and `.asc` signatures shipped on every artifact.

## [1.3.1]

### Added

- Twelve regression tests in `FormikIssueRegressionTest.kt` pinning Kformik's behavior against known historical Formik bug patterns. No code changes required — the design rules them out by construction.

## [1.3.0]

### Added

- Direct iOS bridge tests in `iosTest` covering `FormikIosBridge.create` / `observe` / `snapshot` / setters / `submit` / `resetForm` / `close`.
- Compose adapter unit tests covering the non-`@Composable` surface.
- `:sample-android-app` module with a real Material 3 Compose `LoginScreen`.
- Experimental `:kformik-ksp` module: `@FormValues` annotation + processor emitting `<Name>Paths` objects (flat and nested).
- Maven publishing wired for eight artifacts (`kformik`, `kformik-jvm`, `kformik-android`, three iOS targets, `kformik-compose`, `kformik-ksp`).

### Changed

- Default build is warning-free. Deprecated `compilerOptions {}` DSL migrated; AGP / Xcode compat warnings suppressed where appropriate.

## [1.2.0]

### Added

- `FormikController.array(path)` field-array helpers: `push`, `pop`, `unshift`, `insert`, `remove`, `replace`, `swap`, `move`, `size`, `current`. Touched / errors stay aligned across mutations.
- `:kformik-compose` adapter: `rememberFormik(...)` returning a `ComposeFormik<V>` with snapshot-friendly `State<...>` derivations.
- iOS / SwiftUI bridge: `FormikIosBridge` in `iosMain` exposing observe / snapshot / setters / submit / resetForm / close.
- Schema validation DSL: `formSchema<V> { field("...") { required(); email(); … } }` with built-in rules and cross-field support.

## [1.1.0]

### Added

- `setFieldValue(name, updater: (Any?) -> Any?)` overload (Formik's `(prev) => next` form).
- Top-level `getIn` / `setIn` utilities.
- 23 regression tests covering coverage gaps.

### Fixed

- `submit()` touches every leaf path of `values`, not just registered fields. Matches Formik's `setNestedObjectValues(values, true)`.
- Field-keyed methods (`field`, `registerField`, `setFieldValue`, `setFieldTouched`, `setFieldError`, …) reject blank names.

## [1.0.0]

Initial release.

- KMP port of Formik with a UI-independent core (`commonMain` depends only on `kotlinx-coroutines-core`).
- Targets: JVM 17+, Android (minSdk 21), iOS (`iosX64`, `iosArm64`, `iosSimulatorArm64`).
- 74 `commonTest` tests cross-validated on every target.
- Three examples: login, nested, async.
- Mirrors Formik's submit / reset / touched / errors / dirty / isValid / submitCount / validateOnChange / Blur / Mount semantics.
