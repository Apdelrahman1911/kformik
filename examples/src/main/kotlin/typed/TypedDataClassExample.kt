package io.kformik.examples.typed

import io.kformik.FormikConfig
import io.kformik.FormikController
import io.kformik.ValuesUpdater
import io.kformik.buildErrors
import kotlinx.coroutines.runBlocking

/**
 * Typed `data class` form values with a hand-written [ValuesUpdater].
 *
 * For `Map<String, Any?>`-shaped values, Kformik picks `MapValuesUpdater` automatically. For
 * typed `data class` values, you supply a custom updater. The updater is ~10 lines of
 * `when`-based boilerplate per form — no reflection or codegen required.
 */
data class LoginValues(
    val email: String,
    val password: String,
)

object LoginValuesUpdater : ValuesUpdater<LoginValues> {

    override fun getAt(values: LoginValues, path: String): Any? = when (path) {
        "email" -> values.email
        "password" -> values.password
        else -> null
    }

    override fun setAt(values: LoginValues, path: String, value: Any?): LoginValues = when (path) {
        "email" -> values.copy(email = value as String)
        "password" -> values.copy(password = value as String)
        else -> error("Unknown field: $path")
    }

    // Needed for submit() to touch every leaf, even fields the user hasn't called setFieldValue on yet.
    override fun leafPaths(values: LoginValues): Set<String> = setOf("email", "password")
}

object TypedDataClassExample {

    fun build(scope: kotlinx.coroutines.CoroutineScope): FormikController<LoginValues> {
        return FormikController(
            FormikConfig(
                initialValues = LoginValues(email = "", password = ""),
                valuesUpdater = LoginValuesUpdater,
                validate = { v ->
                    buildErrors {
                        if (v.email.isBlank()) put("email", "Email is required")
                        else if (!v.email.contains("@")) put("email", "Invalid email")
                        if (v.password.length < 8) put("password", "Min 8 characters")
                    }
                },
                onSubmit = { v, actions ->
                    actions.setStatus("Submitted as ${v.email}")
                },
                coroutineScope = scope,
            )
        )
    }
}

fun main() = runBlocking {
    val form = TypedDataClassExample.build(this)
    form.setFieldValue("email", "user@example.com")
    form.setFieldValue("password", "hunter22")
    form.submit()
    println("Typed state: ${form.state.value}")
}
