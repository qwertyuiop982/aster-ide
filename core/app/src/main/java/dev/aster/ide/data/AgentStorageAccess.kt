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

    fun directory(context: Context, uri: Uri): File {
        check(granted(context)) { "请先授予文件访问权限，再打开原目录" }
        require(uri.authority == "com.android.externalstorage.documents") { "原生命令需要设备存储目录，请选择内部存储或 SD 卡中的文件夹" }
        val id = DocumentsContract.getTreeDocumentId(uri)
        val volume = id.substringBefore(':')
        val relative = id.substringAfter(':', "")
        require(relative.split('/').none { it == ".." } && !relative.startsWith('/')) { "目录路径无效" }
        val root = if (volume.equals("primary", true)) Environment.getExternalStorageDirectory()
        else if (Build.VERSION.SDK_INT >= 30) {
            val manager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            manager.storageVolumes.firstOrNull { it.uuid.equals(volume, true) }?.directory ?: File("/storage", volume)
        } else File("/storage", volume)
        val base = root.canonicalFile
        val target = File(base, relative).canonicalFile
        require(target == base || target.path.startsWith(base.path + "/")) { "目录路径无效" }
        check(target.isDirectory && target.canRead() && target.canWrite()) { "此目录没有文件系统读写权限；请重新授权或选择其他目录" }
        return target
    }

    fun persist(context: Context, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
}
