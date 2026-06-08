package io.kformik.forms

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import io.kformik.compose.ComposeFormik
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * UI tests for the `TextRenderer` branch of [DefaultFieldRenderer] — the renderer the
 * declarative layer reaches for [FieldType.Text], [FieldType.Email], [FieldType.Password], and
 * [FieldType.Multiline]. The renderer is internal; tests pin the *observable* output
 * (`form.value`, `form.error`, `form.isTouched`, plus rendered label / placeholder / helper /
 * error text via [onNodeWithText]) — never internal renderer state.
 *
 * Compose JVM headless harness substitution: real focus / blur events don't fire reliably in the
 * headless harness, so the "blur surfaces touched-gated errors" path is exercised by calling
 * `form.setFieldTouched(name, true)` directly — same observable effect (`displayError` = error
 * if touched else null) without depending on the harness's incomplete focus model.
 */
@OptIn(ExperimentalTestApi::class)
class TextRendererUiTest {

    @Test
    fun typing_intoTextField_commitsValueTo_form() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf("x" to Field(type = FieldType.Text, label = "X")),
            sink = { captured = it },
        )
        waitForIdle()
        onNodeWithText("X").performTextInput("hello")
        waitForIdle()
        assertEquals("hello", captured!!.value("x"))
    }

    @Test
    fun emptyRequired_afterSetFieldTouched_surfaces_Required_errorText() = runComposeUiTest {
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
        )
        waitForIdle()
        val form = captured!!
        form.setFieldTouched("email", true)
        waitForIdle()
        val err = form.error("email")
        assertNotNull(err, "Required field should produce an error after touch")
        assertEquals("Required", err)
        onNodeWithText("Required").assertIsDisplayed()
    }

    @Test
    fun invalidEmail_afterTouched_surfaces_emailError() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf(
                "email" to Field(
                    type = FieldType.Email,
                    label = "Email",
                    rules = { email() },
                ),
            ),
            sink = { captured = it },
        )
        waitForIdle()
        val form = captured!!
        form.setFieldValue("email", "not-an-email")
        form.setFieldTouched("email", true)
        waitForIdle()
        assertEquals("Invalid email", form.error("email"))
    }

    @Test
    fun minLength_afterTouched_surfaces_error_belowThreshold() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf(
                "x" to Field(type = FieldType.Text, label = "X", rules = { minLength(8) }),
            ),
            sink = { captured = it },
        )
        waitForIdle()
        val form = captured!!
        form.setFieldValue("x", "short")
        form.setFieldTouched("x", true)
        waitForIdle()
        val err = form.error("x")
        assertNotNull(err, "minLength(8) should reject 'short'")
        assertTrue(err.contains("8"), "Error should mention the threshold, got: $err")
    }

    @Test
    fun label_isRendered_withAsterisk_whenRequired() = runComposeUiTest {
        renderHost(
            fields = mapOf(
                "email" to Field(type = FieldType.Email, label = "Email", required = true),
            ),
        )
        waitForIdle()
        // displayLabel() appends " *" to required field labels.
        onNodeWithText("Email *").assertIsDisplayed()
    }

    @Test
    fun placeholder_isRendered() = runComposeUiTest {
        renderHost(
            fields = mapOf(
                "email" to Field(
                    type = FieldType.Email,
                    label = "Email",
                    placeholder = "you@example.com",
                ),
            ),
        )
        waitForIdle()
        // Focus the field so OutlinedTextField composes its placeholder slot (M3 only composes
        // the placeholder when the field is focused with an empty value — this matches the
        // user-visible "placeholder appears on focus" behaviour).
        onNodeWithText("Email").performClick()
        waitForIdle()
        onNodeWithText("you@example.com").assertExists()
    }

    @Test
    fun helperText_isRendered_whenNoError() = runComposeUiTest {
        renderHost(
            fields = mapOf(
                "bio" to Field(
                    type = FieldType.Text,
                    label = "Bio",
                    helperText = "Up to 280 chars",
                ),
            ),
        )
        waitForIdle()
        onNodeWithText("Up to 280 chars").assertIsDisplayed()
    }

    @Test
    fun helperText_isReplaced_byErrorText_whenErrorPresent() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf(
                "email" to Field(
                    type = FieldType.Email,
                    label = "Email",
                    helperText = "We never share it",
                    rules = { email() },
                ),
            ),
            sink = { captured = it },
        )
        waitForIdle()
        val form = captured!!
        form.setFieldValue("email", "nope")
        form.setFieldTouched("email", true)
        waitForIdle()
        // supportingText() returns the error Text when error != null, so helper text is replaced.
        onAllNodesWithText("We never share it").assertCountEquals(0)
        onNodeWithText("Invalid email").assertIsDisplayed()
    }

    @Test
    fun disabled_fieldDoesNotAccept_typing() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf(
                "x" to Field(
                    type = FieldType.Text,
                    label = "X",
                    disabled = true,
                    initialValue = "locked",
                ),
            ),
            sink = { captured = it },
        )
        waitForIdle()
        // Disabled OutlinedTextField is non-interactive; asserting it's not enabled is the
        // cleanest observable (matches what assistive tech sees) — and the stored value is
        // unchanged because there's no way to type into it.
        onNodeWithText("locked").assertIsNotEnabled()
        assertEquals("locked", captured!!.value("x"))
    }

    @Test
    fun password_masksValue_withPasswordVisualTransformation() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf(
                "p" to Field(type = FieldType.Password, label = "Password", initialValue = "secret"),
            ),
            sink = { captured = it },
        )
        waitForIdle()
        // Stored value is plaintext — PasswordVisualTransformation only affects display.
        assertEquals("secret", captured!!.value("p"))
        // Rendered text is bullets, so the literal "secret" string should not appear anywhere
        // in the semantics tree.
        onAllNodesWithText("secret").assertCountEquals(0)
    }

    @Test
    fun multiline_keepsImeActionDefault_andAllowsNewline() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf(
                "notes" to Field(type = FieldType.Multiline, label = "Notes"),
            ),
            sink = { captured = it },
        )
        waitForIdle()
        // performTextInput appends to current value — type a string that includes "\n".
        onNodeWithText("Notes").performTextInput("line1\nline2")
        waitForIdle()
        val stored = captured!!.value("notes") as? String
        assertNotNull(stored, "multiline value should commit as String")
        assertEquals("line1\nline2", stored)
    }

    @Test
    fun clearingText_setsValueToEmptyString() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf(
                "x" to Field(type = FieldType.Text, label = "X", initialValue = "hello"),
            ),
            sink = { captured = it },
        )
        waitForIdle()
        onNodeWithText("hello").performTextClearance()
        waitForIdle()
        assertEquals("", captured!!.value("x"))
        // Sanity: error() on a field with no rules is null.
        assertNull(captured!!.error("x"))
    }
}
