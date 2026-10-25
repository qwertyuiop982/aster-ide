package dev.aster.ide.data
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import dev.aster.ide.editor.ContentLoader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.CharacterCodingException
import kotlin.coroutines.coroutineContext

class EditorDocument(val path: String, initialText: String, val large: Boolean) {
    @Volatile var text: String = initialText
    var buffer: io.github.rosemoe.sora.text.Content? = null

    var dirty by mutableStateOf(false)
    var revision: Long = 0
    var savedRevision: Long = 0
    var line = 0
    var column = 0
    var scrollX = 0
    var scrollY = 0
    var readOnly by mutableStateOf(false)
    // Set by the active Sora instance. Copies are made only for debounced sync/save.
    var snapshot: (() -> String)? = null
    val name get() = File(path).name
}

suspend fun WorkspaceRepository.open(workspace: Workspace, path: String, settings: IdeSettings): EditorDocument = withContext(Dispatchers.IO) {
        val file = resolve(workspace, path)
        check(file.isFile) { "文件不存在" }
        check(file.length() <= settings.maxFileMB * 1024L * 1024) { "文件超过 ${settings.maxFileMB} MiB；可在性能设置中调整" }
        val operation = coroutineContext
        val content = try {
            file.inputStream().buffered(32768).use { input ->
                input.mark(4096)
                val header = ByteArray(4096)
                val count = input.read(header).coerceAtLeast(0)
                check((0 until count).none { header[it] == 0.toByte() }) { "这是二进制文件" }
                input.reset()
                InputStreamReader(input, Charsets.UTF_8.newDecoder()).use { reader ->
                    ContentLoader.read(reader) { operation.ensureActive() }
                }
            }
        } catch (_: CharacterCodingException) { error("文件不是 UTF-8 编码，请先转换编码") }
        EditorDocument(path, "", file.length() > settings.largeFileKB * 1024L).also { document ->
            document.buffer = content
            document.snapshot = { content.toString() }
        }
    }

