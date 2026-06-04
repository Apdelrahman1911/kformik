package io.kformik

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for the Phase-3 ValuesUpdater / path-handling hardening:
 *  - negative and oversized list indices are no-ops, not crashes
 *  - clearing a nested leaf prunes the empty parent so `dirty` re-baselines
 *  - writing through a scalar replaces it (documented Formik/lodash parity)
 *  - non-Map values without an updater fail fast at construction
 */
class ValuesUpdaterHardeningTest {

    @Test
    fun negativeListIndex_isNoOp() {
        val before = mapOf<String, Any?>("t" to listOf("a", "b"))
        val after = MapValuesUpdater.setAt(before, "t[-1]", "x")
        // Content unchanged (no IndexOutOfBounds crash) — the write resolves to nothing.
        assertEquals(before, after)
    }

    @Test
    fun hugeListIndex_isNoOp_doesNotOOM() {
        val before = mapOf<String, Any?>("t" to listOf("a"))
        val after = MapValuesUpdater.setAt(before, "t[2000000000]", "x")
        // Returns quickly with unchanged content — no ~2e9-element allocation.
        assertEquals(before, after)
    }

    @Test
    fun moderateGrowthIndex_padsWithNulls() {
        val after = MapValuesUpdater.setAt(mapOf("t" to listOf("a")), "t[5]", "x")
        assertEquals(listOf("a", null, null, null, null, "x"), after["t"])
    }

    @Test
    fun writingThroughScalar_clobbersToMap() {
        val after = MapValuesUpdater.setAt(mapOf("a" to 1), "a.b", 2)
        assertEquals(mapOf("a" to mapOf("b" to 2)), after)
    }

    @Test
    fun emptyOrMalformedPath_throwsClearError() {
        assertFailsWith<IllegalArgumentException> { MapValuesUpdater.setAt(mapOf("a" to 1), ".", 2) }
    }

    /**
     * v1.9.0 regression: `getAt` rejects empty / segment-less paths symmetrically with `setAt`.
     * Pre-1.9.0, `getAt("")` / `getAt(".")` / `getAt("[]")` walked zero segments and returned the
     * entire values map verbatim — surprising for callers expecting a leaf value or null, and a
     * minor information-leak path. setAt has always rejected the same inputs.
     */
    @Test
    fun emptyPath_getAt_throwsSymmetric_withSetAt() {
        val values = mapOf<String, Any?>("a" to 1, "b" to "two")
        assertFailsWith<IllegalArgumentException> { MapValuesUpdater.getAt(values, "") }
        assertFailsWith<IllegalArgumentException> { MapValuesUpdater.getAt(values, ".") }
        assertFailsWith<IllegalArgumentException> { MapValuesUpdater.getAt(values, "[]") }
    }

    @Test
    fun clearingNestedLeaf_prunesEmptyParent_soDirtyRebaselines() = runTest {
        val c = FormikController(
            FormikConfig(
                initialValues = mapOf<String, Any?>("name" to "x"),
                onSubmit = { _, _ -> },
                coroutineScope = this,
            )
        )
        c.setFieldValue("user.age", 30, shouldValidate = false)
        assertTrue(c.dirty.value)
        c.setFieldValue("user.age", null, shouldValidate = false)
        // The now-empty "user" container is pruned, so values equal the baseline again.
        assertEquals(mapOf("name" to "x"), c.state.value.values)
        assertFalse(c.dirty.value, "dirty must re-baseline after set-then-clear of a nested leaf")
    }

    @Test
    fun nonMapValues_withoutUpdater_failFast() {
        assertFails {
            FormikController(
                FormikConfig(
                    initialValues = "a single string",
                    onSubmit = { _, _ -> },
                )
            )
        }
    }

    /**
     * Regression guard against StackOverflow when a malformed schema produces an extreme path.
     * `setRecursive` is non-tail recursive (allocates a fresh container at every level), so a
     * 200k-segment path would blow the controller thread's stack before any explicit guard
     * fires. The PathParser now caps segments at 256 — well above the deepest real form.
     */
    @Test
    fun extremePathLength_failsClean_doesNotStackOverflow() {
        val deep = (1..300).joinToString(".") { "a$it" }
        assertFailsWith<IllegalArgumentException> {
            MapValuesUpdater.setAt(mapOf<String, Any?>(), deep, "x")
        }
    }
}
