package io.kformik

/**
 * Deep value equality for arbitrary values. Mirrors `react-fast-compare`'s contract well enough
 * for Formik's needs: same primitives, same lists (element-wise), same maps (key-and-value-wise),
 * same arrays (element-wise). Falls back to `==` for everything else (which calls `data class`
 * `equals`, so typed `Values` already work).
 */
fun deepEquals(a: Any?, b: Any?): Boolean {
    if (a === b) return true
    if (a == null || b == null) return false
    return when {
        a is List<*> && b is List<*> -> {
            if (a.size != b.size) return false
            a.indices.all { deepEquals(a[it], b[it]) }
        }
        a is Map<*, *> && b is Map<*, *> -> {
            if (a.size != b.size) return false
            a.all { (k, v) -> b.containsKey(k) && deepEquals(v, b[k]) }
        }
        a is Set<*> && b is Set<*> -> a == b
        a is Array<*> && b is Array<*> -> {
            if (a.size != b.size) return false
            a.indices.all { deepEquals(a[it], b[it]) }
        }
        else -> a == b
    }
}

/**
 * Convenience: read a value out of a `Map<String, Any?>` by a Formik-style path (`"a.b[0].c"`).
 *
 * Returns `null` if any segment along the path doesn't resolve. Useful for consumers who keep
 * their values as a [Map] rather than building a custom [ValuesUpdater].
 */
fun Map<String, Any?>.path(path: String): Any? = getIn(this, path)

/**
 * Free-function equivalent of Formik's `getIn(obj, key, def?)`. Walks [path] (dot/bracket-style)
 * through a `Map<String, Any?>` / `List<Any?>` tree and returns the leaf value, or [default] if
 * any segment doesn't resolve.
 *
 * This is the canonical Kformik replacement for `lodash.get` / Formik's `getIn`. Built on
 * [io.kformik.internal.PathParser].
 */
fun getIn(values: Any?, path: String, default: Any? = null): Any? {
    if (path.isEmpty()) return values ?: default
    val segments = io.kformik.internal.PathParser.parse(path)
    var current: Any? = values
    for (segment in segments) {
        current = when (current) {
            is Map<*, *> -> current[segment]
            is List<*> -> segment.toIntOrNull()?.let { current.getOrNull(it) }
            else -> return default
        }
        if (current == null) return default
    }
    return current ?: default
}

/**
 * Free-function equivalent of Formik's `setIn(obj, path, value)`. Returns a copy of [values] with
 * [path] set to [value]. Structural sharing is preserved: only objects along the update path are
 * replaced; siblings retain identity.
 *
 * Only supported on `Map<String, Any?>`-shaped values (delegates to [MapValuesUpdater]). For
 * typed `data class` values, use a custom [ValuesUpdater] instead.
 */
fun setIn(values: Map<String, Any?>, path: String, value: Any?): Map<String, Any?> =
    MapValuesUpdater.setAt(values, path, value)
