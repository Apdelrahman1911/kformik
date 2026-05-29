package io.kformik.examples.debounced

import io.kformik.FormikConfig
import io.kformik.FormikController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds

/**
 * Debounced auto-save. Mirrors Formik's `examples/DebouncedAutoSave.js`.
 *
 * The controller's `state` flow is the source of truth. We `debounce(...)` the value stream and
 * call a fake `save()` whenever the user stops typing for 250 ms. No special debounce library
 * needed — kotlinx.coroutines.Flow already has it.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
object DebouncedAutoSaveExample {

    fun build(
        scope: CoroutineScope,
        onAutoSave: suspend (Map<String, Any?>) -> Unit,
    ): FormikController<Map<String, Any?>> {
        val controller = FormikController(
            FormikConfig(
                initialValues = mapOf<String, Any?>("title" to "", "body" to ""),
                onSubmit = { v, _ -> onAutoSave(v) },
                coroutineScope = scope,
            )
        )

        scope.launch {
            controller.state
                .map { it.values }
                .distinctUntilChanged()
                .drop(1) // skip initial emission
                .debounce(250.milliseconds)
                .collect { vals -> onAutoSave(vals) }
        }

        return controller
    }
}

fun main() = runBlocking {
    val ownScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var saveCount = 0
    val form = DebouncedAutoSaveExample.build(ownScope) { v ->
        saveCount++
        println("auto-save #$saveCount: $v")
    }

    form.setFieldValue("title", "draft 1", shouldValidate = false); delay(50)
    form.setFieldValue("title", "draft 2", shouldValidate = false); delay(50)
    form.setFieldValue("title", "final", shouldValidate = false)

    delay(500) // wait past the debounce window
    println("Total saves: $saveCount")  // expect 1
    ownScope.cancel()
}
