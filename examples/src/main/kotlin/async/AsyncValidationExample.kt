package io.kformik.examples.async

import io.kformik.FormikConfig
import io.kformik.FormikController
import io.kformik.buildErrors
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Async validation example. The `validate` callback is `suspend`, so we can `delay` (or call a
 * real API) and the controller's `isValidating` flag flips correctly.
 *
 * Mirrors Formik's `examples/AsyncValidation.js`.
 */
object AsyncValidationExample {

    private val reserved = setOf("admin", "root", "null", "god")

    fun build(scope: kotlinx.coroutines.CoroutineScope): FormikController<Map<String, Any?>> {
        return FormikController(
            FormikConfig(
                initialValues = mapOf("username" to ""),
                validate = { v ->
                    delay(300) // simulate API round-trip
                    val username = v["username"] as String
                    buildErrors {
                        if (username.isBlank()) put("username", "Required")
                        else if (username.lowercase() in reserved) put("username", "Nice try")
                    }
                },
                onSubmit = { values, _ ->
                    delay(500)
                    println("Account created for ${values["username"]}")
                },
                coroutineScope = scope,
            )
        )
    }
}

fun main() = runBlocking {
    val form = AsyncValidationExample.build(this)
    form.setFieldValue("username", "admin")
    println("After 'admin': ${form.state.value.errors.byPath}")
    form.setFieldValue("username", "aisha")
    println("After 'aisha': ${form.state.value.errors.byPath}")
    form.submit()
}
