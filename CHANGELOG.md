# Changelog

All notable changes are documented here. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- Maven group ID is `io.github.apdelrahman1911`. Single source of truth: `gradle.properties` → `kformikGroup`.
- POM metadata (developers, SCM, project URL) point at `github.com/Apdelrahman1911/kformik`. License declared as Apache-2.0 (SPDX).
- Sonatype + signing credentials are now read from uppercase `SONATYPE_USERNAME` / `SONATYPE_PASSWORD` / `SIGNING_KEY` / `SIGNING_PASSWORD` (env or `~/.gradle/gradle.properties`). Legacy lowercase keys still work.
- README install snippets use `io.github.apdelrahman1911:*:1.4.0`.

### Added

- Apache-2.0 `LICENSE` file at the repo root.

## [1.4.0]

### Added

- KSP `ValuesUpdater` generation. `@FormValues data class` now produces a `<Name>Updater : ValuesUpdater<Name>` alongside the existing `<Name>Paths` constants. Flat and nested data classes supported.
- KSP end-to-end compile testing (via `dev.zacsweers.kctfork:ksp`).
- `FormSchema` requiredness introspection: `isRequired(path)`, `requiredFields()`, `fieldInfo(path)`. No validation pass needed.
- `failFast = false` schema mode: collect every failing rule per field via `validateAll(values)` / `validateAllField(values, path)`. Available at the schema level (`formSchema(failFast = false) { … }`) and per-field (`field("p", failFast = false) { … }`).
- Opt-in Robolectric Compose UI tests at `sample-android-app/src/robolectricTest/`, gated behind `-PwithRobolectric=true`.
- POM metadata wired for Maven Central. `signing` plugin present but inert until `SIGNING_KEY` / `SIGNING_PASSWORD` are set.
- Release notes in `docs/RELEASE_PROCESS.md`.

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
