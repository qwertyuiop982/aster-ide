package dev.aster.ide.editor

/** Bounded layout units. Concatenating blocks reproduces the original text byte-for-byte in UTF-16. */
object TextBlocks {
    fun split(text: String, maxChars: Int = 1024, maxLines: Int = 24): List<String> {
        require(maxChars >= 2 && maxLines > 0)
        if (text.isEmpty()) return listOf("")
        val blocks = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(text.length, start + maxChars)
            var lines = 0
            var lastBreak = -1
            var index = start
            while (index < end) {
                if (text[index] == '\n') {
                    lastBreak = index + 1
                    if (++lines >= maxLines) { end = index + 1; break }
                }
                index++
            }
            if (end < text.length && lastBreak > start) end = lastBreak
            if (end < text.length && end > start &&
                ((text[end - 1] == '\r' && text[end] == '\n') ||
                    (Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])))) end--
            if (end == start) end = minOf(start + 2, text.length)
            blocks += text.substring(start, end)
            start = end
        }
        return blocks
    }

    /** Preserve logical log lines while preventing one giant line from becoming a giant paragraph. */
    fun logLines(text: String, maxChars: Int = 1024): List<String> = buildList {
        for (line in text.lineSequence()) addAll(split(line, maxChars))
    }
}