package io.kformik.sample.forms.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.kformik.FormikErrors
import io.kformik.forms.Field
import io.kformik.forms.FieldType
import io.kformik.forms.KformikForm
import io.kformik.forms.SelectOption
import io.kformik.sample.forms.ShowcaseScaffold
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val TAKEN = setOf("admin", "root", "kformik")

@Composable
 fun CustomAndAsyncScreen(onBack: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var forceFailure by remember { mutableStateOf(false) }

    val fields = remember {
        mapOf(
            "username" to Field(
                type = FieldType.Text,
                label = "Username",
                placeholder = "Try \"admin\" (taken)",
                required = true,
                rules = { minLength(3) },
            ),
            "years" to Field(
                type = FieldType.Number(asInt = true),
                label = "Years of experience",
                initialValue = 3,
            ),
            "seniority" to Field(
                type = FieldType.Select(listOf(
                    SelectOption("junior", "Junior"),
                    SelectOption("mid", "Mid"),
                    SelectOption("senior", "Senior"),
                )),
                label = "Seniority",
                required = true,
            ),
        )
    }

    ShowcaseScaffold(title = "Custom + Async", onBack = onBack, snackbarHostState = snackbar) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Force submit failure (demo onError)", modifier = Modifier.weight(1f))
            Switch(checked = forceFailure, onCheckedChange = { forceFailure = it })
        }

        KformikForm(
            fields = fields,
            validateDebounceMs = 400L,
            validateAsync = { v ->
                delay(800)
                if ((v["username"] as? String)?.lowercase() in TAKEN)
                    FormikErrors(mapOf("username" to "Username taken"))
                else FormikErrors.Empty
            },
            onSubmit = { v ->
                if (forceFailure) error("Forced failure to demo onError.")
                delay(600)
                snackbar.showSnackbar("Created '${v["username"]}'")
            },
            onError = { t ->
                scope.launch { snackbar.showSnackbar("Submit failed: ${t.message}") }
            },
            renderOverride = { name, field, form ->
                if (name == "years") {
                    val years = (form.fieldState(name).value.value as? Int) ?: 0
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${field.label}: $years (custom Slider via renderOverride)")
                        Slider(
                            value = years.toFloat(),
                            onValueChange = { form.setFieldValue(name, it.toInt(), true) },
                            valueRange = 0f..40f,
                            steps = 39,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    true
                } else {
                    false
                }
            },
        )
    }
}
