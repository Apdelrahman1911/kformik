package io.kformik.examples.nested

import io.kformik.FormikConfig
import io.kformik.FormikController
import io.kformik.buildErrors
import io.kformik.path
import kotlinx.coroutines.runBlocking

/**
 * Nested-values example. The form values are a deeply-nested map (`user.address.city`,
 * `tags[1]`). The library's [MapValuesUpdater] handles writes through dotted/bracket paths
 * with structural sharing.
 */
object NestedExample {

    fun build(scope: kotlinx.coroutines.CoroutineScope): FormikController<Map<String, Any?>> {
        return FormikController(
            FormikConfig(
                initialValues = mapOf(
                    "user" to mapOf(
                        "name" to "",
                        "address" to mapOf("city" to "", "country" to "NG"),
                    ),
                    "tags" to listOf("alpha", "beta"),
                ),
                validate = { v ->
                    buildErrors {
                        if ((v.path("user.name") as String).isBlank())
                            put("user.name", "Required")
                        val city = v.path("user.address.city") as String
                        if (city.isBlank()) put("user.address.city", "Required")
                        val tags = v.path("tags") as List<*>
                        if (tags.size < 2) put("tags", "Need at least 2 tags")
                    }
                },
                onSubmit = { values, _ -> println("submitted: $values") },
                coroutineScope = scope,
            )
        )
    }
}

fun main() = runBlocking {
    val form = NestedExample.build(this)
    form.setFieldValue("user.name", "Aisha")
    form.setFieldValue("user.address.city", "Lagos")
    form.setFieldValue("tags[1]", "gamma")
    form.submit()
}
