package io.kformik.internal

/**
 * Lodash-`toPath`-style splitter.
 *
 * Examples:
 *   `"a.b.c"`           → ["a", "b", "c"]
 *   `"a[0].b"`          → ["a", "0", "b"]
 *   `"a['b'].c"`        → ["a", "b", "c"]
 *   `"user.friends[1].name"` → ["user", "friends", "1", "name"]
 *
 * Used internally for nested values stored in a [Map] or any structure that [ValuesUpdater]
 * traverses. Field error and touched maps are *flat* — they index by the original string path —
 * so this parser is only invoked when we need to actually walk a nested values tree.
 *
 * Empty segments are collapsed, not rejected: `"a..b"` → `["a","b"]`, `"a."` → `["a"]`,
 * `"."`/`""` → `[]`. A path that yields no segments (e.g. `"."`) therefore resolves to nothing;
 * `MapValuesUpdater.setAt` rejects it with a clear "does not resolve to any field segment" error.
 */
internal object PathParser {

    fun parse(path: String): List<String> {
        if (path.isEmpty()) return emptyList()
        val out = ArrayList<String>(4)
        val cur = StringBuilder()
        var i = 0
        val n = path.length

        fun flush() {
            if (cur.isNotEmpty()) {
                out.add(cur.toString())
                cur.clear()
            }
        }

        while (i < n) {
            val c = path[i]
            when (c) {
                '.' -> { flush(); i++ }
                '[' -> {
                    flush()
                    i++
                    val quote = if (i < n && (path[i] == '\'' || path[i] == '"')) path[i] else null
                    if (quote != null) i++
                    while (i < n && path[i] != ']' && path[i] != quote) {
                        cur.append(path[i]); i++
                    }
                    if (quote != null && i < n && path[i] == quote) i++
                    if (i < n && path[i] == ']') i++
                    flush()
                }
                else -> { cur.append(c); i++ }
            }
        }
        flush()
        return out
    }
}
