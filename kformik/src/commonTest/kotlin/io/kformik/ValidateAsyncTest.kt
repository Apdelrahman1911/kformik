package io.kformik

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavior contract for [FormikConfig.validateAsync] (v1.7.0 — sync-then-async circuit breaking).
 *
 * Invariants verified here:
 *  - `validateAsync` runs **only when** every cheap synchronous rule (the union of
 *    `validate` + `schemaValidator` + field-level validators) produced zero errors.
 *  - When any cheap rule fails, `validateAsync` is **not invoked** — network / heavy work is
 *    saved.
 *  - When the sync layer is empty (no validators configured at all), `validateAsync` still runs.
 *  - Errors from `validateAsync` are committed to state like any other error source.
 *  - Submit awaits the full pipeline (sync + async) and proceeds only if both phases pass.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ValidateAsyncTest {

    private fun ctrl(
        scope: TestScope,
        initialValues: Map<String, Any?> = mapOf("name" to ""),
        validate: (suspend (Map<String, Any?>) -> FormikErrors)? = null,
        validateAsync: (suspend (Map<String, Any?>) -> FormikErrors)? = null,
        schemaValidator: SchemaValidator<Map<String, Any?>>? = null,
        validateOnChange: Boolean = true,
        validateOnBlur: Boolean = true,
        validateOnMount: Boolean = false,
        onSubmit: FormikSubmitHandler<Map<String, Any?>> = { _, _ -> },
    ): FormikController<Map<String, Any?>> = FormikController(
        FormikConfig(
            initialValues = initialValues,
            validate = validate,
            validateAsync = validateAsync,
            schemaValidator = schemaValidator,
            onSubmit = onSubmit,
            validateOnChange = validateOnChange,
            validateOnBlur = validateOnBlur,
            validateOnMount = validateOnMount,
            coroutineScope = scope,
        )
    )

    // ─────────────────────────────────────────────────── async runs when sync is clean / absent

    @Test
    fun validateAsync_runs_whenNoSyncValidatorsConfigured() = runTest {
        var asyncCalls = 0
        val c = ctrl(this, validateAsync = { _ ->
            asyncCalls++
            FormikErrors.Empty
        })
        c.setFieldValue("name", "anything")
        runCurrent()
        assertEquals(1, asyncCalls, "no sync layer → async runs unconditionally on change")
        c.close()
    }

    @Test
    fun validateAsync_runs_whenSyncValidatorReturnsEmpty() = runTest {
        var asyncCalls = 0
        val c = ctrl(
            this,
            validate = { _ -> FormikErrors.Empty },
            validateAsync = { _ -> asyncCalls++; FormikErrors.Empty },
        )
        c.setFieldValue("name", "ok")
        runCurrent()
        assertEquals(1, asyncCalls)
        c.close()
    }

    @Test
    fun validateAsync_errorsAppearInState() = runTest {
        val c = ctrl(this, validateAsync = { _ ->
            buildErrors { put("name", "Async failure") }
        })
        c.setFieldValue("name", "anything")
        runCurrent()
        assertEquals("Async failure", c.state.value.errors["name"])
        c.close()
    }

    // ────────────────────────────────────────────────────── async skipped when sync failed

    @Test
    fun validateAsync_skipped_whenTopLevelValidateFailed() = runTest {
        var asyncCalls = 0
        val c = ctrl(
            this,
            validate = { _ -> buildErrors { put("name", "Required") } },
            validateAsync = { _ -> asyncCalls++; FormikErrors.Empty },
        )
        c.setFieldValue("name", "")
        runCurrent()
        assertEquals(0, asyncCalls, "sync error present → async circuit-broken")
        assertEquals("Required", c.state.value.errors["name"])
        c.close()
    }

    @Test
    fun validateAsync_skipped_whenSchemaFailed() = runTest {
        var asyncCalls = 0
        val schema = formSchema<Map<String, Any?>> {
            field("name") { required() }
        }
        val c = ctrl(
            this,
            schemaValidator = schema,
            validateAsync = { _ -> asyncCalls++; FormikErrors.Empty },
        )
        c.setFieldValue("name", "")
        runCurrent()
        assertEquals(0, asyncCalls, "schema error present → async circuit-broken")
        assertTrue(c.state.value.errors["name"]?.isNotEmpty() == true)
        c.close()
    }

    @Test
    fun validateAsync_skipped_whenFieldLevelValidatorFailed() = runTest {
        var asyncCalls = 0
        val c = ctrl(this, validateAsync = { _ -> asyncCalls++; FormikErrors.Empty })
        c.registerField("name") { v -> if ((v as String).isBlank()) "Required" else null }
        c.setFieldValue("name", "")
        runCurrent()
        assertEquals(0, asyncCalls, "field-level error present → async circuit-broken")
        assertEquals("Required", c.state.value.errors["name"])
        c.close()
    }

    // ──────────────────────────────────────────────────── async vs the various triggers

    @Test
    fun validateAsync_respectsValidateOnChangeFalse() = runTest {
        var asyncCalls = 0
        val c = ctrl(
            this,
            validateOnChange = false,
            validateAsync = { _ -> asyncCalls++; FormikErrors.Empty },
        )
        c.setFieldValue("name", "x")
        runCurrent()
        assertEquals(0, asyncCalls, "validateOnChange=false suppresses async like any other validator")
        c.close()
    }

    @Test
    fun validateAsync_runsOnBlur() = runTest {
        var asyncCalls = 0
        val c = ctrl(this, validateAsync = { _ -> asyncCalls++; FormikErrors.Empty })
        c.setFieldTouched("name", true)
        runCurrent()
        assertEquals(1, asyncCalls)
        c.close()
    }

    @Test
    fun validateAsync_runsOnMount_whenEnabled() = runTest {
        var asyncCalls = 0
        val c = ctrl(
            this,
            validateOnMount = true,
            validateAsync = { _ -> asyncCalls++; FormikErrors.Empty },
        )
        runCurrent()
        assertEquals(1, asyncCalls)
        c.close()
    }

    @Test
    fun validateAsync_isAwaitedBySubmit_whenSyncIsClean() = runTest {
        var asyncCalls = 0
        var submitted = false
        val c = ctrl(
            this,
            onSubmit = { _, _ -> submitted = true },
            validateAsync = { _ ->
                asyncCalls++
                FormikErrors.Empty
            },
        )
        c.submit()
        runCurrent()
        assertEquals(1, asyncCalls, "submit triggers the full pipeline")
        assertTrue(submitted, "async passed → submit fires")
        c.close()
    }

    @Test
    fun validateAsync_blocksSubmit_whenItProducesErrors() = runTest {
        var submitted = false
        val c = ctrl(
            this,
            onSubmit = { _, _ -> submitted = true },
            validateAsync = { _ -> buildErrors { put("name", "Already taken") } },
        )
        c.submit()
        runCurrent()
        assertEquals("Already taken", c.state.value.errors["name"])
        assertEquals(false, submitted, "async error blocks submit")
        c.close()
    }

    @Test
    fun validateAsync_skipped_bySubmit_whenSyncFails() = runTest {
        var asyncCalls = 0
        var submitted = false
        val c = ctrl(
            this,
            validate = { _ -> buildErrors { put("name", "Required") } },
            onSubmit = { _, _ -> submitted = true },
            validateAsync = { _ -> asyncCalls++; FormikErrors.Empty },
        )
        c.submit()
        runCurrent()
        assertEquals(0, asyncCalls, "sync failed → async circuit-broken even via submit")
        assertEquals(false, submitted, "form invalid → submit doesn't fire")
        c.close()
    }

    // ────────────────────────────────────────────────────── F2 × F3 interplay

    @Test
    fun validateAsync_worksWithDebounce_circuitBreaksAfterDebounceFires() = runTest {
        var syncCalls = 0
        var asyncCalls = 0
        val c = FormikController(FormikConfig(
            initialValues = mapOf<String, Any?>("name" to ""),
            validate = { v ->
                syncCalls++
                buildErrors {
                    if ((v["name"] as String).length < 3) put("name", "Too short")
                }
            },
            validateAsync = { _ -> asyncCalls++; FormikErrors.Empty },
            validateDebounceMs = 100L,
            onSubmit = { _, _ -> },
            coroutineScope = this,
        ))
        c.setFieldValue("name", "a")
        c.setFieldValue("name", "ab")
        c.setFieldValue("name", "abc") // passes the length check
        runCurrent()
        // Inside debounce window — nothing has fired yet.
        assertEquals(0, syncCalls)
        assertEquals(0, asyncCalls)

        // Trigger the debounced collector.
        advanceTimeBy(150L); runCurrent()
        assertEquals(1, syncCalls, "debounce → one sync run on the latest value")
        assertEquals(1, asyncCalls, "sync passed → async also runs (once)")
        assertNull(c.state.value.errors["name"])
        c.close()
    }

    // ────────────────────────────────────────────────────── circuit-break payoff measurable

    @Test
    fun validateAsync_neverRuns_whenSyncAlwaysFails() = runTest {
        var asyncCalls = 0
        val c = ctrl(
            this,
            validate = { _ -> buildErrors { put("name", "Required") } },
            validateAsync = { _ -> asyncCalls++; FormikErrors.Empty },
        )
        repeat(10) { c.setFieldValue("name", "") }
        c.setFieldTouched("name", true)
        c.submit()
        runCurrent()
        assertEquals(0, asyncCalls, "sync keeps failing → async skipped on every trigger")
        c.close()
    }

    /**
     * v1.9.0: a slow `validateAsync` (typically a network round-trip) gets cancelled when a
     * newer change-validation supersedes it. Pre-1.9.0, the in-flight call ran to completion
     * only to have its result dropped at the generation-guarded commit step — wasted network
     * round-trips.
     *
     * Setup: 50ms debounce, validateAsync delays 5_000ms. Type once at t=0, advance past the
     * first debounce so async-1 starts. Type again at t=150 (well past the first debounce
     * window, so the second emission is processed separately), advance past the second debounce
     * so async-2 starts. async-1 should be cancelled mid-delay; only async-2 completes.
     */
    @Test
    fun validateAsync_inFlight_isCancelledOnSupersedingChange() = runTest {
        var asyncStartCount = 0
        var asyncCompleteCount = 0
        val c = FormikController(
            FormikConfig(
                initialValues = mapOf<String, Any?>("name" to ""),
                validateDebounceMs = 50L,
                validateAsync = { _ ->
                    asyncStartCount++
                    kotlinx.coroutines.delay(5_000L)
                    asyncCompleteCount++
                    FormikErrors.Empty
                },
                onSubmit = { _, _ -> },
                coroutineScope = this,
            )
        )
        c.setFieldValue("name", "abc")
        advanceTimeBy(100L); runCurrent()
        assertEquals(1, asyncStartCount, "first async started after the first debounce window")
        assertEquals(0, asyncCompleteCount, "still in flight")

        // Supersede with a new change well past the first emission's debounce window so the
        // pipeline emits a second, distinct (values, gen) — and the collector cancels async-1.
        c.setFieldValue("name", "abcd")
        advanceTimeBy(100L); runCurrent()
        assertEquals(2, asyncStartCount, "second async started")
        assertEquals(0, asyncCompleteCount, "first was cancelled mid-delay; second still in flight")

        advanceTimeBy(6_000L); runCurrent()
        assertEquals(
            1,
            asyncCompleteCount,
            "only the second async completed (first cancelled). Pre-1.9.0 both would have completed.",
        )

        c.close()
    }
}
