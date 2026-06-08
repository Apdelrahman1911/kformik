package io.kformik.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.kformik.ValuesUpdater
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Regression suite for v1.9.2 fixes around [rememberFormik] and [ComposeFormik], plus extension
 * coverage for multi-form independence, multi-field recomposition isolation, and the documented
 * `enableReinitialize` contract under hydration-slot changes.
 *
 * Kept as a sibling file to [RememberFormikUiTest] so the canonical UI-test suite stays under the
 * ~400 LOC ceiling that keeps the per-test-file diffs reviewable. Same test harness
 * (`runComposeUiTest`), same style — Buttons drive mutations, helpers are private top-level
 * composables.
 */
@OptIn(ExperimentalTestApi::class)
class RememberFormikRegressionTest {

    /**
     * Pins v1.9.2 fix #1: `fieldState` keys its remembered flow on `controller` (not just `name`),
     * so when [rememberFormik]'s `key` parameter changes and a new controller is built, readers
     * pick up the new controller's flow instead of silently observing the dead controller. Pre-fix
     * the stale flow's last emission would keep displaying the OLD value.
     */
    @Test
    fun fieldState_resetsWhenControllerKeyChanges() = runComposeUiTest {
        setContent {
            var seed by remember { mutableStateOf(0) }
            val initialEmail = if (seed == 0) "first@example.com" else "second@example.com"
            val form = rememberFormik(
                initialValues = mapOf<String, Any?>("email" to initialEmail),
                onSubmit = { _, _ -> },
                key = "k" + seed,
            )
            val email by form.fieldState("email")
            Text(text = (email.value as String), modifier = Modifier.testTag("email"))
            Button(
                onClick = { seed++ },
                modifier = Modifier.testTag("bump"),
            ) { Text("bump") }
        }
        onNodeWithTag("email").assertTextEquals("first@example.com")
        onNodeWithTag("bump").performClick()
        waitForIdle()
        onNodeWithTag("email").assertTextEquals("second@example.com")
    }

    /**
     * Pins v1.9.2 fix #2: when [rememberFormik]'s `valuesUpdater` prop swaps across recompositions,
     * the indirection wrapper inside the config reads through `rememberUpdatedState`, so writes
     * after the swap hit the NEW updater — never the captured-at-construction one. Pre-fix only
     * the first-composition updater would receive writes for the controller's lifetime.
     */
    @Test
    fun valuesUpdater_isReadThroughUpdatedState() = runComposeUiTest {
        val updaterA = RecordingUpdater("A")
        val updaterB = RecordingUpdater("B")
        lateinit var capturedForm: ComposeFormik<Map<String, Any?>>
        setContent {
            var seed by remember { mutableStateOf(0) }
            val activeUpdater: ValuesUpdater<Map<String, Any?>> = if (seed == 0) updaterA else updaterB
            val form = rememberFormik(
                initialValues = mapOf<String, Any?>("email" to ""),
                onSubmit = { _, _ -> },
                valuesUpdater = activeUpdater,
            )
            capturedForm = form
            Column {
                Button(onClick = { seed++ }, modifier = Modifier.testTag("bump")) { Text("bump") }
                Button(
                    onClick = { form.setFieldValue("email", "after-swap@example.com") },
                    modifier = Modifier.testTag("do"),
                ) { Text("do") }
            }
        }
        waitForIdle()
        val aCallsBeforeSwap = updaterA.calls.size
        onNodeWithTag("bump").performClick()
        waitForIdle()
        assertEquals(aCallsBeforeSwap, updaterA.calls.size, "bump alone should not write through updaterA")
        assertEquals(0, updaterB.calls.size, "bump alone should not write through updaterB")
        onNodeWithTag("do").performClick()
        waitForIdle()
        assertEquals(aCallsBeforeSwap, updaterA.calls.size, "updaterA must not see writes after swap")
        assertTrue(
            updaterB.calls.any { it.second == "email" && it.third == "after-swap@example.com" },
            "updaterB should have recorded the post-swap setAt: ${updaterB.calls}",
        )
        assertNotNull(capturedForm)
    }

    /**
     * Extends `fieldState_recomposesPerField_independentOfOtherFields` to three fields. Mutating
     * only `email` must NOT recompose the password or username rows, proving the per-field flow's
     * deduplication isolates each field across an arbitrary fan-out.
     */
    @Test
    fun recomposition_count_perField_doesNotLeak_across_threeFields() = runComposeUiTest {
        var emailRecomps = 0
        var passwordRecomps = 0
        var usernameRecomps = 0
        setContent {
            val form = rememberFormik(
                initialValues = mapOf<String, Any?>(
                    "email" to "",
                    "password" to "",
                    "username" to "",
                ),
                onSubmit = { _, _ -> },
            )
            EmailRowR(form) { emailRecomps++ }
            PasswordRowR(form) { passwordRecomps++ }
            UsernameRowR(form) { usernameRecomps++ }
            Button(
                onClick = { form.setFieldValue("email", "x") },
                modifier = Modifier.testTag("change-email"),
            ) { Text("change-email") }
        }
        waitForIdle()
        val baseEmail = emailRecomps
        val basePwd = passwordRecomps
        val baseUser = usernameRecomps
        onNodeWithTag("change-email").performClick()
        waitForIdle()
        assertTrue(emailRecomps > baseEmail, "email row did not recompose: $baseEmail -> $emailRecomps")
        assertEquals(basePwd, passwordRecomps, "password row leaked recomp on email change")
        assertEquals(baseUser, usernameRecomps, "username row leaked recomp on email change")
    }

    /**
     * Two independent forms in the same composition (distinct `key`s) must not share state.
     * A write to form1's `"a"` field must leave form2's `"a"` untouched.
     */
    @Test
    fun multipleForms_inSameComposition_haveIndependentState() = runComposeUiTest {
        setContent {
            val form1 = rememberFormik(
                initialValues = mapOf<String, Any?>("a" to "alpha"),
                onSubmit = { _, _ -> },
                key = "form1",
            )
            val form2 = rememberFormik(
                initialValues = mapOf<String, Any?>("a" to "bravo"),
                onSubmit = { _, _ -> },
                key = "form2",
            )
            val a1 by form1.fieldState("a")
            val a2 by form2.fieldState("a")
            Column {
                Text(text = (a1.value as String), modifier = Modifier.testTag("a1"))
                Text(text = (a2.value as String), modifier = Modifier.testTag("a2"))
                Button(
                    onClick = { form1.setFieldValue("a", "changed") },
                    modifier = Modifier.testTag("mutate-form1"),
                ) { Text("mutate-form1") }
            }
        }
        onNodeWithTag("a1").assertTextEquals("alpha")
        onNodeWithTag("a2").assertTextEquals("bravo")
        onNodeWithTag("mutate-form1").performClick()
        waitForIdle()
        onNodeWithTag("a1").assertTextEquals("changed")
        onNodeWithTag("a2").assertTextEquals("bravo")
    }

    /**
     * Documents the actual contract of `enableReinitialize`: when `initialValues` changes across
     * recompositions, the controller calls `reinitialize(FormikInitialState(values = new))`, which
     * with the flag set delegates to `resetForm()` with `errors = Empty, touched = Empty,
     * status = null`. So user edits to values are REPLACED, programmatically-set errors are
     * CLEARED, programmatically-set touched are RESET, and status returns to null. (See
     * `kformik/.../ResetTest.kt::reinitialize_resetsForm_whenFlagTrue` and
     * `FormikController.reinitialize` lines 966-994.)
     */
    @Test
    fun enableReinitialize_replacesUserEdits_andResetsTouched_andClearsErrors() = runComposeUiTest {
        lateinit var capturedForm: ComposeFormik<Map<String, Any?>>
        setContent {
            var initial by remember { mutableStateOf("first@example.com") }
            val form = rememberFormik(
                initialValues = mapOf<String, Any?>("email" to initial),
                enableReinitialize = true,
                onSubmit = { _, _ -> },
            )
            capturedForm = form
            val state by form.state
            Column {
                Text(
                    text = state.values["email"] as String,
                    modifier = Modifier.testTag("email"),
                )
                Button(
                    onClick = {
                        form.setFieldValue("email", "user-edit@example.com")
                        form.setFieldTouched("email", true, shouldValidate = false)
                        form.setFieldError("email", "user-error")
                        form.setStatus("user-status")
                    },
                    modifier = Modifier.testTag("seed-user-state"),
                ) { Text("seed-user-state") }
                Button(
                    onClick = { initial = "second@example.com" },
                    modifier = Modifier.testTag("bump"),
                ) { Text("bump") }
            }
        }
        onNodeWithTag("seed-user-state").performClick()
        waitForIdle()
        // Sanity: user-applied edits landed before reinitialize fires.
        assertEquals("user-edit@example.com", capturedForm.value("email"))
        assertTrue(capturedForm.isTouched("email"))
        assertEquals("user-error", capturedForm.error("email"))
        assertEquals("user-status", capturedForm.controller.state.value.status)
        // Now bump initialValues — enableReinitialize must replace ALL of those.
        onNodeWithTag("bump").performClick()
        waitForIdle()
        onNodeWithTag("email").assertTextEquals("second@example.com")
        assertEquals("second@example.com", capturedForm.value("email"), "values replaced by new baseline")
        assertEquals(false, capturedForm.isTouched("email"), "touched reset to Empty by reinitialize")
        assertEquals(null, capturedForm.error("email"), "errors cleared by reinitialize")
        assertEquals(null, capturedForm.controller.state.value.status, "status reset to null by reinitialize")
    }
}

@Composable
private fun EmailRowR(form: ComposeFormik<Map<String, Any?>>, onRecomp: () -> Unit) {
    onRecomp()
    val email by form.fieldState("email")
    Text(
        text = (email.value as? String).orEmpty(),
        modifier = Modifier.testTag("email-r"),
    )
}

@Composable
private fun PasswordRowR(form: ComposeFormik<Map<String, Any?>>, onRecomp: () -> Unit) {
    onRecomp()
    val password by form.fieldState("password")
    Text(
        text = (password.value as? String).orEmpty(),
        modifier = Modifier.testTag("password-r"),
    )
}

@Composable
private fun UsernameRowR(form: ComposeFormik<Map<String, Any?>>, onRecomp: () -> Unit) {
    onRecomp()
    val username by form.fieldState("username")
    Text(
        text = (username.value as? String).orEmpty(),
        modifier = Modifier.testTag("username-r"),
    )
}

/**
 * Recording fake for [ValuesUpdater]. Tracks (name, path, value) tuples for every `setAt` so the
 * `valuesUpdater_isReadThroughUpdatedState` test can prove which updater instance received a write.
 */
private class RecordingUpdater(val name: String) : ValuesUpdater<Map<String, Any?>> {
    val calls: MutableList<Triple<String, String, Any?>> = mutableListOf()
    override fun getAt(values: Map<String, Any?>, path: String): Any? = values[path]
    override fun setAt(values: Map<String, Any?>, path: String, value: Any?): Map<String, Any?> {
        calls += Triple(name, path, value)
        return values + (path to value)
    }
    override fun leafPaths(values: Map<String, Any?>): Set<String> = values.keys
}
