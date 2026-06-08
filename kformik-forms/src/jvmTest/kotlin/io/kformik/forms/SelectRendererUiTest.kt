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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * UI tests for the built-in Select renderer (DefaultRenderers.kt:389-448). Covers the
 * "first option's value is the implicit default", the explicit-null override, the anchor's
 * displayed label, dropdown item clicks committing the option value while toggling touched,
 * and the v1.9.2 regression where a stale-open dropdown survived a controller rebuild.
 *
 * ExposedDropdownMenuBox renders the chosen option's label inside an OutlinedTextField anchor,
 * so the anchor itself is queryable by that label text — which doubles as the click target for
 * tests that need to open the menu.
 */
@OptIn(ExperimentalTestApi::class)
class SelectRendererUiTest {

    private val countryOptions = listOf(
        SelectOption("us", "USA"),
        SelectOption("uk", "UK"),
    )

    @Test
    fun defaultStoredValue_isFirstOptionValue() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf(
                "country" to Field(type = FieldType.Select(countryOptions), label = "Country"),
            ),
            sink = { captured = it },
        )
        waitForIdle()
        val form = captured!!
        assertEquals("us", form.value("country"))
    }

    @Test
    fun explicitNullInitial_storesNull() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf(
                "country" to Field(
                    type = FieldType.Select(countryOptions),
                    label = "Country",
                    initialValue = null,
                ),
            ),
            sink = { captured = it },
        )
        waitForIdle()
        val form = captured!!
        assertNull(form.value("country"))
        // With value = null, no option matches → currentLabel resolves to "" inside the anchor.
        // Neither option's label is displayed (dropdown closed AND anchor is empty).
        onAllNodesWithText("USA").assertCountEquals(0)
        onAllNodesWithText("UK").assertCountEquals(0)
    }

    @Test
    fun selectedLabel_isRenderedInAnchor() = runComposeUiTest {
        renderHost(
            fields = mapOf(
                "country" to Field(
                    type = FieldType.Select(countryOptions),
                    label = "Country",
                    initialValue = "uk",
                ),
            ),
        )
        waitForIdle()
        // Anchor's OutlinedTextField shows the matching option's label.
        onNodeWithText("UK").assertIsDisplayed()
    }

    @Test
    fun clickAnchor_opensDropdown_andShowsAllOptionLabels() = runComposeUiTest {
        renderHost(
            fields = mapOf(
                "country" to Field(
                    type = FieldType.Select(countryOptions),
                    label = "Country",
                    initialValue = "us",
                ),
            ),
        )
        waitForIdle()
        // Anchor currently shows "USA"; clicking it should expand the dropdown.
        onNodeWithText("USA").performClick()
        waitForIdle()
        // Both option labels should now be findable somewhere on screen (anchor + menu for "USA",
        // menu only for "UK"). Asserting "UK" is displayed proves the menu opened.
        onNodeWithText("UK").assertIsDisplayed()
        // "USA" appears at least once (anchor); when the menu is open it also appears as a menu
        // item — both counts >= 1 are acceptable, but UK >= 1 is the actual open-signal.
        assertTrue(onAllNodesWithText("UK").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun clickingMenuItem_commitsItsValue_andClosesMenu() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf(
                "country" to Field(
                    type = FieldType.Select(countryOptions),
                    label = "Country",
                    initialValue = "us",
                ),
            ),
            sink = { captured = it },
        )
        waitForIdle()
        val form = captured!!
        // Open the dropdown by clicking the anchor (shows "USA").
        onNodeWithText("USA").performClick()
        waitForIdle()
        // Click the "UK" menu item.
        onNodeWithText("UK").performClick()
        waitForIdle()
        assertEquals("uk", form.value("country"))
        assertTrue(form.isTouched("country"), "menu-item click should mark the field touched")
        // Menu closed: "USA" no longer rendered anywhere (anchor now displays "UK").
        onAllNodesWithText("USA").assertCountEquals(0)
    }

    @Test
    fun disabled_blocksOpen() = runComposeUiTest {
        renderHost(
            fields = mapOf(
                "country" to Field(
                    type = FieldType.Select(countryOptions),
                    label = "Country",
                    initialValue = "us",
                    disabled = true,
                ),
            ),
        )
        waitForIdle()
        // Try to open the dropdown — anchor click should be a no-op when disabled.
        onNodeWithText("USA").performClick()
        waitForIdle()
        // "UK" only renders inside the dropdown menu — if the menu is closed it doesn't appear.
        // Anchor displays "USA", so the UK label should not be present anywhere.
        onAllNodesWithText("UK").assertCountEquals(0)
    }

    @Test
    fun programmaticSetFieldValue_updatesAnchorLabel() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf(
                "country" to Field(
                    type = FieldType.Select(countryOptions),
                    label = "Country",
                    initialValue = "us",
                ),
            ),
            sink = { captured = it },
        )
        waitForIdle()
        val form = captured!!
        form.setFieldValue("country", "uk")
        waitForIdle()
        onNodeWithText("UK").assertIsDisplayed()
        // Old anchor label gone.
        onAllNodesWithText("USA").assertCountEquals(0)
    }

    /**
     * Regression: v1.9.2 fix #5. Before the fix, `expanded` was `remember { mutableStateOf(false) }`
     * — keyed on nothing — so a dropdown left open before a controller rebuild stayed open over the
     * freshly-initialized field. After the fix it's `remember(name, form) { … }`, which collapses
     * the stale boolean when the form handle is replaced.
     *
     * The headless harness can't always drive ExposedDropdownMenuBox open via anchor click
     * reliably, so this test verifies the regression via the observable form state: after a
     * controller rebuild the anchor reflects the NEW initialValue (not the previous handle's
     * value), and no stale option labels other than the new anchor's label are on screen.
     */
    @Test
    fun expanded_closesAfterControllerRebuild() = runComposeUiTest {
        // Regression for v1.9.2 fix #5. Before the fix, `expanded` was `remember { mutableStateOf
        // (false) }` — keyed on nothing — so a dropdown left open before a controller rebuild
        // stayed open over the freshly-initialized field. After the fix it's
        // `remember(name, form) { … }`, which collapses the stale boolean when the form swaps.
        //
        // Open the dropdown at seed 0 (anchor click), then bump the seed PROGRAMMATICALLY via
        // the returned MutableState<Int>. A click on the "bump" button would itself dismiss the
        // dropdown (click-outside dismiss is wired by ExposedDropdownMenuBox), which would
        // close the dropdown for the wrong reason and mask the regression. Programmatic state
        // mutation triggers recomposition without generating any pointer event, so the
        // dropdown's dismissal is exclusively the responsibility of the (name, form)-keyed
        // remember slot.
        val ab = listOf(SelectOption("a", "Apple"), SelectOption("b", "Banana"))
        var captured: ComposeFormik<Map<String, Any?>>? = null
        val seed = renderSeeded(
            fields = { s ->
                mapOf(
                    "c" to Field(
                        type = FieldType.Select(ab),
                        label = "C",
                        initialValue = if (s == 0) "a" else "b",
                    ),
                )
            },
            sink = { captured = it },
        )
        waitForIdle()
        // Seed 0: anchor shows "Apple". Click it to open the dropdown — both option labels are
        // now rendered as DropdownMenuItem text, on top of the anchor's "Apple".
        onNodeWithText("Apple").performClick()
        waitForIdle()
        // Sanity: dropdown is open. Apple appears twice (anchor + menu item); Banana once (menu item).
        assertTrue(onAllNodesWithText("Apple").fetchSemanticsNodes().size >= 2)
        assertTrue(onAllNodesWithText("Banana").fetchSemanticsNodes().size >= 1)
        // Bump the seed without a pointer event so the dropdown's dismiss isn't triggered by a
        // tap-outside.
        seed.value = seed.value + 1
        waitForIdle()
        val form = captured!!
        assertEquals("b", form.value("c"))
        // Anchor now shows "Banana".
        onNodeWithText("Banana").assertIsDisplayed()
        // With fix #5 in place: dropdown is closed → "Apple" appears nowhere (anchor is now
        // "Banana", and the closed menu does not emit DropdownMenuItem text into the tree).
        // With fix #5 reverted: dropdown is still open → "Apple" appears once (as a menu item).
        onAllNodesWithText("Apple").assertCountEquals(0)
    }
}
