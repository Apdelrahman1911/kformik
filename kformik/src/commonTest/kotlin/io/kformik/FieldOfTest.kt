package io.kformik

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Regression tests for the typed field accessors:
 *  - fieldOf<T> on a non-null T with an absent/typed-mismatched value gives an actionable error
 *    instead of a raw ClassCastException / NPE
 *  - fieldOf<T?> and fieldOfOrNull<T> are null-safe
 */
class FieldOfTest {

    private fun ctrl(scope: TestScope) = FormikController(
        FormikConfig(
            initialValues = mapOf<String, Any?>("name" to "jared", "age" to 30),
            onSubmit = { _, _ -> },
            coroutineScope = scope,
        )
    )

    @Test
    fun fieldOf_typed_returnsTypedValue() = runTest {
        val c = ctrl(this)
        val name = c.fieldOf<String>("name")
        assertEquals("jared", name.value)
        val age = c.fieldOf<Int>("age")
        assertEquals(30, age.value)
    }

    @Test
    fun fieldOf_nonNullT_onAbsentPath_throwsDescriptive() = runTest {
        val c = ctrl(this)
        val ex = assertFailsWith<IllegalStateException> { c.fieldOf<String>("does-not-exist") }
        // message names the field and points at the safe alternatives
        assertEquals(true, ex.message?.contains("does-not-exist"))
    }

    @Test
    fun fieldOf_nonNullT_onTypeMismatch_throwsDescriptive() = runTest {
        val c = ctrl(this)
        // "age" holds an Int; requesting String must fail loudly, not silently/raw-crash
        assertFailsWith<IllegalStateException> { c.fieldOf<String>("age") }
    }

    @Test
    fun fieldOf_nullableT_onAbsentPath_returnsNull() = runTest {
        val c = ctrl(this)
        val f = c.fieldOf<String?>("does-not-exist")
        assertNull(f.value)
    }

    @Test
    fun fieldOfOrNull_onAbsentPath_returnsNull() = runTest {
        val c = ctrl(this)
        val f = c.fieldOfOrNull<String>("does-not-exist")
        assertNull(f.value)
    }

    @Test
    fun fieldOfOrNull_onTypeMismatch_returnsNull() = runTest {
        val c = ctrl(this)
        val f = c.fieldOfOrNull<String>("age") // age is an Int
        assertNull(f.value)
    }

    @Test
    fun fieldOfOrNull_onMatch_returnsValue() = runTest {
        val c = ctrl(this)
        assertEquals("jared", c.fieldOfOrNull<String>("name").value)
    }
}
