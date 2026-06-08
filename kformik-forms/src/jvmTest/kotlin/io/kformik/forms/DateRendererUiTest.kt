package io.kformik.forms

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
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
 * UI tests for [FieldType.Date]'s default renderer (DefaultRenderers.kt:517-595).
 *
 * Scope is deliberately narrow: the renderer displays a read-only OutlinedTextField with a "Pick"
 * trailing TextButton that opens a Material 3 DatePickerDialog. We do NOT exercise day-cell
 * picking — M3 DatePicker internals shift between Compose versions and a brittle "tap the 15th"
 * test would lock us to one version. Instead we assert on the public observables (form.value /
 * form.isTouched / form.error) plus the dialog's stable "OK" / "Cancel" button text, and we cover
 * the v1.9.2 regression where a controller rebuild while the dialog is open would let stale OK
 * write into the new controller.
 */
@OptIn(ExperimentalTestApi::class)
class DateRendererUiTest {

    @Test
    fun defaultStored_isNull_perDefaultValuesContract() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf("dob" to Field(type = FieldType.Date, label = "DOB")),
            sink = { captured = it },
        )
        waitForIdle()
        val form = captured!!
        assertNull(form.value("dob"), "Date default stored value must be null per v1.8.1+ contract")
        // No ISO date should appear anywhere — defensive against accidental "0" / "null" leaks.
        onAllNodesWithText("0000-00-00").assertCountEquals(0)
    }

    @Test
    fun initialValueIsoString_isDisplayed() = runComposeUiTest {
        renderHost(
            fields = mapOf(
                "dob" to Field(type = FieldType.Date, label = "DOB", initialValue = "2025-06-08"),
            ),
        )
        waitForIdle()
        onNodeWithText("2025-06-08").assertIsDisplayed()
    }

    @Test
    fun programmaticSetFieldValue_isoString_displaysInField() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf("dob" to Field(type = FieldType.Date, label = "DOB")),
            sink = { captured = it },
        )
        waitForIdle()
        val form = captured!!
        form.setFieldValue("dob", "2024-01-15")
        waitForIdle()
        onNodeWithText("2024-01-15").assertIsDisplayed()
    }

    @Test
    fun programmaticSetFieldValue_null_clearsDisplay() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf(
                "dob" to Field(type = FieldType.Date, label = "DOB", initialValue = "2024-01-15"),
            ),
            sink = { captured = it },
        )
        waitForIdle()
        val form = captured!!
        onNodeWithText("2024-01-15").assertIsDisplayed()
        form.setFieldValue("dob", null)
        waitForIdle()
        // After clearing the value, the displayed ISO date is gone.
        onAllNodesWithText("2024-01-15").assertCountEquals(0)
        assertNull(form.value("dob"))
    }

    @Test
    fun pickButton_isRendered() = runComposeUiTest {
        renderHost(
            fields = mapOf("dob" to Field(type = FieldType.Date, label = "DOB")),
        )
        waitForIdle()
        onNodeWithText("Pick").assertIsDisplayed()
    }

    @Test
    fun clickingPickButton_opensDialog() = runComposeUiTest {
        renderHost(
            fields = mapOf("dob" to Field(type = FieldType.Date, label = "DOB")),
        )
        waitForIdle()
        onNodeWithText("Pick").performClick()
        waitForIdle()
        // M3 DatePickerDialog renders these two buttons; their presence proves the dialog opened.
        onNodeWithText("OK").assertIsDisplayed()
        onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun clickingCancel_closesDialog_withoutChangingValue() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf(
                "dob" to Field(type = FieldType.Date, label = "DOB", initialValue = "2024-01-15"),
            ),
            sink = { captured = it },
        )
        waitForIdle()
        val form = captured!!
        onNodeWithText("Pick").performClick()
        waitForIdle()
        onNodeWithText("Cancel").assertIsDisplayed()
        onNodeWithText("Cancel").performClick()
        waitForIdle()
        // Dialog closed: OK/Cancel buttons no longer in the tree.
        onAllNodesWithText("OK").assertCountEquals(0)
        onAllNodesWithText("Cancel").assertCountEquals(0)
        // Value unchanged.
        assertEquals("2024-01-15", form.value("dob"))
    }

    @Test
    fun disabled_blocksOpen_viaTrailingPickButton() = runComposeUiTest {
        renderHost(
            fields = mapOf(
                "dob" to Field(type = FieldType.Date, label = "DOB", disabled = true),
            ),
        )
        waitForIdle()
        // Click attempt against the disabled "Pick" button is a no-op; the renderer's onClick guard
        // (`if (!field.disabled) showPicker = true`) also defends against pointer-input edge cases.
        onNodeWithText("Pick").performClick()
        waitForIdle()
        // Dialog must NOT have opened — neither button text should appear.
        onAllNodesWithText("OK").assertCountEquals(0)
        onAllNodesWithText("Cancel").assertCountEquals(0)
    }

    @Test
    fun requiredField_afterTouched_surfaces_RequiredError() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf(
                "dob" to Field(type = FieldType.Date, label = "DOB", required = true),
            ),
            sink = { captured = it },
        )
        waitForIdle()
        val form = captured!!
        form.setFieldTouched("dob", true)
        waitForIdle()
        waitUntil(timeoutMillis = 2_000) { form.error("dob") != null }
        val err = form.error("dob")
        assertNotNull(err, "required=true on a null Date value should produce a validation error")
        assertTrue(form.isTouched("dob"))
    }

    @Test
    fun picker_closesAfterControllerRebuild() = runComposeUiTest {
        // Regression for v1.9.2 fix #6: rebuilding the FormikController while the date picker
        // dialog is open must close the dialog (LaunchedEffect(form) { showPicker = false }).
        // Otherwise OK on the stale dialog would write the previously-selected date into the new
        // controller, overwriting whatever value the new controller was just seeded with.
        //
        // The seed bump uses the returned MutableState<Int> directly rather than clicking the
        // "bump" button, because the open DatePickerDialog is modal — its scrim swallows all
        // pointer events behind it, so a UI-test click on the bump button doesn't reach the button
        // (it dismisses the dialog instead). Driving the state mutation programmatically is
        // equivalent in effect (Compose recomposes the parent → KformikForm sees new fields →
        // controllerKey changes → rememberFormik returns a new ComposeFormik).
        var captured: ComposeFormik<Map<String, Any?>>? = null
        val seed = renderSeeded(
            fields = { s ->
                mapOf(
                    "dob" to Field(
                        type = FieldType.Date,
                        label = "DOB",
                        initialValue = if (s == 0) "2024-01-01" else "2025-06-08",
                    ),
                )
            },
            sink = { captured = it },
        )
        waitForIdle()
        // Open the dialog under seed=0.
        onNodeWithText("Pick").performClick()
        waitForIdle()
        onNodeWithText("OK").assertIsDisplayed()
        // Bump the seed programmatically → controller rebuilds (the controllerKey embeds
        // initialValue, see KformikForm.kt:112-114).
        seed.value = seed.value + 1
        waitForIdle()
        // After rebuild: dialog must be closed — this is the load-bearing assertion for the v1.9.2
        // LaunchedEffect(form) { showPicker = false } regression guard.
        onAllNodesWithText("OK").assertCountEquals(0)
        onAllNodesWithText("Cancel").assertCountEquals(0)
        // The post-rebuild captured handle is the new controller — its stored value is the new
        // initialValue, proving the picker close happened against the new controller (not the
        // stale one).
        assertEquals("2025-06-08", captured!!.value("dob"))
    }
}
