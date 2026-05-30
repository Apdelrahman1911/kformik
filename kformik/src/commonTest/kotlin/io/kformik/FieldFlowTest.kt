package io.kformik

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression test for field-grained observation ([FormikController.fieldFlow]): a collector of one
 * field's flow is only notified when that field's own slices change, not on changes to other fields.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FieldFlowTest {

    @Test
    fun fieldFlow_emitsOnlyForOwnFieldChanges() = runTest {
        // Unconfined dispatcher so StateFlow emissions are delivered eagerly and deterministically.
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val c = FormikController(
            FormikConfig(
                initialValues = mapOf<String, Any?>("a" to "", "b" to ""),
                onSubmit = { _, _ -> },
                validateOnChange = false,
                coroutineScope = scope,
            )
        )
        val seen = mutableListOf<Any?>()
        val collector = scope.launch {
            c.fieldFlow("a").collect { seen += it.value }
        }
        val afterSubscribe = seen.size // initial emission of "a" == ""
        assertEquals(1, afterSubscribe, "collector should receive the initial value")

        // Change a different field: must NOT emit on field "a"'s flow.
        c.setFieldValue("b", "hello", shouldValidate = false)
        assertEquals(afterSubscribe, seen.size, "changing another field must not emit on this field's flow")

        // Change this field: must emit exactly once more.
        c.setFieldValue("a", "world", shouldValidate = false)
        assertEquals(afterSubscribe + 1, seen.size)
        assertEquals("world", seen.last())

        collector.cancel()
        scope.coroutineContext[Job]?.cancel()
    }
}
