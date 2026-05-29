package io.kformik

import io.kformik.internal.PathParser

/**
 * Strategy for reading/writing a single field inside a values object [V] by string path.
 *
 * The two main use cases:
 *
 *  - **Map-shaped values** (`Map<String, Any?>`): use [MapValuesUpdater]. Multiplatform, no
 *    reflection, supports nested paths via [PathParser]. This is the default.
 *
 *  - **Typed `data class` values**: write a hand-rolled `when`-based updater. Compact
 *    (one-time, ~10–20 lines for most forms) and explicit.
 */
interface ValuesUpdater<V> {
    /** Read the value at [path]. Returns null if missing or unresolved. */
    fun getAt(values: V, path: String): Any?

    /** Return a copy of [values] with [path] set to [value]. */
    fun setAt(values: V, path: String, value: Any?): V

    /**
     * Return every leaf path inside [values] (in Formik-style dot/bracket notation).
     *
     * Mirrors Formik's `setNestedObjectValues(values, true)` behavior: walk the values tree
     * and return one path per leaf, where a *leaf* is anything that isn't a `Map` or `List`.
     * Used by [io.kformik.FormikController.submit] to mark every field touched on submit,
     * regardless of whether the field was explicitly registered.
     *
     * Default implementation returns an empty set — overriders should implement the actual walk.
     * (Implementations for opaque value types — e.g. typed `data class` consumers who don't want
     * to enumerate fields — may legitimately return only the registered paths, which is what
     * the controller falls back to.)
     */
    fun leafPaths(values: V): Set<String> = emptySet()
}

/**
 * Default updater for `Map<String, Any?>`-shaped values.
 *
 * Supports nested paths via [PathParser]. Each write produces a new tree with structural sharing
 * (only the objects along the update path are cloned). Mirrors Formik's `setIn` behavior including:
 *
 *  - Creates missing intermediate maps automatically.
 *  - When the next path segment is integer-like and the container is missing, creates a list
 *    instead of a map (matches Formik's "array vs object" heuristic).
 *  - Setting a value equal to the current one returns the original map (referential equality
 *    preserved — observers can bail out cheaply).
 */
@Suppress("UNCHECKED_CAST")
object MapValuesUpdater : ValuesUpdater<Map<String, Any?>> {

    override fun getAt(values: Map<String, Any?>, path: String): Any? {
        val segments = PathParser.parse(path)
        var current: Any? = values
        for (segment in segments) {
            current = when (current) {
                is Map<*, *> -> (current as Map<String, Any?>)[segment]
                is List<*> -> segment.toIntOrNull()?.let { current.getOrNull(it) }
                else -> return null
            }
            if (current == null) return null
        }
        return current
    }

    override fun setAt(values: Map<String, Any?>, path: String, value: Any?): Map<String, Any?> {
        val segments = PathParser.parse(path)
        require(segments.isNotEmpty()) { "Path must not be empty" }
        if (deepEquals(getAt(values, path), value)) return values
        return setRecursive(values, segments, 0, value) as Map<String, Any?>
    }

    override fun leafPaths(values: Map<String, Any?>): Set<String> {
        val out = mutableSetOf<String>()
        walk(values, "", out)
        return out
    }

    private fun walk(node: Any?, prefix: String, out: MutableSet<String>) {
        when (node) {
            is Map<*, *> -> {
                if (node.isEmpty()) {
                    // empty map at a non-root path is itself effectively a leaf
                    if (prefix.isNotEmpty()) out += prefix
                } else {
                    for ((k, v) in node) {
                        val key = k.toString()
                        val nextPrefix = if (prefix.isEmpty()) key else "$prefix.$key"
                        walk(v, nextPrefix, out)
                    }
                }
            }
            is List<*> -> {
                if (node.isEmpty()) {
                    if (prefix.isNotEmpty()) out += prefix
                } else {
                    for (i in node.indices) {
                        walk(node[i], "$prefix[$i]", out)
                    }
                }
            }
            else -> {
                if (prefix.isNotEmpty()) out += prefix
            }
        }
    }

    private fun setRecursive(container: Any?, segments: List<String>, index: Int, value: Any?): Any? {
        val segment = segments[index]
        val isLast = index == segments.lastIndex

        if (isLast) {
            return when (container) {
                is Map<*, *> -> {
                    val m = (container as Map<String, Any?>).toMutableMap()
                    if (value == null) m.remove(segment) else m[segment] = value
                    m.toMap()
                }
                is List<*> -> {
                    val i = segment.toIntOrNull() ?: return container
                    val l = container.toMutableList() as MutableList<Any?>
                    while (l.size <= i) l.add(null)
                    l[i] = value
                    l.toList()
                }
                else -> {
                    // create a new container
                    val nextIsInt = false  // last segment, no "next"
                    if (segment.toIntOrNull() != null && container == null) {
                        // top-level integer path against null container — interpret as list-of-one
                        val l = ArrayList<Any?>(segment.toInt() + 1)
                        repeat(segment.toInt()) { l.add(null) }
                        l.add(value)
                        l.toList()
                    } else {
                        mapOf(segment to value)
                    }
                }
            }
        }

        val nextSegmentIsInt = segments[index + 1].toIntOrNull() != null

        return when (container) {
            is Map<*, *> -> {
                val m = (container as Map<String, Any?>).toMutableMap()
                val existing = m[segment]
                val newChild = if (existing == null) {
                    if (nextSegmentIsInt) setRecursive(emptyList<Any?>(), segments, index + 1, value)
                    else setRecursive(emptyMap<String, Any?>(), segments, index + 1, value)
                } else {
                    setRecursive(existing, segments, index + 1, value)
                }
                m[segment] = newChild
                m.toMap()
            }
            is List<*> -> {
                val i = segment.toIntOrNull() ?: return container
                val l = container.toMutableList() as MutableList<Any?>
                while (l.size <= i) l.add(null)
                val existing = l[i]
                l[i] = if (existing == null) {
                    if (nextSegmentIsInt) setRecursive(emptyList<Any?>(), segments, index + 1, value)
                    else setRecursive(emptyMap<String, Any?>(), segments, index + 1, value)
                } else {
                    setRecursive(existing, segments, index + 1, value)
                }
                l.toList()
            }
            else -> {
                if (nextSegmentIsInt) {
                    mapOf(segment to setRecursive(emptyList<Any?>(), segments, index + 1, value))
                } else {
                    mapOf(segment to setRecursive(emptyMap<String, Any?>(), segments, index + 1, value))
                }
            }
        }
    }
}

/**
 * Fallback updater that supports only flat (single-segment) top-level paths and stores values
 * directly inside the [V] object as if it were a `Map<String, Any?>` lookalike.
 *
 * Useful for the rare case where a consumer wants to use a single-string [V] (e.g. for a one-field
 * form). For anything else, use [MapValuesUpdater] or write a custom one.
 */
class FlatTopLevelUpdater<V> : ValuesUpdater<V> {
    override fun getAt(values: V, path: String): Any? = throw UnsupportedOperationException(
        "FlatTopLevelUpdater cannot read field '$path' — supply a real ValuesUpdater"
    )
    override fun setAt(values: V, path: String, value: Any?): V = throw UnsupportedOperationException(
        "FlatTopLevelUpdater cannot write field '$path' — supply a real ValuesUpdater"
    )
}
