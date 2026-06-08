package io.kformik.forms

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import io.kformik.compose.ComposeFormik
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Smoke test for [TestHelpers]. If this fails, every per-renderer / cross-cutting suite written on
 * top of `renderHost` / `renderSeeded` is also broken — so a single failure here points the
 * triage reader straight at the harness rather than at a downstream renderer.
 *
 * Asserts three load-bearing helper contracts:
 *  1. `renderHost` actually renders the form (Submit button is on screen).
 *  2. The `renderOverride` sink captures the live [ComposeFormik] handle (proves the
 *     undocumented-but-stable "renderOverride returning false falls through to default" contract
 *     that every other helper-driven test relies on).
 *  3. `renderHost` accepts a `footerSlot` and renders it (proves slot params survive the helper).
 */
@OptIn(ExperimentalTestApi::class)
class TestHelpersSmokeTest {

    @Test
    fun renderHost_rendersKformikForm_andCapturesLiveForm() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf(
                "email" to Field(type = FieldType.Email, label = "Email", required = true),
            ),
            sink = { captured = it },
        )
        waitForIdle()
        // KformikForm's default submitButton renders the text "Submit" — proves the form composed.
        onNodeWithText("Submit").assertIsDisplayed()
        // Sink fires inside renderOverride for each field on first composition — proves the
        // helper's "renderOverride returning false falls through" trick captures the form handle
        // without modifying any library code.
        assertNotNull(captured, "renderOverride sink should have captured the live form")
        // The captured handle is functional — read the initial value through the public API.
        assertEquals("", captured!!.value("email"))
    }

    @Test
    fun renderHost_footerSlot_isRendered() = runComposeUiTest {
        renderHost(
            fields = mapOf("name" to Field(type = FieldType.Text)),
            footerSlot = { Text("FOOTER_MARKER") },
        )
        waitForIdle()
        onNodeWithText("FOOTER_MARKER").assertIsDisplayed()
    }

    @Test
    fun renderSeeded_returnsSeedState_andRendersBumpButton() = runComposeUiTest {
        val seed = renderSeeded(
            fields = { s -> mapOf("name" to Field(type = FieldType.Text, initialValue = "v$s")) },
        )
        waitForIdle()
        // The bump button is part of the helper's harness — its presence proves the seeded host
        // wired the rebuild affordance correctly.
        onNodeWithText("bump").assertIsDisplayed()
        // Initial seed is 0.
        assertEquals(0, seed.value)
    }
}
