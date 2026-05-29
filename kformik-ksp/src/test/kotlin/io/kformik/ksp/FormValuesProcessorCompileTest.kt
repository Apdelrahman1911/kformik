package io.kformik.ksp

import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.kspSourcesDir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * End-to-end compile-testing for [FormValuesProcessor]. Uses Zac Sweers' `kctfork` fork of
 * `kotlin-compile-testing` to run the processor against in-memory Kotlin sources and inspect the
 * generated output.
 *
 * Coverage:
 *  - flat `@FormValues data class`
 *  - nested `@FormValues data class` (`UserValues` referencing `AddressValues`)
 *  - multiple annotated classes in one compilation
 *  - generated object name = `<Name>Paths`
 *  - flat `const val email: String = "email"` constants
 *  - nested object scopes (`object address { const val city = "address.city" }`)
 *  - nested object scopes carry a `$path` constant naming the array path itself
 *  - generated `<Name>Updater` (post-Item 2; tests in [FormValuesUpdaterGenerationTest])
 *  - service-provider wiring via `META-INF/services`
 *  - non-annotated sources produce no generated file
 */
class FormValuesProcessorCompileTest {

    /** Build a [KotlinCompilation] and run it, returning (compilation, result) so callers can
     *  reach the generated-sources directory via `compilation.kspSourcesDir`. Uses the modern
     *  `configureKsp` API (kctfork 0.5.x) which wires our processor through the KSP1 plugin. */
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

    /** Read a single generated file from the KSP output directory under a given package. */
    private fun KotlinCompilation.generatedFile(pkg: String, name: String): File {
        val root = kspSourcesDir.resolve("kotlin").resolve(pkg.replace('.', '/'))
        val candidates = root.walkTopDown().filter { it.isFile && it.name == name }.toList()
        assertTrue("expected to find $name under $root, found: ${root.walkTopDown().toList()}",
            candidates.isNotEmpty())
        return candidates.first()
    }

    // ─────────────────────────────────────────────────────────────────── flat

    @Test
    fun flat_dataClass_generatesPathsObject() {
        val src = SourceFile.kotlin(
            "LoginValues.kt",
            """
            package app

            import io.kformik.ksp.FormValues

            @FormValues
            data class LoginValues(
                val email: String,
                val password: String,
            )
            """.trimIndent()
        )
        val (comp, result) = runCompile(src)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val gen = comp.generatedFile("app", "LoginValuesPaths.kt").readText()
        assertTrue("package decl missing", gen.contains("package app"))
        assertTrue("Paths object missing", gen.contains("object LoginValuesPaths {"))
        assertTrue("email const missing", Regex("""const val email: String = "email"""").containsMatchIn(gen))
        assertTrue("password const missing", Regex("""const val password: String = "password"""").containsMatchIn(gen))
    }

    // ─────────────────────────────────────────────────────────────────── nested

    @Test
    fun nested_dataClass_generatesNestedObjectScopes() {
        val src = SourceFile.kotlin(
            "User.kt",
            """
            package app

            import io.kformik.ksp.FormValues

            @FormValues
            data class AddressValues(val city: String, val country: String)

            @FormValues
            data class UserValues(val name: String, val address: AddressValues)
            """.trimIndent()
        )
        val (comp, result) = runCompile(src)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        val gen = comp.generatedFile("app", "UserValuesPaths.kt").readText()
        assertTrue("UserValuesPaths object missing", gen.contains("object UserValuesPaths {"))
        assertTrue("name const missing", Regex("""const val name: String = "name"""").containsMatchIn(gen))
        assertTrue("nested object scope missing", Regex("""object address \{""").containsMatchIn(gen))
        assertTrue("nested city const missing", Regex("""const val city: String = "address.city"""").containsMatchIn(gen))
        assertTrue("nested country const missing", Regex("""const val country: String = "address.country"""").containsMatchIn(gen))
        // The generated identifier is backtick-quoted (`\$path`) so `$` is a valid identifier
        // character inside the backticks. Check the literal text.
        val dollar = '$'
        assertTrue("nested \$path const missing", gen.contains("const val `${dollar}path`: String = \"address\""))
    }

    // ─────────────────────────────────────────────────────────────── multiple types

    @Test
    fun multiple_annotatedClasses_eachGenerateTheirOwnPaths() {
        val src = SourceFile.kotlin(
            "Mix.kt",
            """
            package app

            import io.kformik.ksp.FormValues

            @FormValues
            data class A(val a1: String, val a2: Int)

            @FormValues
            data class B(val b1: String, val b2: String)
            """.trimIndent()
        )
        val (comp, result) = runCompile(src)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        val aGen = comp.generatedFile("app", "APaths.kt").readText()
        val bGen = comp.generatedFile("app", "BPaths.kt").readText()

        assertTrue(aGen.contains("object APaths {"))
        assertTrue(aGen.contains("\"a1\""))
        assertTrue(aGen.contains("\"a2\""))
        assertTrue(bGen.contains("object BPaths {"))
        assertTrue(bGen.contains("\"b1\""))
        assertTrue(bGen.contains("\"b2\""))
    }

    // ─────────────────────────────────────────────────────────── no annotations

    @Test
    fun noAnnotation_doesNotGenerateAnyFile() {
        val src = SourceFile.kotlin(
            "Plain.kt",
            """
            package app
            data class Plain(val x: String, val y: Int)
            """.trimIndent()
        )
        val (comp, result) = runCompile(src)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val root = comp.kspSourcesDir.resolve("kotlin/app")
        val pathsFile = root.walkTopDown().filter { it.isFile && it.name.endsWith("Paths.kt") }.toList()
        assertEquals(0, pathsFile.size)
    }

    // ────────────────────────────────────── multi-file nested + file-scoped deps

    /**
     * Multi-file nested case: AddressValues lives in `address.kt`, UserValues references it from
     * `user.kt`. This exercises the cross-file path that uses
     * [FormValuesProcessor.collectOriginatingFiles] to declare per-file KSP `Dependencies`.
     *
     * What we verify here:
     *  - Both annotated classes generate their `*Paths` + `*Updater`.
     *  - `UserValuesPaths` correctly embeds `AddressValues`' shape (the nested object scope).
     *  - The compilation succeeds end-to-end (KSP would error if a malformed `Dependencies` were
     *    passed — e.g., aggregating-false with an empty file set on KSP2 is rejected as
     *    "non-isolating" output, so reaching ExitCode.OK confirms `Dependencies(aggregating = false,
     *    decl.containingFile, nested.containingFile)` was constructed correctly).
     *
     * What this test does NOT verify (and cannot, given kctfork's API surface):
     *  - That on incremental re-compile, touching only `address.kt` re-invalidates only the
     *    Address/User outputs and nothing else. That guarantee is structural — it follows from the
     *    KSP contract for `Dependencies(aggregating = false, *files)` and is documented on
     *    [FormValuesProcessor.collectOriginatingFiles].
     */
    @Test
    fun multiFile_nested_compilesWithPerFileDependencies() {
        val address = SourceFile.kotlin(
            "address.kt",
            """
            package app

            import io.kformik.ksp.FormValues

            @FormValues
            data class AddressValues(val city: String, val country: String)
            """.trimIndent()
        )
        val user = SourceFile.kotlin(
            "user.kt",
            """
            package app

            import io.kformik.ksp.FormValues

            @FormValues
            data class UserValues(val name: String, val address: AddressValues)
            """.trimIndent()
        )
        val (comp, result) = runCompile(address, user)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        // All four outputs present.
        val addressPaths = comp.generatedFile("app", "AddressValuesPaths.kt").readText()
        val addressUpdater = comp.generatedFile("app", "AddressValuesUpdater.kt").readText()
        val userPaths = comp.generatedFile("app", "UserValuesPaths.kt").readText()
        val userUpdater = comp.generatedFile("app", "UserValuesUpdater.kt").readText()

        // Address's outputs name the address fields only.
        assertTrue(addressPaths.contains("object AddressValuesPaths {"))
        assertTrue(Regex("""const val city: String = "city"""").containsMatchIn(addressPaths))

        // User's Paths embeds the address sub-object with prefixed paths.
        assertTrue(userPaths.contains("object UserValuesPaths {"))
        assertTrue(userPaths.contains("object address {"))
        assertTrue(Regex("""const val city: String = "address\.city"""").containsMatchIn(userPaths))

        // User's Updater delegates into AddressValuesUpdater for the nested path.
        assertTrue(userUpdater.contains("AddressValuesUpdater.getAt(values.address"))
        assertTrue(userUpdater.contains("AddressValuesUpdater.setAt(values.address"))

        // Address's updater stands alone.
        assertTrue(addressUpdater.contains("object AddressValuesUpdater"))
    }

    // ───────────────────────────────────────────────────── service-provider wiring

    @Test
    fun service_provider_wiring_isPresentOnClasspath() {
        // The KSP framework loads providers via the `symbolProcessorProviders` list directly in
        // tests, not via ServiceLoader. We separately assert that the META-INF/services file is
        // shipped (real-world Gradle uses the ServiceLoader).
        val cls = Class.forName("io.kformik.ksp.FormValuesProcessorProvider")
        assertNotNull(cls)
        val resource = cls.classLoader.getResource(
            "META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider"
        )
        assertNotNull("META-INF/services file is missing on the classpath", resource)
        val content = resource!!.readText().trim()
        assertEquals("io.kformik.ksp.FormValuesProcessorProvider", content)
    }
}
