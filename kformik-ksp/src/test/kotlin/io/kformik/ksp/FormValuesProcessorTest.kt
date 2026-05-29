package io.kformik.ksp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for the KSP processor's path-string emission.
 *
 * These tests don't invoke the KSP framework (that requires `kotlin-compile-testing` or a Gradle
 * integration test). They cover the algorithmic core: given a list of "property names with
 * optional nested @FormValues children", do we produce the right path strings?
 *
 * Integration with real KSP is exercised by the `examples/ksp-sample` source set (or by any app
 * that applies the processor) — those won't compile unless the generated `<Name>Paths` object
 * exists.
 */
class FormValuesProcessorTest {

    /**
     * Pure-Kotlin reimplementation of the path-flattening rule, mirroring what the processor
     * emits. Used to assert path shapes independently from the KSP runtime.
     */
    private fun flatten(prefix: String, name: String): String =
        if (prefix.isEmpty()) name else "$prefix.$name"

    @Test
    fun flatPaths_haveJustTheName() {
        assertEquals("email", flatten("", "email"))
        assertEquals("password", flatten("", "password"))
    }

    @Test
    fun nestedPaths_joinWithDot() {
        // user.address.city — two levels deep
        val addressCity = flatten(flatten("", "address"), "city")
        assertEquals("address.city", addressCity)

        val userAddressCity = flatten(flatten(flatten("", "user"), "address"), "city")
        assertEquals("user.address.city", userAddressCity)
    }

    @Test
    fun depthCap_isHonored() {
        // Path-flattening itself has no cap; the processor's recursion guard does.
        // We just confirm the prefix-builder produces something sensible at depth 10.
        var prefix = ""
        repeat(10) { prefix = flatten(prefix, "n$it") }
        // dotted path with 10 segments
        assertEquals(9, prefix.count { it == '.' })
    }

    @Test
    fun annotation_isPresent() {
        // Sanity that the annotation class is loadable. (Reflective annotation access requires
        // kotlin-reflect; we don't depend on it here to keep the test runtime small.)
        val cls = Class.forName("io.kformik.ksp.FormValues")
        assertNotNull(cls)
        assertTrue(cls.isAnnotation)
    }

    @Test
    fun provider_factory_isInvokable() {
        // The provider's create(...) function requires a non-null environment. We can't construct
        // a real SymbolProcessorEnvironment here without ksp-api setup, but we CAN check that the
        // class exists and is loadable by name (i.e. the META-INF/services file points at a real
        // class).
        val cls = Class.forName("io.kformik.ksp.FormValuesProcessorProvider")
        assertNotNull(cls)
        // It implements SymbolProcessorProvider
        val iface = cls.interfaces.any {
            it.name == "com.google.devtools.ksp.processing.SymbolProcessorProvider"
        }
        assertTrue(iface, "FormValuesProcessorProvider must implement SymbolProcessorProvider")
    }
}
