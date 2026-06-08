package io.kformik.ksp

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import io.kformik.FormikConfig
import io.kformik.FormikController
import io.kformik.ValuesUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * End-to-end integration of the KSP-generated [ValuesUpdater] with [FormikController]<V> where V is
 * a typed `data class`. The other `:kformik-ksp` test files exercise generation correctness and
 * isolated updater behavior (via classloader reflection on the generated `INSTANCE`), but none of
 * them plugs the generated updater into a live [FormikController] and drives a full
 * setFieldValue / submit / resetForm cycle. That's the headline value-prop of the `:kformik-ksp`
 * module — "your form is `MyData`, not `Map<String, Any?>`" — and it is what this test pins.
 *
 * Mechanically: compile a small `@FormValues data class User(...)` via kctfork, load the generated
 * `UserUpdater.INSTANCE` from the test classloader, hand it to `FormikController<Any>` (V is
 * erased on JVM, so passing the typed updater through an `Any`-erased controller is sound — the
 * runtime cast inside the updater's `setAt(values: Any, ...): Any` is a no-op on the JVM). Then
 * exercise the public mutation paths and assert the typed values round-trip via reflection on the
 * `User` class.
 */
class TypedValuesEndToEndTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun typedDataClass_withKspGeneratedUpdater_roundTripsThrough_FormikController() = runTest {
        val src = SourceFile.kotlin(
            "User.kt",
            """
            package app
            import io.kformik.ksp.FormValues
            @FormValues
            data class User(val name: String, val age: Int)
            """.trimIndent()
        )
        val (_, result) = runCompile(src)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        val cl = result.classLoader
        val userCls = cl.loadClass("app.User")
        val updaterCls = cl.loadClass("app.UserUpdater")
        // The generated singleton `object UserUpdater : ValuesUpdater<User>` is exposed as a public
        // INSTANCE field; load it via reflection because we can't statically reference the User
        // type from the test classloader.
        @Suppress("UNCHECKED_CAST")
        val updater = updaterCls.getField("INSTANCE").get(null) as ValuesUpdater<Any>

        val initial: Any = userCls
            .getDeclaredConstructor(String::class.java, Int::class.javaPrimitiveType)
            .newInstance("Aisha", 30)

        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        var submittedValues: Any? = null
        val controller = FormikController(
            FormikConfig(
                initialValues = initial,
                valuesUpdater = updater,
                onSubmit = { values, _ -> submittedValues = values },
                coroutineScope = scope,
            )
        )

        val getName = userCls.getMethod("getName")
        val getAge = userCls.getMethod("getAge")

        // Initial state — typed values are stored verbatim.
        val s0 = controller.state.value.values
        assertEquals("Aisha", getName.invoke(s0))
        assertEquals(30, getAge.invoke(s0))

        // setFieldValue mutates the typed values through the generated updater's `copy`-based setAt.
        controller.setFieldValue("name", "Bob")
        yield()
        val s1 = controller.state.value.values
        assertEquals("Bob", getName.invoke(s1), "setFieldValue must mutate the typed field through the generated copy()")
        assertEquals(30, getAge.invoke(s1), "unrelated field stays untouched")

        controller.setFieldValue("age", 42)
        yield()
        val s2 = controller.state.value.values
        assertEquals("Bob", getName.invoke(s2))
        assertEquals(42, getAge.invoke(s2))

        // submit() routes the final typed values to onSubmit.
        controller.submit()
        yield()
        assertNotNull(submittedValues, "onSubmit must fire with the typed values map")
        assertEquals("Bob", getName.invoke(submittedValues))
        assertEquals(42, getAge.invoke(submittedValues))
        assertEquals(1, controller.state.value.submitCount)

        // resetForm() restores the typed initial baseline through the generated updater.
        controller.resetForm()
        yield()
        val sR = controller.state.value.values
        assertEquals("Aisha", getName.invoke(sR), "resetForm must restore the typed initial values")
        assertEquals(30, getAge.invoke(sR))
        assertEquals(0, controller.state.value.submitCount, "resetForm clears submitCount per Formik contract")
    }

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
}
