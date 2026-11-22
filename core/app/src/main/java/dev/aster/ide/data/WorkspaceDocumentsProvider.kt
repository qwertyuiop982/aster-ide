package dev.aster.ide.data

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

/** Only user projects and the explicit exchange folder are reachable from the system picker. */
class WorkspaceDocumentsProvider : DocumentsProvider() {
    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: ROOT_COLUMNS).apply {
            addEntry(mapOf(Root.COLUMN_ROOT_ID to SHARED, Root.COLUMN_DOCUMENT_ID to SHARED,
                Root.COLUMN_TITLE to "Aster 文件", Root.COLUMN_SUMMARY to "共享目录",
                Root.COLUMN_FLAGS to Root.FLAG_SUPPORTS_CREATE))
            addEntry(mapOf(Root.COLUMN_ROOT_ID to PROJECTS, Root.COLUMN_DOCUMENT_ID to PROJECTS,
                Root.COLUMN_TITLE to "Aster 项目", Root.COLUMN_SUMMARY to "应用内工作区",
                Root.COLUMN_FLAGS to 0))
        }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOC_COLUMNS).apply { addDocument(documentId, requireFile(documentId)) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val file = requireFile(parentDocumentId)
        val children = file.listFiles() ?: throw FileNotFoundException(parentDocumentId)
        return MatrixCursor(projection ?: DOC_COLUMNS).apply {
            for (child in children.sortedBy { it.name }) {
                val id = "$parentDocumentId/${child.name}"
                accessibleFile(checkNotNull(context), id)?.let { addDocument(id, it) }
            }
        }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId.startsWith("$parentDocumentId/") &&
            accessibleFile(checkNotNull(context), parentDocumentId)?.isDirectory == true &&
            accessibleFile(checkNotNull(context), documentId) != null

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        signal?.throwIfCanceled()
        val file = requireFile(documentId)
        if (!file.isFile) throw FileNotFoundException(documentId)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        if (parentDocumentId == PROJECTS || !permittedName(displayName)) throw FileNotFoundException(displayName)
        val parent = requireFile(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException(parentDocumentId)
        val file = File(parent, displayName)
        val created = if (mimeType == Document.MIME_TYPE_DIR) file.mkdir() else file.createNewFile()
        if (!created) throw FileNotFoundException(displayName)
        changed(parentDocumentId)
        return "$parentDocumentId/${file.name}"
    }

    override fun deleteDocument(documentId: String) {
        val file = requireFile(documentId)
        if (protectedDirectory(documentId) || !file.delete()) throw FileNotFoundException(documentId)
        changed(documentId.substringBeforeLast('/'))
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val file = requireFile(documentId)
        if (protectedDirectory(documentId) || !permittedName(displayName)) throw FileNotFoundException(documentId)
        val to = File(file.parentFile, displayName)
        if (to.exists() || !file.renameTo(to)) throw FileNotFoundException(displayName)
        val parent = documentId.substringBeforeLast('/')
        changed(parent)
        return "$parent/$displayName"
    }

    private fun requireFile(id: String): File = accessibleFile(checkNotNull(context), id) ?: throw FileNotFoundException(id)

    private fun changed(parent: String) {
        val context = checkNotNull(context)
        context.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(authority(context), parent), null)
    }

    private fun MatrixCursor.addDocument(id: String, file: File) {
        val directory = file.isDirectory
        val mime = if (directory) Document.MIME_TYPE_DIR else
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        val name = when {
            id == SHARED -> "Aster 文件"
            id == PROJECTS -> "Aster 项目"
            protectedDirectory(id) -> runCatching {
                JSONObject(File(file, ".aster-workspace.json").readText()).getString("name")
            }.getOrDefault(file.name)
            else -> file.name
        }
        val edit = if (protectedDirectory(id)) 0 else Document.FLAG_SUPPORTS_RENAME or
            (if (!directory || file.list()?.isEmpty() == true) Document.FLAG_SUPPORTS_DELETE else 0)
        val write = if (directory) {
            if (id == PROJECTS) 0 else Document.FLAG_DIR_SUPPORTS_CREATE
        } else Document.FLAG_SUPPORTS_WRITE
        addEntry(mapOf(Document.COLUMN_DOCUMENT_ID to id, Document.COLUMN_DISPLAY_NAME to name,
            Document.COLUMN_MIME_TYPE to mime, Document.COLUMN_FLAGS to (edit or write),
            Document.COLUMN_SIZE to if (directory) null else file.length(), Document.COLUMN_LAST_MODIFIED to file.lastModified()))
    }

    private fun MatrixCursor.addEntry(values: Map<String, Any?>) = addRow(columnNames.map(values::get))

    companion object {
        private const val PROJECTS = "projects"
        private const val SHARED = "shared"
        private val ROOT_COLUMNS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_FLAGS)
        private val DOC_COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)

        private fun authority(context: Context) = "${context.packageName}.projects"
        private fun permittedName(name: String) = validDocumentName(name) && name != ".aster-workspace.json"
        private fun protectedDirectory(id: String) = '/' !in id || id.startsWith("$PROJECTS/") && id.count { it == '/' } == 1

        internal fun selectedDirectory(context: Context, uri: Uri): File? {
            if (uri.authority != authority(context)) return null
            val id = DocumentsContract.getTreeDocumentId(uri)
            return accessibleFile(context, id.takeUnless { it == PROJECTS } ?: return null)?.takeIf(File::isDirectory)
        }

        private fun accessibleFile(context: Context, id: String): File? {
            val segments = id.split('/')
            val root = when (segments.first()) {
                SHARED -> File(File(context.applicationInfo.dataDir).canonicalFile, "home/shared")
                PROJECTS -> File(context.filesDir.canonicalFile, "workspaces")
                else -> return null
            }
            if (!segments.drop(1).all(::permittedName)) return null
            if (root.canonicalFile != root || !root.isDirectory && !root.mkdirs()) return null
            val file = segments.drop(1).fold(root) { parent, name -> File(parent, name) }
            if (file.canonicalFile != file || !file.isFile && !file.isDirectory) return null
            if (segments[0] == PROJECTS && segments.size > 1) {
                val project = File(root, segments[1])
                val marker = File(project, ".aster-workspace.json")
                if (marker.canonicalFile != marker || !marker.isFile) return null
                if (runCatching { JSONObject(marker.readText()).optString("directPath").isNotEmpty() }.getOrDefault(true)) return null
            }
            return file
        }
    }
}
