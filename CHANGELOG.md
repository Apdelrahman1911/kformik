# Changelog

All notable changes are documented here. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Ongoing v1.9.0 cycle on `harden/v1.9.0` — not yet tagged or published. Commits accumulate here; entries will be promoted to a versioned section when the release is cut. **Do NOT consider any item below a shipping promise** until v1.9.0 is tagged on `main`.

### Added (forms layer)

- `KformikForm.onError: ((Throwable) -> Unit)?` — surface `onSubmit` exceptions that were previously swallowed by the fire-and-forget launch.
- `KformikForm.initialErrors` / `initialTouched` / `initialStatus` — server-side hydration of pre-existing validation state, mirrored on `rememberFormik` for typed consumers.
- `KformikForm.footerSlot` — `@Composable (form) -> Unit` slot rendered between fields and submit button; receives the full `ComposeFormik` handle so consumers can surface form-level (non-field-bound) error summaries, status messages, or dirty indicators.
- `FieldDefaultValue` — public sentinel singleton used as the default for `Field.initialValue`. Lets the renderers distinguish "omitted" (→ type-default via `defaultValueFor`) from "explicit null" (→ stored verbatim; the documented "no selection" path for Select / Radio).
- `SelectOption.value` widened from `Any` to `Any?` so a `null`-valued placeholder option ("— select a country —") can be modeled directly.
- `@Stable` on `Field`, `FieldType`, `SelectOption` — lets Compose skip renderer composition when the Field instance hasn't changed.

### Added (controller / schema)

- `FormSchema.configureValuesUpdater(updater)` (annotated `@InternalKformikApi`) — controller wires its own `ValuesUpdater` into the schema so per-field rules on typed `data class` forms read actual values instead of null. The `@InternalKformikApi` opt-in marker marks it as cross-module wiring rather than a stable public API.

### Fixed

- **`rememberFormik` `schemaValidator` no longer goes stale across recompositions.** Wrapped in `rememberUpdatedState` symmetric with the existing `validate` / `validateAsync` / `onSubmit` / `onReset` / `onError` callbacks, via a `SchemaValidator` delegate that reads from the State at call time.
- **`applyArrayMutation` routes through `scheduleChangeValidation`.** Array mutations (`push`, `pop`, `insert`, …) now respect `validateDebounceMs` like regular value changes; pre-fix they validated synchronously even when a debounce was configured.
- **`applyArrayMutation` bumps `validationGeneration` after the transform commits.** A throwing transform (e.g. `require(idx >= 0)` in `insert(-1, …)`) no longer leaves a phantom gen that invalidates in-flight validators.
- **`setFormikState` generation-tracking caveat documented.** Non-suspending setter can't acquire the mutex; its KDoc now warns when value mutations through this escape hatch can race with in-flight validators.
- **`validateAsync` in-flight calls cancelled on supersede AND on `close()`.** The collector tracks `_inFlightDebouncedValidation` and cancels it on every new emission. `FormikController.close()` ALSO cancels it (not just the collector itself), so a slow `validateAsync` doesn't outlive the controller when caller-owned scopes are in play.
- **Debounce collector skips stale runs.** Pre-fix, a blur-during-debounce-window would execute the queued validator only to discard the result at commit; the collector now checks `gen != validationGeneration` and short-circuits.
- **Debounce collector survives a throwing `validate` / `validateAsync`.** Wraps the body in try/catch, rethrows `CancellationException`, routes other throwables to `onError`. Pre-fix a throw killed the collector for the controller's lifetime, silently stopping all subsequent change-validation.
- **`FormSchema` reads through controller's `ValuesUpdater` for typed forms.** Pre-fix the schema's `readValue` fell through to `getIn` for non-Map values, returning null for every field; typed-form per-field rules silently passed/failed regardless of the actual data.
- **`getIn` discriminates explicit-null from missing keys.** Walks via `containsKey` / bounds-check instead of relying on `?:`-coercion of the leaf — `getIn(mapOf("a" to null), "a", "DEF")` now returns null (not "DEF").
- **`MapValuesUpdater.getAt` rejects empty paths symmetric with `setAt`.** Pre-fix an empty / `.` / `[]` path returned the entire values map; now throws `IllegalArgumentException` with the same message setAt has always used.
- **`fieldOf<T>()` erasure caveat documented for parameterized T.** JVM/Native erasure can't preserve `List<String>` vs `List<Int>` at runtime; KDoc now warns honestly and points at workarounds (fundamental limitation, not fixable without `kotlin-reflect`).
- **`FieldArrayController.current()` / `size()` throw on present-but-non-list paths.** Symmetric with `push` / `pop` / `insert` / `remove` which have always thrown — pre-fix the read path silently returned `emptyList()` / `0`.
- **`FormSchemaBuilder.buildInitialValuesFrom` builds a nested structure for dotted/indexed paths.** Routes through `MapValuesUpdater.setAt` so `"user.email"` produces `{"user": {"email": …}}` (the controller's resolver expects); pre-fix it stored a literal `"user.email"` flat key and `valueAt("user.email")` never resolved.
- **`min` / `max` reject non-finite inputs and bounds.** NaN comparisons silently passed both rules pre-fix; the rule body now checks `isFinite()`, and the schema-build-time `require(bound.isFinite())` catches a programming error early.
- **`FormikController.close()` cancels in-flight debounced validation, not just the collector.** With a caller-owned scope, the previous fix only cancelled the OUTER collector — any actively-running validateAsync continued. Now both Jobs cancel.
- **`enableReinitialize` honors `initialErrors` / `initialTouched` / `initialStatus`.** Pre-fix the LaunchedEffect watched only `initialValues` and called `reinitialize(FormikInitialState(values = initialValues))` — the other three hydration slots were ignored. Now all four are watched and forwarded.
- **`:kformik-forms` renderer accessibility (a11y).**
  - `DateRenderer` tap-anywhere via `interactionSource` + `PressInteraction.Release`.
  - Checkbox / Switch rows use `Modifier.toggleable` with explicit `Role`; inner widget `onCheckedChange = null` to avoid double-dispatch.
  - Checkbox / Switch outer Column uses `Modifier.semantics(mergeDescendants = true)` so the error text is announced inline with the toggle.
  - Radio rows use `Modifier.selectable(role = Role.RadioButton)`, and the outer Column adds `Modifier.selectableGroup()` so TalkBack / VoiceOver announce "1 of N" group navigation.
  - Validation error `Text` adds `Modifier.semantics { liveRegion = LiveRegionMode.Polite }` so newly-appearing errors are spoken when they land.
  - **Programmatic `Required` marker** via `Modifier.semantics { stateDescription = "Required" }` on every renderer's outer modifier. The visual `*` suffix is announced inconsistently by screen readers (TalkBack tends to skip it; VoiceOver reads "asterisk") — the new state description is read alongside the field's role + value/label, producing announcements like "Email, edit text, Required, empty" instead of "Email star, edit text, empty". Compose has no first-class `required` semantic property; `stateDescription` is the pragmatic best fit without an API redesign.
- **NumberRenderer correctness.** Display buffer + `hadFocus` to prevent canonicalization mid-typing ("0.10" no longer snaps to "0.1"); `,` → `.` normalization for decimal-comma locales; renders stored value via natural `toString()` (no asInt-driven truncation of Doubles); `defaultValueFor(FieldType.Number)` is now `null` so `required()` actually enforces "must enter a value".
- **`MainActivity` mounts `RegistrationScreen` and `LoginScreen` via a `TabRow`.** Pre-1.8.1 only `LoginScreen` was reachable from the installed APK despite the CHANGELOG advertising `RegistrationScreen` as the headline v1.8.0 demo.
- **iOS bridge `close()` preserves caller-owned scopes.** Tracks `ownsOuterScope` so a Swift consumer passing `viewModelScope`-equivalent gets only the bridge's own work cancelled — caller's other coroutines on the same scope continue. All bridge-launched coroutines (observers, fire-and-forget setters) cancel via a dedicated `bridgeJob: SupervisorJob` regardless of outer-scope ownership.
- **iOS bridge `StateSnapshot.value(name)` resolves nested paths.** Routes through `MapValuesUpdater.getAt`, so `s.value("user.address.city")` works from Swift like its Compose-side equivalent.
- **iOS bridge `StateSnapshot.value("")` returns null instead of crashing.** The v1.9.0 hardening of `MapValuesUpdater.getAt` to `require` non-empty paths inadvertently made a Swift caller's `@State var name = ""` bound to `bridge.snapshot().value(name)` terminate the iOS process. The bridge now treats empty / `.` / `[]` paths as "no value here" — same lenient behavior as pre-1.9.0.
- **`NumberRenderer` commits `null` (not the raw `String`) on parse failure.** Pre-fix, unparseable input ("abc", "1..2", …) was stored as a String, silently passing `required()` (a non-blank String is "present") and bypassing `min` / `max` (which ignore non-Number values). A user typing "abc" into an Age field would pass validation, then `onSubmit { api.create(age = v["age"] as Int) }` would ClassCastException at the call site. The renderer now commits `parsed` directly; the displayBuffer keeps the typed text on screen so the user can correct it.
- **`PathParser` caps paths at 256 segments.** `MapValuesUpdater.setRecursive` is non-tail-recursive and allocates a fresh container per level — a malformed schema or malicious caller producing a 100k-segment path ("a.".repeat(100_000)) would StackOverflow the controller thread. The new cap throws `IllegalArgumentException` early instead. Real forms rarely exceed 10 segments; 256 is comfortably above any sane depth.
- **KSP `@FormValues` rejects member-level data classes with a clear error.** The processor emits `<Name>Paths.kt` / `<Name>Updater.kt` at the package level using the simple name; for a `data class Inner` inside `object Outer`, the generated `InnerUpdater` referenced an unresolved `Inner` from package scope (uncompilable). Now `isSupported` rejects member-level declarations with a message naming the enclosing parent, mirroring the existing rejections for non-data / sealed / generic targets.
- **`LoginScreen` sample uses `Modifier.fillMaxWidth()`, not `fillMaxSize()`, for the email field.** Inside a vertical `Column`, `fillMaxSize` expanded the email field to the column's full remaining height, pushing the password field and Sign-in button off-screen.
- **`release.yml`: `workflow_dispatch` default now `dry_run = true`.** Pre-fix the default was `false` — a maintainer clicking "Run workflow" without flipping the toggle would silently invoke `publishToSonatype` + `bulk/close` + `bulk/promote` against live Sonatype. Real releases still come from `v*` tag pushes (where the input is unset and the env evaluates to `false`).
- **`release.yml`: drop staging repo after successful dry-run.** Previously a successful dry-run left the staging repo accumulating in Sonatype because the promote step (which would set `autoDropAfterRelease`) was skipped. A dedicated cleanup step now drops the captured repo ID when `DRY_RUN=true` and the workflow otherwise succeeded.

### Binary compatibility

> Source compatibility preserved across the board for callers using named arguments (the dominant Kotlin pattern). For JVM bytecode compiled against v1.8.0 jars, the new `@Deprecated(level = HIDDEN)` overloads keep the v1.8.0 mangled names + descriptors linkable — see Added section above.

- **`rememberFormik`** appended three new optional parameters (`initialErrors`, `initialTouched`, `initialStatus`) at the tail of the parameter list. The v1.8.0 signature is preserved as a `@Deprecated(level = HIDDEN)` overload that forwards to the v1.9.0 form with the new params defaulted, so v1.8.0-compiled bytecode keeps linking. Modern callers see only the primary signature (HIDDEN deprecations are stripped from autocomplete + resolution).
- **`KformikForm`** appended five new optional parameters (`onError`, `initialErrors`, `initialTouched`, `initialStatus`, `footerSlot`). The Compose-mangled symbol changes whenever the parameter list changes (`KformikForm-qmNWa6M` → `KformikForm-DTe7Sbc`); the same `@Deprecated(level = HIDDEN)` overload pattern preserves the v1.8.0 mangled symbol so old bytecode resolves. Both names appear in the api/ baselines; the synthetic one is invisible to source callers.
- The v1.8.0 CHANGELOG committed to "deprecated-hidden overloads for rememberFormik-style API changes" — v1.9.0 delivers on that policy for both `rememberFormik` and `KformikForm`.

### Changed

- `Field.initialValue` defaults to `FieldDefaultValue` (was `null`). Source-incompatible for code that previously passed `initialValue = null` expecting the type-default fallback — pass `Field(initialValue = FieldDefaultValue)` for the explicit type-default opt-in, or omit the parameter.
- `FieldType.Number(...)` default value is `null` (was `0` / `0.0`). Restores `required()` enforcement on Number fields; consumers wanting a seeded zero pass `Field(initialValue = 0)`.

### Build & CI

- `:kformik-compose:jvmTest` added to the CI ubuntu job (only the Android variant ran pre-1.8.1).
- iOS compile coverage expanded to `iosArm64` + `iosX64` for all three KMP modules in the CI ios job; `iosSimulatorArm64` continues to run tests.
- `explicitApi()` (strict mode) is now enabled on **all four** published modules (`:kformik`, `:kformik-compose`, `:kformik-forms`, `:kformik-ksp`). Every public declaration carries an explicit `public` / `internal` / `private` modifier and explicit return type. `apiCheck` baselines pass unchanged across the rollout.
- New JVM-host Compose UI test rig for `:kformik-compose` (`runComposeUiTest`-based). 5 tests covering the `@Composable` accessors (`state`, `dirty`, `isValid`, `fieldState`) and the `enableReinitialize` flow — areas the pure-JVM tests in `ComposeFormikTest` explicitly skipped because they required a Compose runtime host. Runs on the same `:kformik-compose:jvmTest` task already wired into CI; no emulator required.
- Release workflow's `.asc` count guardrail raised from `>= 46` to `>= 90` (v1.8.0 already produced 113 signatures).

### Docs

- README: removed the stale `kformik-gradle-plugin` "coming in v1.6.0+" forward-reference; corrected the "release is not automated" claim that was stale as of v1.7.0.
- `docs/KSP_TYPED_PATHS.md`: same plugin-promise removed.
- `docs/RELEASE_PROCESS.md`: version + module list brought current (was stuck at v1.5.0; missed `:kformik-forms`).
- `DefaultValues.kt`, `FieldType.kt`, `FormSchema.kt` KDocs updated to reflect v1.9.0 semantics (sentinel-based default, Any? value, typed-form schema reads).

### Known limitations (carried into v1.10+)

- **`gradle/libs.versions.toml` + buildSrc convention plugin** for centralized POM / signing — currently duplicated across the four published modules. Functional but redundant; refactor planned for a future cycle.
- **`fieldOf<T>` element-type validation for parameterized T** — fundamentally limited by JVM / Native generics erasure. Documented in the KDoc with workarounds; not solvable without `kotlin-reflect` (not in the core deps).
- **`:kformik-compose` Web / WASM targets** — `wasmJs` / `js` not exposed yet. Not on the immediate roadmap.
- **iOS on-device test execution** — `iosArm64` + `iosX64` cross-compile in CI but only `iosSimulatorArm64Test` runs on the `macos-14` runner. On-device execution would require a self-hosted iOS runner.

## [1.8.0] — 2026-06-03

### Added

- **New `:kformik-forms` module** — a declarative form layer on top of `:kformik-compose`. Describe a form as `Map<String, Field>` (each `Field` carries `type`, `label`, `placeholder`, `helperText`, `initialValue`, `required`, `disabled`, and a `rules` block reusing the existing schema DSL) and pass it to `KformikForm(fields, onSubmit)` to get a fully wired Material 3 form: per-field initial values + validation schema + submit gating + per-keystroke / on-blur / on-submit validation hooks. Ten field types ship in v1: `Text`, `Email`, `Password`, `Multiline`, `Number`, `Checkbox`, `Switch`, `Select`, `Radio`, `Date`. The `Date` renderer stores ISO `yyyy-MM-dd` `String?` (no `kotlinx-datetime` types in the API surface). Escape hatches: per-field `renderOverride`, custom `submitButton` slot, optional `extraValidate` callback, and pass-through for `validateDebounceMs` + `validateAsync` from v1.7.0. New module artifact at `io.github.apdelrahman1911:kformik-forms:1.8.0` (Android + Desktop JVM + iOS targets). Reference: `docs/FORMS_USAGE.md`. 24 unit tests in `kformik-forms/src/jvmTest`.
- `rememberFormik` now forwards the v1.7.0 `validateDebounceMs` and `validateAsync` parameters from `FormikConfig`. Both are appended at the end of the parameter list and `validateAsync` is tracked with `rememberUpdatedState` so callback identity stays fresh across recompositions. `kformik-compose/api/{jvm,android}/kformik-compose.api` baselines updated. See **Binary compatibility** below for the JVM-bytecode caveat.
- `rememberFormik` now also wraps `schemaValidator` via `rememberUpdatedState` (symmetric with `validate` / `validateAsync` / `onSubmit` / `onReset` / `onError`). An inline schema that closes over changing state — e.g. `formSchema { field("email") { minLength(minLenVar) } }` where `minLenVar` is a `State` — now picks up the latest captures across recompositions instead of silently keeping the first-composition instance.

### Fixed (concurrency hardening in `:kformik`)

Three real interleaving races between mutex-held setters and the lock-free `setFormikState` escape hatch, surfaced by a multi-agent review of the v1.7.0 controller. Each one is reproduced by a JVM stress test in `ConcurrencyStressTest`.

- **`setFieldValue` / `setValues((V) -> V)` CAS clobber.** Both setters used to compute `next = setAt(_state.value.values, …)` BEFORE entering `_state.update`. A concurrent lock-free `setFormikState` landing between snapshot read and CAS commit would have its `values` change silently overwritten by the stale `it.copy(values = next)` on the subsequent retry. Fix: move the `setAt` / `updater(…)` call INSIDE the `_state.update` lambda so each CAS retry recomputes against the latest `current.values`. Mirrors the existing `applyArrayMutation` pattern. Pinned by `setFieldValue_andLockFreeSetFormikState_bothWritesSurvive` (150 iters) and the `setValues((V) -> V)` equivalent.
- **`submit()` pinning stale `submitValues`.** `submit()` used to read `cur = _state.value` BEFORE its `_state.update` and return `cur.values` from the `withLock` block as the snapshot passed to `config.onSubmit`. A lock-free `setFormikState` interleaved inside the mutex window caused `onSubmit` to receive OLD values while published state showed NEW ones. Fix: capture `submitValues = current.values` from inside the CAS lambda so the value passed to `onSubmit` matches what was committed.
- **`submit()` single-flight gate independent of `isSubmitting` flag.** The pre-fix code gated reentrant submits on `_state.value.isSubmitting` inside the reducer mutex. A `resetForm()` landing while `submit()` was awaiting `config.onSubmit` (mutex released) would flip `isSubmitting = false`; the next `submit()` would then pass the check and run concurrently with the first. Fix: a dedicated `submitMutex` acquired via `tryLock` for the entire submit lifecycle. `resetForm()` keeps its prior Formik-compatible behavior of clearing the visible flag — the structural gate is independent. Pinned by `submit_singleFlight_secondSubmitNoOpEvenAfterReset` (deterministic).

### Fixed (`:kformik-forms` default renderers)

The forms layer was added in this same release but its renderers shipped with three correctness bugs surfaced by the same review pass — all fixed before tagging.

- **Renderers now subscribe per-field via `ComposeFormik.fieldState(name)`** instead of plain snapshot reads (`form.value(name)` / `form.displayError(name)`). Standalone `KformikFields` now correctly re-renders on every keystroke; the previous code only worked when wrapped in `KformikForm`, which papered over the issue by subscribing to whole-form state at the column level.
- **Renderers now mark fields touched.** Text / Number use `Modifier.onFocusChanged` gated by a `hadFocus` flag so initial composition doesn't pre-touch; Checkbox / Switch / Select / Radio / Date touch on change. Errors now appear after the user actually interacts with the field, not only after `submit()` touches everything.
- **`KformikForm` controller-key derived from field shape** instead of the raw `fields` map. The previous `key = fields` failed for closure-capturing `rules` lambdas: `Field` is a data class whose `rules: () -> Unit` member is part of structural `equals()`, so a non-singleton lambda made every recomposition produce a fresh `Field` and rebuild the controller, wiping user input. The new key incorporates `name`, `type`, `required`, `disabled`, and `initialValue` — stable across lambda-identity changes but rebuilds on real shape changes.
- `RadioRenderer` no longer double-dispatches its click: the inner `RadioButton.onClick = null`, so the parent `selectable` row is the sole click target.

### Binary compatibility

> ⚠️ **`rememberFormik` is a binary break against v1.7.0.** The two new parameters
> (`validateDebounceMs`, `validateAsync`) are APPENDED at the end of the parameter list. This
> preserves Kotlin source compatibility via default arguments, but the JVM positional descriptor
> changes — consumers compiled against v1.7.0's `rememberFormik` will hit `NoSuchMethodError`
> against v1.8.0+. The library is at v1.x and the `rememberFormik` public surface is small enough
> that this is accepted as a documented break rather than mitigated via deprecated-hidden
> overloads; future signature growth will use the deprecated-overload path. `kformik-compose/api/
> {jvm,android}/kformik-compose.api` baselines updated.
>
> See the v1.7.0 errata below for the `FormikConfig` regression that was already shipped with the
> same shape; the policy going forward is append-only-at-tail for `FormikConfig` and
> deprecated-hidden overloads for `rememberFormik`-style API changes.

### Build & CI

- `:kformik-forms` added to the `.github/workflows/ci.yml` JVM job (`jvmTest + testReleaseUnitTest + assembleRelease`) and iOS job (`compileKotlinIosSimulatorArm64`).
- `.github/workflows/release.yml`'s pre-publish verification step extended to cover the new module; the post-publish `.asc` count guardrail raised from `>= 40` to `>= 46` to reflect the additional publication targets.

### Sample

- New `RegistrationScreen` in `:sample-android-app` demonstrating `KformikForm` end-to-end with `Text`, `Email`, `Password`, `Number`, `Select`, and `Checkbox` fields (the last with a custom "must be checked" rule, since the standard `required()` treats Boolean `false` as a value not as missing).
- `MainActivity` now hosts both `RegistrationScreen` (default tab) and `LoginScreen` in a `TabRow`, so reviewers can compare the declarative form against the equivalent hand-wired `rememberFormik` code side-by-side.

### Coordinates

- `io.github.apdelrahman1911:kformik:1.8.0`
- `io.github.apdelrahman1911:kformik-compose:1.8.0`
- `io.github.apdelrahman1911:kformik-forms:1.8.0`
- `io.github.apdelrahman1911:kformik-ksp:1.8.0`

## [1.7.0] — 2026-06-02

### Added

- **`FormikConfig.validateDebounceMs: Long? = null`** — optional debounce window applied to change-triggered validation. When set, rapid mutations via `setFieldValue` / `setValues` coalesce into a single validation run after the debounce window, so a `validate` callback that makes expensive checks (network lookups, heavy regex, etc.) isn't invoked on every keystroke. The debounce applies only to validation triggered by `validateOnChange`; blur (`validateOnBlur`), explicit `validateForm()` / `validateField()`, and submit always validate immediately. `null` (default) preserves current behavior — every change validates synchronously, no regression for callers who don't set the field. `0` and negative values are coerced to `null`.
- **`FormikConfig.validateAsync: (suspend (V) -> FormikErrors)? = null`** — optional **async / expensive validator** that runs **only when** the cheap sync layer (`validate` + `schemaValidator` + field-level validators) produced zero errors. Sync-then-async circuit breaking: a network "is this username taken?" check inside `validateAsync` is skipped if a cheap `validate { it.isBlank() → "Required" }` already invalidated the form. Pair with `validateDebounceMs` for the canonical "debounce on type + skip network if local regex already failed" workflow. `null` (default) preserves current behavior.
- 11 tests in `ValidateDebounceTest` covering rapid-burst coalescing, latest-values-win semantics, blur / submit / `validateForm()` bypass guarantees, and zero/negative coercion.
- 14 tests in `ValidateAsyncTest` covering: async runs when sync is clean / absent, skipped when any of (`validate`, `schemaValidator`, field-level) failed, error commit, `validateOnChange=false` interaction, blur + mount + submit triggers, async-blocks-submit semantics, F2 × F3 interplay (debounce → sync → async circuit-break).

### Binary compatibility

> ⚠️ **Errata (corrected 2026-06-03):** This release IS a binary break for callers compiled
> against v1.6.0's `FormikConfig`. The original CHANGELOG entry below understated the scope;
> what actually happened:
>
> - The two new parameters (`validateAsync`, `validateDebounceMs`) were inserted at constructor
>   positions 6 and 13 respectively — not appended at the tail. The primary constructor's JVM
>   descriptor therefore changed, and `component6` through `component12` shifted return type or
>   meaning (not just `component12`+). `copy()` changed correspondingly. Consumers compiled
>   against v1.6.0's `FormikConfig` hit `NoSuchMethodError` against v1.7.0+.
> - Source compatibility IS preserved for Kotlin callers using named-argument construction (the
>   dominant pattern) — recompiling against v1.7.0 works without source changes.
>
> The library is at v1.x and the published v1.7.0 artifact on Maven Central is unchanged; this
> errata only corrects the documentation. Going forward, `FormikConfig` follows append-only-at-tail
> for new optional parameters, and `rememberFormik`-style API growth uses deprecated-hidden
> overloads.

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
