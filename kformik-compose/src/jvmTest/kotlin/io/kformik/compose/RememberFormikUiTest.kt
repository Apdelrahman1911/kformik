package io.kformik.compose

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.kformik.buildErrors
import org.junit.Test

/**
 * Compose UI tests for `rememberFormik` / `ComposeFormik` `@Composable` surfaces. Covers
 * `state` / `dirty` / `isValid` / `fieldState` accessors and the `enableReinitialize` flow —
 * areas the pure-JVM unit tests in [ComposeFormikTest] explicitly skip because they require a
 * Compose runtime host.
 *
 * Powered by Compose Multiplatform's headless JVM test harness (`runComposeUiTest`). No emulator
 * or display server required — runs on the same CI runners as the rest of `:kformik-compose`'s
 * jvmTest task.
 *
 * Drives mutations via Buttons (deterministic single click → single setFieldValue) rather than
 * BasicTextField, to avoid caret-position quirks and `performTextInput`-vs-coroutine timing.
 */
@OptIn(ExperimentalTestApi::class)
class RememberFormikUiTest {

    @Test
    fun state_reflectsLatestValues_afterSetFieldValue() = runComposeUiTest {
        setContent {
            val form = rememberFormik(
                initialValues = mapOf<String, Any?>("email" to "initial@example.com"),
                onSubmit = { _, _ -> },
            )
            val state by form.state
            Text(
                text = state.values["email"] as String,
                modifier = Modifier.testTag("email-display"),
            )
            Button(
                onClick = { form.setFieldValue("email", "changed@example.com") },
                modifier = Modifier.testTag("change-btn"),
            ) { Text("change") }
        }
        onNodeWithTag("email-display").assertTextEquals("initial@example.com")
        onNodeWithTag("change-btn").performClick()
        waitForIdle()
        onNodeWithTag("email-display").assertTextEquals("changed@example.com")
    }

    @Test
    fun fieldState_recomposesPerField_independentOfOtherFields() = runComposeUiTest {
        var emailRecomps = 0
        var passwordRecomps = 0
        setContent {
            val form = rememberFormik(
                initialValues = mapOf<String, Any?>(
                    "email" to "",
                    "password" to "",
                ),
                onSubmit = { _, _ -> },
            )
            EmailRow(form) { emailRecomps++ }
            PasswordRow(form) { passwordRecomps++ }
            Button(
                onClick = { form.setFieldValue("email", "x") },
                modifier = Modifier.testTag("change-email"),
            ) { Text("change-email") }
        }
        waitForIdle()
        val baseEmail = emailRecomps
        val basePwd = passwordRecomps
        onNodeWithTag("change-email").performClick()
        waitForIdle()
        // Email row recomposed; password row did NOT.
        assert(emailRecomps > baseEmail) {
            "Email row didn't recompose on email change: $baseEmail → $emailRecomps"
        }
        assert(passwordRecomps == basePwd) {
            "Password row recomposed on unrelated email change — fieldState fanout leak: $basePwd → $passwordRecomps"
        }
    }

    @Test
    fun enableReinitialize_resyncsBaseline_whenInitialValuesChange() = runComposeUiTest {
        setContent {
            var initial by remember { mutableStateOf("first@example.com") }
            val form = rememberFormik(
                initialValues = mapOf<String, Any?>("email" to initial),
                enableReinitialize = true,
                onSubmit = { _, _ -> },
            )
            val state by form.state
            Text(
                text = state.values["email"] as String,
                modifier = Modifier.testTag("email"),
            )
            Button(
                onClick = { initial = "second@example.com" },
                modifier = Modifier.testTag("bump"),
            ) { Text("bump") }
        }
        onNodeWithTag("email").assertTextEquals("first@example.com")
        onNodeWithTag("bump").performClick()
        waitForIdle()
        onNodeWithTag("email").assertTextEquals("second@example.com")
    }

    @Test
    fun isValid_andDirty_recomposeAsFieldsChange() = runComposeUiTest {
        setContent {
            val form = rememberFormik(
                initialValues = mapOf<String, Any?>("email" to ""),
                validate = { v ->
                    buildErrors {
                        if ((v["email"] as String).isBlank()) put("email", "Required")
                    }
                },
                validateOnMount = true,
                onSubmit = { _, _ -> },
            )
            val dirty by form.dirty
            val isValid by form.isValid
            Text(text = "dirty=$dirty", modifier = Modifier.testTag("dirty"))
            Text(text = "isValid=$isValid", modifier = Modifier.testTag("valid"))
            Button(
                onClick = { form.setFieldValue("email", "ok@example.com") },
                modifier = Modifier.testTag("fill"),
            ) { Text("fill") }
        }
        waitForIdle()
        onNodeWithTag("dirty").assertTextEquals("dirty=false")
        onNodeWithTag("valid").assertTextEquals("isValid=false")
        onNodeWithTag("fill").performClick()
        waitForIdle()
        onNodeWithTag("dirty").assertTextEquals("dirty=true")
        onNodeWithTag("valid").assertTextEquals("isValid=true")
    }

    @Test
    fun fieldState_value_andError_surfaceAfterTouch() = runComposeUiTest {
        setContent {
            val form = rememberFormik(
                initialValues = mapOf<String, Any?>("email" to ""),
                validate = { v ->
                    buildErrors {
                        if ((v["email"] as String).isBlank()) put("email", "Required")
                    }
                },
                validateOnMount = true,
                onSubmit = { _, _ -> },
            )
            val email by form.fieldState("email")
            Text(
                text = email.displayError ?: "none",
                modifier = Modifier.testTag("display-err"),
            )
            Button(
                onClick = { form.setFieldTouched("email", true) },
                modifier = Modifier.testTag("touch"),
            ) { Text("touch") }
        }
        waitForIdle()
        // Pre-touch: error exists in state but displayError gates it to null.
        onNodeWithTag("display-err").assertTextEquals("none")
        onNodeWithTag("touch").performClick()
        waitForIdle()
        // Post-touch: the binding's displayError now surfaces "Required" through the per-field
        // flow (proving fieldState's displayError computation refreshes on touched changes too,
        // not only on value/error changes).
        onNodeWithTag("display-err").assertTextEquals("Required")
    }
}

@Composable
private fun EmailRow(form: ComposeFormik<Map<String, Any?>>, onRecomp: () -> Unit) {
    onRecomp()
    val email by form.fieldState("email")
    Text(
        text = (email.value as? String).orEmpty(),
        modifier = Modifier.testTag("email"),
    )
}

@Composable
private fun PasswordRow(form: ComposeFormik<Map<String, Any?>>, onRecomp: () -> Unit) {
    onRecomp()
    val password by form.fieldState("password")
    Text(
        text = (password.value as? String).orEmpty(),
        modifier = Modifier.testTag("password"),
    )
}
