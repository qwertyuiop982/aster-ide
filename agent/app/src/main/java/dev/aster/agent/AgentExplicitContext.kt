package dev.aster.agent

import java.io.InputStream
import java.nio.ByteBuffer

internal object AgentExplicitContext {
    const val LIMIT = 12000
    const val FILE_BYTES = 65536
    private const val OMITTED = "\n[Excerpt truncated; use workspace tools for omitted portions.]"
    data class FileText(val path: String, val text: String)
    /** Uses API-24-compatible InputStream reads, not Android API 33 readNBytes. */
    fun readUtf8(input: InputStream): String {
        val bytes = ByteArray(FILE_BYTES + 1)
        var size = 0
        while (size < bytes.size) {
            val n = input.read(bytes, size, bytes.size - size)
            if (n < 0) break
            if (n == 0) {
                val b = input.read()
                if (b < 0) break
                bytes[size++] = b.toByte()
            } else size += n
        }
        require(size <= FILE_BYTES) { "上下文文件超过 64 KiB" }
        require((0 until size).none { bytes[it] == 0.toByte() }) { "请选择文本文件" }
        return Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, 0, size)).toString()
    }
    fun compose(project: String, prompt: String, files: List<FileText>): String {
        require(prompt.length <= 8000 && files.size <= 8)
        require(files.all { it.path.length in 1..512 })
        val output = StringBuilder().append("Project: ").append(project.take(200))
        if (prompt.isNotBlank()) output.append("\nUser instructions:\n").append(prompt)
        if (files.isNotEmpty()) {
            output.append("\nSelected files (use workspace tools for omitted portions):\n")
            files.forEach { output.append("- ").append(it.path).append('\n') }
        }
        val ceiling = LIMIT - OMITTED.length
        if (output.length > ceiling) return output.take(ceiling).toString() + OMITTED
        for (file in files) {
            val heading = "\nUser-selected file: ${file.path}\n"
            val available = ceiling - output.length - heading.length
            if (available < 0) { output.append(OMITTED); break }
            output.append(heading).append(file.text.take(available))
            if (file.text.length > available) { output.append(OMITTED); break }
        }
        return output.toString()
    }
}
