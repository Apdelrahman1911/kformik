package io.kformik

/**
 * The "helpers bag" passed into `onSubmit`/`onReset` callbacks. Matches Formik's `FormikHelpers<V>`.
 *
 * Note that any method that may run validation is `suspend` — Kotlin's coroutines remove the
 * sync/async ambiguity that Formik had to navigate by sniffing for promises.
 */
interface FormikActions<V> {
    suspend fun setFieldValue(name: String, value: Any?, shouldValidate: Boolean? = null)

    /**
     * Updater-function form of [setFieldValue]. The updater receives the *current* value at the
     * path (read via the controller's [ValuesUpdater.getAt]) and returns the new value.
     * Mirrors Formik's `setFieldValue(field, (prev) => next)` API.
     */
    suspend fun setFieldValue(name: String, updater: (Any?) -> Any?, shouldValidate: Boolean? = null)

    suspend fun setValues(values: V, shouldValidate: Boolean? = null)
    suspend fun setValues(updater: (V) -> V, shouldValidate: Boolean? = null)
    suspend fun setFieldTouched(name: String, isTouched: Boolean = true, shouldValidate: Boolean? = null)
    suspend fun setTouched(touched: FormikTouched, shouldValidate: Boolean? = null)
    fun setFieldError(name: String, message: String?)
    fun setErrors(errors: FormikErrors)
    fun setStatus(status: Any?)
    fun setSubmitting(isSubmitting: Boolean)
    fun setFormikState(updater: (FormikState<V>) -> FormikState<V>)
    suspend fun validateForm(values: V? = null): FormikErrors
    suspend fun validateField(name: String): String?
    suspend fun resetForm(nextState: FormikState<V>? = null)
    suspend fun submit()
}
