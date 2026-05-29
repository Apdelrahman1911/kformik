package io.kformik

/**
 * A schema-style validator — a single object that, given the values, returns the full error map.
 *
 * This is the Kotlin equivalent of Formik's `validationSchema` (a Yup schema in JS). Because we
 * cannot depend on Yup in Kotlin Multiplatform, we expose this generic interface. A real adapter
 * (e.g. for [Valiktor](https://www.valiktor.com), or hand-written `when` blocks) is left to the
 * consumer; the interface keeps the contract minimal.
 *
 * Implementations are expected to be **safe under cancellation**: if the coroutine running
 * [validate] is cancelled, the implementation should not retain references to the partial state.
 */
fun interface SchemaValidator<V> {
    /** Returns the full error map for [values]. Empty map means "valid". */
    suspend fun validate(values: V): FormikErrors
}

/** Per-field validator: takes the field's value and returns either an error message or null. */
typealias FieldValidator = suspend (Any?) -> String?
