package io.kformik.forms

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.kformik.compose.ComposeFormik
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * UI tests for the built-in `FieldType.Switch` renderer (DefaultRenderers.kt:341-388).
 *
 * The Switch is visually different from the Checkbox (Role.Switch + SpaceBetween row layout)
 * but stores the same `Boolean` shape and runs the same blur/touch contract. These tests
 * exercise the renderer through KformikForm via the public test host so only the
 * publicly-observable form state (value/error/touched and rendered text) is asserted —
 * keeping the suite robust against internal layout changes.
 */
@OptIn(ExperimentalTestApi::class)
class SwitchRendererUiTest {

    @Test
    fun defaultStored_isFalse_perDefaultValuesContract() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf("s" to Field(type = FieldType.Switch, label = "Notify")),
            sink = { captured = it },
        )
        waitForIdle()
        val form = captured!!
        assertEquals(false, form.value("s"))
    }

    @Test
    fun rowClick_togglesTo_true() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf("s" to Field(type = FieldType.Switch, label = "Notify")),
            sink = { captured = it },
        )
        waitForIdle()
        val form = captured!!
        onNodeWithText("Notify").performClick()
        waitForIdle()
        assertEquals(true, form.value("s"))
    }

    @Test
    fun rowClick_twice_togglesBack() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf("s" to Field(type = FieldType.Switch, label = "Notify")),
            sink = { captured = it },
        )
        waitForIdle()
        val form = captured!!
        onNodeWithText("Notify").performClick(); waitForIdle()
        onNodeWithText("Notify").performClick(); waitForIdle()
        assertEquals(false, form.value("s"))
    }

    @Test
    fun label_renderedWith_optionalAsterisk() = runComposeUiTest {
        renderHost(
            fields = mapOf(
                "req" to Field(type = FieldType.Switch, label = "Accept", required = true),
                "opt" to Field(type = FieldType.Switch, label = "Subscribe", required = false),
            ),
        )
        waitForIdle()
        onNodeWithText("Accept *").assertIsDisplayed()
        onNodeWithText("Subscribe").assertIsDisplayed()
    }

    @Test
    fun helperText_displayed_whenNoError() = runComposeUiTest {
        renderHost(
            fields = mapOf(
                "s" to Field(type = FieldType.Switch, label = "News", helperText = "Get newsletter"),
            ),
        )
        waitForIdle()
        onNodeWithText("Get newsletter").assertIsDisplayed()
    }

    @Test
    fun disabled_blocksToggle() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf(
                "s" to Field(type = FieldType.Switch, label = "Notify", disabled = true, initialValue = false),
            ),
            sink = { captured = it },
        )
        waitForIdle()
        val form = captured!!
        onNodeWithText("Notify").performClick(); waitForIdle()
        val v = form.value("s") as Boolean
        assertFalse(v, "Disabled switch toggled to $v")
    }

    @Test
    fun programmatic_setFieldValue_reflects() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf("s" to Field(type = FieldType.Switch, label = "Notify")),
            sink = { captured = it },
        )
        waitForIdle()
        val form = captured!!
        form.setFieldValue("s", true)
        waitForIdle()
        assertEquals(true, form.value("s"))
    }

    @Test
    fun requiredOnSwitch_withCustomEqualToTrue_failsWhenOff() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf(
                "tos" to Field(
                    type = FieldType.Switch,
                    label = "Accept Terms",
                    required = true,
                    rules = {
                        customValue("equalToTrue") { v ->
                            if (v == true) null else "Must accept terms"
                        }
                    },
                ),
            ),
            sink = { captured = it },
        )
        waitForIdle()
        val form = captured!!
        form.setFieldTouched("tos", true)
        waitForIdle()
        assertNotNull(form.error("tos")) { "Expected non-null error when switch is off" }
    }

    @Test
    fun errorText_replaces_helperText_whenInvalid() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf(
                "tos" to Field(
                    type = FieldType.Switch,
                    label = "Accept Terms",
                    helperText = "Helper hint",
                    initialValue = false,
                    rules = {
                        customValue("equalToTrue") { v ->
                            if (v == true) null else "Must accept terms"
                        }
                    },
                ),
            ),
            sink = { captured = it },
        )
        waitForIdle()
        val form = captured!!
        // Pre-touch: helper is displayed, error is gated to null.
        onNodeWithText("Helper hint").assertIsDisplayed()
        assertNull(form.error("tos"))
        // Touch surfaces the error; renderer's `when` swaps helper for error Text.
        form.setFieldTouched("tos", true)
        waitForIdle()
        onNodeWithText("Must accept terms").assertIsDisplayed()
        // Renderer swaps the supportingText Text composable: when error != null the helper Text
        // is not emitted at all (not just hidden), so the node literally does not exist in the
        // semantics tree. `assertIsNotDisplayed` requires the node to exist; use `assertDoesNotExist`.
        onNodeWithText("Helper hint").assertDoesNotExist()
    }
}
