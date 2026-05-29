package io.kformik.examples.wizard

import io.kformik.FormikConfig
import io.kformik.FormikController
import io.kformik.buildErrors
import kotlinx.coroutines.runBlocking

/**
 * Multistep wizard. Mirrors Formik's `examples/MultistepWizard.js` — multiple `<Formik>`
 * instances composed into a flow that submits a single aggregated payload at the end.
 *
 * In Kformik we just create N controllers and aggregate their `state.values` at submit time.
 * Each step has its own validation.
 */
class MultistepWizard(private val scope: kotlinx.coroutines.CoroutineScope) {

    val step1: FormikController<Map<String, Any?>> = FormikController(
        FormikConfig(
            initialValues = mapOf("firstName" to "", "lastName" to ""),
            validate = { v ->
                buildErrors {
                    if ((v["firstName"] as String).isBlank()) put("firstName", "Required")
                    if ((v["lastName"] as String).isBlank()) put("lastName", "Required")
                }
            },
            onSubmit = { _, _ -> /* no-op: aggregated at the end */ },
            coroutineScope = scope,
        )
    )

    val step2: FormikController<Map<String, Any?>> = FormikController(
        FormikConfig(
            initialValues = mapOf("email" to "", "phone" to ""),
            validate = { v ->
                buildErrors {
                    val email = v["email"] as String
                    if (email.isBlank()) put("email", "Required")
                    else if ("@" !in email) put("email", "Invalid")
                    if ((v["phone"] as String).length < 7) put("phone", "Min 7 digits")
                }
            },
            onSubmit = { _, _ -> },
            coroutineScope = scope,
        )
    )

    val step3: FormikController<Map<String, Any?>> = FormikController(
        FormikConfig(
            initialValues = mapOf("agree" to false),
            validate = { v ->
                buildErrors {
                    if (v["agree"] != true) put("agree", "You must agree to continue")
                }
            },
            onSubmit = { _, _ -> },
            coroutineScope = scope,
        )
    )

    /** Submit a step. Returns true if it passed validation. */
    suspend fun advance(step: FormikController<Map<String, Any?>>): Boolean {
        step.submit()
        return step.state.value.errors.isEmpty
    }

    /** Final submit — aggregates every step's values into one payload. */
    suspend fun finish(): Map<String, Any?>? {
        val ok1 = advance(step1)
        val ok2 = advance(step2)
        val ok3 = advance(step3)
        return if (ok1 && ok2 && ok3) step1.state.value.values + step2.state.value.values + step3.state.value.values
        else null
    }
}

fun main() = runBlocking {
    val wizard = MultistepWizard(this)

    wizard.step1.setFieldValue("firstName", "Aisha", shouldValidate = false)
    wizard.step1.setFieldValue("lastName", "Bello", shouldValidate = false)
    wizard.step2.setFieldValue("email", "aisha@example.com", shouldValidate = false)
    wizard.step2.setFieldValue("phone", "08012345678", shouldValidate = false)
    wizard.step3.setFieldValue("agree", true, shouldValidate = false)

    val final = wizard.finish()
    println("Final payload: $final")
}
