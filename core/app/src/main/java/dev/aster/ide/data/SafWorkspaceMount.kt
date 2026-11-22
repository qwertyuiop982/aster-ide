package dev.aster.ide.data

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext

internal fun validDocumentName(name: String): Boolean = name.isNotEmpty() && name !in setOf(".", "..") &&
    name.toByteArray().size <= 255 && name.none { it == '/' || it == '\\' || it.isISOControl() }

internal class SafWorkspaceMount(
    context: Context,
    private val tree: Uri,
    private val directory: File,
    state: File,
    private val ignored: Set<String>,
    private val job: CoroutineContext,
) {
    private data class Doc(val id: String, val name: String, val mime: String) {
        val directory get() = mime == Document.MIME_TYPE_DIR
    }
    private data class Remote(val document: Doc, val hash: String)

    private val resolver = context.contentResolver
    private val baseline = AtomicFile(state)
    private val rootId = DocumentsContract.getTreeDocumentId(tree)
    private val root = directory.absoluteFile
    private val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)

    private fun documentUri(id: String) = DocumentsContract.buildDocumentUriUsingTree(tree, id)
    private fun requireReady(cursor: Cursor) {
        check(!cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING)) { "文件提供方尚未完成加载，请重试" }
        check(cursor.extras.getString(DocumentsContract.EXTRA_ERROR).isNullOrBlank()) { "文件提供方暂时无法读取原目录" }
    }

    private fun children(id: String): List<Doc> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        return resolver.query(uri, projection, null, null, null)?.use { cursor ->
            requireReady(cursor)
            check(cursor.count <= MAX_ENTRIES) { "挂载目录项目太多" }
            buildList {
                while (cursor.moveToNext()) {
                    job.ensureActive()
                    add(Doc(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
                }
            }
        } ?: error("文件提供方无法列出目录")
    }

    private fun included(path: String): Boolean =
        path.split('/').none { it in ignored || it == ".aster-workspace.json" || it == "local.properties" } &&
            WorkspaceTransferPolicy.shouldCopy(path)

    private fun mode(file: File): Int? = try { Os.lstat(file.path).st_mode } catch (e: ErrnoException) {
        if (e.errno == OsConstants.ENOENT) null else throw e
    }

    private fun target(path: String): File {
        require(path.split('/').all(::validDocumentName))
        return File(root, path).also {
            check(it.canonicalFile == it.absoluteFile) { "挂载不能同步符号链接：$path" }
        }
    }

    private class Limit(var entries: Int = 0, var bytes: Long = 0)

    private fun digest(input: InputStream, limit: Limit = Limit(), output: OutputStream? = null): String {
        val sha = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32768)
        var size = 0L
        while (true) {
            job.ensureActive()
            val n = input.read(buffer)
            if (n == -1) break
            size += n
            limit.bytes += n
            check(size <= MAX_FILE && limit.bytes <= MAX_BYTES) { "挂载文件或目录超过大小限制，请改用可直接访问的目录或排除生成文件" }
            sha.update(buffer, 0, n)
            output?.write(buffer, 0, n)
        }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }

    private fun input(doc: Doc): InputStream = resolver.openInputStream(documentUri(doc.id))
        ?: error("无法读取原目录中的 ${doc.name}")

    private fun remote(): Map<String, Remote> {
        val directoryPresent = resolver.query(documentUri(rootId), arrayOf(Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
            requireReady(cursor)
            cursor.moveToFirst() && cursor.getString(0) == Document.MIME_TYPE_DIR
        }
        check(directoryPresent == true) { "原目录不可用，保留已有镜像" }
        val result = linkedMapOf<String, Remote>()
        val ids = hashSetOf(rootId)
        val limit = Limit()
        fun walk(id: String, relative: String, depth: Int) {
            check(depth <= MAX_DEPTH) { "挂载目录层级超过 $MAX_DEPTH" }
            for (doc in children(id)) {
                val path = if (relative.isEmpty()) doc.name else "$relative/${doc.name}"
                if (!included(path)) continue
                check(validDocumentName(doc.name) && path !in result && ids.add(doc.id)) { "挂载目录包含重名、循环或不可用项目：$path" }
                check(++limit.entries <= MAX_ENTRIES) { "挂载目录超过 $MAX_ENTRIES 项" }
                result[path] = Remote(doc, if (doc.directory) MountMerge.DIRECTORY else input(doc).use { digest(it, limit) })
                if (doc.directory) walk(doc.id, path, depth + 1)
            }
        }
        walk(rootId, "", 0)
        return result
    }

    private fun local(): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val limit = Limit()
        fun walk(parent: File, relative: String, depth: Int) {
            check(depth <= MAX_DEPTH) { "挂载镜像目录层级超过 $MAX_DEPTH" }
            for (file in parent.listFiles() ?: error("无法读取挂载镜像")) {
                job.ensureActive()
                val path = if (relative.isEmpty()) file.name else "$relative/${file.name}"
                if (!included(path)) continue
                check(++limit.entries <= MAX_ENTRIES && validDocumentName(file.name)) { "挂载镜像包含过多或不可用项目" }
                val flags = mode(file) ?: error("同步期间文件已变更：$path")
                if (OsConstants.S_ISDIR(flags)) {
                    result[path] = MountMerge.DIRECTORY
                    walk(file, path, depth + 1)
                } else {
                    check(OsConstants.S_ISREG(flags)) { "挂载不能写回符号链接或特殊文件：$path" }
                    result[path] = file.inputStream().use { digest(it, limit) }
                }
            }
        }
        walk(root, "", 0)
        return result
    }

    private fun currentLocal(path: String): String? {
        val file = target(path)
        val flags = mode(file) ?: return null
        if (OsConstants.S_ISDIR(flags)) return MountMerge.DIRECTORY
        check(OsConstants.S_ISREG(flags)) { "挂载不能写回特殊文件：$path" }
        return file.inputStream().use { digest(it) }
    }

    private fun create(parent: String, name: String, mime: String): String {
        check(children(parent).none { it.name == name }) { "同步期间原目录已变更：$name" }
        val uri = DocumentsContract.createDocument(resolver, documentUri(parent), mime, name) ?: error("无法在原目录创建 $name")
        val id = DocumentsContract.getDocumentId(uri)
        if (children(parent).none { it.id == id && it.name == name }) {
            DocumentsContract.deleteDocument(resolver, documentUri(id))
            error("文件提供方无法保留项目名称：$name")
        }
        return id
    }

    private suspend fun writeRemote(path: String, hash: String, remote: Remote?, parent: String) {
        val file = target(path)
        val stage = File.createTempFile(".aster-sync-", ".tmp", root.parentFile)
        var backup: File? = null
        var retainBackup = false
        try {
            FileOutputStream(stage).use { out ->
                check(file.inputStream().use { digest(it, output = out) } == hash) { "同步期间本地文件已变更：$path" }
                out.fd.sync()
            }
            if (remote != null) {
                val recovery = File(baseline.baseFile.parentFile, ".aster-mount-recovery")
                check(recovery.mkdirs() || recovery.isDirectory) { "无法建立挂载恢复目录" }
                backup = File.createTempFile("before-", ".bak", recovery)
                FileOutputStream(backup).use { out ->
                    check(input(remote.document).use { digest(it, output = out) } == remote.hash) { "同步期间原文件已变更：$path" }
                    out.fd.sync()
                }
            }
            job.ensureActive()
            withContext(NonCancellable) {
                val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
                val uri = documentUri(remote?.document?.id ?: create(parent, file.name, mime))
                fun write(source: File) {
                    resolver.openOutputStream(uri, "wt")?.use { output ->
                        source.inputStream().use { it.copyTo(output) }
                    } ?: error("无法打开原文件供写入")
                }
                // SAF does not guarantee atomic replacement. Keep the original bytes until the provider closes the stream.
                try {
                    write(stage)
                } catch (error: Exception) {
                    val restore = runCatching {
                        val original = backup
                        if (original != null) write(original)
                        else check(DocumentsContract.deleteDocument(resolver, uri))
                    }
                    retainBackup = restore.isFailure && backup != null
                    val details = if (retainBackup) "；原内容备份于 ${backup?.path}" else ""
                    throw IllegalStateException("无法写回原目录：$path$details", error)
                }
            }
        } finally {
            stage.delete()
            if (!retainBackup) backup?.delete()
        }
    }

    suspend fun sync() {
        check(root.canonicalFile == root && (root.mkdirs() || root.isDirectory)) { "无法访问挂载镜像" }
        val previous = try {
            JSONObject(baseline.readFully().toString(Charsets.UTF_8)).let { json ->
                json.keys().asSequence().filter(::included).associateWith(json::getString)
            }
        } catch (_: FileNotFoundException) { emptyMap() }
        val original = remote()
        val working = local()
        val wanted = MountMerge.merge(previous, working, original.mapValues { it.value.hash })
        val ids = original.mapValuesTo(mutableMapOf()) { it.value.document.id }
        fun parentId(path: String) = ids[path.substringBeforeLast('/', "")] ?: rootId

        // Create parents first. File contents are checked again immediately before overwriting either side.
        for ((path, hash) in wanted.entries.sortedBy { it.key.count { char -> char == '/' } }) {
            job.ensureActive()
            val remote = original[path]
            val file = target(path)
            if (working[path] != hash) {
                check(currentLocal(path) == working[path]) { "同步期间本地文件已变更：$path" }
                if (hash == MountMerge.DIRECTORY) check(file.mkdir()) { "无法建立镜像目录：$path" }
                else {
                    val temp = File.createTempFile(".aster-sync-", ".tmp", root.parentFile)
                    try {
                        FileOutputStream(temp).use { out ->
                            check(input(checkNotNull(remote).document).use { digest(it, output = out) } == hash) { "同步期间原文件已变更：$path" }
                            out.fd.sync()
                        }
                        if (file.name in setOf("gradlew", "mvnw") || file.extension == "sh") temp.setExecutable(true, true)
                        check(currentLocal(path) == working[path] && temp.renameTo(file)) { "无法更新镜像文件：$path" }
                    } finally { temp.delete() }
                }
            } else if (remote?.hash != hash) {
                val parent = parentId(path)
                if (hash == MountMerge.DIRECTORY) ids[path] = create(parent, file.name, Document.MIME_TYPE_DIR)
                else writeRemote(path, hash, remote, parent)
            }
        }

        for (path in (working.keys + original.keys).filterNot { it in wanted }.sortedByDescending { it.count { char -> char == '/' } }) {
            job.ensureActive()
            if (path in working) {
                check(currentLocal(path) == working[path] && target(path).delete()) { "无法删除镜像项目或目录不是空的：$path" }
            } else {
                val doc = original.getValue(path)
                check(if (doc.document.directory) children(doc.document.id).isEmpty() else input(doc.document).use { digest(it) } == doc.hash) {
                    "原目录已更改，未删除 $path"
                }
                check(DocumentsContract.deleteDocument(resolver, documentUri(doc.document.id))) { "无法删除原目录项目：$path" }
            }
        }
        baseline.baseFile.parentFile?.mkdirs()
        val output = baseline.startWrite()
        try { output.write(JSONObject(wanted).toString().toByteArray()); baseline.finishWrite(output) }
        catch (e: Exception) { baseline.failWrite(output); throw e }
    }

    companion object {
        private const val MAX_DEPTH = 32
        private const val MAX_ENTRIES = 10000
        private const val MAX_FILE = 64L * 1024 * 1024
        private const val MAX_BYTES = 512L * 1024 * 1024
    }
}
