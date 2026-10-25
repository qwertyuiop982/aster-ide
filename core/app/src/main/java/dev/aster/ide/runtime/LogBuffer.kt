package dev.aster.ide.runtime

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import dev.aster.ide.editor.TextBlocks

data class OutputRow(val text: String, val error: Boolean)

class LogBuffer(scope: CoroutineScope, private val maxChars: Int = 180000) {
    private val buffer = StringBuilder()
    private var dirty = false
    private val ansi = Regex("\u001B\\[[0-9;?]*[A-Za-z]")
    val text = MutableStateFlow("")
    val lines = MutableStateFlow<List<String>>(emptyList())
    val rows = MutableStateFlow<List<OutputRow>>(emptyList())
    val revision = MutableStateFlow(0L)
    var followOutput = true
    var firstVisibleRow = 0
    var firstVisibleOffset = 0

    // Cache display lines once, off the UI thread, even if the output panel is closed.
    init { scope.launch(Dispatchers.Default) { while (isActive) { delay(200); flush() } } }
    @Synchronized fun append(value: String) {
        buffer.append(value)
        if (buffer.length > maxChars) {
            val excess = buffer.length - maxChars
            val line = buffer.indexOf("\n", excess)
            buffer.delete(0, if (line >= 0) line + 1 else excess)
        }
        dirty = true
    }
    private fun flush() {
        val value = synchronized(this) {
            if (!dirty) return
            dirty = false
            buffer.toString()
        }
        val logical = ansi.replace(value, "").replace("\r", "").lines().takeLast(2000)
        val displayed = logical.flatMap { line ->
            val error = line.contains("error:", true) || line.contains("Traceback")
            TextBlocks.split(line).map { OutputRow(it, error) }
        }
        text.value = value
        rows.value = displayed
        lines.value = displayed.map { it.text }
        revision.value++
    }
    @Synchronized fun clear() { buffer.clear(); dirty = true }
}