package io.kformik

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Edge-case coverage gaps for :kformik core — empty/extreme/race/pathological paths the main
 * lifecycle / schema / async suites don't already exercise.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EdgeCasesTest {

    private fun mapCtrl(
        scope: TestScope,
        initialValues: Map<String, Any?> = mapOf("name" to "jared"),
        validate: (suspend (Map<String, Any?>) -> FormikErrors)? = null,
        validateAsync: (suspend (Map<String, Any?>) -> FormikErrors)? = null,
        validateDebounceMs: Long? = null,
        enableReinitialize: Boolean = false,
        onSubmit: FormikSubmitHandler<Map<String, Any?>> = { _, _ -> },
        onError: ((Throwable) -> Unit)? = null,
    ): FormikController<Map<String, Any?>> = FormikController(
        FormikConfig(
            initialValues = initialValues,
            validate = validate,
            validateAsync = validateAsync,
            validateDebounceMs = validateDebounceMs,
            enableReinitialize = enableReinitialize,
            onSubmit = onSubmit,
            onError = onError,
            coroutineScope = scope,
        )
    )

    @Test
    fun emptyForm_initialState_isValid_andClean() = runTest {
        var submitted: Map<String, Any?>? = null
        val c = mapCtrl(this, emptyMap(), onSubmit = { v, _ -> submitted = v })
        assertTrue(c.state.value.errors.isEmpty)
        assertTrue(c.state.value.values.isEmpty())
        assertTrue(c.isValid.value)
        assertFalse(c.dirty.value)
        c.submit()
        assertNotNull(submitted)
        assertEquals(emptyMap(), submitted)
        assertEquals(1, c.state.value.submitCount)
    }

    @Test
    fun singleFieldForm_completeLifecycle() = runTest {
        var submitted: Map<String, Any?>? = null
        val c = mapCtrl(this, mapOf<String, Any?>("name" to ""), onSubmit = { v, _ -> submitted = v })
        c.setFieldValue("name", "ian")
        assertEquals("ian", c.valueAt("name"))
        c.setFieldTouched("name", true)
        assertTrue(c.state.value.touched["name"])
        c.submit()
        assertEquals(mapOf("name" to "ian"), submitted)
        assertEquals(1, c.state.value.submitCount)
    }

    @Test
    fun largeForm_100Fields_renderableAndQueryable() = runTest {
        val initial: Map<String, Any?> = (0..99).associate { "f$it" to (it as Any?) }
        var submitted: Map<String, Any?>? = null
        val c = mapCtrl(this, initial, onSubmit = { v, _ -> submitted = v })
        for (i in 0 until 50) c.setFieldValue("f$i", i + 1000)
        for (i in 0 until 50) assertEquals(i + 1000, c.valueAt("f$i"), "mutated f$i")
        for (i in 50 until 100) assertEquals(i, c.valueAt("f$i"), "untouched f$i")
        c.submit()
        assertNotNull(submitted)
        assertEquals(100, submitted!!.size)
        assertEquals(1049, submitted!!["f49"])
        assertEquals(50, submitted!!["f50"])
    }

    @Test
    fun value_atMissingPath_returnsNull_doesNotThrow() = runTest {
        val c = mapCtrl(this, mapOf<String, Any?>("a" to 1))
        assertNull(c.valueAt("missing"))
    }

    @Test
    fun value_castMismatch_isCallerResponsibility() = runTest {
        val c = mapCtrl(this, mapOf<String, Any?>("x" to "hello"))
        assertNull(c.valueAt("x") as? Int, "as? returns null on mismatch")
    }

    @Test
    fun nullValue_isReadable_andDistinctFromMissing() = runTest {
        // Documents MapValuesUpdater's prune-on-null contract: setAt(path, null) removes the leaf
        // so set-then-clear restores the original shape. Initial-values null, by contrast, is
        // preserved verbatim — the key stays in the map.
        val c = mapCtrl(this, mapOf<String, Any?>("x" to "v"))
        c.setFieldValue("x", null)
        assertNull(c.valueAt("x"))
        assertNull(c.valueAt("never-set"))
        val c2 = mapCtrl(this, mapOf<String, Any?>("k" to null))
        assertNull(c2.valueAt("k"))
        assertTrue(c2.state.value.values.containsKey("k"))
    }

    @Test
    fun setFieldValue_unregistered_path_doesNotThrow() = runTest {
        val c = mapCtrl(this, mapOf<String, Any?>("a" to 1))
        c.setFieldValue("brandNew", 42)
        runCurrent()
        assertEquals(42, c.valueAt("brandNew"))
        assertEquals(1, c.valueAt("a"))
    }

    @Test
    fun rapidFire_setFieldValue_underNoDebounce_finalValueWins() = runTest {
        val c = mapCtrl(this, mapOf<String, Any?>("x" to 0))
        for (i in 1..100) c.setFieldValue("x", i)
        assertEquals(100, c.valueAt("x"))
    }

    @Test
    fun validator_throwingException_inSyncValidate_routes_to_onError() = runTest {
        // Debounce routes change-validation through the collector, whose catch funnels
        // validator throws to onError instead of propagating to the caller.
        var caught: Throwable? = null
        val c = mapCtrl(
            this,
            mapOf<String, Any?>("name" to ""),
            validateDebounceMs = 10L,
            validate = { _ -> throw IllegalStateException("sync-boom") },
            onError = { t -> caught = t },
        )
        c.setFieldValue("name", "trigger")
        advanceUntilIdle()
        assertNotNull(caught)
        assertEquals("sync-boom", caught!!.message)
        c.close()
    }

    @Test
    fun validateAsync_returning_after_dispose_isSwallowed() = runTest {
        val c = mapCtrl(
            this,
            mapOf<String, Any?>("name" to ""),
            validateDebounceMs = 5L,
            validateAsync = { _ ->
                delay(500)
                buildErrors { put("name", "late") }
            },
        )
        c.setFieldValue("name", "trigger")
        advanceTimeBy(20) // past debounce, into the validateAsync delay
        c.close() // cancels in-flight debounced validation job
        advanceTimeBy(1000)
        runCurrent()
        assertNull(c.state.value.errors["name"], "no late commit after close")
    }

    @Test
    fun validator_returningArbitraryErrorKey_isSurfacedAtForm() = runTest {
        val c = mapCtrl(
            this,
            mapOf<String, Any?>("name" to ""),
            validate = { _ -> FormikErrors(mapOf("nonexistent" to "weird")) },
        )
        c.setFieldValue("name", "anything")
        assertEquals("weird", c.state.value.errors["nonexistent"])
    }

    @Test
    fun reinitialize_withSameValues_isNoop() = runTest {
        val v1 = mapOf<String, Any?>("name" to "jared", "age" to 30)
        val c = mapCtrl(this, v1)
        c.reinitialize(FormikInitialState(values = v1))
        assertEquals(v1, c.state.value.values)
    }

    @Test
    fun reinitialize_resetsSubmitCount_toZero() = runTest {
        val v1 = mapOf<String, Any?>("name" to "jared")
        val c = mapCtrl(this, v1, enableReinitialize = true)
        c.submit()
        assertEquals(1, c.state.value.submitCount)
        c.reinitialize(FormikInitialState(values = mapOf("name" to "kelly")))
        assertEquals(0, c.state.value.submitCount, "reinitialize w/ enableReinitialize resets submitCount")
    }

    @Test
    fun submit_withEmptyValues_callsOnSubmit_andIncrementsSubmitCount() = runTest {
        var seen: Map<String, Any?>? = null
        val c = mapCtrl(this, emptyMap(), onSubmit = { v, _ -> seen = v })
        c.submit()
        assertEquals(emptyMap(), seen)
        assertEquals(1, c.state.value.submitCount)
    }

    @Test
    fun setFieldError_to_emptyString_isStoredAsEmpty_NOT_cleared() = runTest {
        val c = mapCtrl(this, mapOf<String, Any?>("x" to "v"))
        c.setFieldError("x", "")
        assertEquals("", c.state.value.errors["x"], "empty-string error stored, not cleared")
        assertTrue(c.state.value.errors.contains("x"))
    }

    @Test
    fun setFieldError_to_null_clearsError() = runTest {
        val c = mapCtrl(this, mapOf<String, Any?>("x" to "v"))
        c.setFieldError("x", "msg")
        assertEquals("msg", c.state.value.errors["x"])
        c.setFieldError("x", null)
        assertNull(c.state.value.errors["x"])
        assertFalse(c.state.value.errors.contains("x"))
    }

    @Test
    fun setStatus_anyType_accepted() = runTest {
        val c = mapCtrl(this, mapOf<String, Any?>("x" to 0))
        c.setStatus(1)
        assertEquals(1, c.state.value.status)
        c.setStatus("two")
        assertEquals("two", c.state.value.status)
        val list = listOf(1, 2)
        c.setStatus(list)
        assertEquals(list, c.state.value.status)
    }

    @Test
    fun controller_closesCleanly_evenWhenInFlightValidation() = runTest {
        val c = mapCtrl(
            this,
            mapOf<String, Any?>("name" to ""),
            validateDebounceMs = 5L,
            validateAsync = { _ ->
                delay(500)
                buildErrors { put("name", "would-fail") }
            },
        )
        c.setFieldValue("name", "trigger")
        advanceTimeBy(20)
        c.close()
        advanceTimeBy(1000)
        runCurrent()
        assertNull(c.state.value.errors["name"])
    }
}
