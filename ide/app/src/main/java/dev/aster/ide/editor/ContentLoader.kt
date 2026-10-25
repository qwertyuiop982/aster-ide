package dev.aster.ide.editor

import io.github.rosemoe.sora.text.Content
import java.io.Reader

/** Assemble one continuous Sora document in bounded reads, before attaching any Android view. */
object ContentLoader {
    fun read(reader: Reader, chunkSize: Int = 8192, checkpoint: () -> Unit = {}): Content {
        require(chunkSize >= 2)
        val content = Content()
        content.setUndoEnabled(false)
        val buffer = CharArray(chunkSize)
        var pending = ""
        while (true) {
            checkpoint()
            val count = reader.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            val value = pending + String(buffer, 0, count)
            val last = value.last()
            val keep = last == '\r' || Character.isHighSurrogate(last)
            val end = value.length - if (keep) 1 else 0
            if (end > 0) {
                val line = content.lineCount - 1
                content.insert(line, content.getColumnCount(line), value.substring(0, end))
            }
            pending = if (keep) value.takeLast(1) else ""
        }
        if (pending.isNotEmpty()) {
            val line = content.lineCount - 1
            content.insert(line, content.getColumnCount(line), pending)
        }
        content.setUndoEnabled(true)
        return content
    }
}