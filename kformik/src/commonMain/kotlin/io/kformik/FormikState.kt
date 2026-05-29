package io.kformik

/**
 * The full, observable form state. Matches Formik's `FormikState<Values>` 1-for-1.
 *
 * `status` is freeform (`Any?`) so consumers can drop arbitrary out-of-band info there
 * (e.g. a server-side success message). It is not interpreted by the controller.
 */
data class FormikState<V>(
    val values: V,
    val errors: FormikErrors = FormikErrors.Empty,
    val touched: FormikTouched = FormikTouched.Empty,
    val status: Any? = null,
    val isSubmitting: Boolean = false,
    val isValidating: Boolean = false,
    val submitCount: Int = 0,
)

/**
 * The snapshot of the form's initial state. Maintained by [FormikController] as a moving baseline
 * (it updates whenever [io.kformik.FormikController.resetForm] or
 * [io.kformik.FormikController.reinitialize] is called).
 */
data class FormikInitialState<V>(
    val values: V,
    val errors: FormikErrors = FormikErrors.Empty,
    val touched: FormikTouched = FormikTouched.Empty,
    val status: Any? = null,
)
