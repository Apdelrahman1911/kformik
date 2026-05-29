package io.kformik.examples.fieldarray

import io.kformik.FormikConfig
import io.kformik.FormikController
import io.kformik.array
import io.kformik.buildErrors
import kotlinx.coroutines.runBlocking

/**
 * Dynamic list editing. Mirrors Formik's `examples/field-arrays`.
 *
 * The form holds a list of friends. We demonstrate every mutation helper:
 * `push`, `pop`, `unshift`, `insert`, `replace`, `swap`, `move`, `remove`.
 *
 * Validation marks empty entries; errors stay aligned with the values list across mutations.
 */
object FieldArrayExample {

    fun build(scope: kotlinx.coroutines.CoroutineScope): FormikController<Map<String, Any?>> {
        return FormikController(
            FormikConfig(
                initialValues = mapOf<String, Any?>(
                    "owner" to "Aisha",
                    "friends" to listOf("alpha", "beta", "gamma"),
                ),
                validate = { v ->
                    buildErrors {
                        val friends = v["friends"] as List<*>
                        if (friends.size < 3) put("friends", "Need at least 3 friends")
                        friends.forEachIndexed { i, name ->
                            if ((name as String).isBlank()) put("friends[$i]", "Required")
                        }
                    }
                },
                onSubmit = { v, _ -> println("submitted: $v") },
                coroutineScope = scope,
            )
        )
    }
}

fun main() = runBlocking {
    val form = FieldArrayExample.build(this)
    val friends = form.array("friends")

    println("initial: ${form.valueAt("friends")}")

    friends.push("delta")
    println("after push: ${form.valueAt("friends")}")

    friends.unshift("ZERO")
    println("after unshift: ${form.valueAt("friends")}")

    friends.insert(2, "INSERTED")
    println("after insert(2, INSERTED): ${form.valueAt("friends")}")

    friends.replace(0, "REPLACED")
    println("after replace(0, REPLACED): ${form.valueAt("friends")}")

    friends.swap(0, friends.size() - 1)
    println("after swap(0, last): ${form.valueAt("friends")}")

    friends.move(friends.size() - 1, 0)
    println("after move(last, 0): ${form.valueAt("friends")}")

    val popped = friends.pop()
    println("popped '$popped': ${form.valueAt("friends")}")

    val removed = friends.remove(1)
    println("removed '$removed': ${form.valueAt("friends")}")

    println("errors: ${form.state.value.errors.byPath}")
    form.submit()
    println("final state: submitCount=${form.state.value.submitCount} errors=${form.state.value.errors.byPath}")
}
