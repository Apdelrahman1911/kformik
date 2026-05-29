package io.kformik

/**
 * Flat, path-keyed map of touched flags.
 *
 * See [FormikErrors] for the rationale behind the path-keyed (vs. recursive) representation.
 */
data class FormikTouched(val byPath: Map<String, Boolean> = emptyMap()) {

    operator fun get(path: String): Boolean = byPath[path] ?: false

    fun contains(path: String): Boolean = byPath.containsKey(path)

    val isEmpty: Boolean get() = byPath.isEmpty()
    val size: Int get() = byPath.size

    /** Returns a copy with `path` set to `touched`. */
    fun with(path: String, touched: Boolean): FormikTouched =
        FormikTouched(byPath + (path to touched))

    /** Returns a copy with all entries of `other` overlaid on top of this one. */
    fun overlay(other: FormikTouched): FormikTouched =
        if (other.isEmpty) this else FormikTouched(byPath + other.byPath)

    companion object {
        val Empty: FormikTouched = FormikTouched()
    }
}
