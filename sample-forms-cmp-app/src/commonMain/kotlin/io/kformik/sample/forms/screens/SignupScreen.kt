package io.kformik.sample.forms.screens

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.kformik.FormikErrors
import io.kformik.forms.Field
import io.kformik.forms.FieldType
import io.kformik.forms.KformikForm
import io.kformik.sample.forms.ShowcaseScaffold
import kotlinx.coroutines.delay

@Composable
 fun SignupScreen(onBack: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }

    val fields = remember {
        mapOf(
            "name" to Field(FieldType.Text, label = "Full name", required = true, rules = { minLength(2) }),
            "email" to Field(FieldType.Email, label = "Email", required = true, rules = { email() }),
            "password" to Field(
                type = FieldType.Password,
                label = "Password",
                required = true,
                rules = {
                    minLength(8)
                    // custom rule — anything the built-ins don't cover. Receives the field value
                    // (v) plus the full values map; return a message string to fail, or null to pass.
                    custom("hasDigit") { v, _ ->
                        if ((v as? String)?.any { it.isDigit() } != true) "Must contain a digit" else null
                    }
                },
            ),
            "confirm" to Field(FieldType.Password, label = "Confirm password", required = true),
            "dob" to Field(FieldType.Date, label = "Date of birth", required = true),
            "newsletter" to Field(FieldType.Switch, label = "Email me the newsletter", initialValue = false),
            "tos" to Field(
                type = FieldType.Checkbox,
                label = "I accept the terms",
                required = true,
                rules = {
                    custom("tos") { v, _ -> if (v != true) "You must accept" else null }
                },
            ),
        )
    }

    ShowcaseScaffold(title = "Signup", onBack = onBack, snackbarHostState = snackbar) {
        KformikForm(
            fields = fields,
            extraValidate = { v ->
                if ((v["password"] as? String) != (v["confirm"] as? String))
                    FormikErrors(mapOf("confirm" to "Passwords don't match"))
                else FormikErrors.Empty
            },
            onSubmit = { _ ->
                delay(900)
                snackbar.showSnackbar("Account created.")
            },
        )
    }
}
