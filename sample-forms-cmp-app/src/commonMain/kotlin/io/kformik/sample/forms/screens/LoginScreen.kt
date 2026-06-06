package io.kformik.sample.forms.screens

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.kformik.forms.Field
import io.kformik.forms.FieldType
import io.kformik.forms.KformikForm
import io.kformik.sample.forms.ShowcaseScaffold
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(onBack: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val fields = remember {
        mapOf(
            "email" to Field(
                type = FieldType.Email,
                label = "Email",
                placeholder = "you@example.com",
                required = true,
                rules = { email() },
            ),
            "password" to Field(
                type = FieldType.Password,
                label = "Password",
                helperText = "At least 8 characters",
                required = true,
                rules = { minLength(8) },

            ),
            "rememberMe" to Field(type = FieldType.Switch, label = "Remember me"),
        )
    }

    ShowcaseScaffold(title = "Login", onBack = onBack, snackbarHostState = snackbar) {
        KformikForm(
            fields = fields,
            onSubmit = { v ->
                delay(700)
                snackbar.showSnackbar("Logged in as ${v["email"]}")
            },
            footerSlot = {
                TextButton(onClick = {
                    scope.launch { snackbar.showSnackbar("Reset link sent (mock).") }
                }) { Text("Forgot password?") }
            },
        )
    }
}
