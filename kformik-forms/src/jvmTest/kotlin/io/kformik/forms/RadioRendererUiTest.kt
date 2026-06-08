package io.kformik.forms

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.kformik.compose.ComposeFormik
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * UI tests for [FieldType.Radio]'s default renderer (DefaultRenderers.kt:450-507).
 *
 * The renderer is a `selectableGroup` Column with one Row per option; each Row carries
 * `Modifier.selectable(role = Role.RadioButton)` and renders the option's label as a `Text`.
 * Tests therefore drive selection by `onNodeWithText(label).performClick()` and verify only the
 * publicly-observable side-effects: `form.value(name)`, `form.error(name)`, `form.isTouched(name)`
 * and the rendered label / error / helper text nodes. Internal selection-state, focus tracking,
 * and group semantics are intentionally NOT asserted — they belong to Material 3, not Kformik.
 */
@OptIn(ExperimentalTestApi::class)
class RadioRendererUiTest {

    private val themeOptions = listOf(
        SelectOption("light", "Light"),
        SelectOption("dark", "Dark"),
    )

    @Test
    fun defaultStored_isFirstOptionValue() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf("theme" to Field(type = FieldType.Radio(themeOptions), label = "Theme")),
            sink = { captured = it },
        )
        waitForIdle()
        val form = captured!!
        assertEquals("light", form.value("theme"))
    }

    @Test
    fun explicitNullInitial_storesNull() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf(
                "theme" to Field(
                    type = FieldType.Radio(themeOptions),
                    label = "Theme",
                    initialValue = null,
                ),
            ),
            sink = { captured = it },
        )
        waitForIdle()
        val form = captured!!
        assertNull(form.value("theme"))
    }

    @Test
    fun clickingOption_commits_andTouches() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf("theme" to Field(type = FieldType.Radio(themeOptions), label = "Theme")),
            sink = { captured = it },
        )
        waitForIdle()
        val form = captured!!
        onNodeWithText("Dark").performClick()
        waitForIdle()
        assertEquals("dark", form.value("theme"))
        assertTrue(form.isTouched("theme"), "Selecting an option must mark the field touched")
    }

    @Test
    fun clickingDifferentOption_replaces() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf("theme" to Field(type = FieldType.Radio(themeOptions), label = "Theme")),
            sink = { captured = it },
        )
        waitForIdle()
        val form = captured!!
        onNodeWithText("Dark").performClick(); waitForIdle()
        onNodeWithText("Light").performClick(); waitForIdle()
        assertEquals("light", form.value("theme"))
    }

    @Test
    fun optionLabels_areRendered() = runComposeUiTest {
        renderHost(
            fields = mapOf("theme" to Field(type = FieldType.Radio(themeOptions), label = "Theme")),
        )
        waitForIdle()
        onNodeWithText("Light").assertIsDisplayed()
        onNodeWithText("Dark").assertIsDisplayed()
    }

    @Test
    fun label_renderedAboveOptions_withAsterisk_whenRequired() = runComposeUiTest {
        renderHost(
            fields = mapOf(
                "theme" to Field(
                    type = FieldType.Radio(themeOptions),
                    label = "Theme",
                    required = true,
                ),
            ),
        )
        waitForIdle()
        onNodeWithText("Theme *").assertIsDisplayed()
    }

    @Test
    fun disabled_blocksSelection() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf(
                "theme" to Field(
                    type = FieldType.Radio(themeOptions),
                    label = "Theme",
                    disabled = true,
                    initialValue = "light",
                ),
            ),
            sink = { captured = it },
        )
        waitForIdle()
        val form = captured!!
        onNodeWithText("Dark").performClick()
        waitForIdle()
        assertEquals(
            "light",
            form.value("theme"),
            "Disabled radio group accepted a click: value moved to ${form.value("theme")}",
        )
    }

    @Test
    fun errorText_displayed_whenValidationFails() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf(
                "theme" to Field(
                    type = FieldType.Radio(themeOptions),
                    label = "Theme",
                    required = true,
                    rules = {
                        custom("test") { _, _ -> "Bad" }
                    },
                ),
            ),
            sink = { captured = it },
        )
        waitForIdle()
        val form = captured!!
        form.setFieldTouched("theme", true)
        waitForIdle()
        assertNotNull(form.error("theme")) { "Expected validation error after touching field" }
        onNodeWithText("Bad").assertIsDisplayed()
    }

    @Test
    fun helperText_displayed_whenNoError() = runComposeUiTest {
        renderHost(
            fields = mapOf(
                "theme" to Field(
                    type = FieldType.Radio(themeOptions),
                    label = "Theme",
                    helperText = "helper",
                ),
            ),
        )
        waitForIdle()
        onNodeWithText("helper").assertIsDisplayed()
    }

    @Test
    fun programmatic_setFieldValue_reflectsInSelection() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf("theme" to Field(type = FieldType.Radio(themeOptions), label = "Theme")),
            sink = { captured = it },
        )
        waitForIdle()
        val form = captured!!
        form.setFieldValue("theme", "dark")
        waitForIdle()
        assertEquals("dark", form.value("theme"))
        // Labels of both options remain rendered; the renderer keys RadioButton.selected on the
        // bound value, so the public state change implies the visual selection change without
        // needing to peek at internal selected-state.
        onNodeWithText("Dark").assertIsDisplayed()
        onNodeWithText("Light").assertIsDisplayed()
    }
}
