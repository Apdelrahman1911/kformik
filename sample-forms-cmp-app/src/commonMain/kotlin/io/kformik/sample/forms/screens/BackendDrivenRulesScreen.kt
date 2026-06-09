package io.kformik.sample.forms.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.kformik.RuleSpec
import io.kformik.forms.Field
import io.kformik.forms.FieldType
import io.kformik.forms.KformikForm
import io.kformik.ruleRegistry
import io.kformik.sample.forms.ShowcaseScaffold
import io.kformik.specs
import kotlinx.coroutines.delay

/**
 * Demo: form fields and validation rules driven entirely by a simulated backend payload.
 *
 * On mount we `delay(300)` to simulate a fetch, then build a `Map<String, Field>` whose `rules`
 * lambdas resolve through a [io.kformik.RuleRegistry]. The registry seeds the 7 declarative
 * built-ins (`required`, `minLength`, `maxLength`, `email`, `pattern`, `min`, `max`) and registers
 * one project-specific rule (`serverUniqueCheck`) that calls a suspending "API" check.
 *
 * Backend payload (hand-coded here for the demo; in production this would come from your wire layer):
 *
 *  - `age` → `required`, `min(18)`, `max(60)`
 *  - `username` → `required`, `minLength(3)`, `serverUniqueCheck`
 *  - `email` → `required`, `email`
 *
 * Try `username` = `"taken"` to trigger the async server-uniqueness failure.
 */
@Composable
fun BackendDrivenRulesScreen(onBack: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    var payload by remember { mutableStateOf<BackendForm?>(null) }

    LaunchedEffect(Unit) {
        delay(300)  // simulate the network fetch
        payload = simulatedBackendPayload()
    }

    ShowcaseScaffold(title = "Backend-driven rules", onBack = onBack, snackbarHostState = snackbar) {
        val p = payload
        if (p == null) {
            Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@ShowcaseScaffold
        }

        // Project-specific registry: 7 built-ins (from ruleRegistry()) plus serverUniqueCheck.
        // Aliased so a backend that ships "length_min" still resolves to the canonical minLength.
        val registry = remember {
            ruleRegistry<Map<String, Any?>> {
                register("serverUniqueCheck") { params ->
                    val msg = params.stringOrNull("message") ?: "Already taken"
                    custom("serverUniqueCheck") { value, _ ->
                        val s = value as? String ?: return@custom null
                        if (s.isBlank()) return@custom null
                        delay(250)  // suspending "API" call
                        if (s.equals("taken", ignoreCase = true)) msg else null
                    }
                }
                alias(from = "length_min", to = "minLength")
            }
        }

        // Convert each BackendField into a Kformik Field whose rules resolve through the registry.
        // No hardcoded min/max constants in this composable — everything comes from `p`.
        val fields = remember(p, registry) {
            p.fields.associate { bf ->
                bf.name to Field(
                    type = bf.type,
                    label = bf.label,
                    placeholder = bf.placeholder,
                    required = bf.specs.any { it.name == "required" },
                    rules = { specs(registry, bf.specs) },
                )
            }
        }

        Text("Constraints below were generated from a simulated backend payload.")
        KformikForm(
            fields = fields,
            validateDebounceMs = 300L,
            onSubmit = { v ->
                delay(400)
                snackbar.showSnackbar("Submitted: ${v.entries.joinToString { "${it.key}=${it.value}" }}")
            },
        )
    }
}

// ---------------------------------------------------------------------------- mock backend

private data class BackendField(
    val name: String,
    val type: FieldType,
    val label: String? = null,
    val placeholder: String? = null,
    val specs: List<RuleSpec>,
)

private data class BackendForm(val fields: List<BackendField>)

private fun simulatedBackendPayload(): BackendForm = BackendForm(
    fields = listOf(
        BackendField(
            name = "age",
            type = FieldType.Number(asInt = true),
            label = "Age",
            specs = listOf(
                RuleSpec("required"),
                RuleSpec("min", mapOf("value" to 18, "message" to "Must be 18 or older")),
                RuleSpec("max", mapOf("value" to 60)),
            ),
        ),
        BackendField(
            name = "username",
            type = FieldType.Text,
            label = "Username",
            placeholder = "try \"taken\"",
            specs = listOf(
                RuleSpec("required"),
                RuleSpec("length_min", mapOf("value" to 3)),  // exercise the alias path
                RuleSpec("serverUniqueCheck"),
            ),
        ),
        BackendField(
            name = "email",
            type = FieldType.Email,
            label = "Email",
            specs = listOf(
                RuleSpec("required"),
                RuleSpec("email"),
            ),
        ),
    ),
)
