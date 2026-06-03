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
    /**
     * Read the value at [path]. Returns null if missing or unresolved.
     *
     * The returned object is a **live reference** into the form's values tree, not a copy. Values
     * must be treated as deeply immutable — do not mutate a returned collection (use `listOf`/`mapOf`,
     * not `mutableListOf`/`mutableMapOf`). Mutating a returned object corrupts internal state and
     * will not emit a new state to observers.
     */
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
 *  - Clearing a nested leaf (`setAt(path, null)`) prunes any intermediate container it leaves
 *    empty, so set-then-clear restores the original shape and `dirty` re-baselines.
 *
 * Contracts / caveats:
 *  - **String-keyed maps only.** Nested maps are read/written by the string path segment; a map
 *    with non-`String` keys will not resolve and writes would insert a parallel `String` key.
 *  - **Descending through a scalar replaces it.** If a path descends through an existing non-Map/
 *    non-List value (e.g. `setAt(mapOf("a" to 1), "a.b", 2)`), that scalar is discarded and a fresh
 *    container is created — mirroring lodash `set` / Formik `setIn`. An over-deep path (typo/schema
 *    drift) therefore silently overwrites the stored scalar rather than erroring.
 *  - Negative and excessively large (> current size + 10_000) list indices are treated as no-ops,
 *    matching the graceful handling of non-numeric segments.
 */
@Suppress("UNCHECKED_CAST")
object MapValuesUpdater : ValuesUpdater<Map<String, Any?>> {

    override fun getAt(values: Map<String, Any?>, path: String): Any? {
        val segments = PathParser.parse(path)
        // Symmetric with setAt: a path that resolves to no segments (e.g. `""`, `"."`, `"[]"`) is
        // not a valid field reference. Pre-1.9.0, getAt walked zero segments and returned the
        // entire values map verbatim — surprising and a minor information leak vs. setAt which
        // already rejected with this same error.
        require(segments.isNotEmpty()) { "Path '$path' does not resolve to any field segment" }
        return getAtSegments(values, segments)
    }

    private fun getAtSegments(values: Map<String, Any?>, segments: List<String>): Any? {
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
        require(segments.isNotEmpty()) { "Path '$path' does not resolve to any field segment" }
        // Reuse the parsed segments for the equality short-circuit instead of re-parsing the path
        // and walking the tree a second time.
        if (deepEquals(getAtSegments(values, segments), value)) return values
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

    /**
     * Maximum number of slots auto-vivified when writing past the end of a list. Bounds the
     * allocation a stray large index (e.g. `"tags[2000000000]"` from a malformed path) could
     * otherwise trigger. An index beyond this is treated as a no-op, like a non-numeric segment.
     */
    private const val MAX_AUTO_GROW = 10_000

    /** Parse a list-index segment, rejecting non-integers, negatives, and out-of-range growth. */
    private fun listIndexOrNull(segment: String, currentSize: Int): Int? {
        val i = segment.toIntOrNull() ?: return null
        if (i < 0) return null
        if (i > currentSize + MAX_AUTO_GROW) return null
        return i
    }

    private fun Any?.isEmptyContainer(): Boolean =
        (this is Map<*, *> && this.isEmpty()) || (this is List<*> && this.isEmpty())

    private fun setRecursive(container: Any?, segments: List<String>, index: Int, value: Any?): Any? {
        val segment = segments[index]
        val isLast = index == segments.lastIndex

        if (isLast) {
            return when (container) {
                is Map<*, *> -> {
                    val m = (container as Map<String, Any?>).toMutableMap()
                    if (value == null) m.remove(segment) else m[segment] = value
                    m
                }
                is List<*> -> {
                    // Reject negative / oversized indices as a no-op (read/write symmetry with getAt).
                    val i = listIndexOrNull(segment, container.size) ?: return container
                    val l = container.toMutableList() as MutableList<Any?>
                    while (l.size <= i) l.add(null)
                    l[i] = value
                    l
                }
                else -> {
                    // create a new container
                    if (segment.toIntOrNull() != null && container == null) {
                        // top-level integer path against null container — interpret as list-of-one
                        val i = listIndexOrNull(segment, 0) ?: return mapOf(segment to value)
                        val l = ArrayList<Any?>(i + 1)
                        repeat(i) { l.add(null) }
                        l.add(value)
                        l
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
                // Prune a child that collapsed to an empty container as a result of clearing
                // (value == null), so set-then-clear of a nested leaf restores the original shape
                // and `dirty` re-baselines correctly.
                if (value == null && newChild.isEmptyContainer()) m.remove(segment) else m[segment] = newChild
                m
            }
            is List<*> -> {
                val i = listIndexOrNull(segment, container.size) ?: return container
                val l = container.toMutableList() as MutableList<Any?>
                while (l.size <= i) l.add(null)
                val existing = l[i]
                val newChild = if (existing == null) {
                    if (nextSegmentIsInt) setRecursive(emptyList<Any?>(), segments, index + 1, value)
                    else setRecursive(emptyMap<String, Any?>(), segments, index + 1, value)
                } else {
                    setRecursive(existing, segments, index + 1, value)
                }
                l[i] = if (value == null && newChild.isEmptyContainer()) null else newChild
                l
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
 * Fail-fast placeholder updater. **Every** read and write throws [UnsupportedOperationException] —
 * it does not actually store or resolve any field. [FormikController] no longer selects it as a
 * default (a non-`Map` values type with no `valuesUpdater` now fails at construction with an
 * actionable message). It remains only so an explicit, intentional "no updater" can be expressed.
 *
 * For real typed (`data class`) values, supply a hand-written [ValuesUpdater] or the KSP-generated
 * `<Name>Updater`; for `Map<String, Any?>` values use [MapValuesUpdater].
 */
class FlatTopLevelUpdater<V> : ValuesUpdater<V> {
    override fun getAt(values: V, path: String): Any? = throw UnsupportedOperationException(
        "FlatTopLevelUpdater cannot read field '$path' — supply a real ValuesUpdater"
    )
    override fun setAt(values: V, path: String, value: Any?): V = throw UnsupportedOperationException(
        "FlatTopLevelUpdater cannot write field '$path' — supply a real ValuesUpdater"
    )
}
