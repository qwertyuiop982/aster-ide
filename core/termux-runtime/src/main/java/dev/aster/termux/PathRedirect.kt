package dev.aster.termux

import android.content.Context
import java.io.File
import org.json.JSONObject

object PathRedirect {
    init { System.loadLibrary("aster_runtime") }
    private external fun arguments(proot: String, root: String, cwd: String, command: Array<String>): Array<String>
    data class Layout(val root: File) {
        val files get() = File(root, "files")
        val home get() = File(files, "home")
        val usr get() = File(files, "usr")
        val tmp get() = File(root, "tmp")
        val cache get() = File(root, "cache")
        val workspaceRoot get() = File(home, ".aster/workspaces")
    }
    private val workspaceLock = Any()
    private fun bindingsFile(context: Context) = File(context.filesDir, "native-workspaces.json")
    fun workspaceBindings(context: Context): String = synchronized(workspaceLock) {
        bindingsFile(context).takeIf(File::isFile)?.readText() ?: "{}"
    }
    fun workspaceBound(context: Context, id: String, directory: File): Boolean =
        JSONObject(workspaceBindings(context)).optString(id) == directory.canonicalPath
    fun registerWorkspace(context: Context, id: String, directory: File) = synchronized(workspaceLock) {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val root = directory.canonicalFile
        require(root.isDirectory && ':' !in root.path && '\n' !in root.path) { "原目录不能映射到运行环境" }
        val file = bindingsFile(context)
        val values = JSONObject(workspaceBindings(context))
        if (values.optString(id) != root.path) {
            values.put(id, root.path)
            val temporary = File(file.parentFile, file.name + ".tmp")
            temporary.outputStream().use { output -> output.write(values.toString().toByteArray()); output.fd.sync() }
            check(temporary.renameTo(file)) { "无法保存项目目录映射" }
        }
    }
    fun layout(context: Context): Layout = Layout(File(context.filesDir, "termux"))
    fun bootstrap(context: Context): File = File(context.filesDir, "termux-runtime/bootstrap-aarch64.zip")
    fun proot(context: Context): File = File(context.applicationInfo.nativeLibraryDir, "libaster_proot.so")
    fun command(context: Context, cwd: File, vararg command: String): Array<String> {
        val layout = layout(context)
        val guestCwd = if (cwd.absolutePath.startsWith(layout.files.absolutePath)) {
            "/data/data/com.termux/files/" + cwd.absolutePath.removePrefix(layout.files.absolutePath).trimStart('/')
        } else "/data/data/com.termux/files/home"
        val guestEnvironment = listOf("/system/bin/env", "-i", "HOME=/data/data/com.termux/files/home", "PREFIX=/data/data/com.termux/files/usr",
            "TMPDIR=/data/data/com.termux/files/usr/tmp", "PATH=/data/data/com.termux/files/home/.aster/bin:/data/data/com.termux/files/usr/bin:/system/bin",
            "ASTER_NATIVE_LIB_DIR=/aster-native", "LD_LIBRARY_PATH=/data/data/com.termux/files/usr/lib", "TERM=xterm-256color", "LANG=C.UTF-8", "ANDROID_DATA=/data", "ANDROID_ROOT=/system")
        val mapped = arguments(proot(context).absolutePath, layout.root.absolutePath, guestCwd, (guestEnvironment + command.toList()).toTypedArray()).toMutableList()
        mapped.addAll(1, listOf("-b", context.applicationInfo.nativeLibraryDir + ":/aster-native"))
        val bindings = JSONObject(workspaceBindings(context))
        bindings.keys().asSequence().sorted().forEach { id ->
            val source = File(bindings.getString(id))
            if (source.isDirectory) {
                val target = "/data/data/com.termux/files/home/.aster/workspaces/$id"
                File(layout.workspaceRoot, id).mkdirs()
                mapped.addAll(1, listOf("-b", source.canonicalPath + ":" + target))
            }
        }
        listOf("/storage", "/sdcard").forEach { path ->
            if (File(path).exists()) mapped.addAll(1, listOf("-b", path))
        }
        val codexHost = File(context.applicationInfo.nativeLibraryDir, "libaster_codex_host.so")
        if (codexHost.isFile) {
            mapped.addAll(1, listOf("-b", codexHost.absolutePath + ":/aster-native/codex-code-mode-host"))
        }
        // PRoot's quiet level keeps fatal diagnostics and leaves child stderr intact.
        mapped.addAll(1, listOf("-v", "-1"))
        return mapped.toTypedArray()
    }
    fun environment(context: Context): Map<String, String> {
        val layout = layout(context)
        return mapOf(
            "HOME" to "/data/data/com.termux/files/home",
            "PREFIX" to "/data/data/com.termux/files/usr",
            "TMPDIR" to "/data/data/com.termux/files/usr/tmp",
            "PATH" to "/data/data/com.termux/files/usr/bin:/data/data/com.termux/files/home/.aster/bin",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "ANDROID_DATA" to "/data",
            "ANDROID_ROOT" to "/system",
            "ASTER_PRIVATE_FILES" to layout.files.absolutePath,
            "LD_LIBRARY_PATH" to File(layout.root, "host").absolutePath,
            "PROOT_LOADER" to File(context.applicationInfo.nativeLibraryDir, "libaster_loader.so").absolutePath,
            "PROOT_TMP_DIR" to layout.tmp.absolutePath
        )
    }
    fun ensureLayout(context: Context) {
        val l = layout(context)
        listOf(l.root, l.files, l.home, l.usr, l.tmp, l.cache, File(l.cache, "apt/archives/partial"), l.workspaceRoot).forEach { it.mkdirs() }
        val rootfs = File(l.root, "rootfs")
        listOf("aster-native", "data/data/com.termux/files", "data/user/0/com.termux/files", "data/data/com.termux/cache", "data/user/0/com.termux/cache", "system", "apex", "proc", "dev", "tmp", "bin", "usr", "storage", "sdcard").forEach { File(rootfs, it).mkdirs() }
        File(l.home, ".termux/termux.properties").apply { parentFile?.mkdirs(); if (!exists()) writeText("allow-external-apps=false\n") }
    }
}