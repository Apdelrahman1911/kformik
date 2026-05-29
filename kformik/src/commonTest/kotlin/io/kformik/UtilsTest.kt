package io.kformik

import io.kformik.internal.PathParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertSame

class UtilsTest {

    // ---------------------------------------------------------------------------- PathParser

    @Test
    fun pathParser_emptyString_isEmpty() {
        assertEquals(emptyList(), PathParser.parse(""))
    }

    @Test
    fun pathParser_dottedPath() {
        assertEquals(listOf("a", "b", "c"), PathParser.parse("a.b.c"))
    }

    @Test
    fun pathParser_bracketIndex() {
        assertEquals(listOf("a", "0", "b"), PathParser.parse("a[0].b"))
    }

    @Test
    fun pathParser_quotedBracket() {
        assertEquals(listOf("a", "b", "c"), PathParser.parse("a['b'].c"))
        assertEquals(listOf("a", "b", "c"), PathParser.parse("a[\"b\"].c"))
    }

    @Test
    fun pathParser_mixedNested() {
        assertEquals(listOf("user", "friends", "1", "name"), PathParser.parse("user.friends[1].name"))
    }

    @Test
    fun pathParser_singleSegment() {
        assertEquals(listOf("hello"), PathParser.parse("hello"))
    }

    // ---------------------------------------------------------------------------- deepEquals

    @Test
    fun deepEquals_primitives() {
        assertTrue(deepEquals(1, 1))
        assertTrue(deepEquals("x", "x"))
        assertTrue(deepEquals(true, true))
        assertFalse(deepEquals(1, 2))
        assertFalse(deepEquals(null, "x"))
        assertTrue(deepEquals(null, null))
    }

    @Test
    fun deepEquals_lists() {
        assertTrue(deepEquals(listOf(1, 2, 3), listOf(1, 2, 3)))
        assertFalse(deepEquals(listOf(1, 2), listOf(1, 2, 3)))
        assertFalse(deepEquals(listOf(1, 2, 3), listOf(1, 2, 4)))
        assertTrue(deepEquals(emptyList<Any?>(), emptyList<Any?>()))
    }

    @Test
    fun deepEquals_maps() {
        assertTrue(deepEquals(mapOf("a" to 1, "b" to 2), mapOf("b" to 2, "a" to 1)))
        assertFalse(deepEquals(mapOf("a" to 1), mapOf("a" to 2)))
        assertTrue(deepEquals(mapOf("a" to listOf(1, 2)), mapOf("a" to listOf(1, 2))))
    }

    @Test
    fun deepEquals_nested() {
        val a = mapOf("user" to mapOf("name" to "x", "friends" to listOf("y", "z")))
        val b = mapOf("user" to mapOf("name" to "x", "friends" to listOf("y", "z")))
        val c = mapOf("user" to mapOf("name" to "x", "friends" to listOf("y", "Z")))
        assertTrue(deepEquals(a, b))
        assertFalse(deepEquals(a, c))
    }

    // ---------------------------------------------------------------------------- Map.path

    @Test
    fun path_resolvesNested() {
        val m: Map<String, Any?> = mapOf(
            "user" to mapOf("name" to "X", "friends" to listOf("a", "b")),
        )
        assertEquals("X", m.path("user.name"))
        assertEquals("a", m.path("user.friends[0]"))
        assertEquals("b", m.path("user.friends[1]"))
        assertNull(m.path("user.missing"))
        assertNull(m.path("user.friends[10]"))
    }

    // ---------------------------------------------------------------------------- MapValuesUpdater

    @Test
    fun mapValuesUpdater_setsFlatField() {
        val before = mapOf<String, Any?>("a" to 1, "b" to 2)
        val after = MapValuesUpdater.setAt(before, "a", 99)
        assertEquals(mapOf("a" to 99, "b" to 2), after)
        assertEquals(1, before["a"]) // original unchanged
    }

    @Test
    fun mapValuesUpdater_setsNestedField() {
        val before = mapOf<String, Any?>("user" to mapOf("name" to "x"))
        val after = MapValuesUpdater.setAt(before, "user.name", "y")
        assertEquals(mapOf("user" to mapOf("name" to "y")), after)
    }

    @Test
    fun mapValuesUpdater_createsMissingIntermediates() {
        val before = mapOf<String, Any?>()
        val after = MapValuesUpdater.setAt(before, "user.address.city", "Lagos")
        assertEquals(mapOf("user" to mapOf("address" to mapOf("city" to "Lagos"))), after)
    }

    @Test
    fun mapValuesUpdater_handlesArrayPaths() {
        val before = mapOf<String, Any?>("tags" to listOf("a", "b", "c"))
        val after = MapValuesUpdater.setAt(before, "tags[1]", "B")
        assertEquals(mapOf("tags" to listOf("a", "B", "c")), after)
    }

    @Test
    fun mapValuesUpdater_returnsSameWhenUnchanged() {
        val before = mapOf<String, Any?>("a" to 1)
        val after = MapValuesUpdater.setAt(before, "a", 1)
        assertSame(before, after)
    }
}
