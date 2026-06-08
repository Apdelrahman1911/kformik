package io.kformik.forms

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import io.kformik.FormikErrors
import io.kformik.FormikTouched
import io.kformik.compose.ComposeFormik
import kotlinx.coroutines.delay
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * UI-level contract tests for every public parameter of the top-level [KformikForm] composable
 * (signature at `KformikForm.kt:72-101`). One test (or a small group) per parameter, pinning the
 * observable behaviour through public API only — `ComposeFormik.value` / `error` / `isTouched` /
 * `controller.state.value` / rendered text nodes.
 *
 * These complement the per-renderer tests in `*RendererUiTest` files: those pin the widget-level
 * output; this file pins the top-level wiring of `KformikForm` itself (validate-async pipeline,
 * footer slot, custom submit button, render override, reinitialization, error/touched hydration).
 */
@OptIn(ExperimentalTestApi::class)
class KformikFormUiTest {

    // ------------------------------------------------------------------- extraValidate

    @Test
    fun extraValidate_addsCrossFieldError() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf(
                "pwd" to Field(type = FieldType.Password, label = "Password"),
                "cnf" to Field(type = FieldType.Password, label = "Confirm"),
            ),
            sink = { captured = it },
            extraValidate = { v ->
                if (v["pwd"] != v["cnf"]) {
                    FormikErrors(mapOf("cnf" to "Passwords don't match"))
                } else {
                    FormikErrors.Empty
                }
            },
        )
        waitForIdle()
        val form = captured!!
        form.setFieldValue("pwd", "abc12345")
        form.setFieldValue("cnf", "different")
        form.setFieldTouched("cnf", true)
        waitUntil(timeoutMillis = 2_000) { form.error("cnf") == "Passwords don't match" }
        assertEquals("Passwords don't match", form.error("cnf"))
    }

    // ------------------------------------------------------------------- validateAsync / validateDebounceMs

    @Test
    fun validateAsync_runsAfterSync_andSurfacesErrors() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf("username" to Field(type = FieldType.Text, label = "Username")),
            sink = { captured = it },
            validateAsync = { v ->
                if ((v["username"] as String?) == "taken") {
                    FormikErrors(mapOf("username" to "Taken"))
                } else {
                    FormikErrors.Empty
                }
            },
        )
        waitForIdle()
        val form = captured!!
        form.setFieldValue("username", "taken")
        waitUntil(timeoutMillis = 2_000) { form.error("username") == "Taken" }
        assertEquals("Taken", form.error("username"))
    }

    @Test
    fun validateAsync_isDebouncedBy_validateDebounceMs() = runComposeUiTest {
        // We count async-validator invocations to confirm the debounce window collapsed back-to-back
        // setFieldValue calls into fewer runs than keystrokes. Exact count is timing-dependent on the
        // headless harness — the load-bearing assertion is "not every setFieldValue produced a run".
        var captured: ComposeFormik<Map<String, Any?>>? = null
        var runs = 0
        renderHost(
            fields = mapOf("u" to Field(type = FieldType.Text, label = "U")),
            sink = { captured = it },
            validateDebounceMs = 100L,
            validateAsync = { _ ->
                runs += 1
                FormikErrors.Empty
            },
        )
        waitForIdle()
        val form = captured!!
        form.setFieldValue("u", "a")
        form.setFieldValue("u", "ab")
        form.setFieldValue("u", "abc")
        waitUntil(timeoutMillis = 2_000) { form.value("u") == "abc" }
        // Give the debounce a chance to fire.
        waitUntil(timeoutMillis = 2_000) { runs >= 1 || form.value("u") != "abc" }
        assertTrue(runs < 3, "validateDebounceMs should collapse rapid edits; saw runs=$runs")
    }

    // ------------------------------------------------------------------- renderOverride

    @Test
    fun renderOverride_replacesDefault_forNamedField() = runComposeUiTest {
        setContent {
            KformikForm(
                fields = mapOf(
                    "x" to Field(type = FieldType.Text, label = "X-LABEL"),
                    "y" to Field(type = FieldType.Text, label = "Y-LABEL"),
                ),
                onSubmit = { },
                renderOverride = { name, _, _ ->
                    if (name == "x") {
                        Text("CUSTOM-X-OVERRIDE")
                        true
                    } else {
                        false
                    }
                },
            )
        }
        waitForIdle()
        onNodeWithText("CUSTOM-X-OVERRIDE").assertIsDisplayed()
        // The default renderer for "x" would put "X-LABEL" into the semantics tree via
        // OutlinedTextField — the override returned true so that path is skipped.
        onAllNodesWithText("X-LABEL").assertCountEquals(0)
        // "y" still uses the default renderer so its label is present.
        onNodeWithText("Y-LABEL").assertIsDisplayed()
    }

    @Test
    fun renderOverride_returningFalse_fallsThroughToDefault() = runComposeUiTest {
        setContent {
            KformikForm(
                fields = mapOf("x" to Field(type = FieldType.Text, label = "X-LABEL")),
                onSubmit = { },
                renderOverride = { _, _, _ -> false },
            )
        }
        waitForIdle()
        // Default OutlinedTextField path still renders the label.
        onNodeWithText("X-LABEL").assertIsDisplayed()
    }

    // ------------------------------------------------------------------- submitButton

    @Test
    fun submitButton_customSlot_renders_andRespectsIsValid() = runComposeUiTest {
        // validateOnMount=true ensures the required-empty error is in state on first render so
        // isValid is observably false (otherwise the form starts with empty errors → isValid=true
        // → the test would see an enabled button before the user touched anything).
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf(
                "x" to Field(type = FieldType.Text, label = "X", required = true),
            ),
            sink = { captured = it },
            validateOnMount = true,
            submitButton = { onSubmit, isValid, _ ->
                Button(onClick = onSubmit, enabled = isValid) {
                    Text("MY-SUBMIT")
                }
            },
        )
        waitForIdle()
        val form = captured!!
        // Wait for the mount-time validation to populate the required error.
        waitUntil(timeoutMillis = 2_000) { form.error("x") != null }
        onNodeWithText("MY-SUBMIT").assertIsNotEnabled()
        form.setFieldValue("x", "filled")
        waitUntil(timeoutMillis = 2_000) { form.error("x") == null }
        onNodeWithText("MY-SUBMIT").assertIsEnabled()
    }

    @Test
    fun submitButton_default_says_Submit_then_Submitting_duringSubmit() = runComposeUiTest {
        // The default button reads its label from `isSubmitting` (KformikForm.kt:229). We hold the
        // submission for long enough to observe the "Submitting…" text, then release.
        var captured: ComposeFormik<Map<String, Any?>>? = null
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        renderHost(
            fields = mapOf("x" to Field(type = FieldType.Text, label = "X")),
            sink = { captured = it },
            onSubmit = { gate.await() },
        )
        waitForIdle()
        onNodeWithText("Submit").assertIsDisplayed()
        val form = captured!!
        form.submit()
        // While in flight, the default submit button text flips to "Submitting…".
        waitUntil(timeoutMillis = 2_000) {
            onAllNodesWithText("Submitting…").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Submitting…").assertIsDisplayed()
        // Release the in-flight submission; label returns to "Submit".
        gate.complete(Unit)
        waitUntil(timeoutMillis = 2_000) {
            onAllNodesWithText("Submit").fetchSemanticsNodes().isNotEmpty() &&
                onAllNodesWithText("Submitting…").fetchSemanticsNodes().isEmpty()
        }
        onNodeWithText("Submit").assertIsDisplayed()
    }

    // ------------------------------------------------------------------- footerSlot

    @Test
    fun footerSlot_isRendered_betweenFieldsAndSubmit() = runComposeUiTest {
        renderHost(
            fields = mapOf("x" to Field(type = FieldType.Text, label = "X")),
            footerSlot = { Text("FOOTER") },
        )
        waitForIdle()
        onNodeWithText("FOOTER").assertIsDisplayed()
    }

    @Test
    fun footerSlot_canRead_formState() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf("x" to Field(type = FieldType.Text, label = "X")),
            sink = { captured = it },
            footerSlot = { form ->
                // Subscribe to live state via form.state (the @Composable getter that wraps the
                // controller StateFlow in collectAsState) so the slot recomposes when submitCount
                // changes. Reading form.controller.state.value directly would not subscribe.
                val s = form.state.value
                Text("count=${s.submitCount}")
            },
        )
        waitForIdle()
        onNodeWithText("count=0").assertIsDisplayed()
        val form = captured!!
        form.submit()
        waitUntil(timeoutMillis = 2_000) {
            onAllNodesWithText("count=1").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("count=1").assertIsDisplayed()
    }

    // ------------------------------------------------------------------- enableReinitialize

    @Test
    fun enableReinitialize_on_replacesUserEdits_whenInitialValuesChange() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        val seed = renderSeeded(
            fields = { s ->
                mapOf(
                    "x" to Field(
                        type = FieldType.Text,
                        label = "X",
                        initialValue = if (s == 0) "alpha" else "bravo",
                    ),
                )
            },
            sink = { captured = it },
            enableReinitialize = true,
        )
        waitForIdle()
        val form = captured!!
        form.setFieldValue("x", "edited")
        waitUntil(timeoutMillis = 2_000) { form.value("x") == "edited" }
        // Bump the seed programmatically so we don't dismiss any open UI element (consistent with
        // how StaleStateRegressionTest drives controller rebuilds).
        seed.value = seed.value + 1
        waitForIdle()
        val newForm = captured!!
        waitUntil(timeoutMillis = 2_000) { newForm.value("x") == "bravo" }
        assertEquals("bravo", newForm.value("x"))
        onAllNodesWithText("edited").assertCountEquals(0)
        onNodeWithText("bravo").assertIsDisplayed()
    }

    // ------------------------------------------------------------------- validateOnMount

    @Test
    fun validateOnMount_true_surfacesErrors_atFirstRender_visibleAfterTouch() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf(
                "email" to Field(
                    type = FieldType.Email,
                    label = "Email",
                    required = true,
                    rules = { email() },
                ),
            ),
            sink = { captured = it },
            validateOnMount = true,
        )
        waitForIdle()
        val form = captured!!
        // Mount validation should have run; the empty required email field has an error in state
        // even before the user interacts.
        waitUntil(timeoutMillis = 2_000) { form.error("email") != null }
        assertNotNull(form.error("email"), "validateOnMount should populate errors at first render")
        // Touch to surface it through displayError → rendered Text.
        form.setFieldTouched("email", true)
        waitForIdle()
        onNodeWithText("Required").assertIsDisplayed()
    }

    // ------------------------------------------------------------------- validateOnBlur / validateOnChange

    @Test
    fun validateOnBlur_false_doesNotValidateOnTouched() = runComposeUiTest {
        // With both onChange and onBlur disabled, neither setFieldValue nor setFieldTouched should
        // trigger schema validation — error must stay null even though the value clearly violates
        // minLength(8).
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf(
                "p" to Field(type = FieldType.Text, label = "P", rules = { minLength(8) }),
            ),
            sink = { captured = it },
            validateOnChange = false,
            validateOnBlur = false,
        )
        waitForIdle()
        val form = captured!!
        form.setFieldValue("p", "short")
        form.setFieldTouched("p", true)
        waitForIdle()
        assertNull(form.error("p"), "validateOnBlur=false + validateOnChange=false should suppress validation")
    }

    @Test
    fun validateOnChange_false_doesNotValidateOnSetFieldValue() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf(
                "x" to Field(type = FieldType.Text, label = "X", required = true),
            ),
            sink = { captured = it },
            validateOnChange = false,
            validateOnBlur = false,
        )
        waitForIdle()
        val form = captured!!
        form.setFieldValue("x", "")
        waitForIdle()
        assertNull(form.error("x"), "validateOnChange=false should suppress on-change validation")
    }

    // ------------------------------------------------------------------- onError

    @Test
    fun onError_routesThrowable_from_onSubmit() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        var caught: Throwable? = null
        renderHost(
            fields = mapOf(
                // No rules → form is valid → submit() proceeds into onSubmit and throws.
                "x" to Field(type = FieldType.Text, label = "X"),
            ),
            sink = { captured = it },
            onSubmit = { throw IllegalStateException("oops") },
            onError = { caught = it },
        )
        waitForIdle()
        val form = captured!!
        form.submit()
        waitUntil(timeoutMillis = 2_000) { caught != null }
        assertNotNull(caught)
        assertEquals("oops", caught!!.message)
    }

    // ------------------------------------------------------------------- initialErrors / initialTouched

    @Test
    fun initialErrors_andTouched_surface_atFirstRender() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf("x" to Field(type = FieldType.Text, label = "X")),
            sink = { captured = it },
            initialErrors = FormikErrors(mapOf("x" to "Server error")),
            initialTouched = FormikTouched(mapOf("x" to true)),
        )
        waitForIdle()
        val form = captured!!
        assertEquals("Server error", form.error("x"))
        assertEquals(true, form.isTouched("x"))
    }

    // ------------------------------------------------------------------- initialStatus

    @Test
    fun initialStatus_isReadable_via_controller_state() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf("x" to Field(type = FieldType.Text, label = "X")),
            sink = { captured = it },
            initialStatus = "awaiting",
        )
        waitForIdle()
        val form = captured!!
        assertEquals("awaiting", form.controller.state.value.status)
    }
}
