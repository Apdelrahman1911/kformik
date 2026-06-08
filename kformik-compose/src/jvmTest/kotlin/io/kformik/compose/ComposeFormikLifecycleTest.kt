package io.kformik.compose

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.delay
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lifecycle tests for `rememberFormik` / [ComposeFormik] that need a live Compose runtime: scope
 * teardown on leaving composition (rejects mutations, cancels in-flight `launch { … }` blocks and
 * debounced validation), per-field flow re-subscription across `key` bumps, async `onSubmit` round
 * trips, and `onError` routing under the real scope. [ComposeFormikTest] skips these because its
 * pure-JVM `UnconfinedTestDispatcher` doesn't model "scope cancellation on leave".
 */
@OptIn(ExperimentalTestApi::class)
class ComposeFormikLifecycleTest {

    @Test
    fun controllerDisposed_whenParentLeavesComposition() = runComposeUiTest {
        val sink = arrayOfNulls<ComposeFormik<Map<String, Any?>>>(1)
        setContent {
            var show by remember { mutableStateOf(true) }
            if (show) {
                val form = rememberFormik(
                    initialValues = mapOf<String, Any?>("x" to "initial"),
                    onSubmit = { _, _ -> },
                )
                sink[0] = form
            }
            Button(
                onClick = { show = false },
                modifier = Modifier.testTag("hide"),
            ) { Text("hide") }
        }
        waitForIdle()
        val form = assertNotNull(sink[0], "form should have been captured")
        val before = form.controller.state.value.values["x"]
        onNodeWithTag("hide").performClick()
        waitForIdle()
        // Scope cancelled by leaving composition → fire-and-forget setter no-ops.
        form.setFieldValue("x", "after-dispose")
        waitForIdle()
        assertEquals(before, form.controller.state.value.values["x"], "disposed form must not mutate")
    }

    @Test
    fun launchBlock_isCancelled_whenParentLeavesComposition() = runComposeUiTest {
        val sink = arrayOfNulls<ComposeFormik<Map<String, Any?>>>(1)
        setContent {
            var show by remember { mutableStateOf(true) }
            if (show) {
                val form = rememberFormik(
                    initialValues = mapOf<String, Any?>("x" to "initial"),
                    onSubmit = { _, _ -> },
                )
                sink[0] = form
            }
            Button(
                onClick = { show = false },
                modifier = Modifier.testTag("hide"),
            ) { Text("hide") }
        }
        waitForIdle()
        val form = assertNotNull(sink[0])
        val before = form.controller.state.value.values["x"]
        onNodeWithTag("hide").performClick()
        waitForIdle()
        // After disposal: launch a NEW block on the cancelled scope. ComposeFormik.launch routes
        // through `scope.launch { … }` (RememberFormik.kt:186); on a cancelled scope, the launch
        // is created in cancelled state and the block never runs — so no late write arrives.
        form.launch { setFieldValue("x", "late") }
        Thread.sleep(150)
        waitForIdle()
        assertEquals(before, form.controller.state.value.values["x"], "launch on cancelled scope must not write")
    }

    @Test
    fun fieldState_keyChange_reconnects_toNewControllerFlow() = runComposeUiTest {
        setContent {
            var k by remember { mutableStateOf(0) }
            val form = rememberFormik(
                initialValues = mapOf<String, Any?>("x" to "v$k"),
                onSubmit = { _, _ -> },
                key = k,
            )
            val x by form.fieldState("x")
            Text(text = (x.value as? String).orEmpty(), modifier = Modifier.testTag("x"))
            Button(onClick = { k += 1 }, modifier = Modifier.testTag("bump")) { Text("bump") }
        }
        waitForIdle()
        onNodeWithTag("x").assertTextEquals("v0")
        onNodeWithTag("bump").performClick()
        waitForIdle()
        // After a `key` bump rememberFormik rebuilds the controller from the new initialValues.
        // fieldState's `remember(name, controller)` then re-subscribes to the new fieldFlow,
        // so the reader must see the new initial value rather than the old controller's last value.
        onNodeWithTag("x").assertTextEquals("v1")
    }

    @Test
    fun multipleObservers_perField_recompose_independently() = runComposeUiTest {
        var aRecomps = 0
        var bRecomps = 0
        setContent {
            val form = rememberFormik(
                initialValues = mapOf<String, Any?>("email" to ""),
                onSubmit = { _, _ -> },
            )
            EmailRow(form, "email-a") { aRecomps++ }
            EmailRow(form, "email-b") { bRecomps++ }
            Button(
                onClick = { form.setFieldValue("email", "x") },
                modifier = Modifier.testTag("change"),
            ) { Text("change") }
        }
        waitForIdle()
        val baseA = aRecomps
        val baseB = bRecomps
        onNodeWithTag("change").performClick()
        waitForIdle()
        assertTrue(aRecomps > baseA, "row A should have recomposed: $baseA -> $aRecomps")
        assertTrue(bRecomps > baseB, "row B should have recomposed: $baseB -> $bRecomps")
    }

    @Test
    fun submitButton_invocation_doesNotCrash_when_onSubmit_async() = runComposeUiTest {
        val sink = arrayOfNulls<ComposeFormik<Map<String, Any?>>>(1)
        setContent {
            val form = rememberFormik(
                initialValues = mapOf<String, Any?>("x" to "v"),
                onSubmit = { _, actions -> delay(50); actions.setStatus("done") },
            )
            sink[0] = form
        }
        waitForIdle()
        val form = assertNotNull(sink[0])
        form.submit()
        waitUntil(timeoutMillis = 2_000) { form.controller.state.value.submitCount == 1 }
        waitUntil(timeoutMillis = 2_000) { form.controller.state.value.status == "done" }
        assertEquals("done", form.controller.state.value.status)
    }

    @Test
    fun resetForm_runsOn_form_scope_restores_initialValues() = runComposeUiTest {
        val sink = arrayOfNulls<ComposeFormik<Map<String, Any?>>>(1)
        setContent {
            val form = rememberFormik(
                initialValues = mapOf<String, Any?>("x" to "initial"),
                onSubmit = { _, _ -> },
            )
            sink[0] = form
        }
        waitForIdle()
        val form = assertNotNull(sink[0])
        form.setFieldValue("x", "edited")
        waitUntil(timeoutMillis = 2_000) { form.controller.state.value.values["x"] == "edited" }
        form.resetForm()
        waitUntil(timeoutMillis = 2_000) { form.controller.state.value.values["x"] == "initial" }
        assertEquals("initial", form.controller.state.value.values["x"])
    }

    @Test
    fun launch_routesThrowable_to_onError_when_set() = runComposeUiTest {
        var caught: Throwable? = null
        val sink = arrayOfNulls<ComposeFormik<Map<String, Any?>>>(1)
        setContent {
            val form = rememberFormik(
                initialValues = mapOf<String, Any?>("x" to ""),
                onSubmit = { _, _ -> },
                onError = { t -> caught = t },
            )
            sink[0] = form
        }
        waitForIdle()
        val form = assertNotNull(sink[0])
        form.launch { throw IllegalStateException("ui-boom") }
        waitUntil(timeoutMillis = 2_000) { caught != null }
        assertTrue(caught is IllegalStateException)
        assertEquals("ui-boom", caught!!.message)
    }

    @Test
    fun setFieldValue_inflightSchedule_is_cancelled_byForm_disposal() = runComposeUiTest {
        val sink = arrayOfNulls<ComposeFormik<Map<String, Any?>>>(1)
        setContent {
            var show by remember { mutableStateOf(true) }
            if (show) {
                val form = rememberFormik(
                    initialValues = mapOf<String, Any?>("x" to ""),
                    onSubmit = { _, _ -> },
                    validateDebounceMs = 30L,
                    validateAsync = { _ ->
                        // Long enough that disposal definitely interrupts it.
                        delay(500)
                        io.kformik.buildErrors { put("x", "Async") }
                    },
                )
                sink[0] = form
                // Schedule a write that triggers debounced async validation while still mounted.
                LaunchedEffect(Unit) { form.setFieldValue("x", "edited") }
            }
            Button(
                onClick = { show = false },
                modifier = Modifier.testTag("hide"),
            ) { Text("hide") }
        }
        waitForIdle()
        val form = assertNotNull(sink[0])
        // Tear the host down before the debounced + async validation can resolve.
        onNodeWithTag("hide").performClick()
        waitForIdle()
        val before = form.controller.state.value
        Thread.sleep(700)
        val after = form.controller.state.value
        // No late mutation arrives: the validator never gets to set the "Async" error post-disposal.
        assertNull(after.errors["x"], "post-disposal validate must not write an error")
        assertEquals(before.values["x"], after.values["x"], "no late value mutation post-disposal")
    }

    /**
     * Pins the `onReset` parameter wiring through the Compose adapter. `rememberFormik` routes
     * `onReset` into `FormikConfig` via `onResetState` (rememberUpdatedState) at
     * `RememberFormik.kt:272,296`. Without this test, a future refactor could silently drop the
     * wire-up (the lambda is captured but never invoked) and only the core `ResetTest` would catch
     * it — and only for the controller layer, not the Compose adapter.
     */
    @Test
    fun onReset_callback_firesOnResetForm() = runComposeUiTest {
        var resetCount = 0
        var resetSnapshot: Map<String, Any?>? = null
        val sink = arrayOfNulls<ComposeFormik<Map<String, Any?>>>(1)
        setContent {
            val form = rememberFormik(
                initialValues = mapOf<String, Any?>("email" to "initial@example.com"),
                onSubmit = { _, _ -> },
                onReset = { values, _ ->
                    resetCount++
                    resetSnapshot = values
                },
            )
            sink[0] = form
        }
        waitForIdle()
        val form = assertNotNull(sink[0])
        // Edit, then reset.
        form.setFieldValue("email", "edited@example.com")
        waitForIdle()
        form.resetForm()
        waitUntil(timeoutMillis = 2_000) { resetCount == 1 }
        assertEquals(1, resetCount, "onReset must fire exactly once per resetForm() through the Compose adapter")
        // onReset receives the PRE-RESET (current) snapshot per FormikController.handleReset
        // contract — see ResetTest.resetForm_invokesOnReset_withCurrentValues.
        assertEquals(
            "edited@example.com",
            resetSnapshot?.get("email"),
            "onReset must receive the pre-reset values snapshot",
        )
        // Post-reset state is back to the initial baseline.
        assertEquals("initial@example.com", form.value("email"))
    }
}

@Composable
private fun EmailRow(form: ComposeFormik<Map<String, Any?>>, tag: String, onRecomp: () -> Unit) {
    onRecomp()
    val email by form.fieldState("email")
    Text(text = (email.value as? String).orEmpty(), modifier = Modifier.testTag(tag))
}
