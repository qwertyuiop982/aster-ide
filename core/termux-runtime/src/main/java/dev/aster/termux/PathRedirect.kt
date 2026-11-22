package dev.aster.termux

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import org.json.JSONObject

object PathRedirect {
    data class Layout(val root: File) {
        val files get() = File(root, "files")
        val home get() = File(files, "home")
        val usr get() = File(files, "usr")
        val tmp get() = File(usr, "tmp")
        val cache get() = File(root, "cache")
        val packageRoot get() = File(root, "packages")
        val workspaceRoot get() = File(home, ".aster/workspaces")
    }
    private val workspaceLock = Any()
    private val workspaceId = Regex("[A-Za-z0-9_-]{1,64}")
    private fun bindingsFile(context: Context) = File(appFilesDirectory(context), "native-workspaces.json")
    fun workspaceBindings(context: Context): String = synchronized(workspaceLock) {
        bindingsFile(context).takeIf(File::isFile)?.readText() ?: "{}"
    }
    fun workspaceDirectory(context: Context, id: String): File? =
        JSONObject(workspaceBindings(context)).optString(id).takeIf(String::isNotEmpty)?.let(::File)

    fun workspaceBound(context: Context, id: String, directory: File): Boolean =
        workspaceDirectory(context, id)?.path == directory.canonicalPath

    fun registerWorkspace(context: Context, id: String, directory: File) = synchronized(workspaceLock) {
        require(id.matches(workspaceId))
        val root = directory.canonicalFile
        require(root.isDirectory && root.canRead() && root.canExecute()) { "无法访问项目目录" }
        val values = JSONObject(workspaceBindings(context))
        if (values.optString(id) != root.path) {
            values.put(id, root.path)
            writeBindings(context, values)
        }
    }

    fun unregisterWorkspace(context: Context, id: String) = synchronized(workspaceLock) {
        require(id.matches(workspaceId))
        val values = JSONObject(workspaceBindings(context))
        if (values.remove(id) != null) writeBindings(context, values)
    }

    private fun writeBindings(context: Context, values: JSONObject) {
        val file = bindingsFile(context)
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.outputStream().use { output -> output.write(values.toString().toByteArray()); output.fd.sync() }
        check(temporary.renameTo(file)) { "无法保存项目目录映射" }
    }

    private fun appFilesDirectory(context: Context): File = appFilesDirectory(context.filesDir, context.packageName)

    @SuppressLint("SdCardPath")
    internal fun appFilesDirectory(
        current: File,
        packageName: String,
        sameDirectory: (File, File) -> Boolean = ::sameUsableDirectory,
    ): File {
        val preferred = File("/data/data/$packageName/files")
        // This spelling may not be an alias for the current app in another Android profile.
        return if (current == preferred || sameDirectory(current, preferred)) preferred else current
    }

    private fun sameUsableDirectory(current: File, candidate: File): Boolean = try {
        val original = Os.stat(current.path)
        val alias = Os.stat(candidate.path)
        OsConstants.S_ISDIR(alias.st_mode) && original.st_dev == alias.st_dev && original.st_ino == alias.st_ino &&
            Os.access(candidate.path, OsConstants.R_OK or OsConstants.W_OK or OsConstants.X_OK)
    } catch (_: ErrnoException) {
        false
    }

    fun layout(context: Context): Layout = Layout(File(appFilesDirectory(context), "termux"))

    fun realPath(context: Context, path: String): String {
        if (!path.startsWith("/data/")) return path
        return realPath(layout(context), context.packageName, path)
    }

    internal fun realPath(
        layout: Layout,
        packageName: String,
        path: String,
        sameDirectory: (File, File) -> Boolean = ::sameUsableDirectory,
    ): String {
        val legacyRoot = "/data/data/com.termux"
        val legacyAlias = legacyRoot.takeIf { isAtOrUnder(path, it) } ?: androidUserAppRoot(path, "com.termux")
        if (legacyAlias != null) return layout.root.path + path.removePrefix(legacyAlias)

        if (isAtOrUnder(path, layout.root.path)) return path
        val appAlias = androidUserAppRoot(path, packageName) ?: return path
        val aliasFiles = File(appAlias, "files")
        val aliasRuntime = File(aliasFiles, "termux").path
        val runtimeParent = layout.root.parentFile ?: return path
        return if (isAtOrUnder(path, aliasRuntime) && sameDirectory(runtimeParent, aliasFiles)) {
            layout.root.path + path.removePrefix(aliasRuntime)
        } else path
    }

    private fun isAtOrUnder(path: String, root: String): Boolean = path == root || path.startsWith("$root/")

    private fun androidUserAppRoot(path: String, packageName: String): String? {
        val userPrefix = "/data/user/"
        if (!path.startsWith(userPrefix)) return null
        val userEnd = path.indexOf('/', userPrefix.length)
        if (userEnd <= userPrefix.length) return null
        val userId = path.substring(userPrefix.length, userEnd)
        if (userId.any { it !in '0'..'9' }) return null
        val alias = "$userPrefix$userId/$packageName"
        return alias.takeIf { isAtOrUnder(path, it) }
    }

    fun process(context: Context, cwd: File, vararg command: String): ProcessBuilder {
        val l = layout(context)
        val native = File(context.applicationInfo.nativeLibraryDir)
        val compat = File(native, "libaster_runtime.so")
        check(compat.isFile) { "缺少内置 Termux 路径兼容模块" }
        val builder = ProcessBuilder(command.toList()).directory(cwd)
        val variables = mapOf(
            "HOME" to l.home.path,
            "PREFIX" to l.usr.path,
            // termux-exec's postinst uses this prefix even when its helper unloads the shim.
            "TERMUX__PREFIX" to l.usr.path,
            "TMPDIR" to l.tmp.path,
            "PATH" to "${l.home}/.aster/bin:${l.usr}/bin:/system/bin",
            "SHELL" to File(l.usr, "bin/bash").path,
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "ANDROID_DATA" to "/data",
            "ANDROID_ROOT" to "/system",
            "ANDROID__BUILD_VERSION_SDK" to Build.VERSION.SDK_INT.toString(),
            "ASTER_RUNTIME_ROOT" to l.root.path,
            "ASTER_PRIVATE_FILES" to l.files.path,
            "ASTER_PRIVATE_CACHE" to l.cache.path,
            "ASTER_APT_ROOT" to l.packageRoot.path,
            "ASTER_NATIVE_LIB_DIR" to native.path,
            "ASTER_WORKSPACE_BINDINGS" to bindingsFile(context).path,
            "APT_CONFIG" to File(l.usr, "etc/apt/aster-runtime.conf").path,
            "DPKG_ADMINDIR" to File(l.usr, "var/lib/dpkg").path,
            "SSL_CERT_FILE" to File(l.usr, "etc/tls/cert.pem").path,
            "LD_LIBRARY_PATH" to File(l.usr, "lib").path,
            "LD_PRELOAD" to compat.path,
            "GOROOT" to File(l.usr, "lib/go").path
        )
        builder.environment().apply {
            clear()
            putAll(variables)
        }
        return builder
    }

    fun ensureLayout(context: Context) {
        val l = layout(context)
        // The bootstrap installer owns the contents of usr, including its tmp directory.
        listOf(l.root, l.files, l.home, l.cache, l.packageRoot, File(l.cache, "apt/archives/partial"), l.workspaceRoot).forEach { it.mkdirs() }
        File(l.home, ".termux/termux.properties").apply { parentFile?.mkdirs(); if (!exists()) writeText("allow-external-apps=false\n") }
    }
}
