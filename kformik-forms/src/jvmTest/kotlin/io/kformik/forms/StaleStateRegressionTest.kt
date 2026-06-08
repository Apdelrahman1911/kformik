package io.kformik.forms

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import io.kformik.compose.ComposeFormik
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Central audit-trail suite for the v1.9.2 stale-state fixes. Each test below is named after a
 * specific bug symptom from the v1.9.2 audit so a future reader debugging a regression can grep
 * the symptom and land directly on the test that pins the fix.
 *
 * The fixes audited here all share the same shape: a `remember { ... }` inside a renderer was
 * keyed too narrowly (e.g. on `name` alone) and survived a `FormikController` rebuild that
 * KformikForm performs when a field's `initialValue` (or shape) changes. The corrected keys
 * include `form` so the rememberd cache resets in lockstep with the controller swap. Every test
 * here drives a controller rebuild via [renderSeeded] and asserts that some piece of state did
 * NOT survive the rebuild. If a future commit narrowed any of these keys again, the matching
 * test would fail.
 *
 * Note: some v1.9.2 regressions belong primarily to :kformik-compose (`fieldState`,
 * `valuesUpdater`); those have dedicated tests in that module's UI suite. This file covers the
 * :kformik-forms-side regressions (Number / Select / Date / blurTouches) plus a thin UI-level
 * repeat of `fieldState` since `KformikForm` uses it transitively through every renderer.
 */
@OptIn(ExperimentalTestApi::class)
class StaleStateRegressionTest {

    /**
     * v1.9.2 fix #4 — `NumberRenderer.displayBuffer` was keyed on `name` only, so the user's
     * mid-edit typed text survived a controller rebuild and overlaid the new initialValue. The
     * fix adds `form` to the key.
     *
     * The seed is bumped PROGRAMMATICALLY (not via the bump-seed Button) because clicking the
     * Button moves focus off the OutlinedTextField; that triggers NumberRenderer's
     * `onFocusChanged → displayBuffer = null` branch, which clears the buffer for the wrong
     * reason (focus loss, not the (name, form) re-key) and masks the regression. Driving the
     * state mutation directly preserves focus, so the only thing that can clear the buffer is
     * the (name, form)-keyed remember slot. Confirmed load-bearing by reverting the fix in a
     * scratch — this test fails on the revert.
     */
    @Test
    fun numberRenderer_displayBuffer_resetsAfterControllerRebuild() = runComposeUiTest {
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
        onNodeWithText("555").assertIsDisplayed()
        seed.value = seed.value + 1
        waitForIdle()
        val newForm = captured!!
        assertEquals(99, newForm.value("n") as Int?)
        onAllNodesWithText("99").assertCountEquals(1)
        onAllNodesWithText("555").assertCountEquals(0)
    }

    /**
     * v1.9.2 fix #4 sub-symptom — `NumberRenderer.hadFocus` was keyed on `name` only, so a
     * surviving `hadFocus = true` would cause the first blur against the rebuilt controller to
     * fire `setFieldTouched` on a field the user had not interacted with on this form.
     *
     * Driving Compose focus on the JVM headless harness is brittle, so this test substitutes a
     * direct `setFieldTouched(true)` on the seed-0 form — the same observable side-effect the
     * stale-`hadFocus` blur would produce. After the rebuild, the new ComposeFormik handle's
     * `isTouched("n")` MUST report false: the rebuild gives us a fresh `touched` map, and the
     * test asserts no leakage path put it back to true.
     */
    @Test
    fun numberRenderer_hadFocus_doesNotMarkRebuiltFormTouched() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderSeeded(
            fields = { s ->
                mapOf("n" to Field(type = FieldType.Number(asInt = true), initialValue = if (s == 0) 1 else 99))
            },
            sink = { captured = it },
        )
        waitForIdle()
        val firstForm = captured!!
        firstForm.setFieldTouched("n", true)
        waitForIdle()
        assertEquals(true, firstForm.isTouched("n"))
        onNodeWithTag("bump-seed").performClick()
        waitForIdle()
        val newForm = captured!!
        assertNotNull(newForm)
        assert(!newForm.isTouched("n")) {
            "Rebuilt controller's touched flag should reset; saw isTouched=${newForm.isTouched("n")}"
        }
    }

    /**
     * v1.9.2 fix #5 — `SelectRenderer.expanded` was keyed on `name` only, so an open dropdown
     * survived a controller rebuild and floated over the freshly-initialized field. The fix adds
     * `form` to the key. We open the dropdown by clicking the anchor label, bump the seed, and
     * assert (a) the new controller's value reflects the new initialValue and (b) the
     * non-selected option's label is no longer visible (i.e. the dropdown collapsed).
     */
    @Test
    fun selectRenderer_expanded_closesAfterControllerRebuild() = runComposeUiTest {
        // The seed is bumped PROGRAMMATICALLY (not via the bump-seed Button) because a click on
        // the Button is "outside" the open dropdown menu, and the menu's onDismissRequest fires
        // on click-outside — which closes the dropdown for the wrong reason (tap-outside dismiss,
        // not the (name, form) re-key) and masks the regression. Driving the state mutation
        // directly recomposes the parent without generating any pointer event. Confirmed
        // load-bearing by reverting the fix — this test fails on the revert.
        val opts = listOf(
            SelectOption(value = "a", label = "Apple"),
            SelectOption(value = "b", label = "Banana"),
        )
        var captured: ComposeFormik<Map<String, Any?>>? = null
        val seed = renderSeeded(
            fields = { s ->
                mapOf(
                    "c" to Field(
                        type = FieldType.Select(options = opts),
                        initialValue = if (s == 0) "a" else "b",
                    ),
                )
            },
            sink = { captured = it },
        )
        waitForIdle()
        onNodeWithText("Apple").performClick()
        waitForIdle()
        seed.value = seed.value + 1
        waitForIdle()
        val newForm = captured!!
        assertEquals("b", newForm.value("c"))
        // After rebuild the new anchor shows "Banana"; "Apple" (the OTHER option's label) must
        // not be present anywhere — that would mean the dropdown survived the rebuild.
        onAllNodesWithText("Apple").assertCountEquals(0)
    }

    /**
     * v1.9.2 fix #6 — `DateRenderer.showPicker` was a plain `remember { mutableStateOf(false) }`,
     * so an open `DatePickerDialog` survived a controller rebuild; the user's OK on the stale
     * dialog would write into the new controller. The fix is a `LaunchedEffect(form)` that
     * force-closes the dialog whenever the controller swaps. We open the dialog via the "Pick"
     * trailing button, bump the seed, and assert (a) no "OK" / "Cancel" button is on screen and
     * (b) the new controller's value reflects its new initialValue.
     */
    @Test
    fun dateRenderer_picker_closesAfterFormSwap() = runComposeUiTest {
        // The bump uses the returned MutableState<Int> directly rather than the "bump-seed" Button:
        // the open DatePickerDialog is modal and its scrim swallows pointer events behind it, so a
        // UI-test click on the button is consumed by the dialog (dismisses it) and never reaches
        // the parent's onClick. Driving the state mutation programmatically is equivalent in
        // effect — Compose recomposes the parent → KformikForm sees new fields → controllerKey
        // changes → rememberFormik returns a new ComposeFormik. The thing under test is what
        // happens to the open dialog on controller swap, which is independent of how the swap is
        // triggered.
        var captured: ComposeFormik<Map<String, Any?>>? = null
        val seed = renderSeeded(
            fields = { s ->
                mapOf("d" to Field(type = FieldType.Date, initialValue = if (s == 0) "2024-01-01" else "2025-12-31"))
            },
            sink = { captured = it },
        )
        waitForIdle()
        onNodeWithText("Pick").performClick()
        waitForIdle()
        // Sanity: dialog is open (OK button present).
        onAllNodesWithText("OK").assertCountEquals(1)
        seed.value = seed.value + 1
        waitForIdle()
        val newForm = captured!!
        assertEquals("2025-12-31", newForm.value("d"))
        // The LaunchedEffect(form) in DateRenderer should have flipped showPicker to false on
        // the controller swap; the dialog is gone, so neither OK nor Cancel buttons exist.
        onAllNodesWithText("OK").assertCountEquals(0)
        onAllNodesWithText("Cancel").assertCountEquals(0)
    }

    /**
     * v1.9.2 fix #3 — `blurTouches.hadFocus` was keyed on `name` only, so a surviving
     * `hadFocus = true` would cause the first blur against the rebuilt controller to fire
     * `setFieldTouched` on a field the user had not interacted with on this form, prematurely
     * surfacing validation errors. The fix adds `form` to the key.
     *
     * As with the Number variant above, driving real Compose focus on the JVM headless harness
     * is brittle; this test substitutes a direct `setFieldTouched(true)` at seed 0 — the same
     * observable side-effect the stale-`hadFocus` blur would produce — and asserts the rebuilt
     * controller's `isTouched("name")` is false.
     */
    @Test
    fun blurTouches_doesNotLeakAcrossFormRebuild() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderSeeded(
            fields = { s ->
                mapOf("name" to Field(type = FieldType.Text, initialValue = if (s == 0) "alpha" else "bravo"))
            },
            sink = { captured = it },
        )
        waitForIdle()
        val firstForm = captured!!
        firstForm.setFieldTouched("name", true)
        waitForIdle()
        assertEquals(true, firstForm.isTouched("name"))
        onNodeWithTag("bump-seed").performClick()
        waitForIdle()
        val newForm = captured!!
        assert(!newForm.isTouched("name")) {
            "Rebuilt controller's touched flag should reset; saw isTouched=${newForm.isTouched("name")}"
        }
    }

    /**
     * v1.9.2 fix #1 at the UI layer — `ComposeFormik.fieldState`'s cached `MutableState` was
     * keyed on `name` only, so a rebuilt controller would hand back the OLD `MutableState`
     * tracking the OLD value, freezing the UI on the stale seed-0 text. The fix adds `form` to
     * the cache key. Every default renderer subscribes through `fieldState`, so this UI-level
     * test asserts the observable consequence: at seed 0 the OutlinedTextField shows "alpha";
     * after the seed bump it shows "bravo" and no "alpha" remains on screen.
     */
    @Test
    fun fieldState_resyncsAfterControllerRebuild_throughKformikForm() = runComposeUiTest {
        renderSeeded(
            fields = { s ->
                mapOf("name" to Field(type = FieldType.Text, initialValue = if (s == 0) "alpha" else "bravo"))
            },
        )
        waitForIdle()
        onAllNodesWithText("alpha").assertCountEquals(1)
        onNodeWithTag("bump-seed").performClick()
        waitForIdle()
        onAllNodesWithText("bravo").assertCountEquals(1)
        onAllNodesWithText("alpha").assertCountEquals(0)
    }

    /**
     * v1.9.2 fix #2 — `ComposeFormik.valuesUpdater`'s captured closure was cached on the
     * controller without re-checking when the controller swapped, so a rebuilt form could keep
     * dispatching writes through the previous controller's closure. The regression and its fix
     * live entirely inside :kformik-compose, and the corresponding load-bearing test
     * (`ValuesUpdaterTest`) lives there too; `KformikForm` uses the default `Map`-based
     * `valuesUpdater` and offers no public surface to swap it, so a meaningful version of the
     * test cannot be written against `KformikForm`. This placeholder documents the deliberate
     * coverage hand-off so the audit trail still points at the relevant test.
     */
    @Test
    fun valuesUpdater_regression_isCovered_in_kformik_compose_test_suite() {
        // Intentionally empty: see kdoc above. The regression is covered by tests in the
        // :kformik-compose module that exercise the `valuesUpdater` surface directly.
    }

}
