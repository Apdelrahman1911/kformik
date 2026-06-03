package io.kformik

/**
 * Deep value equality for arbitrary values. Mirrors `react-fast-compare`'s contract well enough
 * for Formik's needs: same primitives, same lists (element-wise), same maps (key-and-value-wise),
 * same arrays (element-wise). Falls back to `==` for everything else (which calls `data class`
 * `equals`, so typed `Values` already work).
 */
public fun deepEquals(a: Any?, b: Any?): Boolean {
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
public fun Map<String, Any?>.path(path: String): Any? = getIn(this, path)

/**
 * Free-function equivalent of `lodash.get` / Formik's `getIn(obj, key, def?)`. Walks [path]
 * (dot/bracket-style) through a `Map<String, Any?>` / `List<Any?>` tree and returns the leaf
 * value, or [default] if any segment doesn't resolve.
 *
 * **Resolution vs. null discrimination (v1.9.0+):** a path that resolves to an explicitly-stored
 * `null` leaf returns `null` (not [default]). Only truly *unresolvable* paths — missing map key,
 * out-of-range list index, descending through a non-container — fall back to [default]. Pre-1.9.0
 * the function conflated the two, breaking nullable-field readers (`getIn(mapOf("a" to null), "a")`
 * returned the default instead of `null`).
 *
 * Built on [io.kformik.internal.PathParser].
 */
public fun getIn(values: Any?, path: String, default: Any? = null): Any? {
    // Treat a blank path (empty or all-whitespace) as "the whole object", symmetric with the
    // empty-string fast path, rather than looking up a literal whitespace key.
    if (path.isBlank()) return values ?: default
    val segments = io.kformik.internal.PathParser.parse(path)
    var current: Any? = values
    for ((idx, segment) in segments.withIndex()) {
        val isLast = idx == segments.lastIndex
        when (val node = current) {
            is Map<*, *> -> {
                if (!node.containsKey(segment)) return default  // missing key — fall back
                current = node[segment]                         // present (possibly explicit null)
            }
            is List<*> -> {
                val i = segment.toIntOrNull() ?: return default
                if (i !in node.indices) return default
                current = node[i]
            }
            null -> return default     // can't descend through null intermediate
            else -> return default     // can't descend through scalar
        }
        // An intermediate null means the path can't continue beyond this point — treat as
        // unresolved. (At the leaf, however, an explicit null IS the value: fall through to
        // `return current`.)
        if (current == null && !isLast) return default
    }
    return current  // preserves explicit-null at leaf (was: `?: default` pre-1.9.0)
}

/**
 * Free-function equivalent of Formik's `setIn(obj, path, value)`. Returns a copy of [values] with
 * [path] set to [value]. Structural sharing is preserved: only objects along the update path are
 * replaced; siblings retain identity.
 *
 * Only supported on `Map<String, Any?>`-shaped values (delegates to [MapValuesUpdater]). For
 * typed `data class` values, use a custom [ValuesUpdater] instead.
 */
public fun setIn(values: Map<String, Any?>, path: String, value: Any?): Map<String, Any?> =
    MapValuesUpdater.setAt(values, path, value)
