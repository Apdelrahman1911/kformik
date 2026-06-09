# Declarative forms (`kformik-forms`)

A higher-level layer on top of `:kformik-compose` that lets you describe a form as `Map<String, Field>` and render it with one composable. Behind the scenes it assembles the same `FormSchema` and `rememberFormik` you'd write by hand; you trade a little flexibility for a lot less code.

If you're already comfortable with `rememberFormik` and only need the engine, you don't need this module.

## Install

```kotlin
plugins {
    kotlin("multiplatform")       // or kotlin("android") / kotlin("jvm")
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

dependencies {
    implementation("io.github.apdelrahman1911:kformik-forms:1.10.0")
}
```

`:kformik-forms` re-exports `:kformik-compose` (and transitively `:kformik`), so a single dependency is enough.

Targets: Android, Desktop JVM, iOS (`iosX64`, `iosArm64`, `iosSimulatorArm64`).

## Minimal example

```kotlin
@Composable
fun RegistrationScreen() {
    KformikForm(
        fields = mapOf(
            "fullName" to Field(
                type = FieldType.Text,
                label = "Full name",
                required = true,
                rules = { minLength(2) },
            ),
            "email" to Field(
                type = FieldType.Email,
                label = "Email",
                required = true,
                rules = { email() },
            ),
            "password" to Field(
                type = FieldType.Password,
                label = "Password",
                required = true,
                rules = { minLength(8) },
            ),
        ),
        onSubmit = { values -> api.register(values) },
    )
}
```

`KformikForm` renders the fields, validates per-keystroke (configurable), and only calls `onSubmit` once everything passes.

## The `Field` shape

| Property | Type | Purpose |
|---|---|---|
| `type` | [`FieldType`](#fieldtype) | Picks the default Material 3 renderer |
| `label` | `String?` | Shown above/beside the widget. Gets a `*` suffix when `required = true` |
| `placeholder` | `String?` | Hint inside empty text inputs |
| `helperText` | `String?` | Muted text below the widget; replaced by the validation error when present |
| `initialValue` | `Any?` | Seed value. Default is the public sentinel `FieldDefaultValue` (means "no explicit value — use the type default below"). Pass `null` to store an explicit null (e.g. "no selection yet" for `Select`); pass any other value to override. |
| `required` | `Boolean = false` | Auto-prepends a `required()` rule into the schema (unless your `rules` block already declares one). Also marks the label |
| `disabled` | `Boolean = false` | Renders widget as non-interactive; form state is preserved |
| `rules` | `FieldRulesBuilder<…>.() -> Unit = {}` | Same DSL as `formSchema { field("…") { … } }`: `required()`, `email()`, `minLength(n)`, `maxLength(n)`, `pattern(regex)`, `min(n)`, `max(n)`, `custom(name) { v, allValues -> … }` |

### Default value per `FieldType`

| Type | Default | Notes |
|---|---|---|
| `Text` / `Email` / `Password` / `Multiline` | `""` | Pairs with `required()`'s blank-string check |
| `Number(asInt = true)` / `Number(asInt = false)` | `null` | The renderer commits typed input as `Int?` / `Double?`; `null` lets `required()` catch a missing value. Pass `Field(initialValue = 0)` to seed zero. (v1.8.0 defaulted to `0` / `0.0`; that silently passed `required()` because Boolean `false` / Int `0` are "present" — changed in v1.9.0.) |
| `Checkbox` / `Switch` | `false` | |
| `Select(options)` / `Radio(options)` | first option's value, or `null` if `options` is empty | |
| `Date` | `null` | Stored as ISO `"yyyy-MM-dd"` `String?` when set |

Explicit `Field(initialValue = …)` always wins over the default.

## `FieldType` catalog

```kotlin
FieldType.Text                                   // single-line text
FieldType.Email                                  // text + email keyboard hint
FieldType.Password                               // text + masked visual transform
FieldType.Multiline                              // multi-line text
FieldType.Number(asInt = true)                   // Int input
FieldType.Number(asInt = false)                  // Double input
FieldType.Checkbox                               // Boolean — Checkbox widget
FieldType.Switch                                 // Boolean — Switch widget (visual alternative)
FieldType.Select(options = listOf(SelectOption("us", "USA"), SelectOption("eg", "Egypt")))
FieldType.Radio(options = listOf(SelectOption(1, "One"), SelectOption(2, "Two")))
FieldType.Date                                   // M3 DatePickerDialog → stored as ISO yyyy-MM-dd String?
```

`SelectOption.value` is typed `Any?` and is what gets stored in the form's value map; `SelectOption.label` is the display text. A null value option is supported for "— select a … —" placeholders.

## Common rule patterns

```kotlin
// Cross-field — confirm password matches the password field
Field(
    type = FieldType.Password,
    label = "Confirm password",
    required = true,
    rules = {
        custom("Doesn't match") { v, allValues ->
            if (v != allValues["password"]) "Doesn't match" else null
        }
    },
)

// "Must be checked" checkbox (the standard required() rule treats `false` as "present", not
// "missing" — Boolean false is a value, not absence. Use a custom rule for ToS-style acceptance.)
Field(
    type = FieldType.Checkbox,
    label = "I accept the Terms of Service",
    rules = { custom("Must accept the ToS") { v, _ -> if (v != true) "Must accept" else null } },
)

// Number range
Field(
    type = FieldType.Number(asInt = true),
    label = "Age",
    rules = { min(18); max(120) },
)

// Regex pattern
Field(
    type = FieldType.Text,
    label = "Username",
    rules = {
        pattern(Regex("^[a-z0-9_]{3,20}$"), "Lowercase letters, digits, underscores; 3–20 chars")
    },
)
```

## `KformikForm` signature

```kotlin
@Composable
fun KformikForm(
    fields: Map<String, Field>,
    onSubmit: suspend (values: Map<String, Any?>) -> Unit,
    modifier: Modifier = Modifier,
    spacing: Dp = 12.dp,
    submitButton: @Composable (
        onSubmit: () -> Unit,
        isValid: Boolean,
        isSubmitting: Boolean,
    ) -> Unit = { /* default M3 Button */ },
    renderOverride: (@Composable (
        name: String,
        field: Field,
        form: ComposeFormik<Map<String, Any?>>,
    ) -> Boolean)? = null,
    validateOnChange: Boolean = true,
    validateOnBlur: Boolean = true,
    validateOnMount: Boolean = false,
    enableReinitialize: Boolean = false,
    validateDebounceMs: Long? = null,
    validateAsync: (suspend (Map<String, Any?>) -> FormikErrors)? = null,
    extraValidate: (suspend (Map<String, Any?>) -> FormikErrors)? = null,
    // v1.9.0 additions:
    onError: ((Throwable) -> Unit)? = null,
    initialErrors: FormikErrors = FormikErrors.Empty,
    initialTouched: FormikTouched = FormikTouched.Empty,
    initialStatus: Any? = null,
    footerSlot: @Composable (form: ComposeFormik<Map<String, Any?>>) -> Unit = {},
)
```

**v1.9.0 additions** — server-side hydration via `initialErrors` / `initialTouched` / `initialStatus`; `onError` slot for surfacing exceptions thrown by `onSubmit`; `footerSlot` for rendering form-level (non-field-bound) errors / status messages between the fields and the submit button.

## Escape hatches

### Custom rendering for one field

`renderOverride` is checked first for each field; returning `true` skips the default renderer, `false` falls through:

```kotlin
KformikForm(
    fields = fields,
    onSubmit = { … },
    renderOverride = { name, field, form ->
        if (name == "country") {
            MyCustomCountryPicker(
                value = form.value(name) as? String,
                onValueChange = { form.setFieldValue(name, it) },
                error = form.displayError(name),
            )
            true       // handled
        } else false   // fall back to default
    },
)
```

### Custom submit button

```kotlin
KformikForm(
    fields = fields,
    onSubmit = { … },
    submitButton = { submit, valid, submitting ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { /* reset */ }) { Text("Cancel") }
            Button(onClick = submit, enabled = valid && !submitting) {
                Text(if (submitting) "Saving…" else "Save")
            }
        }
    },
)
```

### Extra validation outside the field map

```kotlin
KformikForm(
    fields = fields,
    onSubmit = { … },
    extraValidate = { values ->
        buildErrors {
            if ((values["startDate"] as? String).orEmpty() > (values["endDate"] as? String).orEmpty()) {
                put("endDate", "Must be after start date")
            }
        }
    },
)
```

`extraValidate` runs alongside the auto-built schema and its errors merge with field-rule errors.

## What it stores in the form's value map

The renderer for each `FieldType` writes a specific Kotlin type into `Map<String, Any?>` — no coercion happens at submit time, so plan your `onSubmit` accordingly:

| Type | Stored as |
|---|---|
| `Text` / `Email` / `Password` / `Multiline` | `String` |
| `Number(asInt = true)` | `Int` (or `String` while a partial value like `"-"` is being typed) |
| `Number(asInt = false)` | `Double` (same `String` fallback during partial input) |
| `Checkbox` / `Switch` | `Boolean` |
| `Select` / `Radio` | the chosen `SelectOption.value` (`Any`) |
| `Date` | ISO `"yyyy-MM-dd"` `String?` |

If you need typed values (a `data class` for the whole form rather than a `Map`), the underlying `rememberFormik(valuesUpdater = …)` supports that — but that path doesn't currently flow through `KformikForm`; use the `:kformik-compose` API directly for typed forms.

## Render order

`KformikFields` iterates the map in insertion order, so `mapOf(...)` (which returns `LinkedHashMap`) gives a deterministic top-to-bottom layout. A plain `HashMap` will give nondeterministic order.

## Limitations / not-done-in-v1

- Typed `data class` form values (the layer is currently `Map<String, Any?>`-only — fall back to `:kformik-compose`'s `rememberFormik(valuesUpdater = …)` if you need typed values).
- Custom theming arguments — colour / shape overrides go via `MaterialTheme` (the entire renderer surface respects it).
- The `Date` renderer does not currently emit a `LocalDate`; consumers needing kotlinx-datetime types parse the ISO string downstream.
- File upload, rich-text, search-as-you-type select — out of scope for v1. `renderOverride` covers these.
