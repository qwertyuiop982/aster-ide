package dev.aster.ide.runtime

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import dev.aster.termux.PathRedirect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

object RuntimeInstaller {
    const val VERSION = "2025.03.28-r1+apt-android-7"
    const val SHA256 = "c8d702b6f742935001c37cda81b8ac69504a95d5cf28f2899532dd8cd4b057eb"
    const val USTC_SOURCE = "deb https://mirrors.ustc.edu.cn/termux/termux-main stable main\n"
    private const val LAYOUT_VERSION = "2"
    private val installing = Mutex()

    fun installed(context: Context): Boolean {
        val usr = PathRedirect.layout(context).usr
        return File(usr, ".aster-bootstrap").readTextOrNull() == VERSION && File(usr, "bin/bash").isFile
    }
    private fun File.readTextOrNull() = runCatching { readText().trim() }.getOrNull()

    suspend fun install(context: Context, mirror: String, progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        installing.withLock {
            check(Build.SUPPORTED_ABIS.contains("arm64-v8a")) { "当前内置工具链只支持 arm64-v8a" }
            PathRedirect.ensureLayout(context)
            if (!installed(context)) extract(context, progress)
            repairLayout(context, progress)
            configureSources(context, mirror)
            prepareHost(context)
        }
    }

    private suspend fun extract(context: Context, progress: (String) -> Unit) {
        val root = PathRedirect.layout(context).files
        val usr = PathRedirect.layout(context).usr
        check(!usr.exists() || usr.listFiles().isNullOrEmpty()) { "已有工具链，请保留安装日志以便修复；不会覆盖现有环境" }
        val staging = File(root, "usr-staging").apply { deleteRecursively(); check(mkdirs()) { "无法创建解包目录" } }
        try {
            progress("校验内置 Termux bootstrap…\n")
            val digest = MessageDigest.getInstance("SHA-256")
            context.assets.open("runtime/bootstrap-aarch64.zip").use { input ->
                val buffer = ByteArray(262144)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == SHA256) { "Termux bootstrap 校验失败" }
            progress("解包到应用私有目录…\n")
            val links = ArrayList<Pair<String, String>>()
            var count = 0
            var total = 0L
            ZipInputStream(context.assets.open("runtime/bootstrap-aarch64.zip")).use { zip ->
                val buffer = ByteArray(65536)
                while (true) {
                    coroutineContext.ensureActive()
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "SYMLINKS.txt") {
                        zip.bufferedReader(Charsets.UTF_8).readText().lineSequence().filter(String::isNotBlank).forEach { line ->
                            val pair = line.split('\u2190', limit = 2)
                            check(pair.size == 2) { "无效的 bootstrap 软链接" }
                            links += pair[0] to pair[1].removePrefix("./")
                        }
                        continue
                    }
                    val name = entry.name.removePrefix("./")
                    check(!name.startsWith('/') && name.split('/').none { it == ".." }) { "不安全的 bootstrap 路径" }
                    val file = File(staging, name).canonicalFile
                    check(file.path.startsWith(staging.canonicalPath + "/")) { "路径超出工具链目录" }
                    if (entry.isDirectory) file.mkdirs()
                    else {
                        file.parentFile?.mkdirs()
                        val header = ByteArray(4)
                        var headerSize = 0
                        FileOutputStream(file).use { output ->
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = zip.read(buffer)
                                if (n < 0) break
                                val copy = minOf(n, header.size - headerSize)
                                if (copy > 0) { buffer.copyInto(header, headerSize, 0, copy); headerSize += copy }
                                total += n
                                check(total <= 256L * 1024 * 1024) { "bootstrap 解包超过大小限制" }
                                output.write(buffer, 0, n)
                            }
                        }
                        Os.chmod(file.path, if (BootstrapPermissions.executable(name, header.copyOf(headerSize))) 448 else 384)
                    }
                    if (++count % 500 == 0) progress("已解包 $count 个文件\n")
                }
            }
            for ((target, name) in links) {
                coroutineContext.ensureActive()
                check(!name.startsWith('/') && name.split('/').none { it == ".." }) { "不安全的软链接路径" }
                val link = File(staging, name)
                link.parentFile?.mkdirs()
                if (target.startsWith('/')) check(target.startsWith("/data/data/com.termux/files/") || target.startsWith("/data/user/0/com.termux/files/") || target.startsWith("/system/")) { "软链接目标不在运行环境" }
                else check(File(link.parentFile, target).canonicalPath.startsWith(root.canonicalPath + "/")) { "软链接目标超出运行环境" }
                Os.symlink(target, link.path)
            }
            usr.delete()
            check(staging.renameTo(usr)) { "无法完成 bootstrap 安装" }
            File(usr, ".aster-bootstrap").writeText(VERSION + "\n")
            progress("Termux 模块已安装：$count 个文件\n")
        } finally { staging.deleteRecursively() }
    }

    private suspend fun repairLayout(context: Context, progress: (String) -> Unit) {
        val usr = PathRedirect.layout(context).usr
        val marker = File(usr, ".aster-layout-version")
        if (marker.readTextOrNull() != LAYOUT_VERSION || !File(usr, "lib/apt/methods/http").canExecute()) {
            progress("检查并修复运行程序权限…\n")
            var repaired = 0
            for (file in usr.walkTopDown()) {
                coroutineContext.ensureActive()
                val stat = runCatching { Os.lstat(file.path) }.getOrNull() ?: continue
                if (!OsConstants.S_ISREG(stat.st_mode) || file.canExecute()) continue
                val header = file.inputStream().use { input ->
                    val value = ByteArray(4)
                    val size = input.read(value)
                    value.copyOf(size.coerceAtLeast(0))
                }
                if (BootstrapPermissions.executable(file.relativeTo(usr).invariantSeparatorsPath, header)) {
                    Os.chmod(file.path, 448)
                    repaired++
                }
            }
            marker.writeText(LAYOUT_VERSION + "\n")
            progress("运行程序权限就绪（修复 $repaired 项）\n")
        }
        val configuration = "APT::Sandbox::User \"root\";\nDPkg::Options { \"--force-confold\"; };\nAcquire::Retries \"1\";\nAcquire::http::Timeout \"15\";\nAcquire::https::Timeout \"15\";\nDPkg::Lock::Timeout \"30\";\n"
        writeChanged(File(usr, "etc/apt/apt.conf.d/99aster"), configuration)
    }

    private fun configureSources(context: Context, mirror: String) {
        if (mirror != "ustc" && mirror != "tsinghua") return
        val sources = File(PathRedirect.layout(context).usr, "etc/apt/sources.list")
        val backup = File(sources.parentFile, "sources.list.aster-backup")
        if (sources.isFile && !backup.exists()) sources.copyTo(backup)
        writeChanged(sources, USTC_SOURCE)
    }
    private fun writeChanged(file: File, value: String) {
        if (runCatching { file.readText() }.getOrNull() == value) return
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".aster-tmp")
        temporary.writeText(value)
        check(temporary.renameTo(file)) { "无法保存 ${file.name}" }
    }
    fun prepareHost(context: Context) {
        val host = File(PathRedirect.layout(context).root, "host").apply { mkdirs() }
        val libraries = File(context.applicationInfo.nativeLibraryDir)
        for ((source, target) in listOf("libtalloc.so" to "libtalloc.so.2", "libandroid-shmem.so" to "libandroid-shmem.so")) {
            val original = File(libraries, source)
            val copy = File(host, target)
            if (!copy.isFile || !copy.readBytes().contentEquals(original.readBytes())) original.copyTo(copy, overwrite = true)
            Os.chmod(copy.path, 448)
        }
    }
}