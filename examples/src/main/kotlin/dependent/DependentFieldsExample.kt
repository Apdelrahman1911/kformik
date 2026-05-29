package io.kformik.examples.dependent

import io.kformik.FormikConfig
import io.kformik.FormikController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

/**
 * Dependent fields. Mirrors Formik's `examples/dependent-fields` where `textC` is derived from
 * `textA` + `textB` whenever both have been touched and are non-empty.
 *
 * In Formik this lives in a React `useEffect`. In Kformik we observe the `state` flow and react
 * by calling `setFieldValue`. This is the idiomatic KMP pattern: no React lifecycle, just a
 * coroutine collecting from the controller's `state`.
 */
object DependentFieldsExample {

    fun build(scope: kotlinx.coroutines.CoroutineScope): FormikController<Map<String, Any?>> {
        val controller = FormikController(
            FormikConfig(
                initialValues = mapOf<String, Any?>("textA" to "", "textB" to "", "textC" to ""),
                onSubmit = { v, _ -> println("submitted: $v") },
                coroutineScope = scope,
            )
        )

        // Reactive derivation: whenever textA, textB, or their touched flags change, recompute textC.
        scope.launch {
            controller.state
                .map { Triple(it.values["textA"] as String, it.values["textB"] as String, it.touched["textA"] && it.touched["textB"]) }
                .distinctUntilChanged()
                .drop(1) // skip initial emission so we don't fight first render
                .collect { (a, b, bothTouched) ->
                    if (a.isNotBlank() && b.isNotBlank() && bothTouched) {
                        controller.setFieldValue("textC", "textA: $a, textB: $b", shouldValidate = false)
                    }
                }
        }

        return controller
    }
}

fun main() = runBlocking {
    // Use an isolated scope so cancelling it at the end terminates the derived-field collector.
    val ownScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val form = DependentFieldsExample.build(ownScope)

    form.setFieldValue("textA", "alpha", shouldValidate = false)
    form.setFieldTouched("textA", true, shouldValidate = false)
    yield()
    println("After textA: ${form.state.value.values["textC"]}")  // still empty — textB not set

    form.setFieldValue("textB", "beta", shouldValidate = false)
    form.setFieldTouched("textB", true, shouldValidate = false)
    yield()
    println("After both: ${form.state.value.values["textC"]}")   // textC populated

    form.submit()
    ownScope.cancel()
}
