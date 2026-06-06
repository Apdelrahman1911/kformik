package io.kformik.sample.forms.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.kformik.forms.Field
import io.kformik.forms.FieldType
import io.kformik.forms.KformikForm
import io.kformik.forms.SelectOption
import io.kformik.sample.forms.ShowcaseScaffold
import kotlinx.coroutines.delay

@Composable
fun ProfileScreen(onBack: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    var loaded by remember { mutableStateOf(false) }

    val fields = remember(loaded) {
        mapOf(
            "country" to Field(
                type = FieldType.Select(listOf(
                    SelectOption("US", "United States"),
                    SelectOption("EG", "Egypt"),
                    SelectOption("DE", "Germany"),
                    SelectOption("JP", "Japan"),
                )),
                label = "Country",
                required = true,
                initialValue = if (loaded) "EG" else "US",
            ),
            "theme" to Field(
                type = FieldType.Radio(listOf(
                    SelectOption("light", "Light"),
                    SelectOption("dark", "Dark"),
                    SelectOption("system", "System"),
                )),
                label = "Theme",
                initialValue = if (loaded) "dark" else "system",
            ),
            "bio" to Field(
                type = FieldType.Multiline,
                label = "Bio",
                helperText = "Up to 280 characters",
                initialValue = if (loaded) "Kotlin Multiplatform fan." else "",
                rules = { maxLength(280) },
            ),
            "age" to Field(
                type = FieldType.Number(asInt = true),
                label = "Age",
                required = true,
                initialValue = if (loaded) 32 else null,
                rules = { min(13);max(120) },
            ),
            "height" to Field(
                type = FieldType.Number(asInt = false),
                label = "Height (m)",
                initialValue = if (loaded) 1.78 else null,
            ),
            "plan" to Field(
                type = FieldType.Text,
                label = "Plan",
                initialValue = if (loaded) "Pro" else "Free",
                disabled = true,
            ),
        )
    }

    ShowcaseScaffold(title = "Profile", onBack = onBack, snackbarHostState = snackbar) {
        OutlinedButton(
            onClick = { loaded = !loaded },
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        ) {
            Text(if (loaded) "Reset" else "Load profile from server")
        }

        KformikForm(
            fields = fields,
            enableReinitialize = true,
            validateOnMount = true,
            submitButton = { onSubmit, isValid, isSubmitting ->
                FilledTonalButton(
                    onClick = onSubmit,
                    enabled = isValid && !isSubmitting,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (isSubmitting) "Saving…" else "Save profile") }
            },
            onSubmit = { _ ->
                delay(600)
                snackbar.showSnackbar("Profile saved.")
            },
        )
    }
}
