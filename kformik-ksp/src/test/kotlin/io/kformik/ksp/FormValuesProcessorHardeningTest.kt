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
 * Compile-tests for the Phase-5 KSP hardening: the cases that previously emitted uncompilable code
 * (computed/inherited props, cross-package types, collections, keyword names, null-to-non-null) now
 * compile, and unsupported targets (non-data / generic) are reported via the logger instead.
 */
class FormValuesProcessorHardeningTest {

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

    @Test
    fun computedBodyVal_isExcludedFromGeneratedCode() {
        val src = SourceFile.kotlin(
            "WithComputed.kt",
            """
            package app
            import io.kformik.ksp.FormValues
            @FormValues
            data class WithComputed(val name: String) {
                val display: String get() = name.uppercase()
            }
            """.trimIndent()
        )
        val (comp, result) = runCompile(src)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val updater = comp.generatedFile("app", "WithComputedUpdater.kt").readText()
        val paths = comp.generatedFile("app", "WithComputedPaths.kt").readText()
        assertFalse("computed val must not appear in updater copy()", updater.contains("display"))
        assertFalse("computed val must not appear in Paths", paths.contains("display"))
        assertTrue(updater.contains("values.name"))
    }

    @Test
    fun crossPackagePropertyType_castsWithFqn_compiles() {
        val src = SourceFile.kotlin(
            "Money.kt",
            """
            package app
            import io.kformik.ksp.FormValues
            @FormValues
            data class Money(val amount: java.math.BigDecimal, val label: String)
            """.trimIndent()
        )
        val (comp, result) = runCompile(src)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val updater = comp.generatedFile("app", "MoneyUpdater.kt").readText()
        assertTrue("cross-package type must be cast by FQN", updater.contains("as java.math.BigDecimal"))
    }

    @Test
    fun collectionProperty_castsWithTypeArgs_compiles() {
        val src = SourceFile.kotlin(
            "WithList.kt",
            """
            package app
            import io.kformik.ksp.FormValues
            @FormValues
            data class WithList(val tags: List<String>, val name: String)
            """.trimIndent()
        )
        val (comp, result) = runCompile(src)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val updater = comp.generatedFile("app", "WithListUpdater.kt").readText()
        assertTrue("collection cast must render type arguments", updater.contains("kotlin.collections.List<kotlin.String>"))
    }

    @Test
    fun crossPackageNested_qualifiesNestedUpdater_compiles() {
        val address = SourceFile.kotlin(
            "address.kt",
            """
            package app.address
            import io.kformik.ksp.FormValues
            @FormValues
            data class AddressValues(val city: String, val country: String)
            """.trimIndent()
        )
        val user = SourceFile.kotlin(
            "user.kt",
            """
            package app.user
            import io.kformik.ksp.FormValues
            import app.address.AddressValues
            @FormValues
            data class UserValues(val name: String, val address: AddressValues)
            """.trimIndent()
        )
        val (comp, result) = runCompile(address, user)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val userUpdater = comp.generatedFile("app.user", "UserValuesUpdater.kt").readText()
        assertTrue(
            "nested updater reference must be package-qualified",
            userUpdater.contains("app.address.AddressValuesUpdater.getAt"),
        )
    }

    @Test
    fun keywordNamedProperties_areBacktickEscaped_compiles() {
        val src = SourceFile.kotlin(
            "WithKeyword.kt",
            """
            package app
            import io.kformik.ksp.FormValues
            @FormValues
            data class WithKeyword(val `in`: String, val `is`: Boolean)
            """.trimIndent()
        )
        val (comp, result) = runCompile(src)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val updater = comp.generatedFile("app", "WithKeywordUpdater.kt").readText()
        assertTrue(updater.contains("values.`in`"))
        // string-literal path uses the raw name
        assertTrue(updater.contains("path == \"in\""))
    }

    @Test
    fun nonDataClass_isReported_notSilentlyMiscompiled() {
        val src = SourceFile.kotlin(
            "Plain.kt",
            """
            package app
            import io.kformik.ksp.FormValues
            @FormValues
            class Plain(val x: String)
            """.trimIndent()
        )
        val (_, result) = runCompile(src)
        assertFalse("a non-data target must fail the build", result.exitCode == KotlinCompilation.ExitCode.OK)
        assertTrue(
            "diagnostic must explain @FormValues requirements; was:\n${result.messages}",
            result.messages.contains("@FormValues requires a concrete, non-generic"),
        )
    }

    @Test
    fun genericClass_isReported() {
        val src = SourceFile.kotlin(
            "Box.kt",
            """
            package app
            import io.kformik.ksp.FormValues
            @FormValues
            data class Box<T>(val item: T)
            """.trimIndent()
        )
        val (_, result) = runCompile(src)
        assertFalse("a generic target must fail the build", result.exitCode == KotlinCompilation.ExitCode.OK)
        assertTrue(result.messages.contains("@FormValues requires a concrete, non-generic"))
    }

    @Test
    fun nonNullField_setToNull_throwsPathNamedError() {
        val src = SourceFile.kotlin(
            "Simple.kt",
            """
            package app
            import io.kformik.ksp.FormValues
            @FormValues data class Simple(val x: String)
            """.trimIndent()
        )
        val (_, result) = runCompile(src)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val cl = result.classLoader
        val updaterCls = cl.loadClass("app.SimpleUpdater")
        val updater = updaterCls.getField("INSTANCE").get(null)
        val simpleCls = cl.loadClass("app.Simple")
        val simple = simpleCls.getDeclaredConstructor(String::class.java).newInstance("v")
        val setAt = updaterCls.getMethod("setAt", simpleCls, String::class.java, Any::class.java)
        val ex = runCatching { setAt.invoke(updater, simple, "x", null) }.exceptionOrNull()
        assertNotNull("setting a non-null field to null must throw", ex)
        val msg = (ex!!.cause ?: ex).message ?: ""
        assertTrue("error must name the field; was: $msg", msg.contains("'x'") && msg.contains("non-null"))
    }
}
