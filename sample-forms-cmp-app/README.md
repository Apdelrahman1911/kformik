# Kformik Forms Showcase

A Compose Multiplatform app that demonstrates every important capability of
[`:kformik-forms`](../kformik-forms/) — the declarative `Map<String, Field>` form layer.

One `commonMain` codebase. Four screens. Runs on Android, Desktop JVM, and iOS.

## Run

```bash
# Desktop (any OS — JDK 17+):
./gradlew :sample-forms-cmp-app:run

# Android:
./gradlew :sample-forms-cmp-app:installDebug

# iOS (macOS):
open sample-forms-cmp-app/iosApp/iosApp.xcodeproj
# Then set TEAM_ID in `iosApp/Configuration/Config.xcconfig` and Run.
```

The Xcode build phase calls `./gradlew :sample-forms-cmp-app:embedAndSignAppleFrameworkForXcode`
to embed the Kotlin/Native framework, so Xcode does not need any Gradle wiring beyond Open and Run.

## Feature → screen matrix

Every row is demonstrated by at least one screen.

| Capability | API | Screen |
|---|---|---|
| One-call declarative form | `KformikForm(fields, onSubmit)` | All 4 |
| Cross-field validation | `extraValidate: suspend (Map) -> FormikErrors` | Signup |
| Async validation | `validateAsync: suspend (Map) -> FormikErrors` | Custom + Async |
| Debounced async | `validateDebounceMs: Long` | Custom + Async |
| Custom per-field renderer | `renderOverride: @Composable (name, field, form) -> Boolean` | Custom + Async (Slider) |
| Required auto-asterisk + auto-rule | `Field(required = true)` | Login, Signup, Profile |
| Built-in rules | `rules { email() / minLength / min / max / pattern / custom }` | Login, Signup, Profile, Custom |
| Server-reload pattern | `enableReinitialize = true` | Profile ("Load profile") |
| Default-value validation on mount | `validateOnMount = true` | Profile |
| Custom submit button | `submitButton = { onSubmit, isValid, isSubmitting -> … }` | Profile |
| Footer slot under fields | `footerSlot = { form -> … }` | Login ("Forgot password?") |
| Exception routing | `onError: (Throwable) -> Unit` | Custom + Async ("Force submit failure" toggle) |
| FieldType.Text + placeholder + helperText | `Field(type = Text, placeholder, helperText)` | Login, Signup, Custom |
| FieldType.Email | `FieldType.Email` | Login, Signup |
| FieldType.Password (masked) | `FieldType.Password` | Login, Signup |
| FieldType.Multiline | `FieldType.Multiline` | Profile |
| FieldType.Number(asInt = true) | `FieldType.Number(asInt = true)` | Profile (age), Custom (years) |
| FieldType.Number(asInt = false) | `FieldType.Number(asInt = false)` | Profile (height) |
| FieldType.Checkbox | `FieldType.Checkbox` | Signup (TOS) |
| FieldType.Switch | `FieldType.Switch` | Login, Signup |
| FieldType.Select | `FieldType.Select(options)` | Profile, Custom |
| FieldType.Radio | `FieldType.Radio(options)` | Profile (theme) |
| FieldType.Date | `FieldType.Date` | Signup (DOB) |
| `Field.disabled` | `Field(disabled = true)` | Profile (Plan: read-only) |

## Screens

### 1. Login — declarative-form baseline
Three Fields and one `onSubmit` lambda. `validateOnBlur` (the default) means errors appear after
the user leaves a field, not while typing. The submit button is auto-disabled until the form is
valid and re-disabled while submitting.

### 2. Signup — cross-field validation
Password / Confirm-password is checked via `extraValidate`, which receives the full values map
and returns errors keyed by path. The Date-of-birth field uses `FieldType.Date` (Material 3
DatePicker). Asterisks on required fields are auto-rendered by the library; the Terms-of-service
checkbox uses a `custom { v, _ -> if (v != true) "…" else null }` rule for its own message.

### 3. Profile — non-text widgets + server reload + escape hatches
Eight field types in one form. Tap "Load profile from server" to push a fresh `initialValues`
map; because `enableReinitialize = true`, the form resets to the new values even if you had
edits in progress. `validateOnMount = true` surfaces default-value errors immediately. The
submit button uses a custom `submitButton` slot.

### 4. Custom + Async — the escape hatches
`renderOverride` swaps a `Slider` in for the default Number input on a single field — every
other field keeps the default renderer. `validateAsync` hits a fake "server" (a 800 ms
`delay` + a set of taken usernames) to check availability; `validateDebounceMs = 400`
collapses fast typing into one check. The "Force submit failure" toggle makes `onSubmit`
throw on the next submit, proving exceptions route to `onError` instead of crashing the
process.

## Explicitly NOT shown (and why)

- **`initialErrors` / `initialTouched` / `initialStatus`** — hydration corners that are hard to demo
  without contrived setup. Use them when restoring a form mid-flow.
- **`KformikFields` standalone** (without the `KformikForm` wrapper). `KformikForm` is the headline
  call site; the lower-level composable is documented in the [forms module source](../kformik-forms/src/commonMain/kotlin/io/kformik/forms/KformikFields.kt).
- **iOS-specific renderers** — the showcase uses the same M3 widgets on all three platforms to
  prove CMP parity. `renderOverride` + `expect/actual` lets you swap in `UIKit` widgets where
  needed.

## Module layout

```
sample-forms-cmp-app/
├── build.gradle.kts                       Single KMP module — android + jvm("desktop") + iosX64/Arm64/SimulatorArm64
├── src/commonMain/kotlin/.../
│   ├── App.kt                             Routes list + back-stack of one
│   ├── HomeScreen.kt                      Card list of the 4 demos
│   ├── ScaffoldUi.kt                      Per-screen header strip
│   └── screens/{Login,Signup,Profile,CustomAndAsync}Screen.kt
├── src/androidMain/kotlin/.../MainActivity.kt
├── src/desktopMain/kotlin/.../Main.kt
├── src/iosMain/kotlin/.../MainViewController.kt
└── iosApp/                                Minimal Xcode shell
    ├── iosApp/                            iOSApp.swift + ContentView.swift + Info.plist + Assets.xcassets
    ├── iosApp.xcodeproj/                  Project + shared scheme
    └── Configuration/Config.xcconfig      BUNDLE_ID + TEAM_ID (TEAM_ID left blank in the committed file)
```

Apart from the iOS shell, this is the same shape any consumer of `:kformik-forms` would write.
