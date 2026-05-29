package io.kformik.examples.schema

import io.kformik.FormikConfig
import io.kformik.FormikController
import io.kformik.buildErrors
import io.kformik.formSchema
import kotlinx.coroutines.runBlocking

/**
 * Schema-based validation. Mirrors Formik's `examples/SchemaValidation.js` (Yup) using the
 * Kformik [io.kformik.formSchema] DSL.
 *
 * Compared to Yup: no type coercion; rules see raw values. Cross-field rules use [cross] to
 * express constraints that span multiple paths.
 */
object SchemaValidationExample {

    private val schema = formSchema<Map<String, Any?>> {
        field("email") {
            required("Email is required")
            email("Invalid email")
        }
        field("password") {
            required("Password is required")
            minLength(8, "Min 8 characters")
        }
        field("confirmPassword") {
            required("Please confirm your password")
        }
        cross { v ->
            val p = v["password"] as? String ?: return@cross io.kformik.FormikErrors.Empty
            val c = v["confirmPassword"] as? String ?: return@cross io.kformik.FormikErrors.Empty
            if (p != c) buildErrors { put("confirmPassword", "Passwords must match") }
            else io.kformik.FormikErrors.Empty
        }
    }

    fun build(scope: kotlinx.coroutines.CoroutineScope): FormikController<Map<String, Any?>> {
        return FormikController(
            FormikConfig(
                initialValues = mapOf<String, Any?>(
                    "email" to "",
                    "password" to "",
                    "confirmPassword" to "",
                ),
                schemaValidator = schema,
                onSubmit = { v, actions -> actions.setStatus("Signed up: ${v["email"]}") },
                coroutineScope = scope,
            )
        )
    }
}

fun main() = runBlocking {
    val form = SchemaValidationExample.build(this)

    println("--- empty submit ---")
    form.submit()
    println("errors: ${form.state.value.errors.byPath}")

    println("--- partial fill ---")
    form.setFieldValue("email", "not-an-email", shouldValidate = false)
    form.setFieldValue("password", "short", shouldValidate = false)
    form.validateForm()
    println("errors: ${form.state.value.errors.byPath}")

    println("--- correct values ---")
    form.setFieldValue("email", "aisha@example.com", shouldValidate = false)
    form.setFieldValue("password", "hunter22long", shouldValidate = false)
    form.setFieldValue("confirmPassword", "hunter22long", shouldValidate = false)
    form.submit()
    println("status: ${form.state.value.status}")
    println("submitCount: ${form.state.value.submitCount}")
}
