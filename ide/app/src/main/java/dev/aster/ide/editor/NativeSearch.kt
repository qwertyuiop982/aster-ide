package dev.aster.ide.editor

object NativeSearch {
    private val available = runCatching { System.loadLibrary("aster") }.isSuccess
    private external fun findNative(text: String, query: String, ignoreCase: Boolean, limit: Int): IntArray

    fun find(text: String, query: String, ignoreCase: Boolean = false, limit: Int = 1000): IntArray {
        if (query.isEmpty() || limit <= 0) return IntArray(0)
        // Unicode case folding has different rules from ASCII; use Kotlin for those strings.
        if (available && (!ignoreCase || (query.all { it.code < 128 } && text.all { it.code < 128 }))) {
            return findNative(text, query, ignoreCase, limit.coerceAtMost(10000))
        }
        val matches = ArrayList<Int>()
        var from = 0
        while (matches.size < limit.coerceAtMost(10000)) {
            val found = text.indexOf(query, from, ignoreCase)
            if (found < 0) break
            matches.add(found)
            from = found + 1
        }
        return matches.toIntArray()
    }
}
