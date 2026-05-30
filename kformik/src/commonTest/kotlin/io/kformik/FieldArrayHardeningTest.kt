package io.kformik

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for the Phase-2 FieldArray hardening:
 *  - pop() returns the correct value even when indexed touched/errors exist
 *  - non-list paths are rejected instead of silently overwritten
 *  - orphan / negative index keys are preserved, not dropped or relocated
 *  - boundary operations (insert-at-size, same-index move/swap) behave
 */
class FieldArrayHardeningTest {

    private fun ctrl(
        scope: TestScope,
        initialValues: Map<String, Any?> = mapOf("friends" to listOf("a", "b", "c")),
        validate: (suspend (Map<String, Any?>) -> FormikErrors)? = null,
        validateOnChange: Boolean = true,
    ) = FormikController(
        FormikConfig(
            initialValues = initialValues,
            validate = validate,
            onSubmit = { _, _ -> },
            validateOnChange = validateOnChange,
            coroutineScope = scope,
        )
    )

    @Test
    fun pop_returnsLast_evenWhenIndexedTouchedAndErrorExist() = runTest {
        val c = ctrl(this)
        c.setFieldError("friends[1]", "Required")
        c.setFieldTouched("friends[0]", true, shouldValidate = false)
        val popped = c.array("friends").pop(shouldValidate = false)
        assertEquals("c", popped, "pop must return the real last element, not a touched/error bucket")
        assertEquals(listOf("a", "b"), c.valueAt("friends"))
    }

    @Test
    fun pop_dropsIndexedTouchedAndErrorForRemovedTail() = runTest {
        val c = ctrl(this)
        c.setFieldTouched("friends[2]", true, shouldValidate = false)
        c.setFieldError("friends[2]", "bad-c")
        c.setFieldTouched("friends[0]", true, shouldValidate = false)
        c.array("friends").pop(shouldValidate = false)
        assertFalse(c.state.value.touched["friends[2]"])
        assertNull(c.state.value.errors["friends[2]"])
        assertTrue(c.state.value.touched["friends[0]"])
    }

    @Test
    fun unshift_alignsIndexedErrors() = runTest {
        val c = ctrl(this)
        c.setFieldError("friends[0]", "bad-a")
        c.setFieldError("friends[1]", "bad-b")
        c.array("friends").unshift("X", shouldValidate = false)
        assertNull(c.state.value.errors["friends[0]"]) // new [0] has no error
        assertEquals("bad-a", c.state.value.errors["friends[1]"])
        assertEquals("bad-b", c.state.value.errors["friends[2]"])
    }

    @Test
    fun fieldArray_onScalarPath_throws_insteadOfClobbering() = runTest {
        val c = ctrl(this, mapOf("name" to "scalar"))
        assertFailsWith<IllegalStateException> {
            c.array("name").push("x", shouldValidate = false)
        }
        // the scalar must be untouched after the refused mutation
        assertEquals("scalar", c.valueAt("name"))
    }

    @Test
    fun orphanIndexKey_beyondLiveArray_isPreserved_acrossRemove() = runTest {
        val c = ctrl(this) // friends = [a, b, c], size 3
        c.setFieldError("friends[5]", "orphan")    // index beyond the live array
        c.setFieldError("friends[1]", "bad-b")
        c.array("friends").remove(0, shouldValidate = false)
        // in-range key realigned (1 -> 0), orphan key left exactly where it was
        assertEquals("bad-b", c.state.value.errors["friends[0]"])
        assertEquals("orphan", c.state.value.errors["friends[5]"])
    }

    @Test
    fun move_sameIndex_isNoOp() = runTest {
        val c = ctrl(this)
        c.setFieldError("friends[1]", "bad-b")
        c.array("friends").move(1, 1, shouldValidate = false)
        assertEquals(listOf("a", "b", "c"), c.valueAt("friends"))
        assertEquals("bad-b", c.state.value.errors["friends[1]"])
    }

    @Test
    fun swap_sameIndex_isNoOp() = runTest {
        val c = ctrl(this)
        c.array("friends").swap(1, 1, shouldValidate = false)
        assertEquals(listOf("a", "b", "c"), c.valueAt("friends"))
    }

    @Test
    fun insert_atSize_appends() = runTest {
        val c = ctrl(this) // size 3
        c.array("friends").insert(3, "X", shouldValidate = false)
        assertEquals(listOf("a", "b", "c", "X"), c.valueAt("friends"))
    }

    @Test
    fun move_alignsIndexedTouched() = runTest {
        val c = ctrl(this)
        c.setFieldTouched("friends[2]", true, shouldValidate = false)
        c.array("friends").move(2, 0, shouldValidate = false)
        assertTrue(c.state.value.touched["friends[0]"])
        assertFalse(c.state.value.touched["friends[2]"])
    }
}
