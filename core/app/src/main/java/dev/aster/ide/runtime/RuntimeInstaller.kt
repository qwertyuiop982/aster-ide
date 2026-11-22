package dev.aster.ide.runtime

import android.content.Context
import android.os.Build
import android.system.ErrnoException
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
import java.util.ArrayDeque
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

object RuntimeInstaller {
    const val VERSION = "2025.03.28-r1+apt-android-7"
    const val SHA256 = "c8d702b6f742935001c37cda81b8ac69504a95d5cf28f2899532dd8cd4b057eb"
    private const val LAYOUT_VERSION = "4"
    private val legacyAptConfiguration = (
        "APT::Sandbox::User \"root\";\n" +
        "DPkg::Options { \"--force-confold\"; };\n" +
        "Acquire::Retries \"1\";\n" +
        "Acquire::http::Timeout \"15\";\n" +
        "Acquire::https::Timeout \"15\";\n" +
        "DPkg::Lock::Timeout \"30\";\n"
    ).toByteArray()
    private val installing = Mutex()

    fun installed(context: Context): Boolean {
        val usr = PathRedirect.layout(context).usr
        return File(usr, ".aster-bootstrap").readTextOrNull() == VERSION && canAdoptExisting(usr)
    }
    private fun File.readTextOrNull() = runCatching { readText().trim() }.getOrNull()

    internal enum class PrefixEntry { MISSING, DIRECTORY, OTHER }

    private fun prefixEntry(file: File): PrefixEntry = try {
        if (OsConstants.S_ISDIR(Os.lstat(file.path).st_mode)) PrefixEntry.DIRECTORY else PrefixEntry.OTHER
    } catch (e: ErrnoException) {
        if (e.errno == OsConstants.ENOENT) PrefixEntry.MISSING else throw e
    }

    internal fun canInstallBootstrap(usr: File, entry: (File) -> PrefixEntry = ::prefixEntry): Boolean =
        when (entry(usr)) {
            PrefixEntry.MISSING -> true
            PrefixEntry.OTHER -> false
            PrefixEntry.DIRECTORY -> {
                val children = usr.list()
                val tmp = File(usr, "tmp")
                children != null && (children.isEmpty() ||
                    (children.singleOrNull() == "tmp" && entry(tmp) == PrefixEntry.DIRECTORY &&
                        tmp.list()?.isEmpty() == true))
            }
        }

    internal fun canAdoptExisting(usr: File): Boolean {
        val executables = listOf("bin/bash", "bin/apt-get", "bin/dpkg")
        return usr.isDirectory && executables.all { File(usr, it).let { file -> file.isFile && file.canExecute() } } &&
            File(usr, "lib").isDirectory && File(usr, "etc/apt").isDirectory &&
            File(usr, "var/lib/dpkg/status").isFile
    }

    suspend fun install(context: Context, progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        installing.withLock {
            check(Build.SUPPORTED_ABIS.contains("arm64-v8a")) { "当前内置工具链只支持 arm64-v8a" }
            PathRedirect.ensureLayout(context)
            val layout = PathRedirect.layout(context)
            val usr = layout.usr
            check(prefixEntry(usr) != PrefixEntry.OTHER) { "工具链路径不是实际目录，未覆盖现有文件：$usr" }
            if (!installed(context)) {
                if (canInstallBootstrap(usr)) {
                    extract(context, progress)
                } else {
                    check(canAdoptExisting(usr)) {
                        "已有工具链但无法安全识别，未覆盖任何文件。请保留 $usr 与安装日志后修复。"
                    }
                    progress("检测到现有 Termux 工具链，将保留已安装程序并迁移 Aster 路径配置…\n")
                }
            }
            val tmp = layout.tmp
            check(tmp.isDirectory || tmp.mkdir()) { "无法创建 Termux 临时目录：$tmp" }
            repairLayout(context, progress)
            check(canAdoptExisting(usr)) { "Termux 工具链检查失败：bash、apt、dpkg 或 dpkg 数据库缺失" }
            writeChanged(File(usr, ".aster-bootstrap"), VERSION + "\n")
        }
    }

    private suspend fun extract(context: Context, progress: (String) -> Unit) {
        val root = PathRedirect.layout(context).files
        val usr = PathRedirect.layout(context).usr
        val staging = File(root, "usr-staging")
        check(removeStaging(staging) && staging.mkdir()) { "无法创建解包目录" }
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
                Os.symlink(PathRedirect.realPath(context, target), link.path)
            }
            coroutineContext.ensureActive()
            check(canAdoptExisting(staging)) { "内置 Termux 工具链解包不完整" }
            check(canInstallBootstrap(usr)) { "工具链目录已变化，未覆盖现有环境：$usr" }
            val legacyTmp = File(usr, "tmp")
            if (prefixEntry(legacyTmp) == PrefixEntry.DIRECTORY) {
                check(legacyTmp.delete()) { "无法移除旧版留下的空临时目录：$legacyTmp" }
            }
            Os.rename(staging.path, usr.path)
            progress("Termux 模块已安装：$count 个文件\n")
        } finally {
            runCatching { removeStaging(staging) }
        }
    }

    private fun removeStaging(file: File): Boolean = when (prefixEntry(file)) {
        PrefixEntry.MISSING -> true
        PrefixEntry.DIRECTORY -> file.listFiles()?.all(::removeStaging) == true && file.delete()
        PrefixEntry.OTHER -> file.delete()
    }

    private suspend fun repairLayout(context: Context, progress: (String) -> Unit) {
        val usr = PathRedirect.layout(context).usr
        val marker = File(usr, ".aster-layout-version")
        if (marker.readTextOrNull() != LAYOUT_VERSION || !File(usr, "lib/apt/methods/http").canExecute()) {
            progress("检查运行程序权限并迁移旧版路径…\n")
            var repaired = 0
            val pending = ArrayDeque<File>().apply { add(usr) }
            while (pending.isNotEmpty()) {
                coroutineContext.ensureActive()
                val file = pending.removeLast()
                val stat = runCatching { Os.lstat(file.path) }.getOrNull() ?: continue
                if (OsConstants.S_ISDIR(stat.st_mode)) {
                    file.listFiles()?.forEach { pending.add(it) }
                    continue
                }
                if (OsConstants.S_ISLNK(stat.st_mode)) {
                    val old = Os.readlink(file.path)
                    val target = PathRedirect.realPath(context, old)
                    if (target != old) {
                        val temporary = File.createTempFile(".aster-link-", ".tmp", file.parentFile)
                        check(temporary.delete()) { "无法准备软链接迁移：${file.name}" }
                        Os.symlink(target, temporary.path)
                        try { Os.rename(temporary.path, file.path) } finally { temporary.delete() }
                        repaired++
                    }
                    continue
                }
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
        val layout = PathRedirect.layout(context)
        val configuration = """
            Dir::Etc "$usr/etc/apt";
            Dir::State "$usr/var/lib/apt";
            Dir::State::status "$usr/var/lib/dpkg/status";
            Dir::Cache "${layout.cache}/apt";
            Dir::Bin::methods "$usr/lib/apt/methods";
            Dir::Bin::dpkg "$usr/bin/dpkg";
            Dir::Log "$usr/var/log/apt";
            APT::Sandbox::User "root";
            DPkg::Path "$usr/bin:/system/bin";
            DPkg::Options { "--force-confold"; "--instdir=${layout.packageRoot}"; "--force-script-chrootless"; };
        """.trimIndent() + "\n"
        writeChanged(File(usr, "etc/apt/aster-runtime.conf"), configuration)
        if (removeLegacyAptConfiguration(usr)) progress("已移除旧版 Aster 的 APT 超时配置\n")
        writeChanged(File(usr, "etc/dpkg/dpkg.cfg.d/99aster"), "instdir ${layout.packageRoot}\nforce-script-chrootless\n")
    }

    internal fun removeLegacyAptConfiguration(usr: File): Boolean {
        // APT still reads apt.conf.d when APT_CONFIG is set.
        val file = File(usr.canonicalFile, "etc/apt/apt.conf.d/99aster")
        if (!file.isFile || file.canonicalFile != file || file.length() != legacyAptConfiguration.size.toLong() ||
            !file.readBytes().contentEquals(legacyAptConfiguration)) return false
        check(file.delete()) { "无法移除旧版 APT 配置：${file.path}" }
        return true
    }

    private fun writeChanged(file: File, value: String) {
        if (runCatching { file.readText() }.getOrNull() == value) return
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".aster-tmp")
        temporary.writeText(value)
        check(temporary.renameTo(file)) { "无法保存 ${file.name}" }
    }
}
