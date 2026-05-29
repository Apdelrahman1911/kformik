package io.kformik.compose

import io.kformik.FormikConfig
import io.kformik.FormikController
import io.kformik.buildErrors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JVM unit tests for [ComposeFormik]'s non-`@Composable` surface.
 *
 * The `@Composable` accessors (`state`, `dirty`, `isValid`, `fieldState`) require a Compose
 * runtime host (Activity / Compose UI testing), which we don't spin up here. We DO cover:
 *
 *  - `value`, `error`, `isTouched`, `displayError`  — snapshot reads
 *  - `setFieldValue(name, value)` + `setFieldValue(name, updater)` — fire-and-forget setters
 *  - `setFieldTouched`, `setFieldError`, `setStatus`, `setSubmitting`, `setErrors`
 *  - `submit()` and `resetForm()` — fire-and-forget routing through the underlying controller
 *  - `launch { ... }` — escape hatch onto the form scope
 *
 * Live UI behavior (`@Composable` state observation, recomposition triggers) is documented as
 * requiring an emulator or Compose UI test environment; see `docs/COMPOSE_USAGE.md`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComposeFormikTest {

    private val scopes = mutableListOf<CoroutineScope>()

    private fun build(
        initial: Map<String, Any?> = mapOf("email" to "", "password" to ""),
        validate: (suspend (Map<String, Any?>) -> io.kformik.FormikErrors)? = null,
    ): ComposeFormik<Map<String, Any?>> {
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        scopes += scope
        val controller = FormikController(
            FormikConfig(
                initialValues = initial,
                validate = validate,
                onSubmit = { _, _ -> },
                coroutineScope = scope,
            )
        )
        return ComposeFormik.forTesting(controller, scope)
    }

    @AfterTest
    fun cleanup() {
        scopes.forEach { runCatching { it.coroutineContext[Job]?.cancel() } }
        scopes.clear()
    }

    // ---------------------------------------------------------- snapshot accessors

    @Test
    fun value_readsCurrentField() = runTest {
        val f = build(mapOf("email" to "x@y.com", "password" to "secret"))
        assertEquals("x@y.com", f.value("email"))
        assertEquals("secret", f.value("password"))
    }

    @Test
    fun error_andIsTouched_reflectControllerState() = runTest {
        val f = build()
        f.setFieldError("email", "Required")
        yield()
        assertEquals("Required", f.error("email"))
        assertFalse(f.isTouched("email"))
        f.setFieldTouched("email", true)
        yield()
        assertTrue(f.isTouched("email"))
    }

    @Test
    fun displayError_gatedByTouched() = runTest {
        val f = build()
        f.setFieldError("email", "Required")
        yield()
        assertNull(f.displayError("email"))
        f.setFieldTouched("email", true, shouldValidate = false)
        yield()
        assertEquals("Required", f.displayError("email"))
    }

    // ----------------------------------------------------------------- setters

    @Test
    fun setFieldValue_routesToController() = runTest {
        val f = build()
        f.setFieldValue("email", "ian@example.com")
        yield()
        assertEquals("ian@example.com", f.value("email"))
    }

    @Test
    fun setFieldValue_updaterForm_routesToController() = runTest {
        val f = build(mapOf("email" to "base"))
        f.setFieldValue("email", updater = { prev -> "${prev}-suffix" })
        yield()
        assertEquals("base-suffix", f.value("email"))
    }

    @Test
    fun setFieldTouched_routesToController() = runTest {
        val f = build()
        f.setFieldTouched("email")
        yield()
        assertTrue(f.isTouched("email"))
    }

    @Test
    fun setFieldError_syncSet() = runTest {
        val f = build()
        f.setFieldError("email", "Bad")
        assertEquals("Bad", f.controller.state.value.errors["email"])
    }

    @Test
    fun setStatus_syncSet() = runTest {
        val f = build()
        f.setStatus("ok")
        assertEquals("ok", f.controller.state.value.status)
    }

    @Test
    fun setSubmitting_syncSet() = runTest {
        val f = build()
        f.setSubmitting(true)
        assertTrue(f.controller.state.value.isSubmitting)
    }

    @Test
    fun setErrors_syncReplaces() = runTest {
        val f = build()
        f.setErrors(io.kformik.FormikErrors(mapOf("a" to "x")))
        assertEquals("x", f.controller.state.value.errors["a"])
    }

    // ----------------------------------------------------------- submit / reset

    @Test
    fun submit_routesToController() = runTest {
        val f = build()
        f.submit()
        yield()
        assertEquals(1, f.controller.state.value.submitCount)
    }

    @Test
    fun resetForm_routesToController() = runTest {
        val f = build()
        f.setFieldValue("email", "x")
        yield()
        f.resetForm()
        yield()
        assertEquals("", f.value("email"))
    }

    // ---------------------------------------------------------------- launch

    @Test
    fun launch_blockReceivesActions() = runTest {
        val f = build()
        var seen: Any? = null
        f.launch {
            // 'this' is FormikActions<V> — call setStatus through the receiver
            setStatus("from-launch")
            seen = "ok"
        }
        yield()
        assertEquals("ok", seen)
        assertEquals("from-launch", f.controller.state.value.status)
    }

    // -------------------------------------------------------- controller escape

    @Test
    fun controller_isExposedForAdvancedUse() = runTest {
        val f = build()
        assertNotNull(f.controller)
        // Issue a suspend call through the controller directly
        f.controller.setFieldValue("password", "via-controller")
        assertEquals("via-controller", f.value("password"))
    }

    // --------------------------------------------- integration with validate

    @Test
    fun validate_runsOnSetFieldValue_andSurfacesErrors() = runTest {
        val f = build(validate = { v ->
            buildErrors {
                if ((v["email"] as String).isBlank()) put("email", "Required")
            }
        })
        f.setFieldValue("email", "")
        yield()
        assertEquals("Required", f.error("email"))
        f.setFieldValue("email", "x@y.com")
        yield()
        assertNull(f.error("email"))
    }
}

