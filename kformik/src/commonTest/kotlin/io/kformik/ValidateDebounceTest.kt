package io.kformik

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull

/**
 * Behavior contract for [FormikConfig.validateDebounceMs] (v1.7.0).
 *
 * Invariants verified here:
 *  - When `null` (default), validation runs once per change synchronously — no regression vs.
 *    pre-1.7 behavior.
 *  - When set to a positive value, rapid changes coalesce into a single validation that observes
 *    the **latest** values (debounce semantics, not throttle).
 *  - Blur (`setFieldTouched`), explicit `validateForm()` / `validateField()`, and `submit()`
 *    bypass the debounce — they always validate immediately.
 *  - Negative and zero values are coerced to "no debounce" so callers can pass dynamic values
 *    safely.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ValidateDebounceTest {

    private fun ctrl(
        scope: TestScope,
        validateDebounceMs: Long? = null,
        validateOnChange: Boolean = true,
        validateOnBlur: Boolean = true,
        validate: suspend (Map<String, Any?>) -> FormikErrors,
    ): FormikController<Map<String, Any?>> = FormikController(
        FormikConfig(
            initialValues = mapOf<String, Any?>("name" to ""),
            validate = validate,
            onSubmit = { _, _ -> },
            validateOnChange = validateOnChange,
            validateOnBlur = validateOnBlur,
            validateDebounceMs = validateDebounceMs,
            coroutineScope = scope,
        )
    )

    // ───────────────────────────────────────────────────── baseline: null = current behavior

    @Test
    fun nullDebounce_validatesEveryChange() = runTest {
        var count = 0
        val c = ctrl(this) { _ -> count++; FormikErrors.Empty }
        c.setFieldValue("name", "a")
        c.setFieldValue("name", "ab")
        c.setFieldValue("name", "abc")
        runCurrent()
        assertEquals(3, count, "no debounce → every change validates")
        c.close()
    }

    @Test
    fun zeroDebounce_isTreatedAsNoDebounce() = runTest {
        var count = 0
        val c = ctrl(this, validateDebounceMs = 0L) { _ -> count++; FormikErrors.Empty }
        c.setFieldValue("name", "a")
        c.setFieldValue("name", "ab")
        runCurrent()
        assertEquals(2, count, "zero debounce → immediate, every change")
        c.close()
    }

    @Test
    fun negativeDebounce_isTreatedAsNoDebounce() = runTest {
        var count = 0
        val c = ctrl(this, validateDebounceMs = -100L) { _ -> count++; FormikErrors.Empty }
        c.setFieldValue("name", "a")
        c.setFieldValue("name", "ab")
        runCurrent()
        assertEquals(2, count, "negative debounce → coerced to no-debounce")
        c.close()
    }

    // ─────────────────────────────────────────────────────── debounce coalesces rapid changes

    @Test
    fun positiveDebounce_coalescesRapidChanges() = runTest {
        var count = 0
        val seenValues = mutableListOf<String?>()
        val c = ctrl(this, validateDebounceMs = 100L) { v ->
            count++
            seenValues.add(v["name"] as? String)
            FormikErrors.Empty
        }
        c.setFieldValue("name", "a")
        c.setFieldValue("name", "ab")
        c.setFieldValue("name", "abc")
        runCurrent()
        // Inside the debounce window, no validation has fired yet.
        assertEquals(0, count, "before debounce window elapses, no validation")
        advanceTimeBy(150L) // > 100ms; the debounced collector fires once
        runCurrent()
        assertEquals(1, count, "after debounce, exactly one validation")
        // The validator must observe the LATEST values, not the first or an intermediate.
        assertEquals(listOf<String?>("abc"), seenValues)
        c.close()
    }

    @Test
    fun positiveDebounce_separateBurstsValidateSeparately() = runTest {
        var count = 0
        val c = ctrl(this, validateDebounceMs = 100L) { _ -> count++; FormikErrors.Empty }
        c.setFieldValue("name", "a")
        advanceTimeBy(150L); runCurrent()
        assertEquals(1, count)
        c.setFieldValue("name", "ab")
        advanceTimeBy(150L); runCurrent()
        assertEquals(2, count)
        c.close()
    }

    // ───────────────────────────────────────────────────── bypass: blur / submit / validateForm

    @Test
    fun blur_bypassesDebounce() = runTest {
        var count = 0
        val c = ctrl(this, validateDebounceMs = 100L) { _ -> count++; FormikErrors.Empty }
        c.setFieldTouched("name", true)
        runCurrent()
        // Blur triggers validation immediately even though debounce is set.
        assertEquals(1, count, "blur is not debounced")
        c.close()
    }

    @Test
    fun submit_bypassesDebounce() = runTest {
        var count = 0
        val c = ctrl(this, validateDebounceMs = 100L) { _ -> count++; FormikErrors.Empty }
        c.submit()
        runCurrent()
        assertEquals(1, count, "submit always validates immediately")
        c.close()
    }

    @Test
    fun validateForm_bypassesDebounce() = runTest {
        var count = 0
        val c = ctrl(this, validateDebounceMs = 100L) { _ -> count++; FormikErrors.Empty }
        c.validateForm()
        runCurrent()
        assertEquals(1, count, "validateForm() is explicit → not debounced")
        c.close()
    }

    @Test
    fun explicitChangeAfterBlur_stillDebounces() = runTest {
        var count = 0
        val c = ctrl(this, validateDebounceMs = 100L) { _ -> count++; FormikErrors.Empty }
        c.setFieldTouched("name", true)
        runCurrent()
        assertEquals(1, count) // immediate from blur

        c.setFieldValue("name", "a")
        c.setFieldValue("name", "ab")
        runCurrent()
        // Changes after the blur are still debounced.
        assertEquals(1, count, "change-validation after blur is still debounced")
        advanceTimeBy(150L); runCurrent()
        assertEquals(2, count, "single debounced validation fires after window")
        c.close()
    }

    // ───────────────────────────────────────────────────────── correctness: latest-values wins

    @Test
    fun debounce_observesLatestValuesNotIntermediate() = runTest {
        var lastSeen: String? = null
        val c = ctrl(this, validateDebounceMs = 100L) { v ->
            lastSeen = v["name"] as? String
            buildErrors {
                if ((v["name"] as String).length < 3) put("name", "Too short")
            }
        }
        // Rapid keystrokes — first two would fail the length check, the third would pass.
        c.setFieldValue("name", "a")
        c.setFieldValue("name", "ab")
        c.setFieldValue("name", "abcd")
        advanceTimeBy(150L); runCurrent()

        assertEquals("abcd", lastSeen, "validator must see the final value, not an intermediate")
        // And because "abcd" passes the rule, the error should NOT be set.
        assertNull(c.state.value.errors["name"], "error reflects validation of the final value")
        c.close()
    }

    // ──────────────────────────────────────────────────────── validateOnChange=false is honored

    @Test
    fun validateOnChangeFalse_skipsEvenWithDebounce() = runTest {
        var count = 0
        val c = ctrl(this, validateDebounceMs = 100L, validateOnChange = false) { _ ->
            count++
            FormikErrors.Empty
        }
        c.setFieldValue("name", "a")
        c.setFieldValue("name", "ab")
        advanceTimeBy(200L); runCurrent()
        assertEquals(0, count, "validateOnChange=false suppresses change-validation entirely")
        // Blur path still works.
        c.setFieldTouched("name", true)
        runCurrent()
        assertTrue(count >= 1, "blur fires normally (validateOnBlur=true by default)")
        c.close()
    }

    // ────────────────── collector survives validate throw (regression for v1.8.1 hardening)

    /**
     * Pre-1.8.1, a `validate` that threw inside the debounced pipeline killed the collector for
     * the controller's lifetime — subsequent change-triggered validations silently never fired.
     * The fix wraps the collect body in try/catch, routing throwables to `onError` and continuing
     * the collection.
     */
    @Test
    fun debounceCollector_survivesValidateThrow_routesToOnError() = runTest {
        var validateCalls = 0
        var onErrorCalls = 0
        var shouldThrow = true
        val c = FormikController(
            FormikConfig(
                initialValues = mapOf<String, Any?>("name" to ""),
                validate = { _ ->
                    validateCalls++
                    if (shouldThrow) {
                        shouldThrow = false
                        throw RuntimeException("validate boom (one-shot)")
                    }
                    FormikErrors.Empty
                },
                onSubmit = { _, _ -> },
                validateDebounceMs = 50L,
                onError = { onErrorCalls++ },
                coroutineScope = this,
            )
        )

        // First change triggers the throw via the debounced path.
        c.setFieldValue("name", "first")
        advanceTimeBy(100L); runCurrent()
        assertEquals(1, validateCalls, "validate fired once for first change")
        assertEquals(1, onErrorCalls, "validate's throw routed to onError, not propagated out")

        // Second change must STILL be processed — the collector survived the throw.
        c.setFieldValue("name", "second")
        advanceTimeBy(100L); runCurrent()
        assertEquals(2, validateCalls, "collector must still process subsequent changes after a throw")
        assertEquals(1, onErrorCalls, "second change does not throw")

        c.close()
    }
}
