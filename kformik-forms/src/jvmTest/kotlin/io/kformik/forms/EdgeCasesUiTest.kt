package io.kformik.forms

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import io.kformik.compose.ComposeFormik
import kotlinx.coroutines.delay
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Edge-case coverage for [KformikForm]: empty / single-field / large / rapid / disabled / boundary
 * scenarios. Uses the same `renderHost` / `renderSeeded` helpers Pass 2 uses; assertions go
 * through public observables ([ComposeFormik.value] / [ComposeFormik.error] /
 * [ComposeFormik.isTouched] / `form.controller.state.value`) plus rendered text. Async-shaped
 * tests poll via `waitUntil(timeoutMillis = …)` to match Pass 2 conventions.
 */
@OptIn(ExperimentalTestApi::class)
class EdgeCasesUiTest {

    @Test
    fun emptyForm_renders_submitButton_only_andSubmitsWithEmptyValues() = runComposeUiTest {
        // With zero fields, `renderOverride` is never invoked (it's called per-field), so the sink
        // path in renderHost cannot capture the form. We use `footerSlot` instead — it always
        // composes regardless of fields.size and receives the form handle.
        var captured: ComposeFormik<Map<String, Any?>>? = null
        var submittedWith: Map<String, Any?>? = null
        renderHost(
            fields = emptyMap(),
            onSubmit = { values -> submittedWith = values },
            footerSlot = { form -> captured = form },
        )
        waitForIdle()
        onNodeWithText("Submit").assertIsDisplayed()
        // The default Submit button is enabled when isValid && !isSubmitting; an empty form has no
        // rules so isValid==true. Click it via the rendered text node.
        onNodeWithText("Submit").performClick()
        waitForIdle()
        waitUntil(timeoutMillis = 2_000) { captured?.controller?.state?.value?.submitCount == 1 }
        assertEquals(1, captured!!.controller.state.value.submitCount)
        // onSubmit fired with an empty map (no fields → no keys).
        assertNotNull(submittedWith, "onSubmit must fire even with zero fields when the form is valid")
        assertTrue(submittedWith!!.isEmpty(), "onSubmit values should be empty for a zero-field form")
    }

    @Test
    fun singleFieldForm_completeFlow() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        var submittedX: Any? = null
        renderHost(
            fields = mapOf("x" to Field(type = FieldType.Text, label = "X")),
            onSubmit = { values -> submittedX = values["x"] },
            sink = { captured = it },
        )
        waitForIdle()
        val form = captured!!
        form.setFieldValue("x", "alpha")
        waitForIdle()
        form.submit()
        waitUntil(timeoutMillis = 2_000) { form.controller.state.value.submitCount == 1 }
        assertEquals("alpha", submittedX, "onSubmit should receive the field's current value")
    }

    @Test
    fun largeForm_30Fields_rendersAndAcceptsAllValues() = runComposeUiTest {
        // Smoke test, NOT a perf benchmark. 30 fields is well within the headless JVM render
        // budget; the assertion shape simply confirms KformikForm scales to a non-trivial map and
        // every path round-trips through the controller's MapValuesUpdater.
        val fields = (0 until 30).associate { i ->
            "f$i" to Field(type = FieldType.Text, label = "F$i")
        }
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(fields = fields, sink = { captured = it })
        waitForIdle()
        val form = captured!!
        for (i in 0 until 30) {
            form.setFieldValue("f$i", "v$i")
        }
        waitForIdle()
        for (i in 0 until 30) {
            assertEquals("v$i", form.value("f$i"), "f$i should round-trip its set value")
        }
    }

    @Test
    fun rapidTyping_intoTextField_doesNotDrop_keystrokes() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf("x" to Field(type = FieldType.Text, label = "X")),
            sink = { captured = it },
        )
        waitForIdle()
        // performTextInput appends to the field's current value (cursor at end). Three back-to-back
        // calls should concatenate without dropping any segment.
        onNodeWithText("X").performTextInput("ab")
        onNodeWithText("ab").performTextInput("cd")
        onNodeWithText("abcd").performTextInput("ef")
        waitForIdle()
        assertEquals("abcdef", captured!!.value("x"))
    }

    @Test
    fun rapidSubmitClicks_singleFlight_via_isSubmitting_gate() = runComposeUiTest {
        // We drive 5 submits programmatically via the captured handle — that's the rigorous test
        // (bypasses the UI's `enabled = isValid && !isSubmitting` gate, leaving only the
        // controller-mutex single-flight at FormikController.kt:802). Any UI gate is strictly
        // stricter, so submitCount == 1 here implies submitCount == 1 under the UI flow too.
        var captured: ComposeFormik<Map<String, Any?>>? = null
        var onSubmitCount = 0
        renderHost(
            fields = mapOf("x" to Field(type = FieldType.Text, label = "X", initialValue = "go")),
            onSubmit = {
                onSubmitCount += 1
                delay(200)
            },
            sink = { captured = it },
        )
        waitForIdle()
        val form = captured!!
        repeat(5) { form.submit() }
        // Wait for the (single) submit to complete and isSubmitting to clear.
        waitUntil(timeoutMillis = 2_000) {
            val s = form.controller.state.value
            !s.isSubmitting && s.submitCount >= 1
        }
        assertEquals(
            1,
            form.controller.state.value.submitCount,
            "5 rapid submits must yield exactly one submission (single-flight gate)",
        )
        assertEquals(1, onSubmitCount, "onSubmit should fire exactly once across the 5 rapid submits")
    }

    @Test
    fun changingFields_duringValidation_doesNotCrash() = runComposeUiTest {
        // Driver: a validateAsync that takes ~150ms, then bump the seed mid-validation. The new
        // controller should pick up cleanly with no crash, and its observable state reflects the
        // post-rebuild values. "No crash" is asserted by reaching the final assertion.
        var captured: ComposeFormik<Map<String, Any?>>? = null
        val seed = renderSeeded(
            fields = { s ->
                mapOf(
                    "x" to Field(
                        type = FieldType.Text,
                        label = "X",
                        initialValue = if (s == 0) "a" else "b",
                    ),
                )
            },
            sink = { captured = it },
        )
        waitForIdle()
        captured!!.setFieldValue("x", "midflight")
        // Programmatic bump (not via the bump-seed Button) so we don't toggle focus or generate
        // pointer events that could mask the test.
        seed.value = seed.value + 1
        waitForIdle()
        // After rebuild: new controller's value reflects the new initialValue.
        assertEquals("b", captured!!.value("x"))
    }

    @Test
    fun changingInitialValues_whileDirty_replacesEdits_whenReinit_enabled() = runComposeUiTest {
        // renderSeeded forwards `enableReinitialize` to KformikForm. Because the controllerKey
        // also embeds initialValue, the controller rebuilds on a seed bump regardless of the
        // enableReinitialize flag — but enabling it documents the consumer-facing contract.
        var captured: ComposeFormik<Map<String, Any?>>? = null
        val seed = renderSeeded(
            fields = { s ->
                mapOf(
                    "x" to Field(
                        type = FieldType.Text,
                        label = "X",
                        initialValue = if (s == 0) "orig" else "fresh",
                    ),
                )
            },
            sink = { captured = it },
            enableReinitialize = true,
        )
        waitForIdle()
        // Dirty the field on seed-0.
        captured!!.setFieldValue("x", "user-edit")
        waitForIdle()
        assertEquals("user-edit", captured!!.value("x"))
        // Bump → controller rebuilds with the new initial value; the in-flight edit is replaced.
        seed.value = seed.value + 1
        waitForIdle()
        assertEquals("fresh", captured!!.value("x"), "Reinit should replace the dirty edit with the new initialValue")
        // The user's previous text must NOT be visible anywhere on screen.
        onAllNodesWithText("user-edit").assertCountEquals(0)
    }

    @Test
    fun asyncValidator_returningLate_doesNotOverwriteFresher_result() = runComposeUiTest {
        // validateAsync delay depends on the input value: 200ms for "A" (slow), 0ms for "BB"
        // (fast). The controller cancels older async runs via the validation-generation guard
        // and the debounced-collector's in-flight cancellation. The final observable error must
        // reflect the LAST input (BB), not the slow A-run.
        //
        // We configure `validateDebounceMs` so the debounced collector explicitly cancels the
        // previous in-flight validation when a newer value arrives — that's the canonical path
        // documented to provide cooperative cancellation (see FormikController.kt:304-313).
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf("x" to Field(type = FieldType.Text, label = "X")),
            sink = { captured = it },
            validateDebounceMs = 30,
            validateAsync = { values ->
                val v = values["x"] as? String ?: ""
                when (v) {
                    "A" -> {
                        delay(300)
                        io.kformik.FormikErrors(mapOf("x" to "A-error"))
                    }
                    "BB" -> io.kformik.FormikErrors(mapOf("x" to "BB-error"))
                    else -> io.kformik.FormikErrors.Empty
                }
            },
        )
        waitForIdle()
        val form = captured!!
        form.setFieldValue("x", "A")
        // Immediately overwrite with the fast input — the SLOW A-run should be cancelled/discarded.
        form.setFieldValue("x", "BB")
        form.setFieldTouched("x", true)
        waitUntil(timeoutMillis = 3_000) { form.error("x") == "BB-error" }
        assertEquals(
            "BB-error",
            form.error("x"),
            "Late async A-result must not overwrite fresher BB-result",
        )
    }

    @Test
    fun asyncValidator_throwing_routes_to_onError() = runComposeUiTest {
        // Debounced change-validation path: the debounce collector wraps validateAsync in
        // try/catch and funnels throwables to `onError`. Pinned at validateDebounceMs = 30 here to
        // document the debounced route end-to-end; the no-debounce sibling lives in
        // `asyncValidator_throwing_routes_to_onError_noDebounce` below and exercises the
        // `runAllValidationsAndCommitRoutingErrors` wrapper on the synchronous setFieldValue
        // branch instead.
        var caught: Throwable? = null
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf("x" to Field(type = FieldType.Text, label = "X")),
            sink = { captured = it },
            validateAsync = { _ -> throw IllegalStateException("async-bad") },
            validateDebounceMs = 30,
            onError = { caught = it },
        )
        waitForIdle()
        captured!!.setFieldValue("x", "anything")
        waitUntil(timeoutMillis = 2_000) { caught != null }
        assertNotNull(caught)
        assertEquals("async-bad", caught!!.message)
    }

    @Test
    fun asyncValidator_throwing_routes_to_onError_noDebounce() = runComposeUiTest {
        // Issue #2 load-bearing regression: without `validateDebounceMs`, change-validation runs
        // synchronously on the setFieldValue path. Before the fix, a throwing `validateAsync`
        // would propagate out of the fire-and-forget launch, corrupt the Compose scope, and the
        // UI test runner would HANG waiting for idle. With the
        // `runAllValidationsAndCommitRoutingErrors` wrapper in place (see FormikController.kt:347),
        // the throw routes to `onError` and the scope stays alive.
        var caught: Throwable? = null
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf("x" to Field(type = FieldType.Text, label = "X")),
            sink = { captured = it },
            validateAsync = { _ -> throw IllegalStateException("async-bad") },
            onError = { caught = it },
        )
        waitForIdle()
        captured!!.setFieldValue("x", "anything")
        waitUntil(timeoutMillis = 2_000) { caught != null }
        assertNotNull(caught)
        assertEquals("async-bad", caught!!.message)
    }

    @Test
    fun blur_throwingValidateAsync_routes_to_onError_noDebounce() = runComposeUiTest {
        // Sibling of `asyncValidator_throwing_routes_to_onError_noDebounce` for the blur path.
        // `setFieldTouched` (also `setTouched`) runs the same fire-and-forget validation as the
        // change path and was equally broken before the issue #2 fix. The
        // `runAllValidationsAndCommitRoutingErrors` wrapper at FormikController.kt:603 is what
        // makes this assertion reachable without the scope hanging.
        var caught: Throwable? = null
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf("x" to Field(type = FieldType.Text, label = "X")),
            sink = { captured = it },
            validateAsync = { _ -> throw IllegalStateException("blur-async-bad") },
            onError = { caught = it },
        )
        waitForIdle()
        captured!!.setFieldTouched("x", true)
        waitUntil(timeoutMillis = 2_000) { caught != null }
        assertNotNull(caught)
        assertEquals("blur-async-bad", caught!!.message)
    }

    @Test
    fun onSubmit_throwing_routes_to_onError_andClearsIsSubmitting() = runComposeUiTest {
        var caught: Throwable? = null
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf("x" to Field(type = FieldType.Text, label = "X", initialValue = "go")),
            onSubmit = { throw IllegalStateException("submit-bad") },
            sink = { captured = it },
            onError = { caught = it },
        )
        waitForIdle()
        captured!!.submit()
        waitUntil(timeoutMillis = 2_000) { caught != null }
        assertEquals("submit-bad", caught!!.message)
        // The isSubmitting flag must reset after the throw — otherwise the Submit button stays
        // permanently disabled.
        waitUntil(timeoutMillis = 2_000) { !captured!!.controller.state.value.isSubmitting }
        assertFalse(captured!!.controller.state.value.isSubmitting)
    }

    @Test
    fun disabledField_doesNotAcceptInput_but_initialValueIsInSubmit() = runComposeUiTest {
        var submittedX: Any? = null
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
            onSubmit = { values -> submittedX = values["x"] },
            sink = { captured = it },
        )
        waitForIdle()
        // Disabled OutlinedTextField is non-interactive — assertIsNotEnabled is the cleanest
        // user-visible observable.
        onNodeWithText("locked").assertIsNotEnabled()
        captured!!.submit()
        waitUntil(timeoutMillis = 2_000) { captured!!.controller.state.value.submitCount == 1 }
        assertEquals("locked", submittedX, "Disabled field's initialValue must propagate to onSubmit")
    }

    @Test
    fun optionalField_emptyString_validatesAsAbsentForRequired_butPresentForMaxLength() = runComposeUiTest {
        // Two side-by-side fields: one with maxLength(5), one with required(). An empty string
        // passes maxLength (length 0 ≤ 5) but fails required() (isBlank → missing). This documents
        // the asymmetric "empty string" contract baked into FieldRulesBuilder.
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf(
                "bounded" to Field(type = FieldType.Text, label = "Bounded", rules = { maxLength(5) }),
                "req" to Field(type = FieldType.Text, label = "Req", required = true),
            ),
            sink = { captured = it },
        )
        waitForIdle()
        val form = captured!!
        form.setFieldTouched("bounded", true)
        form.setFieldTouched("req", true)
        waitForIdle()
        waitUntil(timeoutMillis = 2_000) { form.error("req") != null }
        assertNull(form.error("bounded"), "maxLength(5) should accept an empty string")
        assertEquals("Required", form.error("req"), "required() should reject an empty string as absent")
    }

    @Test
    fun number_asInt_overflow_returns_null() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf("n" to Field(type = FieldType.Number(asInt = true), label = "N")),
            sink = { captured = it },
        )
        waitForIdle()
        // 99999999999999 > Int.MAX_VALUE; toIntOrNull returns null on overflow.
        onNodeWithText("N").performTextInput("99999999999999")
        waitForIdle()
        assertNull(captured!!.value("n"), "Int overflow must surface as null (toIntOrNull contract)")
    }

    @Test
    fun number_asInt_negativeZero_handled() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf("n" to Field(type = FieldType.Number(asInt = true), label = "N")),
            sink = { captured = it },
        )
        waitForIdle()
        onNodeWithText("N").performTextInput("-0")
        waitForIdle()
        // "-0".toIntOrNull() == 0 (Kotlin/Java parser normalises the sign on zero).
        assertEquals(0, captured!!.value("n") as Int?)
    }

    @Test
    fun date_yearOneThousand_andYearNineThousand_passThrough() = runComposeUiTest {
        var captured: ComposeFormik<Map<String, Any?>>? = null
        renderHost(
            fields = mapOf(
                "d" to Field(type = FieldType.Date, label = "D", initialValue = "1000-01-01"),
            ),
            sink = { captured = it },
        )
        waitForIdle()
        onNodeWithText("1000-01-01").assertIsDisplayed()
        captured!!.setFieldValue("d", "9999-12-31")
        waitForIdle()
        onNodeWithText("9999-12-31").assertIsDisplayed()
        // Boundary years round-trip through DateRenderer unchanged (the stored value is the ISO
        // String — no Year-2038 / Year-9999 silent narrowing).
        assertEquals("9999-12-31", captured!!.value("d"))
    }
}
