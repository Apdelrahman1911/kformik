package io.kformik.compose.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import io.kformik.buildErrors
import io.kformik.compose.rememberFormik

/**
 * Compile-only sample demonstrating the canonical Compose usage pattern.
 *
 * The sample doesn't depend on `androidx.compose.foundation` or any UI library; it just exercises
 * the [rememberFormik] / [io.kformik.compose.ComposeFormik] surface so that the public API stays
 * type-checked by `:kformik-compose:compileReleaseKotlin`. In a real app you would call this
 * inside a `Column { … }` with `OutlinedTextField` / `Button` from Material 3 — the form API
 * itself is identical.
 *
 * Reference app-side usage (uncompiled, but valid against the Compose-foundation API):
 *
 * ```kotlin
 * @Composable
 * fun LoginScreen() {
 *   val form = rememberFormik(
 *     initialValues = mapOf<String, Any?>("email" to "", "password" to ""),
 *     validate = { v -> buildErrors {
 *       if ((v["email"] as String).isBlank()) put("email", "Email required")
 *       if ((v["password"] as String).length < 8) put("password", "Min 8")
 *     }},
 *     onSubmit = { values, actions -> actions.setStatus("Welcome ${values["email"]}") },
 *   )
 *   val state by form.state
 *   val isValid by form.isValid
 *
 *   Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
 *     OutlinedTextField(
 *       value = state.values["email"] as String,
 *       onValueChange = { form.setFieldValue("email", it) },
 *       isError = form.displayError("email") != null,
 *       label = { Text("Email") },
 *     )
 *     form.displayError("email")?.let { Text(it, color = Color.Red) }
 *
 *     OutlinedTextField(
 *       value = state.values["password"] as String,
 *       onValueChange = { form.setFieldValue("password", it) },
 *       isError = form.displayError("password") != null,
 *       visualTransformation = PasswordVisualTransformation(),
 *     )
 *
 *     Button(enabled = isValid && !state.isSubmitting, onClick = { form.submit() }) {
 *       Text(if (state.isSubmitting) "Signing in…" else "Sign in")
 *     }
 *   }
 * }
 * ```
 */
@Composable
internal fun LoginScreenSample() {
    val form = rememberFormik(
        initialValues = mapOf<String, Any?>("email" to "", "password" to ""),
        validate = { v ->
            buildErrors {
                val email = v["email"] as String
                val password = v["password"] as String
                if (email.isBlank()) put("email", "Email is required")
                else if ("@" !in email) put("email", "Invalid email")
                if (password.length < 8) put("password", "Password must be at least 8 characters")
            }
        },
        onSubmit = { values, actions ->
            actions.setStatus("Welcome ${values["email"]}")
        },
    )

    // Touch every exposed state surface so the compiler checks the API signatures.
    val state by form.state
    val isValid by form.isValid
    val dirty by form.dirty
    val emailBinding by form.fieldState("email")

    // Side-effect: snapshot read on first composition (purely to exercise the accessors).
    LaunchedEffect(state, isValid, dirty, emailBinding) {
        @Suppress("UNUSED_VARIABLE")
        val seen = "${state.values["email"]} valid=$isValid dirty=$dirty err=${form.error("email")}"
    }
}
