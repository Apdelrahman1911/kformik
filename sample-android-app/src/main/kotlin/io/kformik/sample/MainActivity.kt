package io.kformik.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.kformik.buildErrors
import io.kformik.compose.rememberFormik

/**
 * Minimal demonstration of both Kformik adapters against the real Material 3 API.
 *
 * Two tabs:
 *  - **Register** — `:kformik-forms` declarative `KformikForm` (the v1.8.0 headline demo).
 *  - **Login** — hand-wired `:kformik-compose` `rememberFormik` (the pre-v1.8.0 alternative,
 *    kept here so reviewers can compare the boilerplate side-by-side).
 *
 * Beyond the install demo, this module exists as a *compile target* — it forces every public
 * API in `:kformik-compose` and `:kformik-forms` to be exercised against the real Compose
 * foundation + Material 3 deps, which catches API drift faster than the runtime-only sample
 * inside `:kformik-compose`.
 *
 * To install on an emulator:
 *   ./gradlew :sample-android-app:installDebug
 *
 * To just compile (no device needed — what CI / our verification sweep does):
 *   ./gradlew :sample-android-app:assembleDebug
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SampleHost()
                }
            }
        }
    }
}

@Composable
private fun SampleHost() {
    var tab by remember { mutableStateOf(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                text = { Text("Register") },
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text("Login") },
            )
        }
        when (tab) {
            0 -> RegistrationScreen()
            1 -> LoginScreen()
        }
    }
}

@Composable
fun LoginScreen() {
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

    val state by form.state
    val isValid by form.isValid

    Column(
        modifier = Modifier.padding(16.dp).fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Kformik sample", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = state.values["email"] as String,
            onValueChange = { form.setFieldValue("email", it) },
            label = { Text("Email") },
            isError = form.displayError("email") != null,
            supportingText = { form.displayError("email")?.let { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = state.values["password"] as String,
            onValueChange = { form.setFieldValue("password", it) },
            label = { Text("Password") },
            isError = form.displayError("password") != null,
            supportingText = { form.displayError("password")?.let { Text(it) } },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            enabled = isValid && !state.isSubmitting,
            onClick = { form.submit() },
        ) {
            Text(if (state.isSubmitting) "Signing in…" else "Sign in")
        }

        state.status?.let { Text("$it") }
    }
}

@Preview(showBackground = true)
@Composable
fun LoginScreenPreview() {
    MaterialTheme { LoginScreen() }
}
