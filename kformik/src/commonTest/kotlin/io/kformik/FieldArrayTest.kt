package io.kformik

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class FieldArrayTest {

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

    // ---------------------------------------------------------------------------- push / pop

    @Test
    fun push_appends() = runTest {
        val c = ctrl(this)
        c.array("friends").push("d")
        assertEquals(listOf("a", "b", "c", "d"), c.valueAt("friends"))
    }

    @Test
    fun push_doesNotAlterTouched() = runTest {
        val c = ctrl(this)
        c.setFieldTouched("friends[1]", true, shouldValidate = false)
        c.array("friends").push("d", shouldValidate = false)
        // existing touched entry stays at its original index
        assertTrue(c.state.value.touched["friends[1]"])
    }

    @Test
    fun pop_removesLast_andReturnsIt() = runTest {
        val c = ctrl(this)
        val popped = c.array("friends").pop()
        assertEquals("c", popped)
        assertEquals(listOf("a", "b"), c.valueAt("friends"))
    }

    @Test
    fun pop_emptyList_returnsNull() = runTest {
        val c = ctrl(this, mapOf("friends" to emptyList<String>()))
        assertNull(c.array("friends").pop())
    }

    // ----------------------------------------------------------------------------- unshift

    @Test
    fun unshift_prependsAndReturnsNewSize() = runTest {
        val c = ctrl(this)
        val size = c.array("friends").unshift("X")
        assertEquals(4, size)
        assertEquals(listOf("X", "a", "b", "c"), c.valueAt("friends"))
    }

    @Test
    fun unshift_alignsIndexedTouched() = runTest {
        val c = ctrl(this)
        c.setFieldTouched("friends[0]", true, shouldValidate = false)
        c.setFieldTouched("friends[1]", true, shouldValidate = false)
        c.array("friends").unshift("X", shouldValidate = false)
        // old [0] should be at new [1]; old [1] at [2]; new [0] is untouched
        assertFalse(c.state.value.touched["friends[0]"])
        assertTrue(c.state.value.touched["friends[1]"])
        assertTrue(c.state.value.touched["friends[2]"])
    }

    // ----------------------------------------------------------------------------- insert

    @Test
    fun insert_addsAtIndex() = runTest {
        val c = ctrl(this)
        c.array("friends").insert(1, "X")
        assertEquals(listOf("a", "X", "b", "c"), c.valueAt("friends"))
    }

    @Test
    fun insert_outOfBounds_throws() = runTest {
        val c = ctrl(this)
        assertFails { c.array("friends").insert(99, "X") }
    }

    @Test
    fun insert_alignsIndexedErrors() = runTest {
        val c = ctrl(this)
        c.setFieldError("friends[2]", "bad-c")
        c.array("friends").insert(0, "X", shouldValidate = false)
        // old [2] should be at new [3] now
        assertNull(c.state.value.errors["friends[2]"])
        assertEquals("bad-c", c.state.value.errors["friends[3]"])
    }

    // ----------------------------------------------------------------------------- remove

    @Test
    fun remove_removesAndReturns() = runTest {
        val c = ctrl(this)
        val removed = c.array("friends").remove(1)
        assertEquals("b", removed)
        assertEquals(listOf("a", "c"), c.valueAt("friends"))
    }

    @Test
    fun remove_outOfBounds_throws() = runTest {
        val c = ctrl(this)
        assertFails { c.array("friends").remove(99) }
    }

    @Test
    fun remove_alignsIndexedErrors() = runTest {
        val c = ctrl(this)
        c.setFieldError("friends[2]", "bad-c")
        c.array("friends").remove(0, shouldValidate = false)
        // old [2] should be at new [1] now
        assertNull(c.state.value.errors["friends[2]"])
        assertEquals("bad-c", c.state.value.errors["friends[1]"])
    }

    // ----------------------------------------------------------------------------- replace

    @Test
    fun replace_replacesValue() = runTest {
        val c = ctrl(this)
        c.array("friends").replace(1, "X")
        assertEquals(listOf("a", "X", "c"), c.valueAt("friends"))
    }

    @Test
    fun replace_doesNotAlterTouched() = runTest {
        val c = ctrl(this)
        c.setFieldTouched("friends[1]", true, shouldValidate = false)
        c.array("friends").replace(1, "X", shouldValidate = false)
        assertTrue(c.state.value.touched["friends[1]"])
    }

    @Test
    fun replace_outOfBounds_throws() = runTest {
        val c = ctrl(this)
        assertFails { c.array("friends").replace(99, "X") }
    }

    // ----------------------------------------------------------------------------- swap

    @Test
    fun swap_exchangesValues() = runTest {
        val c = ctrl(this)
        c.array("friends").swap(0, 2)
        assertEquals(listOf("c", "b", "a"), c.valueAt("friends"))
    }

    @Test
    fun swap_alignsIndexedTouchedAndErrors() = runTest {
        val c = ctrl(this)
        c.setFieldTouched("friends[0]", true, shouldValidate = false)
        c.setFieldError("friends[2]", "bad-c")
        c.array("friends").swap(0, 2, shouldValidate = false)
        assertTrue(c.state.value.touched["friends[2]"])  // was [0]
        assertEquals("bad-c", c.state.value.errors["friends[0]"]) // was [2]
    }

    @Test
    fun swap_outOfBounds_throws() = runTest {
        val c = ctrl(this)
        assertFails { c.array("friends").swap(0, 99) }
    }

    // ----------------------------------------------------------------------------- move

    @Test
    fun move_movesValue() = runTest {
        val c = ctrl(this)
        c.array("friends").move(2, 0)
        assertEquals(listOf("c", "a", "b"), c.valueAt("friends"))
    }

    @Test
    fun move_outOfBounds_throws() = runTest {
        val c = ctrl(this)
        assertFails { c.array("friends").move(99, 0) }
    }

    @Test
    fun move_alignsIndexedErrors() = runTest {
        val c = ctrl(this)
        c.setFieldError("friends[2]", "bad-c")
        c.array("friends").move(2, 0, shouldValidate = false)
        assertEquals("bad-c", c.state.value.errors["friends[0]"])
        assertNull(c.state.value.errors["friends[2]"])
    }

    // ----------------------------------------------------------------------- nested paths

    @Test
    fun nestedArray_canBeMutated() = runTest {
        val c = ctrl(
            this,
            initialValues = mapOf(
                "user" to mapOf("tags" to listOf("k", "v")),
                "groups" to listOf(mapOf("members" to listOf("a", "b"))),
            ),
        )
        c.array("user.tags").push("new")
        assertEquals(listOf("k", "v", "new"), c.valueAt("user.tags"))

        c.array("groups[0].members").swap(0, 1)
        assertEquals(listOf("b", "a"), c.valueAt("groups[0].members"))
    }

    @Test
    fun nestedArray_alignsNestedSuffixesInErrors() = runTest {
        val c = ctrl(
            this,
            initialValues = mapOf(
                "users" to listOf(
                    mapOf("name" to "a"),
                    mapOf("name" to "b"),
                    mapOf("name" to "c"),
                ),
            ),
        )
        c.setFieldError("users[2].name", "bad-c")
        c.array("users").remove(0, shouldValidate = false)
        // suffix `.name` should travel with index shift: 2 → 1
        assertNull(c.state.value.errors["users[2].name"])
        assertEquals("bad-c", c.state.value.errors["users[1].name"])
    }

    // ----------------------------------------------------------------- validateOnChange

    @Test
    fun array_push_triggersValidate_byDefault() = runTest {
        var called = 0
        val c = ctrl(this, validate = { called++; FormikErrors.Empty })
        c.array("friends").push("d")
        assertEquals(1, called)
    }

    @Test
    fun array_push_skipsValidate_whenFlagFalse() = runTest {
        var called = 0
        val c = ctrl(this, validateOnChange = false, validate = { called++; FormikErrors.Empty })
        c.array("friends").push("d")
        assertEquals(0, called)
    }

    @Test
    fun array_explicitShouldValidate_overrides() = runTest {
        var called = 0
        val c = ctrl(this, validateOnChange = false, validate = { called++; FormikErrors.Empty })
        c.array("friends").push("d", shouldValidate = true)
        assertEquals(1, called)
    }

    // ----------------------------------------------------------------- input validation

    @Test
    fun array_blankPath_throws() = runTest {
        val c = ctrl(this)
        assertFails { c.array("") }
        assertFails { c.array("   ") }
    }

    @Test
    fun array_onMissingPath_treatedAsEmpty() = runTest {
        val c = ctrl(this, mapOf("name" to "x"))
        // push to a non-existent path: list is created from scratch
        c.array("missing").push("first")
        assertEquals(listOf("first"), c.valueAt("missing"))
    }
}
