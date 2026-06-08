package io.kformik.forms

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import io.kformik.compose.ComposeFormik
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * UI contract for [DefaultRenderers]' `NumberRenderer`. Covers parse-and-commit (asInt true/false,
 * comma/dot decimal, non-numeric fallback), schema-driven min/max errors, programmatic
 * round-tripping of `Int` / `Double` into canonical display, and the v1.9.2 controller-rebuild
 * reset of the renderer-local displayBuffer + hadFocus flags (now keyed on `(name, form)`).
 * Assertions go through public `ComposeFormik` accessors and rendered text only.
 */
@OptIn(ExperimentalTestApi::class)
class NumberRendererUiTest {

    @Test
    fun asInt_true_parses_validInt() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf("age" to Field(type = FieldType.Number(asInt = true), label = "Age")),
            sink = { captured = it },
        )
        waitForIdle()
        onNodeWithText("Age").performTextInput("42")
        waitForIdle()
        val form = captured!!
        assertEquals(42, form.value("age") as Int?)
    }

    @Test
    fun asInt_true_empty_commits_null() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf("age" to Field(type = FieldType.Number(asInt = true), label = "Age")),
            sink = { captured = it },
        )
        waitForIdle()
        onNodeWithText("Age").performTextInput("7")
        waitForIdle()
        onNodeWithText("Age").performTextClearance()
        waitForIdle()
        assertNull(captured!!.value("age"))
    }

    @Test
    fun asInt_true_nonNumeric_commits_null_butDisplayShowsTypedText() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf("age" to Field(type = FieldType.Number(asInt = true), label = "Age")),
            sink = { captured = it },
        )
        waitForIdle()
        onNodeWithText("Age").performTextInput("abc")
        waitForIdle()
        // Stored: null (parseIntOrNull failed). Buffer keeps "abc" visible for the user to correct.
        assertNull(captured!!.value("age"))
        onNodeWithText("abc").assertIsDisplayed()
    }

    @Test
    fun asInt_false_acceptsCommaAsDecimalSeparator() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf("price" to Field(type = FieldType.Number(asInt = false), label = "Price")),
            sink = { captured = it },
        )
        waitForIdle()
        onNodeWithText("Price").performTextInput("1,5")
        waitForIdle()
        assertEquals(1.5, captured!!.value("price") as Double?)
    }

    @Test
    fun asInt_false_acceptsDotAsDecimalSeparator() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf("price" to Field(type = FieldType.Number(asInt = false), label = "Price")),
            sink = { captured = it },
        )
        waitForIdle()
        onNodeWithText("Price").performTextInput("1.5")
        waitForIdle()
        assertEquals(1.5, captured!!.value("price") as Double?)
    }

    @Test
    fun min_belowBound_afterTouched_surfaces_error() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf(
                "age" to Field(
                    type = FieldType.Number(asInt = true),
                    label = "Age",
                    initialValue = 5,
                    rules = { min(10) },
                ),
            ),
            sink = { captured = it },
        )
        waitForIdle()
        val form = captured!!
        form.setFieldTouched("age", true)
        waitForIdle()
        val err = form.error("age")
        assertEquals("Must be at least 10", err, "Expected min(10) violation message, got: $err")
    }

    @Test
    fun max_aboveBound_afterTouched_surfaces_error() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf(
                "age" to Field(
                    type = FieldType.Number(asInt = true),
                    label = "Age",
                    initialValue = 200,
                    rules = { max(100) },
                ),
            ),
            sink = { captured = it },
        )
        waitForIdle()
        val form = captured!!
        form.setFieldTouched("age", true)
        waitForIdle()
        val err = form.error("age")
        assertEquals("Must be at most 100", err, "Expected max(100) violation message, got: $err")
    }

    @Test
    fun programmatic_setFieldValue_double_roundTrips_canonical() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf("x" to Field(type = FieldType.Number(asInt = false), label = "X")),
            sink = { captured = it },
        )
        waitForIdle()
        captured!!.setFieldValue("x", 0.1)
        waitForIdle()
        // Canonical: Double.toString() → "0.1". Buffer is null, so the field renders canonical.
        onNodeWithText("0.1").assertIsDisplayed()
    }

    @Test
    fun programmatic_setFieldValue_int_renders_as_naturalString() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf("x" to Field(type = FieldType.Number(asInt = true), label = "X")),
            sink = { captured = it },
        )
        waitForIdle()
        captured!!.setFieldValue("x", 5)
        waitForIdle()
        onNodeWithText("5").assertIsDisplayed()
    }

    @Test
    fun nullInitialValue_displaysEmptyString() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf(
                "x" to Field(
                    type = FieldType.Number(asInt = true),
                    label = "Count",
                    initialValue = null,
                ),
            ),
            sink = { captured = it },
        )
        waitForIdle()
        assertNull(captured!!.value("x"))
        // The renderer maps null → "" (NOT the string "null"). Verify the literal "null" text is
        // absent from the rendered tree — the OutlinedTextField's display is empty.
        assertEquals(
            0,
            onAllNodesWithText("null").fetchSemanticsNodes().size,
            "null initialValue must render as empty string, never the literal 'null'",
        )
    }

    @Test
    fun displayBuffer_resetsAfter_controllerRebuild() = runComposeUiTest {
        // Regression for v1.9.2 fix #4: displayBuffer is now `remember(name, form)` so a new
        // ComposeFormik instance flushes the buffer (pre-fix the stale typed text leaked over
        // the new initialValue).
        //
        // The seed is bumped PROGRAMMATICALLY (not via a Button click) because clicking the bump
        // button moves focus off the text field; that triggers NumberRenderer's
        // onFocusChanged → displayBuffer = null branch, which clears the buffer for the WRONG
        // reason (focus loss, not the (name, form) re-key) and would mask the regression. Driving
        // the state mutation directly preserves the field's focus while the controller rebuilds —
        // so the only thing that can clear the buffer is the (name, form)-keyed remember slot.
        var captured: ComposeFormik<Map<String, Any?>>? = null
        val seed = renderSeeded(
            fields = { s ->
                mapOf(
                    "n" to Field(
                        type = FieldType.Number(asInt = true),
                        label = "N",
                        initialValue = if (s == 0) null else 99,
                    ),
                )
            },
            sink = { captured = it },
        )
        waitForIdle()
        onNodeWithText("N").performTextInput("555")
        waitForIdle()
        // Buffer holds "555" (stored value is parsed Int 555); display shows "555".
        onNodeWithText("555").assertIsDisplayed()
        seed.value = seed.value + 1
        waitForIdle()
        // After the rebuild: the new controller's canonical value "99" displays, AND the typed
        // "555" buffer is GONE. If fix #4 regressed (displayBuffer keyed on name only), the
        // OutlinedTextField would still show "555" and the assertCountEquals(0) below would fail.
        onNodeWithText("99").assertIsDisplayed()
        assertEquals(
            0,
            onAllNodesWithText("555").fetchSemanticsNodes().size,
            "stale displayBuffer leaked across controller rebuild — v1.9.2 fix #4 regressed",
        )
        assertEquals(99, captured!!.value("n") as Int?)
    }

    @Test
    fun hadFocus_doesNotLeakAcross_controllerRebuild() = runComposeUiTest {
        // Regression for v1.9.2 fix #4 sub-symptom: hadFocus is now re-keyed on `(name, form)`
        // so a fresh controller starts with no phantom "touched-on-next-blur" pending. Headless
        // JVM cannot drive real focus — we substitute the public-observable side effect:
        // isTouched on the fresh controller is false even after the old one was touched.
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderSeeded(
            fields = { s ->
                mapOf(
                    "n" to Field(
                        type = FieldType.Number(asInt = true),
                        label = "N",
                        initialValue = if (s == 0) 1 else 99,
                    ),
                )
            },
            sink = { captured = it },
        )
        waitForIdle()
        captured!!.setFieldTouched("n", true)
        waitForIdle()
        assertTrue(captured!!.isTouched("n"), "seed-0 controller should be touched after setFieldTouched")
        onNodeWithText("bump").performClick()
        waitForIdle()
        assertFalse(
            captured!!.isTouched("n"),
            "fresh controller after rebuild reported isTouched=true — touched leaked across rebuild",
        )
    }
}
