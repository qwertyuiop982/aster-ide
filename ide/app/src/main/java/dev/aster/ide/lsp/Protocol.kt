package dev.aster.ide.lsp

import dev.aster.ide.runtime.readLine
import org.json.JSONArray
import org.json.JSONObject
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

object LspFraming {
    private const val MAX_MESSAGE = 8 * 1024 * 1024
    fun read(input: InputStream): JSONObject {
        var length = -1
        var headers = 0
        while (true) {
            val line = readLine(input, 8192)
            if (line.isEmpty()) break
            check(++headers <= 20) { "Too many LSP headers" }
            val key = line.substringBefore(':').trim()
            if (key.equals("Content-Length", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
        }
        check(length in 1..MAX_MESSAGE) { "Invalid LSP Content-Length: $length" }
        val data = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = input.read(data, offset, length - offset)
            if (n < 0) throw EOFException("Truncated LSP message")
            offset += n
        }
        return JSONObject(String(data, Charsets.UTF_8))
    }
    fun write(output: OutputStream, message: JSONObject) {
        val bytes = message.toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_MESSAGE)
        output.write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.write(bytes); output.flush()
    }
}

data class LspPosition(val line: Int, val character: Int) {
    fun json() = JSONObject().put("line", line).put("character", character)
    companion object { fun from(j: JSONObject) = LspPosition(j.getInt("line"), j.getInt("character")) }
}
data class LspRange(val start: LspPosition, val end: LspPosition) {
    fun json() = JSONObject().put("start", start.json()).put("end", end.json())
    companion object { fun from(j: JSONObject) = LspRange(LspPosition.from(j.getJSONObject("start")), LspPosition.from(j.getJSONObject("end"))) }
}
data class LspEdit(val range: LspRange, val text: String) {
    companion object { fun from(j: JSONObject) = LspEdit(LspRange.from(j.getJSONObject("range")), j.getString("newText")) }
}
data class LspDiagnostic(val uri: String, val range: LspRange, val severity: Int, val message: String, val source: String, val raw: JSONObject)
data class CompletionEntry(val label: String, val detail: String, val insert: String, val kind: Int, val range: LspRange?, val additional: List<LspEdit>, val raw: JSONObject)
data class Location(val uri: String, val range: LspRange)

object TextCoordinates {
    fun position(text: String, offset: Int): LspPosition {
        val end = offset.coerceIn(0, text.length)
        var line = 0; var start = 0
        for (i in 0 until end) if (text[i] == '\n') { line++; start = i + 1 }
        return LspPosition(line, end - start)
    }
    fun offset(text: String, position: LspPosition): Int {
        require(position.line >= 0 && position.character >= 0)
        var line = 0; var start = 0
        for (i in text.indices) {
            if (line == position.line) break
            if (text[i] == '\n') { line++; start = i + 1 }
        }
        require(line == position.line) { "Line is outside document" }
        var end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        if (end > start && text[end - 1] == '\r') end--
        require(position.character <= end - start) { "Column is outside document" }
        return start + position.character
    }
    fun change(previous: String, current: String): JSONObject? {
        if (previous == current) return null
        var start = 0
        val common = minOf(previous.length, current.length)
        while (start < common && previous[start] == current[start]) start++
        // Do not split a UTF-16 surrogate pair at either edge.
        if (start > 0 && start < previous.length && previous[start].isLowSurrogate() && previous[start - 1].isHighSurrogate()) start--
        if (start > 0 && start < previous.length && previous[start - 1] == '\r' && previous[start] == '\n') start--
        var oldEnd = previous.length; var newEnd = current.length
        while (oldEnd > start && newEnd > start && previous[oldEnd - 1] == current[newEnd - 1]) { oldEnd--; newEnd-- }
        if (oldEnd < previous.length && oldEnd > start && previous[oldEnd].isLowSurrogate()) { oldEnd++; newEnd++ }
        if (oldEnd > 0 && oldEnd < previous.length && previous[oldEnd - 1] == '\r' && previous[oldEnd] == '\n') { oldEnd++; newEnd++ }
        return JSONObject().put("range", LspRange(position(previous, start), position(previous, oldEnd)).json()).put("text", current.substring(start, newEnd))
    }
    fun apply(text: String, edits: List<LspEdit>): String {
        data class Edit(val start: Int, val end: Int, val value: String)
        val sorted = edits.map { Edit(offset(text, it.range.start), offset(text, it.range.end), it.text) }.sortedByDescending { it.start }
        var boundary = text.length
        val result = StringBuilder(text)
        sorted.forEach { edit ->
            require(edit.start <= edit.end && edit.end <= boundary) { "Overlapping or invalid edits" }
            result.replace(edit.start, edit.end, edit.value)
            boundary = edit.start
        }
        return result.toString()
    }
}

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONObject.document(uri: String) = put("textDocument", JSONObject().put("uri", uri))
fun JSONObject.at(position: LspPosition) = put("position", position.json())
fun markup(value: Any?): String = when (value) {
    null, JSONObject.NULL -> ""
    is String -> value
    is JSONObject -> value.optString("value", value.optString("label"))
    is JSONArray -> (0 until value.length()).joinToString("\n\n") { markup(value.opt(it)) }
    else -> value.toString()
}

object Snippets {
    // Clients advertise snippetSupport=false; this also handles servers that still return snippets.
    fun plain(value: String): String = value
        .replace(Regex("\\$\\{[0-9]+:([^}]+)}")) { it.groupValues[1] }
        .replace(Regex("\\$\\{[0-9]+\\|([^}]+)\\|}")) { it.groupValues[1].substringBefore(',') }
        .replace(Regex("\\$\\{[0-9]+}|\\$[0-9]+"), "")
        .replace("\\$", "$").replace("\\}", "}")
}