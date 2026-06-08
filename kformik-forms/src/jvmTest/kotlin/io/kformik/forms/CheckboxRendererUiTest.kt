package io.kformik.forms

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.kformik.compose.ComposeFormik
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * UI tests for the built-in [FieldType.Checkbox] renderer (DefaultRenderers.kt → CheckboxRenderer).
 *
 * Locks down the public observable contract: stored type is `Boolean`, the whole row is a single
 * toggle target (Modifier.toggleable on Row), labels carry the trailing `*` for required fields,
 * disabled state blocks gestures while preserving form state, and validation errors surface
 * through the same `form.error(name)` channel as every other renderer. No internal-state probing —
 * every assertion goes through `form.value` / `form.error` / `form.controller` or the rendered
 * text nodes.
 */
@OptIn(ExperimentalTestApi::class)
class CheckboxRendererUiTest {

    @Test
    fun unchecked_initialState_storedValueIsFalse() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf("c" to Field(type = FieldType.Checkbox, label = "Accept")),
            sink = { captured = it },
        )
        waitForIdle()
        val form = captured!!
        assertEquals(false, form.value("c"))
    }

    @Test
    fun rowClick_togglesState_to_true() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf("c" to Field(type = FieldType.Checkbox, label = "Accept TOS")),
            sink = { captured = it },
        )
        waitForIdle()
        onNodeWithText("Accept TOS").performClick()
        waitForIdle()
        assertEquals(true, captured!!.value("c"))
    }

    @Test
    fun rowClick_twice_togglesBackTo_false() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf("c" to Field(type = FieldType.Checkbox, label = "Accept TOS")),
            sink = { captured = it },
        )
        waitForIdle()
        onNodeWithText("Accept TOS").performClick()
        waitForIdle()
        onNodeWithText("Accept TOS").performClick()
        waitForIdle()
        assertEquals(false, captured!!.value("c"))
    }

    @Test
    fun requiredEqualTo_true_failsWhen_unchecked_afterTouched() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf(
                "c" to Field(
                    type = FieldType.Checkbox,
                    label = "Accept TOS",
                    required = true,
                    rules = {
                        customValue(name = "equalTo") { v ->
                            if (v == true) null else "You must accept"
                        }
                    },
                ),
            ),
            sink = { captured = it },
        )
        waitForIdle()
        val form = captured!!
        form.setFieldTouched("c", true)
        waitForIdle()
        assertEquals("You must accept", form.error("c"))
    }

    @Test
    fun requiredEqualTo_true_passesWhen_checked() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf(
                "c" to Field(
                    type = FieldType.Checkbox,
                    label = "Accept TOS",
                    required = true,
                    rules = {
                        customValue(name = "equalTo") { v ->
                            if (v == true) null else "You must accept"
                        }
                    },
                ),
            ),
            sink = { captured = it },
        )
        waitForIdle()
        val form = captured!!
        onNodeWithText("Accept TOS *").performClick()
        waitForIdle()
        assertEquals(true, form.value("c"))
        assertNull(form.error("c"))
        assert(form.controller.isValid.value) {
            "form should be valid after checking: errors=${form.controller.state.value.errors}"
        }
    }

    @Test
    fun label_isRendered() = runComposeUiTest {
        renderHost(
            fields = mapOf("c" to Field(type = FieldType.Checkbox, label = "Accept TOS")),
        )
        waitForIdle()
        onNodeWithText("Accept TOS").assertIsDisplayed()
    }

    @Test
    fun label_withRequiredAsterisk() = runComposeUiTest {
        renderHost(
            fields = mapOf(
                "c" to Field(type = FieldType.Checkbox, label = "Accept TOS", required = true),
            ),
        )
        waitForIdle()
        onNodeWithText("Accept TOS *").assertIsDisplayed()
    }

    @Test
    fun disabled_blocksToggle() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf(
                "c" to Field(
                    type = FieldType.Checkbox,
                    label = "Accept TOS",
                    disabled = true,
                    initialValue = false,
                ),
            ),
            sink = { captured = it },
        )
        waitForIdle()
        onNodeWithText("Accept TOS").performClick()
        waitForIdle()
        val actual = captured!!.value("c")
        assert(actual == false) {
            "disabled checkbox toggled despite Modifier.toggleable(enabled = !field.disabled): $actual"
        }
    }

    @Test
    fun programmatic_setFieldValue_true_reflectsInUi() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf("c" to Field(type = FieldType.Checkbox, label = "Accept TOS")),
            sink = { captured = it },
        )
        waitForIdle()
        val form = captured!!
        assertEquals(false, form.value("c"))
        form.setFieldValue("c", true)
        waitForIdle()
        assertEquals(true, form.value("c"))
        // The Row carries Role.Checkbox via Modifier.toggleable — Compose maps that to an
        // isToggleable() / isOn() semantics node. Asserting the (toggleable && isOn) node exists
        // proves the renderer wired the boolean back into the toggleable's `value` (not just into
        // form state).
        onNode(isToggleable()).assertIsDisplayed()
        onNode(isToggleable() and isOn()).assertIsDisplayed()
    }

    @Test
    fun errorText_isRendered_when_validationFails() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf(
                "c" to Field(
                    type = FieldType.Checkbox,
                    label = "Accept TOS",
                    required = true,
                    rules = {
                        customValue(name = "equalTo") { v ->
                            if (v == true) null else "You must accept"
                        }
                    },
                ),
            ),
            sink = { captured = it },
        )
        waitForIdle()
        captured!!.setFieldTouched("c", true)
        waitForIdle()
        onNodeWithText("You must accept").assertIsDisplayed()
        // Sanity: the failing-state isValid is observable through the controller path the task
        // spec calls out — proves the renderer's surfaced error and the form-level validity flag
        // stay in sync.
        assertFalse(captured!!.controller.isValid.value)
    }
}
