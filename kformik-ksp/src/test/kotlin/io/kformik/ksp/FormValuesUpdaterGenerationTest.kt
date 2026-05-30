package io.kformik.ksp

import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.kspSourcesDir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Compile-tests for the **generated `<Name>Updater`** (item 2 of v1.4).
 *
 * These tests not only assert that the generated source contains the expected text, but also
 * load the generated class via the compile-testing classloader and exercise its
 * `ValuesUpdater` contract (get / set / leafPaths) end-to-end.
 *
 * Coverage:
 *  - flat data class — `getAt`, `setAt`, `leafPaths` round-trip a plain `data class`
 *  - nested `@FormValues` — `setAt("address.city", …)` delegates to the nested `AddressUpdater`
 *  - leafPaths flattens nested children with the `<parent>.<child>` prefix
 *  - unknown paths return `null` from `getAt` and throw from `setAt`
 *  - `setAt` returns a NEW instance (data class `copy` semantics)
 */
class FormValuesUpdaterGenerationTest {

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

    // ────────────────────────────────────────────────────────────── flat

    @Test
    fun flat_dataClass_generatesUpdater_thatRoundTrips() {
        val src = SourceFile.kotlin(
            "LoginValues.kt",
            """
            package app

            import io.kformik.ksp.FormValues

            @FormValues
            data class LoginValues(val email: String, val password: String)
            """.trimIndent()
        )
        val (comp, result) = runCompile(src)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        val gen = comp.generatedFile("app", "LoginValuesUpdater.kt").readText()
        // Generated source must declare the updater object
        assertTrue("updater object missing", gen.contains("object LoginValuesUpdater : io.kformik.ValuesUpdater<LoginValues>"))
        assertTrue("getAt body missing", gen.contains("path == \"email\" -> values.email"))
        // setAt now: copy(email = (value ?: error(...)) as kotlin.String) — FQN cast + non-null guard.
        assertTrue("setAt body missing", gen.contains("path == \"email\" -> values.copy(email ="))
        assertTrue("FQN cast missing", gen.contains("as kotlin.String"))
        assertTrue("non-null guard missing", gen.contains("cannot be set to null"))
        assertTrue("leafPaths body missing", gen.contains("\"email\""))

        // Now load + invoke the compiled object
        val cl = result.classLoader
        val updaterCls = cl.loadClass("app.LoginValuesUpdater")
        val updater = updaterCls.getField("INSTANCE").get(null)
        val loginCls = cl.loadClass("app.LoginValues")
        val login = loginCls.getDeclaredConstructor(String::class.java, String::class.java)
            .newInstance("a@b.com", "secret")

        val getAt = updaterCls.getMethod("getAt", loginCls, String::class.java)
        assertEquals("a@b.com", getAt.invoke(updater, login, "email"))
        assertEquals("secret", getAt.invoke(updater, login, "password"))
        assertNull(getAt.invoke(updater, login, "unknown"))

        val setAt = updaterCls.getMethod("setAt", loginCls, String::class.java, Any::class.java)
        val updated = setAt.invoke(updater, login, "email", "new@example.com")
        assertNotNull(updated)
        assertEquals("new@example.com", getAt.invoke(updater, updated, "email"))
        // Original instance unchanged
        assertEquals("a@b.com", getAt.invoke(updater, login, "email"))

        val leafPaths = updaterCls.getMethod("leafPaths", loginCls)
        @Suppress("UNCHECKED_CAST")
        val paths = leafPaths.invoke(updater, login) as Set<String>
        assertEquals(setOf("email", "password"), paths)
    }

    // ────────────────────────────────────────────────────────────── nested

    @Test
    fun nested_dataClass_generatesUpdater_thatRecurses() {
        val src = SourceFile.kotlin(
            "User.kt",
            """
            package app

            import io.kformik.ksp.FormValues

            @FormValues data class AddressValues(val city: String, val country: String)
            @FormValues data class UserValues(val name: String, val address: AddressValues)
            """.trimIndent()
        )
        val (comp, result) = runCompile(src)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        val gen = comp.generatedFile("app", "UserValuesUpdater.kt").readText()
        // Generated nested delegation — nested updater references are package-qualified (here "app").
        assertTrue(gen.contains("path.startsWith(\"address.\") -> app.AddressValuesUpdater.getAt"))
        assertTrue(gen.contains("path.startsWith(\"address.\") -> values.copy(address = app.AddressValuesUpdater.setAt"))
        // Generated leafPaths flattens
        assertTrue(gen.contains("app.AddressValuesUpdater.leafPaths"))

        val cl = result.classLoader
        val updaterCls = cl.loadClass("app.UserValuesUpdater")
        val updater = updaterCls.getField("INSTANCE").get(null)
        val addrCls = cl.loadClass("app.AddressValues")
        val userCls = cl.loadClass("app.UserValues")

        val addr = addrCls.getDeclaredConstructor(String::class.java, String::class.java)
            .newInstance("Lagos", "NG")
        val user = userCls.getDeclaredConstructor(String::class.java, addrCls)
            .newInstance("Aisha", addr)

        val getAt = updaterCls.getMethod("getAt", userCls, String::class.java)
        assertEquals("Aisha", getAt.invoke(updater, user, "name"))
        assertEquals("Lagos", getAt.invoke(updater, user, "address.city"))
        assertEquals("NG", getAt.invoke(updater, user, "address.country"))

        val setAt = updaterCls.getMethod("setAt", userCls, String::class.java, Any::class.java)
        val updatedUser = setAt.invoke(updater, user, "address.city", "Abuja")
        assertEquals("Abuja", getAt.invoke(updater, updatedUser, "address.city"))
        // Other field unchanged
        assertEquals("NG", getAt.invoke(updater, updatedUser, "address.country"))
        assertEquals("Aisha", getAt.invoke(updater, updatedUser, "name"))

        val leafPaths = updaterCls.getMethod("leafPaths", userCls)
        @Suppress("UNCHECKED_CAST")
        val paths = leafPaths.invoke(updater, user) as Set<String>
        assertEquals(setOf("name", "address.city", "address.country"), paths)
    }

    // ────────────────────────────────────────────────────────────── unknown path

    @Test
    fun unknownPath_returnsNull_fromGet_andThrows_fromSet() {
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

        val getAt = updaterCls.getMethod("getAt", simpleCls, String::class.java)
        assertNull(getAt.invoke(updater, simple, "does-not-exist"))

        val setAt = updaterCls.getMethod("setAt", simpleCls, String::class.java, Any::class.java)
        val ex = runCatching { setAt.invoke(updater, simple, "does-not-exist", "x") }
            .exceptionOrNull()
        assertNotNull(ex)
        // The error message must include the bad path
        val msg = (ex!!.cause ?: ex).message ?: ""
        assertTrue("error must mention bad path; was: $msg", msg.contains("does-not-exist"))
    }
}
