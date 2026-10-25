package dev.aster.termux

import android.content.Context
import android.os.Build
import java.io.File

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
        val codexHost = File(context.applicationInfo.nativeLibraryDir, "libaster_codex_host.so")
        if (codexHost.isFile) {
            mapped.addAll(1, listOf("-b", codexHost.absolutePath + ":/aster-native/codex-code-mode-host"))
        }
        // PRoot's quiet level keeps fatal diagnostics and leaves child stderr intact.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            mapped.addAll(1, listOf("-v", "-1"))
        }
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
        listOf("aster-native", "data/data/com.termux/files", "data/user/0/com.termux/files", "data/data/com.termux/cache", "data/user/0/com.termux/cache", "system", "apex", "proc", "dev", "tmp", "bin", "usr").forEach { File(rootfs, it).mkdirs() }
        File(l.home, ".termux/termux.properties").apply { parentFile?.mkdirs(); if (!exists()) writeText("allow-external-apps=false\n") }
    }
}