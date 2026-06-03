# Changelog

All notable changes are documented here. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- `rememberFormik` now forwards the v1.7.0 `validateDebounceMs` and `validateAsync` parameters from `FormikConfig`. Both are appended at the end of the parameter list (binary-compatible for older positional callers) and `validateAsync` is tracked with `rememberUpdatedState` so callback identity stays fresh across recompositions. `kformik-compose/api/{jvm,android}/kformik-compose.api` baselines updated.

## [1.7.0] — 2026-06-02

### Added

- **`FormikConfig.validateDebounceMs: Long? = null`** — optional debounce window applied to change-triggered validation. When set, rapid mutations via `setFieldValue` / `setValues` coalesce into a single validation run after the debounce window, so a `validate` callback that makes expensive checks (network lookups, heavy regex, etc.) isn't invoked on every keystroke. The debounce applies only to validation triggered by `validateOnChange`; blur (`validateOnBlur`), explicit `validateForm()` / `validateField()`, and submit always validate immediately. `null` (default) preserves current behavior — every change validates synchronously, no regression for callers who don't set the field. `0` and negative values are coerced to `null`.
- **`FormikConfig.validateAsync: (suspend (V) -> FormikErrors)? = null`** — optional **async / expensive validator** that runs **only when** the cheap sync layer (`validate` + `schemaValidator` + field-level validators) produced zero errors. Sync-then-async circuit breaking: a network "is this username taken?" check inside `validateAsync` is skipped if a cheap `validate { it.isBlank() → "Required" }` already invalidated the form. Pair with `validateDebounceMs` for the canonical "debounce on type + skip network if local regex already failed" workflow. `null` (default) preserves current behavior.
- 11 tests in `ValidateDebounceTest` covering rapid-burst coalescing, latest-values-win semantics, blur / submit / `validateForm()` bypass guarantees, and zero/negative coercion.
- 14 tests in `ValidateAsyncTest` covering: async runs when sync is clean / absent, skipped when any of (`validate`, `schemaValidator`, field-level) failed, error commit, `validateOnChange=false` interaction, blur + mount + submit triggers, async-blocks-submit semantics, F2 × F3 interplay (debounce → sync → async circuit-break).

### Binary compatibility

- `FormikConfig`'s synthetic constructor signature gained two parameters (`validateDebounceMs: Long?`, `validateAsync: (suspend (V) -> FormikErrors)?`) alongside the other `validate*` options. Source-compatible for Kotlin callers using named-argument construction (the dominant pattern). Component functions (`component12`+) shift — only affects code that positionally destructures `FormikConfig`, which is rare. `api/jvm/kformik.api` and `api/android/kformik.api` baselines updated.

### Build & CI

- **Automated release workflow** (`.github/workflows/release.yml`). Pushing a `v*` tag now runs the full release pipeline on a `macos-14` runner: pre-publish verification (tests + apiCheck) → signed `publishToMavenLocal` sanity → `publishToSonatype` (staging upload) → `bulk/close` + state poll → `bulk/promote` → `gh release create` with the matching CHANGELOG section as body. Failures drop the staging repo via `bulk/drop`. The irreversible promote step is gated behind a configurable `release` GitHub environment (recommended: add yourself as a required reviewer). `workflow_dispatch` with `dry_run = true` exercises the pipeline without publishing. Setup, prerequisites, and dry-run docs in `docs/RELEASE_PROCESS.md`.

## [1.6.0] — 2026-05-30

A correctness-and-robustness release. The headline is a hardened concurrency/state model: the
controller now genuinely honours its documented "safe from multiple coroutines on multiple threads"
guarantee and its async-validation semantics. Additive API only — existing source compiles
unchanged — but a few **behaviour corrections** are called out under _Changed_ below.

### Added

- `FormikController.fieldOfOrNull<T>(name)` — null-safe typed `FieldBinding<T?>` for optional / not-yet-populated fields.
- `FormikController.fieldFlow(name)` — a per-field, deduplicated `StateFlow<FieldBinding<Any?>>` that only emits when *that field's* value/error/touched change. `ComposeFormik.fieldState(name)` is now backed by it (genuinely field-grained recomposition), plus `ComposeFormik.valueOf<T>(name)`.
- `FormikConfig.onError` — an error sink for the fire-and-forget `handleSubmit()` / `handleReset()` paths (previously failures were silently swallowed); also exposed as `rememberFormik(onError = …)`.
- iOS bridge: `FormikIosBridge.createSimple(…)` (non-suspending Swift-friendly `onSubmit`), `StateSnapshot.isDirty()` / `isValid()`, a configurable `observe(callbackDispatcher = …)` (defaults to `Dispatchers.Main`), `@ObjCName` refinements, and a real `Kformik` framework binary on each iOS target (the documented `linkReleaseFrameworkIos*` tasks now exist).
- `FormSchema.validateFieldIncludingCross(values, path)` — focused validation that also consults cross-field rules.
- Public-ABI guardrail: the Kotlin Binary Compatibility Validator with committed `api/*.api` baselines and an `apiCheck` step in CI.
- Extensive regression coverage, including JVM real-thread concurrency stress tests.

### Fixed

- **Concurrency / state model.** Stale/slow async validations can no longer overwrite a fresher result, and a validation launched before a `resetForm`/`reinitialize` no longer repopulates cleared errors (monotonic validation-generation guard). `isValidating` is published from an in-flight-run counter (overlapping runs no longer clear it early) and is restored even on cancellation. All `_state` writes are compare-and-set, so the lock-free setters no longer clobber a value/touched mutation on a disjoint slice. `submit()` is single-flight and validates the exact snapshot it submits. The field registry is thread-safe (no `ConcurrentModificationException` / Native crash).
- **`validateField`.** Preserves cross-field errors instead of clearing them, uses the same cross-overrides-per-field precedence as `validateForm`, and no longer overwrites a fresher full-validation result from a stale snapshot.
- **Field arrays.** `pop()` returns the correct element even when indexed `touched`/`errors` entries exist; index re-alignment is bounded to the live array and preserves orphan keys; negative indices are guarded; operating on a present-but-non-list path now throws instead of silently overwriting it.
- **Path / value updater.** Negative and excessively large list indices are bounded no-ops (no `IndexOutOfBounds`, no OOM); clearing a nested leaf prunes the now-empty parent so `dirty` re-baselines; `setAt` reuses the parsed path and drops a redundant copy per level.
- **`fieldOf<T>`.** No longer throws a raw `ClassCastException`/NPE on an absent or type-mismatched field — it gives an actionable message (or use `fieldOfOrNull`).
- **KSP.** Generated `<Name>Updater` now compiles for properties whose type is in another package, collection-typed properties, keyword-named properties, computed/inherited properties, and **nullable nested `@FormValues`**; setting a non-null field to `null` yields a path-named error; unsupported targets (non-data / sealed / abstract / generic) are reported via the KSP logger instead of emitting uncompilable code.
- **Compose.** Field-grained recomposition (above); `onSubmit`/`validate`/`onReset` callbacks no longer go stale across recompositions (`rememberUpdatedState`); `enableReinitialize` now re-syncs the baseline when `initialValues` changes.
- **iOS.** `observe` callbacks are delivered on the main dispatcher regardless of the work scope; setter call order is preserved.

### Changed

> Behaviour corrections that could affect existing forms — review before upgrading:

- `email()` / `pattern()` schema rules now **pass on blank/absent input** (combine with `required()` to forbid an empty value); an optional empty field is no longer flagged.
- `submit()` is now **single-flight**: a concurrent or double submit is a no-op (no second `onSubmit`, `submitCount` not double-incremented).
- A blank/empty error string is no longer surfaced via `displayError` (it still counts as invalid for `isValid`).
- A non-`Map` values type constructed **without** a `valuesUpdater` now **fails fast at construction** with an actionable message (previously it threw on first field access).
- `LoginScreenSample` in `:kformik-compose` is now `internal` (it was inadvertently part of the published API).
- Dropped the unused `compose.runtime-saveable` dependency from `:kformik-compose`.
- The build now fails fast if `kformikGroup` / `kformikVersion` are missing (no stale fallback coordinates).

### Docs

- README schema `custom` examples corrected to the real `(value, allValues) -> String?` contract; suspend calls shown in a coroutine context; CI-vs-release framing fixed.
- `docs/RELEASE_PROCESS.md` migrated from the decommissioned `s01.oss.sonatype.org` flow to the Sonatype Central Publisher Portal; `COMPOSE_USAGE.md`, `IOS_USAGE.md`, `KSP_TYPED_PATHS.md`, and `FIELD_ARRAY.md` brought in line with the code; KDoc added to the `FormikActions` members and the controller's config-mirror flags.

### Coordinates (once released)

- `io.github.apdelrahman1911:kformik:1.6.0`
- `io.github.apdelrahman1911:kformik-compose:1.6.0`
- `io.github.apdelrahman1911:kformik-ksp:1.6.0`

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
