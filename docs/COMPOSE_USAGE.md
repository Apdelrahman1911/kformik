# Compose adapter (`kformik-compose`)

Jetpack Compose integration for Kformik. **Not** required for the core — Kformik's core has no Compose dependency. This module is a thin, optional adapter.

## Gradle setup

```kotlin
// settings.gradle.kts
include(":kformik-compose")

// app build.gradle.kts
dependencies {
    implementation(project(":kformik-compose"))   // transitively brings :kformik
}
```

The module is published as an Android AAR (`com.android.library`) with `compose-runtime` + `compose-runtime-saveable` dependencies. It does **not** depend on Material 3 or `compose.foundation` — you bring whatever UI library you prefer.

## API

```kotlin
@Composable
fun <V> rememberFormik(
    initialValues: V,
    validate: (suspend (V) -> FormikErrors)? = null,
    schemaValidator: SchemaValidator<V>? = null,
    onSubmit: FormikSubmitHandler<V>,
    onReset: FormikResetHandler<V>? = null,
    validateOnChange: Boolean = true,
    validateOnBlur: Boolean = true,
    validateOnMount: Boolean = false,
    enableReinitialize: Boolean = false,
    valuesUpdater: ValuesUpdater<V>? = null,
    key: Any? = Unit,
): ComposeFormik<V>
```

Returned `ComposeFormik<V>` exposes:

| Member                     | Type                              | Notes                                          |
|----------------------------|-----------------------------------|------------------------------------------------|
| `controller`               | `FormikController<V>`             | The underlying controller (escape hatch)       |
| `state`                    | `State<FormikState<V>>` (Composable getter) | Snapshot-friendly                |
| `dirty`                    | `State<Boolean>` (Composable getter) |                                              |
| `isValid`                  | `State<Boolean>` (Composable getter) |                                              |
| `fieldState(name)`         | `State<FieldBinding<Any?>>` (Composable) | Re-computes on state change             |
| `value(name)`              | `Any?`                            | Snapshot read                                   |
| `error(name)`              | `String?`                         | Snapshot read                                   |
| `isTouched(name)`          | `Boolean`                         | Snapshot read                                   |
| `displayError(name)`       | `String?`                         | `error` if touched, else null                  |
| `setFieldValue(name, v)`   | `Unit` (fire-and-forget)          | Launches on the remembered scope               |
| `setFieldValue(name, fn)`  | `Unit` (fire-and-forget)          | Updater overload                               |
| `setFieldTouched(name)`    | `Unit`                            |                                                |
| `setFieldError(name, msg)` | `Unit`                            | Synchronous                                     |
| `setStatus(status)`        | `Unit`                            | Synchronous                                     |
| `setSubmitting(b)`         | `Unit`                            | Synchronous                                     |
| `setErrors(errors)`        | `Unit`                            | Synchronous                                     |
| `submit()`                 | `Unit` (fire-and-forget)          | Calls `controller.handleSubmit()`              |
| `resetForm()`              | `Unit` (fire-and-forget)          | Calls `controller.handleReset()`               |
| `launch { … }`             | `Unit`                            | Run a suspend block with `FormikActions`       |

The remembered controller is disposed when the composable leaves composition (`DisposableEffect`).

## Recommended pattern

```kotlin
@Composable
fun LoginScreen() {
    val form = rememberFormik(
        initialValues = mapOf<String, Any?>("email" to "", "password" to ""),
        validate = { v ->
            buildErrors {
                if ((v["email"] as String).isBlank()) put("email", "Email required")
                else if ("@" !in (v["email"] as String)) put("email", "Invalid email")
                if ((v["password"] as String).length < 8) put("password", "Min 8")
            }
        },
        onSubmit = { values, actions ->
            try {
                api.login(values["email"] as String, values["password"] as String)
                actions.setStatus("Welcome ${values["email"]}")
            } catch (e: AuthException) {
                actions.setFieldError("password", "Invalid credentials")
            }
        },
    )

    val state by form.state
    val isValid by form.isValid

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.values["email"] as String,
            onValueChange = { form.setFieldValue("email", it) },
            isError = form.displayError("email") != null,
            label = { Text("Email") },
        )
        form.displayError("email")?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        OutlinedTextField(
            value = state.values["password"] as String,
            onValueChange = { form.setFieldValue("password", it) },
            isError = form.displayError("password") != null,
            visualTransformation = PasswordVisualTransformation(),
        )

        Button(
            enabled = isValid && !state.isSubmitting,
            onClick = { form.submit() },
        ) {
            Text(if (state.isSubmitting) "Signing in…" else "Sign in")
        }
    }
}
```

## Foot-guns

- **Don't pass field-mutating lambdas as keys to `remember`**: `setFieldValue` is launched on the remembered scope, so calling it inside a `LaunchedEffect(...)` is fine. Avoid the temptation to `LaunchedEffect(form.value("email")) { form.setFieldValue(...) }` — this creates a recomposition loop. Use `controller.state.collect { … }` from inside `LaunchedEffect(Unit)` instead.
- **Config changes do not rebuild the controller across recompositions.** This matches Formik's React semantics — the config snapshot is taken once. To force a rebuild, change the `key` arg.
- **Don't share a `ComposeFormik` instance across composables.** Each `rememberFormik` call creates a new controller. To share across screens, hoist the underlying `FormikController` into a `ViewModel`.

## Sample

See `kformik-compose/src/main/kotlin/io/kformik/compose/sample/LoginScreenSample.kt` — compile-checked at every build.

## Why no `:examples:run` task for Compose

Compose composables can't run in a JVM `main()` without a Compose runtime host (Activity / `application` block). Use the sample as a reference and copy it into your own Android app to see it live.
