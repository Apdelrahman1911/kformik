# Schema validation DSL (`formSchema { … }`)

The Kotlin equivalent of Formik's Yup integration. Build a schema once and reuse it across forms.

## Quick start

```kotlin
val schema = formSchema<Map<String, Any?>> {
    field("email") {
        required("Email is required")
        email("Invalid email")
    }
    field("password") {
        required("Password is required")
        minLength(8, "Password must be at least 8 characters")
    }
    field("user.age") {
        min(18, "Must be 18+")
        max(120)
    }
    cross { values ->
        val pwd = values["password"] as? String
        val confirm = values["confirmPassword"] as? String
        if (pwd != confirm) buildErrors { put("confirmPassword", "Passwords must match") }
        else FormikErrors.Empty
    }
}

val form = FormikController(FormikConfig(
    initialValues = mapOf(/* ... */),
    schemaValidator = schema,
    onSubmit = { values, _ -> /* ... */ },
))
```

## Built-in rules

| Rule                                   | Behavior                                                        |
|----------------------------------------|-----------------------------------------------------------------|
| `required(message?)`                   | Rejects `null`, empty `String`, empty `Collection`, empty `Map` |
| `minLength(min, message?)`             | Minimum length for `String`/`Collection`/`Map`                  |
| `maxLength(max, message?)`             | Maximum length for `String`/`Collection`/`Map`                  |
| `email(message?)`                      | Pragmatic email regex (`^[^\s@]+@[^\s@]+\.[^\s@]+$`)            |
| `pattern(regex, message?)`             | Custom regex; only applies if value is a `String`               |
| `min(value, message?)`                 | Numeric minimum (compared as `Double`)                          |
| `max(value, message?)`                 | Numeric maximum                                                  |
| `custom(name?) { v, all -> … }`        | Full custom rule with cross-field access                         |
| `customValue(name?) { v -> … }`        | Short-form custom rule, value-only                               |

All rules are `suspend` — `delay(…)`, HTTP calls, and database lookups are fine inside `custom`/`customValue` blocks.

## First-failing rule wins (per path)

Rules attached to the same path are evaluated in declaration order; the first one that returns a non-null message is the result for that path. Subsequent rules are short-circuited.

```kotlin
field("password") {
    required("Required")     // runs first
    minLength(8, "Min 8")    // only runs if value is non-empty
}
schema.validate(mapOf("password" to ""))["password"]
// → "Required"  (not "Min 8")
```

## Cross-field rules

`cross { values -> FormikErrors }` runs after all per-field rules. The result is merged on top of per-field errors (so a cross-field error can override a per-field error on the same path — matches Formik's `deepmerge` order).

## Focused field validation

`FormikController.validateField(name)` calls the schema's `validateField(values, name)` if the schema is a `FormSchema`. This runs only the rules for that single path, which is faster than a full re-validation.

```kotlin
form.validateField("email")        // only runs "email" rules
form.state.value.errors["password"] // unchanged
```

If the schema is *not* a `FormSchema` (e.g. a custom `SchemaValidator`), the controller falls back to running the full schema and plucking the error at the requested path.

## Nested and bracketed paths

`field("user.address.city")` and `field("tags[1]")` resolve values via `MapValuesUpdater`, so they work the same way as `FormikController.setFieldValue(path, …)`.

## Differences from Yup

| Yup                        | Kformik schema                              |
|----------------------------|---------------------------------------------|
| Type coercion (numbers, strings, booleans aggressively) | No coercion — rules see the raw value     |
| Nested schemas (`Yup.object().shape(...)`)              | Flat path-keyed `field("a.b.c") { … }`     |
| `when` / `oneOf` / `shape`                              | Use `custom { v, all -> … }` or `cross { … }` |
| Sync or sync-only validators                            | Suspending validators (await any IO)        |
| `ValidationError` → `yupToFormErrors` flattening        | Direct `FormikErrors` return                |
| Empty-string normalization (`prepareDataForValidation`) | Not applied — handle in the rule itself     |

## Examples

- `examples/src/main/kotlin/schema/SchemaValidationExample.kt` — run via `./gradlew :examples:run -PrunExample=schema`.

## Tests

- `kformik/src/commonTest/kotlin/io/kformik/FormSchemaTest.kt` (24 tests).

## Source

- `kformik/src/commonMain/kotlin/io/kformik/FormSchema.kt`.
