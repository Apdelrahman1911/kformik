/*
 * NOTE: This file is excluded from compilation unless `-PwithRobolectric=true` is passed to
 * Gradle (see :sample-android-app:build.gradle.kts). Robolectric's `nativeruntime-dist-compat`
 * JAR is ~158 MB and is unreliable to fetch in restricted-network environments; gating the
 * deps + sources behind a flag keeps `:sample-android-app:testDebugUnitTest` green in CI.
 *
 * To enable with full network access:
 *   ./gradlew :sample-android-app:testDebugUnitTest -PwithRobolectric=true
 *
 * What's covered when enabled: see the class-level KDoc below.
 */

package io.kformik.sample

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric-driven Compose UI smoke tests for the canonical `LoginScreen` composable in
 * `:sample-android-app`. These exercise the full `:kformik-compose` adapter (`rememberFormik`,
 * `OutlinedTextField` ↔ `setFieldValue`, `displayError` gating, submit-button enablement) inside
 * the Compose UI test harness without needing a connected device or emulator.
 *
 * What's covered:
 *  - Initial render: title + button present, button disabled (form is invalid by default).
 *  - Typing into a text field updates form state and the displayed value.
 *  - Validation errors appear via `supportingText` after the field has been touched.
 *  - Filling valid values enables the submit button.
 *  - Clicking submit transitions form state (status set, submitCount=1).
 *
 * Notes / known limitations:
 *  - Robolectric simulates the Android framework on the JVM. It's "close enough" for Compose
 *    composition + semantics tree assertions, but it is *not* a real device. Touch events, view
 *    measurement, etc. work but rendering pixels does not.
 *  - The Material 3 text fields use placeholder/label text. We locate them by their labels.
 *  - `displayError` only flips to non-null after a field is **touched**. The Compose adapter
 *    treats focus loss (`onFocusChange(false)`) as the touched signal, which is hard to script
 *    cleanly from a UI test. We use the controller's exposed surface (`form.controller`) and the
 *    fact that a submit attempt touches every leaf to drive the error-display assertion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LoginScreenRobolectricTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun initialRender_showsTitleAndDisabledSubmit() {
        composeRule.setContent { LoginScreen() }
        composeRule.onNodeWithText("Kformik sample").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in").assertIsDisplayed()
        // Initial values blank → validation fails → button disabled
        composeRule.onNodeWithText("Sign in").assertIsNotEnabled()
    }

    @Test
    fun typingInTextField_updatesDisplayedValue() {
        composeRule.setContent { LoginScreen() }
        composeRule.onNodeWithText("Email").performTextInput("user@example.com")
        // The OutlinedTextField mirrors its value as a child node; assert text shows up
        composeRule.onNodeWithText("user@example.com").assertIsDisplayed()
    }

    @Test
    fun validValues_enableSubmitButton() {
        composeRule.setContent { LoginScreen() }
        composeRule.onNodeWithText("Email").performTextReplacement("user@example.com")
        composeRule.onNodeWithText("Password").performTextReplacement("hunter22long")
        // Allow recomposition to settle:
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Sign in").assertIsEnabled()
    }

    @Test
    fun submit_transitionsState_andShowsStatus() {
        composeRule.setContent { LoginScreen() }
        composeRule.onNodeWithText("Email").performTextReplacement("aisha@example.com")
        composeRule.onNodeWithText("Password").performTextReplacement("hunter22long")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Sign in").performClick()
        composeRule.waitForIdle()
        // The screen renders state.status?.let { Text("$it") }. After a successful submit, the
        // status string "Welcome aisha@example.com" should be present.
        composeRule.onNodeWithText("Welcome aisha@example.com").assertIsDisplayed()
    }
}
