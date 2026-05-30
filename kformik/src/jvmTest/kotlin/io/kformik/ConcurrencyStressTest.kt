package io.kformik

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Real multi-threaded stress tests (JVM only — `runTest`'s virtual-time scheduler is single
 * threaded and cannot exercise true preemption). These prove the documented thread-safety
 * guarantee: no lost updates under concurrent mutation, and no `ConcurrentModificationException`
 * from the field registry. They reliably fail against blind-write / plain-`mutableMap` code.
 */
class ConcurrencyStressTest {

    @Test
    fun concurrentArrayPush_noLostUpdates() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val c = FormikController(
                FormikConfig(
                    initialValues = mapOf("items" to emptyList<Int>()),
                    onSubmit = { _, _ -> },
                    coroutineScope = scope,
                )
            )
            val n = 300
            coroutineScope {
                repeat(n) { i ->
                    launch(Dispatchers.Default) { c.array("items").push(i, shouldValidate = false) }
                }
            }
            @Suppress("UNCHECKED_CAST")
            val items = c.state.value.values["items"] as List<Int>
            assertEquals(n, items.size, "every concurrent push must land (no lost updates)")
            assertEquals((0 until n).toSet(), items.toSet())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun concurrentValueAndErrorWrites_noLostUpdates() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val keys = (0 until 120).map { "f$it" }
            val c = FormikController(
                FormikConfig(
                    initialValues = keys.associateWith { "" } as Map<String, Any?>,
                    onSubmit = { _, _ -> },
                    validateOnChange = false,
                    coroutineScope = scope,
                )
            )
            // For each key, race a (mutex-guarded) value write against a (lock-free CAS) error write.
            // Because every mutex-held write is itself a compare-and-set on the latest state, neither
            // path clobbers the other — both must survive.
            coroutineScope {
                keys.forEach { k ->
                    launch(Dispatchers.Default) { c.setFieldValue(k, "v-$k", shouldValidate = false) }
                    launch(Dispatchers.Default) { c.setFieldError(k, "e-$k") }
                }
            }
            val st = c.state.value
            keys.forEach { k ->
                assertEquals("v-$k", st.values[k], "value write for $k lost")
                assertEquals("e-$k", st.errors[k], "error write for $k lost")
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun concurrentRegisterUnregister_duringValidation_noCrash() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val c = FormikController(
                FormikConfig(
                    initialValues = mapOf("name" to "x"),
                    validate = { FormikErrors.Empty },
                    onSubmit = { _, _ -> },
                    coroutineScope = scope,
                )
            )
            coroutineScope {
                // hammer the registry while validations iterate it
                repeat(200) { i ->
                    launch(Dispatchers.Default) { c.registerField("f$i") { null } }
                    launch(Dispatchers.Default) { c.validateForm() }
                    launch(Dispatchers.Default) { c.unregisterField("f$i") }
                }
            }
            // reaching here without a ConcurrentModificationException / native crash is the assertion
            assertEquals("x", c.valueAt("name"))
        } finally {
            scope.cancel()
        }
    }
}
