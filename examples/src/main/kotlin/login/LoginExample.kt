package io.kformik.examples.login

import io.kformik.FormikConfig
import io.kformik.FormikController
import io.kformik.buildErrors
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Login form example. Multiplatform-friendly: uses Map<String, Any?> as the values type so it
 * runs as-is on Android, iOS, and JVM.
 *
 * Run on JVM with:
 *   kotlinc -cp <kformik-classpath> -script LoginExample.kt
 * or by calling [main] from your platform entrypoint.
 */
object LoginExample {

    fun build(scope: kotlinx.coroutines.CoroutineScope): FormikController<Map<String, Any?>> {
        return FormikController(
            FormikConfig(
                initialValues = mapOf("email" to "", "password" to ""),
                validate = { v ->
                    buildErrors {
                        val email = v["email"] as String
                        val password = v["password"] as String
                        if (email.isBlank()) put("email", "Email is required")
                        else if (!email.contains("@")) put("email", "Invalid email")
                        if (password.length < 8) put("password", "Password must be at least 8 characters")
                    }
                },
                onSubmit = { values, actions ->
                    actions.setStatus(null)
                    delay(200) // simulate network call
                    val email = values["email"] as String
                    if (email.endsWith("@blocked.com")) {
                        actions.setFieldError("email", "Domain blocked")
                    } else {
                        actions.setStatus("Welcome, $email")
                    }
                },
                coroutineScope = scope,
            )
        )
    }
}

/** A runnable JVM demo. */
fun main() = runBlocking {
    val form = LoginExample.build(this)
    form.setFieldValue("email", "user@example.com")
    form.setFieldValue("password", "hunter22")
    form.submit()
    println("State: ${form.state.value}")
}
