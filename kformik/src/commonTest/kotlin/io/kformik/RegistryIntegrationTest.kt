package io.kformik

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration coverage proving the rule-registry composes cleanly with the live controller via the
 * existing rule DSL. Pass 2 is tests-only — no library code changes here. These tests verify that
 * registry-resolved rules participate in the same sync/async/cancellation/onError plumbing as
 * hand-written DSL rules.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RegistryIntegrationTest {

    private fun ctrl(
        scope: TestScope,
        initialValues: Map<String, Any?>,
        schema: FormSchema<Map<String, Any?>>,
        onError: ((Throwable) -> Unit)? = null,
        validateDebounceMs: Long? = null,
    ): FormikController<Map<String, Any?>> = FormikController(
        FormikConfig(
            initialValues = initialValues,
            schemaValidator = schema,
            onSubmit = { _, _ -> },
            onError = onError,
            validateDebounceMs = validateDebounceMs,
            coroutineScope = scope,
        )
    )

    // ------------------------------------------------------------------ async + onError

    @Test
    fun asyncRule_routesThrowToOnError_onChangePath() = runTest {
        // A registered async-style rule that throws should be caught and routed to onError,
        // matching the existing FieldRule throw-routing contract. Registry is just composition
        // on top of the existing custom { } DSL — no new error paths.
        val reg = ruleRegistry<Map<String, Any?>> {
            register("alwaysThrows") { _ ->
                custom("alwaysThrows") { _, _ ->
                    delay(1)  // ensure we're on the suspending path
                    throw IllegalStateException("async-rule-boom")
                }
            }
        }
        val schema = formSchema<Map<String, Any?>> {
            field("x") { spec(reg, RuleSpec("alwaysThrows")) }
        }
        var caught: Throwable? = null
        val c = ctrl(this, mapOf("x" to ""), schema,
            onError = { t -> caught = t },
            validateDebounceMs = 5L)
        c.setFieldValue("x", "trigger")
        advanceUntilIdle()
        assertNotNull(caught, "throw should reach onError")
        assertEquals("async-rule-boom", caught!!.message)
        c.close()
    }

    @Test
    fun asyncRule_cancelsOnSupersedingChange() = runTest {
        // The handler enters a suspending delay; a superseding setFieldValue MUST actually cancel
        // the in-flight coroutine (CancellationException reaches the catch block) — not merely
        // get generation-discarded after natural completion. We pin both halves:
        //   (1) firstEnteredSuspend completes BEFORE the supersede, proving the rule reached the
        //       suspending region — distinguishes a real cancellation from "rule never started".
        //   (2) firstSawCancellation completes with true after the supersede, proving the catch
        //       block ran (i.e. CancellationException was thrown into the rule's coroutine).
        val firstEnteredSuspend = CompletableDeferred<Unit>()
        val firstSawCancellation = CompletableDeferred<Boolean>()
        var sawSecondResult = false

        val reg = ruleRegistry<Map<String, Any?>> {
            register("slow") { _ ->
                custom("slow") { value, _ ->
                    if (value == "first") {
                        firstEnteredSuspend.complete(Unit)  // signal arrival at suspending region
                        try {
                            delay(10_000)
                            firstSawCancellation.complete(false)  // unreachable on cancellation
                            "slow-result-first"
                        } catch (t: kotlinx.coroutines.CancellationException) {
                            firstSawCancellation.complete(true)
                            throw t
                        }
                    } else {
                        sawSecondResult = true
                        null
                    }
                }
            }
        }
        val schema = formSchema<Map<String, Any?>> {
            field("x") { spec(reg, RuleSpec("slow")) }
        }
        val c = ctrl(this, mapOf("x" to ""), schema, validateDebounceMs = 5L)
        c.setFieldValue("x", "first")
        advanceTimeBy(10)                  // past debounce — into the delay(10_000)
        assertTrue(firstEnteredSuspend.isCompleted, "first rule must reach the suspending region before supersede")
        c.setFieldValue("x", "second")     // supersedes — must cancel the first rule's coroutine
        advanceUntilIdle()
        // Strong claim: the catch block actually ran AND saw a CancellationException.
        assertTrue(firstSawCancellation.isCompleted, "first rule's catch block must execute on supersede")
        assertEquals(true, firstSawCancellation.getCompleted(),
            "first rule must have been cancelled (CancellationException), not run to completion")
        // Plus the no-late-commit / second-runs invariants from before.
        assertEquals("second", c.valueAt("x"))
        assertNull(c.state.value.errors["x"], "no error from either run after supersede")
        assertTrue(sawSecondResult, "second invocation should have run")
        c.close()
    }

    // ------------------------------------------------------------------ spec + DSL coexistence at runtime

    @Test
    fun specsAndInlineDsl_appliesBoth_validates_inLambdaOrder() = runTest {
        // Specs and inline custom rules run together; under default failFast, the first failure wins.
        val reg = ruleRegistry<Map<String, Any?>>()
        val schema = formSchema<Map<String, Any?>> {
            field("name") {
                specs(reg, listOf(
                    RuleSpec("required"),
                    RuleSpec("minLength", mapOf("value" to 2)),
                ))
                custom("noDigits") { v, _ ->
                    val s = v as? String ?: return@custom null
                    if (s.any { it.isDigit() }) "no digits" else null
                }
            }
        }
        // Empty → spec-required fires first.
        assertEquals("Required", schema.validate(mapOf("name" to "")).byPath["name"])
        // 1 char → spec-minLength fires before the inline noDigits check.
        assertEquals("Must be at least 2 characters", schema.validate(mapOf("name" to "a")).byPath["name"])
        // Valid length but has digits → inline rule fires.
        assertEquals("no digits", schema.validate(mapOf("name" to "ab1")).byPath["name"])
        // Fully valid.
        assertNull(schema.validate(mapOf("name" to "abc")).byPath["name"])
    }

    @Test
    fun customRegistryRule_runsThroughController_andSurfacesError() = runTest {
        val reg = ruleRegistry<Map<String, Any?>> {
            register("blocklist") { params ->
                val msg = params.stringOrNull("message") ?: "Blocked"
                custom("blocklist") { v, _ ->
                    val s = v as? String ?: return@custom null
                    if (s.equals("forbidden", ignoreCase = true)) msg else null
                }
            }
        }
        val schema = formSchema<Map<String, Any?>> {
            field("name") { spec(reg, RuleSpec("blocklist", mapOf("message" to "Reserved word"))) }
        }
        val c = ctrl(this, mapOf("name" to "ok"), schema)
        c.setFieldValue("name", "forbidden")
        advanceUntilIdle()
        assertEquals("Reserved word", c.state.value.errors["name"])
        c.setFieldValue("name", "fine")
        advanceUntilIdle()
        assertNull(c.state.value.errors["name"])
        c.close()
    }
}
