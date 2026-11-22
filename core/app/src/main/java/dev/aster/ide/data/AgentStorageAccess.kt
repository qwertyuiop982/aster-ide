package dev.aster.ide.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.Settings
import java.io.File

/** Shell tools need a filesystem directory in addition to the persisted SAF grant. */
object AgentStorageAccess {
    fun granted(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun permissionIntent(context: Context): Intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:" + context.packageName))

    fun directory(context: Context, uri: Uri): File? {
        WorkspaceDocumentsProvider.selectedDirectory(context, uri)?.let { return it }
        if (uri.authority != "com.android.externalstorage.documents" || !granted(context)) return null
        val id = DocumentsContract.getTreeDocumentId(uri)
        val volume = id.substringBefore(':')
        val relative = id.substringAfter(':', "")
        require(volume.equals("primary", true) || volume.matches(Regex("[0-9A-Fa-f-]+"))) { "存储卷无效" }
        require(relative.isEmpty() || relative.split('/').all(::validDocumentName)) { "目录路径无效" }
        val root = if (volume.equals("primary", true)) Environment.getExternalStorageDirectory()
        else if (Build.VERSION.SDK_INT >= 30) {
            val manager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            manager.storageVolumes.firstOrNull { it.uuid.equals(volume, true) }?.directory ?: File("/storage", volume)
        } else File("/storage", volume)
        val base = root.canonicalFile
        val target = File(base, relative).canonicalFile
        require(target == base || target.path.startsWith(base.path + "/")) { "目录路径无效" }
        return target.takeIf { it.isDirectory && it.canRead() && it.canWrite() }
    }

    fun persist(context: Context, uri: Uri) {
        require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) { "请选择一个可读写的目录" }
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    fun hasTreeAccess(context: Context, uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
}
