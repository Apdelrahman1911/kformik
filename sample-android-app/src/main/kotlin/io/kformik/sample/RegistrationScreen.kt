package io.kformik.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.kformik.forms.Field
import io.kformik.forms.FieldType
import io.kformik.forms.KformikForm
import io.kformik.forms.SelectOption

/**
 * Demonstrates `:kformik-forms` against real Material 3 — the entire form (text + email + password
 * + number + checkbox + select) is described as a `Map<String, Field>` plus an `onSubmit` callback.
 * Compare with [LoginScreen] to see what the hand-written-per-widget alternative looks like for
 * the same shape of form.
 *
 * The submission target here is just a `println` so a maintainer can pull logcat to verify the
 * `acceptTos` custom rule wired through correctly; in a real app this would call a repo or API.
 */
@Composable
fun RegistrationScreen() {
    val fields = mapOf(
        "fullName" to Field(
            type = FieldType.Text,
            label = "Full name",
            placeholder = "Aisha Bello",
            required = true,
            rules = { minLength(2) },
        ),
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
        "age" to Field(
            type = FieldType.Number(asInt = true),
            label = "Age",
            initialValue = 18,
            rules = { min(18); max(120) },
        ),
        "country" to Field(
            type = FieldType.Select(
                options = listOf(
                    SelectOption("eg", "Egypt"),
                    SelectOption("us", "United States"),
                    SelectOption("uk", "United Kingdom"),
                ),
            ),
            label = "Country",
        ),
        "acceptTos" to Field(
            type = FieldType.Checkbox,
            label = "I accept the Terms of Service",
            rules = {
                // required() doesn't enforce "must be checked" on a Boolean — see the test
                // io.kformik.forms.FormSchemaBuilderTest.required_onCheckbox_doesNotEnforceMustBeChecked
                // for why. Use a custom rule comparing to `true`.
                custom("Must accept the ToS") { v, _ -> if (v != true) "Must accept the Terms of Service" else null }
            },
        ),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Create your account", style = MaterialTheme.typography.headlineSmall)

        KformikForm(
            fields = fields,
            onSubmit = { values ->
                // Real app would call a suspend repo / API here; submit re-runs validation, then
                // only invokes this when everything passes.
                println("Submitted registration: $values")
            },
        )
    }
}
