package io.kformik.examples.fieldlevel

import io.kformik.FormikConfig
import io.kformik.FormikController
import io.kformik.FormikErrors
import kotlinx.coroutines.runBlocking

/**
 * Field-level validation example. Mirrors Formik's `examples/field-level-validation` — each field
 * carries its own validator, registered via [FormikController.registerField]. The form-level
 * `validate` is unused; per-field validators are the source of truth.
 *
 * Demonstrates [FormikController.validateField] (validates just one field) and
 * [FormikController.validateForm] (runs every registered validator).
 */
object FieldLevelValidationExample {

    private fun required(message: String): suspend (Any?) -> String? = { v ->
        if ((v as? String).isNullOrBlank()) message else null
    }

    private fun minLength(n: Int, message: String): suspend (Any?) -> String? = { v ->
        if ((v as? String).orEmpty().length < n) message else null
    }

    fun build(scope: kotlinx.coroutines.CoroutineScope): FormikController<Map<String, Any?>> {
        val controller = FormikController(
            FormikConfig(
                initialValues = mapOf<String, Any?>("username" to "", "email" to ""),
                onSubmit = { v, actions ->
                    actions.setStatus("Hi ${v["username"]} — confirmation sent to ${v["email"]}")
                },
                coroutineScope = scope,
            )
        )

        // Per-field validators
        controller.registerField("username", required("This field is required"))
        controller.registerField("email") { v ->
            val s = (v as? String).orEmpty()
            when {
                s.isBlank() -> "This field is required"
                "@" !in s -> "Looks malformed"
                else -> null
            }
        }

        return controller
    }
}

fun main() = runBlocking {
    val form = FieldLevelValidationExample.build(this)

    println("--- Bare submit, no values set ---")
    form.submit()
    println("Errors: ${form.state.value.errors.byPath}")
    println("Touched: ${form.state.value.touched.byPath}")

    println("--- Set username, validate one field ---")
    form.setFieldValue("username", "aisha", shouldValidate = false)
    val msg = form.validateField("username")
    println("validateField(username) = $msg")

    println("--- Set email and submit ---")
    form.setFieldValue("email", "aisha@example.com", shouldValidate = false)
    form.submit()
    println("Status: ${form.state.value.status}")
    println("SubmitCount: ${form.state.value.submitCount}")
}
