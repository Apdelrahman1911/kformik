package io.kformik

/**
 * Imperative array-field helpers. Mirrors Formik's `<FieldArray>` mutation helpers:
 *
 * - [push] / [unshift] — add an element to the end / beginning.
 * - [pop] — remove and return the last element.
 * - [insert] — insert at a given index.
 * - [remove] — remove and return the element at a given index.
 * - [replace] — replace the element at a given index.
 * - [swap] — exchange elements at two indices.
 * - [move] — move an element from one index to another.
 *
 * The `touched` and `errors` arrays are aligned per Formik's semantics:
 *
 * | Helper    | Alters touched? | Alters errors? | Notes                                |
 * |-----------|-----------------|----------------|--------------------------------------|
 * | push      | no              | no             | adding a new row shouldn't mark touched |
 * | unshift   | yes (null prepended) | yes (null prepended) | indices stay aligned  |
 * | pop       | yes             | yes            | pops both                            |
 * | insert    | yes (null inserted) | yes (null inserted) | indices stay aligned |
 * | remove    | yes             | yes            | splices both                         |
 * | replace   | no              | no             | nothing structural changed           |
 * | swap      | yes             | yes            | swaps both                           |
 * | move      | yes             | yes            | moves both                           |
 *
 * Where alignment is required and the touched/errors entry at the array path doesn't exist or
 * isn't a list, no alignment is performed for that side.
 *
 * Each mutation respects [FormikConfig.validateOnChange] by default and runs validation if
 * the controller is configured to validate on change. A `shouldValidate` override is available
 * on every method.
 *
 * Obtain a [FieldArrayController] via [FormikController.array]:
 * ```kotlin
 * form.array("friends").push("aisha")
 * form.array("friends").remove(0)
 * form.array("user.tags").swap(0, 2)
 * ```
 *
 * Multi-statement operations are not transactional across calls (each helper takes the mutex,
 * applies its mutation, releases, then optionally validates). For batched edits use
 * [FormikController.setFormikState].
 *
 * @param V the form's values type.
 */
class FieldArrayController<V> internal constructor(
    private val controller: FormikController<V>,
    val path: String,
) {

    init {
        require(path.isNotBlank()) { "Field array path must not be blank" }
    }

    /** Convenience: current list value at [path], or empty if unresolved or not a list. */
    fun current(): List<Any?> = controller.valueAt(path) as? List<Any?> ?: emptyList()

    /** Convenience: current size. Returns 0 if the path doesn't resolve to a list. */
    fun size(): Int = current().size

    // ----------------------------------------------------------------------- mutations

    /** Append [value] to the end of the array. */
    suspend fun push(value: Any?, shouldValidate: Boolean? = null) {
        updateValues(
            fn = { it + value },
            alterTouched = false,
            alterErrors = false,
            shouldValidate = shouldValidate,
        )
    }

    /** Prepend [value] to the beginning of the array. Returns the new length. */
    suspend fun unshift(value: Any?, shouldValidate: Boolean? = null): Int {
        var newSize = 0
        updateValues(
            fn = { list -> (listOf<Any?>(value) + list).also { newSize = it.size } },
            alterTouched = true,
            alterErrors = true,
            alignFn = { list -> listOf<Any?>(null) + list },
            shouldValidate = shouldValidate,
        )
        return newSize
    }

    /** Remove the last element and return it. Returns null if the array is empty or absent. */
    suspend fun pop(shouldValidate: Boolean? = null): Any? {
        var popped: Any? = null
        updateValues(
            fn = { list ->
                if (list.isEmpty()) list
                else {
                    popped = list.last()
                    list.dropLast(1)
                }
            },
            alterTouched = true,
            alterErrors = true,
            shouldValidate = shouldValidate,
        )
        return popped
    }

    /** Insert [value] at [index]. Throws if [index] is outside `[0, size]`. */
    suspend fun insert(index: Int, value: Any?, shouldValidate: Boolean? = null) {
        updateValues(
            fn = { list ->
                require(index in 0..list.size) { "insert index $index out of bounds [0, ${list.size}]" }
                list.toMutableList().also { it.add(index, value) }
            },
            alterTouched = true,
            alterErrors = true,
            alignFn = { list -> list.toMutableList().also { it.add(minOf(index, it.size), null) } },
            shouldValidate = shouldValidate,
        )
    }

    /** Remove the element at [index] and return it. Throws if [index] is out of bounds. */
    suspend fun remove(index: Int, shouldValidate: Boolean? = null): Any? {
        var removed: Any? = null
        updateValues(
            fn = { list ->
                require(index in list.indices) { "remove index $index out of bounds [0, ${list.size - 1}]" }
                removed = list[index]
                list.toMutableList().also { it.removeAt(index) }
            },
            alterTouched = true,
            alterErrors = true,
            alignFn = { list ->
                if (index in list.indices) list.toMutableList().also { it.removeAt(index) } else list
            },
            shouldValidate = shouldValidate,
        )
        return removed
    }

    /** Replace the element at [index] with [value]. Throws if [index] is out of bounds. */
    suspend fun replace(index: Int, value: Any?, shouldValidate: Boolean? = null) {
        updateValues(
            fn = { list ->
                require(index in list.indices) { "replace index $index out of bounds [0, ${list.size - 1}]" }
                list.toMutableList().also { it[index] = value }
            },
            alterTouched = false,
            alterErrors = false,
            shouldValidate = shouldValidate,
        )
    }

    /** Swap elements at [indexA] and [indexB]. Throws if either index is out of bounds. */
    suspend fun swap(indexA: Int, indexB: Int, shouldValidate: Boolean? = null) {
        updateValues(
            fn = { list ->
                require(indexA in list.indices) { "swap indexA $indexA out of bounds" }
                require(indexB in list.indices) { "swap indexB $indexB out of bounds" }
                list.toMutableList().also {
                    val tmp = it[indexA]
                    it[indexA] = it[indexB]
                    it[indexB] = tmp
                }
            },
            alterTouched = true,
            alterErrors = true,
            alignFn = { list ->
                if (indexA in list.indices && indexB in list.indices) {
                    list.toMutableList().also {
                        val tmp = it[indexA]
                        it[indexA] = it[indexB]
                        it[indexB] = tmp
                    }
                } else list
            },
            shouldValidate = shouldValidate,
        )
    }

    /** Move the element at [from] to [to]. Throws if either index is out of bounds. */
    suspend fun move(from: Int, to: Int, shouldValidate: Boolean? = null) {
        updateValues(
            fn = { list ->
                require(from in list.indices) { "move from $from out of bounds" }
                require(to in list.indices) { "move to $to out of bounds" }
                if (from == to) return@updateValues list
                list.toMutableList().also {
                    val v = it.removeAt(from)
                    it.add(to, v)
                }
            },
            alterTouched = true,
            alterErrors = true,
            alignFn = { list ->
                if (from in list.indices && to in list.indices && from != to) {
                    list.toMutableList().also {
                        val v = it.removeAt(from)
                        it.add(to, v)
                    }
                } else list
            },
            shouldValidate = shouldValidate,
        )
    }

    // -------------------------------------------------------------------- internals

    /**
     * Single atomic mutation. Holds the controller's mutex while applying `fn` to the values list,
     * and (optionally) aligning the parallel touched/errors arrays via `alignFn` (defaults to `fn`).
     * Then triggers validation per [shouldValidate]/[FormikConfig.validateOnChange].
     */
    private suspend fun updateValues(
        fn: (List<Any?>) -> List<Any?>,
        alterTouched: Boolean,
        alterErrors: Boolean,
        alignFn: (List<Any?>) -> List<Any?> = fn,
        shouldValidate: Boolean?,
    ) {
        val willValidate = shouldValidate ?: controller.validateOnChange
        controller.applyArrayMutation(validate = willValidate) { state ->
            // Read the current list from the locked snapshot (`state`), not the live controller, so
            // the transform is a pure function of `state` and cannot tear against a concurrent write.
            val currentList = (controller.updaterValue.getAt(state.values, path) as? List<Any?>) ?: emptyList()
            val newList = fn(currentList)

            val newValuesObj = controller.updaterValue.setAt(state.values, path, newList)

            // touched/errors are stored flat by path; realign index-keyed entries (e.g. "friends[0]")
            // so they keep pointing at the same logical row after the structural change.
            val newTouched: FormikTouched = if (alterTouched) {
                FormikTouched(realignIndexedKeys(state.touched.byPath, path, currentList.size, alignFn))
            } else state.touched

            val newErrors: FormikErrors = if (alterErrors) {
                FormikErrors(realignIndexedKeys(state.errors.byPath, path, currentList.size, alignFn))
            } else state.errors

            state.copy(values = newValuesObj, touched = newTouched, errors = newErrors)
        }
    }

    /**
     * Realign keys shaped like `"<path>[idx]"` (or `"<path>[idx].subfield"`) after the array at
     * `[path]` was transformed by [alignFn]. Keys outside the array path are untouched. Keys at
     * exactly `path` (no index) are untouched.
     *
     * Algorithm: extract index-keyed entries, place them into a parallel `List<Map<String,V>?>`
     * (one bucket per old index, each bucket holds the suffix→value subset), run `alignFn` on that
     * list, then re-emit the keys with new indices.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> realignIndexedKeys(
        flat: Map<String, T>,
        arrayPath: String,
        currentSize: Int,
        alignFn: (List<Any?>) -> List<Any?>,
    ): Map<String, T> {
        val prefix = "$arrayPath["
        // Bucket per old index; each bucket is a map of suffix → value
        val buckets: MutableMap<Int, MutableMap<String, T>> = HashMap()
        val untouched: MutableMap<String, T> = HashMap()
        for ((k, v) in flat) {
            if (k.startsWith(prefix)) {
                val rest = k.substring(prefix.length)
                val close = rest.indexOf(']')
                if (close > 0) {
                    val idxStr = rest.substring(0, close)
                    val idx = idxStr.toIntOrNull()
                    val suffix = rest.substring(close + 1) // may be "", ".name", "[2].nested", etc.
                    if (idx != null) {
                        buckets.getOrPut(idx) { HashMap() }[suffix] = v
                        continue
                    }
                }
            }
            untouched[k] = v
        }
        if (buckets.isEmpty()) return flat

        val maxIndex = buckets.keys.max()
        val bucketList: List<MutableMap<String, T>?> = List(maxOf(currentSize, maxIndex + 1)) { i -> buckets[i] }

        @Suppress("UNCHECKED_CAST")
        val aligned = alignFn(bucketList as List<Any?>) as List<Any?>

        val out = HashMap<String, T>(untouched)
        for ((newIdx, bucketAny) in aligned.withIndex()) {
            @Suppress("UNCHECKED_CAST")
            val bucket = bucketAny as? Map<String, T> ?: continue
            for ((suffix, v) in bucket) {
                out["$arrayPath[$newIdx]$suffix"] = v
            }
        }
        return out
    }
}

// =============================================================== FormikController extension

/**
 * Return a [FieldArrayController] for the array at [path]. The path must resolve (or be expected
 * to resolve) to a `List<Any?>` inside the form's values.
 */
fun <V> FormikController<V>.array(path: String): FieldArrayController<V> =
    FieldArrayController(this, path)
