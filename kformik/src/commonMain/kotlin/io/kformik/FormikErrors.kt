package io.kformik

/**
 * Flat, path-keyed map of error messages.
 *
 * In Formik (JS), [FormikErrors] is a recursive mirror of [V] — e.g. `{ user: { name: "Required" } }`.
 * Recreating that in Kotlin would require either reflection or KSP. Instead Kformik uses a
 * flat map keyed by string paths (`"user.name"` → `"Required"`). The expressive power is
 * identical because every Formik consumer ultimately reads errors by path anyway
 * (`getIn(errors, fieldName)`).
 */
data class FormikErrors(val byPath: Map<String, String> = emptyMap()) {

    operator fun get(path: String): String? = byPath[path]

    fun contains(path: String): Boolean = byPath.containsKey(path)

    val isEmpty: Boolean get() = byPath.isEmpty()
    val isNotEmpty: Boolean get() = byPath.isNotEmpty()
    val size: Int get() = byPath.size

    /** Returns a copy with `path` set to `message`, or removed if `message` is null. */
    fun with(path: String, message: String?): FormikErrors =
        if (message == null) FormikErrors(byPath - path)
        else FormikErrors(byPath + (path to message))

    /** Returns a copy with all entries of `other` overlaid on top of this one. */
    fun overlay(other: FormikErrors): FormikErrors =
        if (other.isEmpty) this else FormikErrors(byPath + other.byPath)

    companion object {
        val Empty: FormikErrors = FormikErrors()

        /** Convenience builder: `FormikErrors.build { put("name", "Required") }`. */
        inline fun build(block: MutableMap<String, String>.() -> Unit): FormikErrors =
            FormikErrors(buildMap(block))
    }
}

/** DSL equivalent of `FormikErrors.build`. */
inline fun buildErrors(block: MutableMap<String, String>.() -> Unit): FormikErrors =
    FormikErrors(buildMap(block))
