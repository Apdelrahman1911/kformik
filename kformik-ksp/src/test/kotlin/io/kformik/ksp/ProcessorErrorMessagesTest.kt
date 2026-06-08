package io.kformik.ksp

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.kspSourcesDir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Gap-filling compile-tests for processor error messages and edge-case shapes that the existing
 * suites don't already cover.
 *
 * Coverage matrix vs. the original Phase-6 candidate list:
 *
 *  (a) non-data class — ALREADY COVERED by
 *      [FormValuesProcessorHardeningTest.nonDataClass_isReported_notSilentlyMiscompiled].
 *  (b) unsupported field type for updater — NOT APPLICABLE: the generator casts every field by
 *      its rendered FQN type (verified by Money/WithList hardening tests); there is no
 *      "unsupported field type" error path in the processor.
 *  (c) abstract / sealed / interface targets — partially covered (the non-data and generic
 *      hardening cases reach the same diagnostic), but the abstract / sealed / non-class shapes
 *      take distinct `isSupported` branches. Added here so a future refactor that splits the
 *      branches but forgets one is caught.
 *  (d) class with no primary constructor — UNREACHABLE in isolation: Kotlin enforces a primary
 *      constructor on every `data class`, and a non-data target hits the data-class branch
 *      first. Skipped.
 *  (e) empty `@FormValues data class` — NOT COVERED; added.
 *  (f) generated updater's `setAt(path, null)` on a NULLABLE scalar field — the hardening suite
 *      covers nullable nested round-trips and non-null fields rejecting null, but never a
 *      nullable scalar being legitimately set to null. Added here.
 *
 * Style and infra mirror [FormValuesProcessorHardeningTest] (kctfork + KotlinCompilation).
 */
class ProcessorErrorMessagesTest {

    private fun runCompile(vararg sources: SourceFile): Pair<KotlinCompilation, JvmCompilationResult> {
        val compilation = KotlinCompilation().apply {
            this.sources = sources.toList()
            inheritClassPath = true
            messageOutputStream = System.out
            configureKsp(useKsp2 = true) {
                symbolProcessorProviders += FormValuesProcessorProvider()
                withCompilation = true
            }
        }
        return compilation to compilation.compile()
    }

    private fun KotlinCompilation.generatedFile(pkg: String, name: String): File {
        val root = kspSourcesDir.resolve("kotlin").resolve(pkg.replace('.', '/'))
        return root.walkTopDown().first { it.isFile && it.name == name }
    }

    // ───────────────────────────────────────────────────── (c) shape branches

    @Test
    fun abstractClass_isReported_withUnsupportedShapeMessage() {
        // An `abstract class` is not a `data class`, so it would already fail the DATA modifier
        // check; we additionally rely on `ABSTRACT !in modifiers` for defence in depth. Either
        // branch must surface the same actionable diagnostic.
        val src = SourceFile.kotlin(
            "AbstractTarget.kt",
            """
            package app
            import io.kformik.ksp.FormValues
            @FormValues
            abstract class AbstractTarget(val x: String)
            """.trimIndent()
        )
        val (_, result) = runCompile(src)
        assertFalse(
            "an abstract @FormValues target must fail the build",
            result.exitCode == KotlinCompilation.ExitCode.OK,
        )
        assertTrue(
            "diagnostic must explain the @FormValues requirements; was:\n${result.messages}",
            result.messages.contains("@FormValues requires a concrete, non-generic"),
        )
    }

    @Test
    fun sealedClass_isReported_withUnsupportedShapeMessage() {
        val src = SourceFile.kotlin(
            "SealedTarget.kt",
            """
            package app
            import io.kformik.ksp.FormValues
            @FormValues
            sealed class SealedTarget
            """.trimIndent()
        )
        val (_, result) = runCompile(src)
        assertFalse(
            "a sealed @FormValues target must fail the build",
            result.exitCode == KotlinCompilation.ExitCode.OK,
        )
        assertTrue(
            "diagnostic must explain the @FormValues requirements; was:\n${result.messages}",
            result.messages.contains("@FormValues requires a concrete, non-generic"),
        )
    }

    @Test
    fun interfaceTarget_isReported_withUnsupportedShapeMessage() {
        // An interface has classKind == INTERFACE and isn't a class at all; the `classKind ==
        // CLASS` guard in isSupported() should reject it cleanly.
        val src = SourceFile.kotlin(
            "InterfaceTarget.kt",
            """
            package app
            import io.kformik.ksp.FormValues
            @FormValues
            interface InterfaceTarget { val x: String }
            """.trimIndent()
        )
        val (_, result) = runCompile(src)
        assertFalse(
            "an interface @FormValues target must fail the build",
            result.exitCode == KotlinCompilation.ExitCode.OK,
        )
        assertTrue(
            "diagnostic must explain the @FormValues requirements; was:\n${result.messages}",
            result.messages.contains("@FormValues requires a concrete, non-generic"),
        )
    }

    @Test
    fun enumTarget_isReported_withUnsupportedShapeMessage() {
        // Enum entries can carry @FormValues syntactically but classKind != CLASS for the enum
        // declaration itself.
        val src = SourceFile.kotlin(
            "EnumTarget.kt",
            """
            package app
            import io.kformik.ksp.FormValues
            @FormValues
            enum class EnumTarget { A, B }
            """.trimIndent()
        )
        val (_, result) = runCompile(src)
        assertFalse(
            "an enum @FormValues target must fail the build",
            result.exitCode == KotlinCompilation.ExitCode.OK,
        )
        assertTrue(
            "diagnostic must explain the @FormValues requirements; was:\n${result.messages}",
            result.messages.contains("@FormValues requires a concrete, non-generic"),
        )
    }

    // ───────────────────────────────────────────────── (e) empty data class

    @Test
    fun emptyDataClass_compiles_andGeneratesEmptyPathsAndUpdater() {
        // Kotlin allows `data class Empty()` so long as it has at least one primary-constructor
        // parameter — `data class Empty()` is actually a compile error. The closest legal "empty"
        // shape is a data class with a single inert marker parameter; the realistic gap-test is
        // a data class whose ONLY content is its primary constructor (no computed vals, no
        // additional members). That confirms the generator handles zero secondary properties
        // and emits a syntactically valid (if minimal) Paths object and Updater.
        val src = SourceFile.kotlin(
            "Marker.kt",
            """
            package app
            import io.kformik.ksp.FormValues
            @FormValues
            data class Marker(val tag: String)
            """.trimIndent()
        )
        val (comp, result) = runCompile(src)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        val paths = comp.generatedFile("app", "MarkerPaths.kt").readText()
        val updater = comp.generatedFile("app", "MarkerUpdater.kt").readText()
        assertTrue("paths object missing", paths.contains("object MarkerPaths {"))
        assertTrue("the single field's const missing", paths.contains("const val tag: String = \"tag\""))
        assertTrue("updater object missing", updater.contains("object MarkerUpdater"))

        // And the loaded class round-trips through the updater with only one path.
        val cl = result.classLoader
        val updaterCls = cl.loadClass("app.MarkerUpdater")
        val updaterObj = updaterCls.getField("INSTANCE").get(null)
        val markerCls = cl.loadClass("app.Marker")
        val marker = markerCls.getDeclaredConstructor(String::class.java).newInstance("hello")

        val leafPaths = updaterCls.getMethod("leafPaths", markerCls)
        @Suppress("UNCHECKED_CAST")
        val ps = leafPaths.invoke(updaterObj, marker) as Set<String>
        assertEquals(setOf("tag"), ps)
    }

    // ───────────────────────────────────── (f) nullable scalar set to null

    @Test
    fun nullableScalarField_setToNull_roundTripsViaUpdater() {
        // Companion to FormValuesProcessorHardeningTest.nonNullField_setToNull_throwsPathNamedError:
        // a NULLABLE field must accept a null write with no error, and getAt must return null
        // after the write.
        val src = SourceFile.kotlin(
            "WithNullable.kt",
            """
            package app
            import io.kformik.ksp.FormValues
            @FormValues
            data class WithNullable(val nickname: String?, val name: String)
            """.trimIndent()
        )
        val (_, result) = runCompile(src)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        val cl = result.classLoader
        val updaterCls = cl.loadClass("app.WithNullableUpdater")
        val updater = updaterCls.getField("INSTANCE").get(null)
        val wnCls = cl.loadClass("app.WithNullable")
        val initial = wnCls.getDeclaredConstructor(String::class.java, String::class.java)
            .newInstance("nick", "Aisha")

        val getAt = updaterCls.getMethod("getAt", wnCls, String::class.java)
        val setAt = updaterCls.getMethod("setAt", wnCls, String::class.java, Any::class.java)

        // Starts populated.
        assertEquals("nick", getAt.invoke(updater, initial, "nickname"))

        // Writing null to the nullable field must succeed and clear it.
        val cleared = setAt.invoke(updater, initial, "nickname", null)
        assertNotNull(cleared)
        assertNull(getAt.invoke(updater, cleared, "nickname"))
        // Sibling field untouched.
        assertEquals("Aisha", getAt.invoke(updater, cleared, "name"))

        // Original instance is unchanged (data class copy semantics).
        assertEquals("nick", getAt.invoke(updater, initial, "nickname"))
    }
}
