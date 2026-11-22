package dev.aster.ide.data

import android.content.Context
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import androidx.documentfile.provider.DocumentFile
import dev.aster.termux.PathRedirect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class Workspace(
    val id: String, val name: String, val directory: File, val source: String? = null,
    val direct: Boolean = false, val safMirror: Boolean = false,
)
data class FileNode(val path: String, val name: String, val directory: Boolean, val depth: Int, val size: Long)
class WorkspaceRepository(private val context: Context, private val settings: SettingsStore) {
    private val home = File(context.filesDir, "workspaces").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("workspace", Context.MODE_PRIVATE)
    private val mounting = Mutex()
    private val mountRoot = File(File(context.applicationInfo.dataDir).canonicalFile, "home/app_sda")
    private val ignored get() = settings.value.excludedDirs.split(',').map(String::trim).filter(String::isNotEmpty).toSet()

    suspend fun conversationWorkspace(): Workspace = withContext(Dispatchers.IO) {
        Workspace("agent-chat", "未绑定工作区", File(context.filesDir, "agent-chat").apply { mkdirs() })
    }
    suspend fun initialize(): Workspace = withContext(Dispatchers.IO) {
        val recent = prefs.getString("current", null)?.let { load(it) }
        recent ?: demo().also { select(it) }
    }

    private fun load(id: String): Workspace? = runCatching {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val dir = File(home, id)
        val meta = JSONObject(File(dir, ".aster-workspace.json").readText())
        val mirror = meta.optBoolean("safMirror")
        val directPath = meta.optString("directPath").takeIf(String::isNotBlank)?.let(::File)
        Workspace(id, meta.getString("name"), if (mirror) File(mountRoot, id) else directPath ?: dir,
            meta.optString("source").takeIf(String::isNotBlank), mirror || directPath != null, mirror)
    }.getOrNull()

    suspend fun find(id: String): Workspace? = withContext(Dispatchers.IO) { load(id) }

    suspend fun recent(): List<Workspace> = withContext(Dispatchers.IO) {
        home.listFiles()?.filter(File::isDirectory)?.mapNotNull { load(it.name) }?.sortedByDescending { it.directory.lastModified() } ?: emptyList()
    }

    fun select(workspace: Workspace) { prefs.edit().putString("current", workspace.id).apply() }

    private fun metadata(workspace: Workspace) {
        File(File(home, workspace.id).apply { mkdirs() }, ".aster-workspace.json").writeText(JSONObject()
            .put("name", workspace.name).put("source", workspace.source.orEmpty()).put("safMirror", workspace.safMirror)
            .put("directPath", if (workspace.direct) workspace.directory.canonicalPath else "").toString())
    }

    suspend fun create(name: String): Workspace = withContext(Dispatchers.IO) {
        require(name.isNotBlank()) { "项目名称不能为空" }
        val id = UUID.randomUUID().toString()
        Workspace(id, name.trim(), File(home, id).apply { mkdirs() }).also { metadata(it); select(it) }
    }

    suspend fun importTree(uri: Uri): Pair<Workspace, Int> = withContext(Dispatchers.IO) {
        val tree = DocumentFile.fromTreeUri(context, uri) ?: error("无法读取这个目录")
        val work = create(tree.name ?: "导入的项目").copy(source = uri.toString())
        var count = 0
        var bytes = 0L
        var skipped = 0
        fun walk(folder: DocumentFile, relative: String, depth: Int) {
            check(depth <= 32) { "目录层级超过 32" }
            folder.listFiles().sortedBy { it.name }.forEach { file ->
                val name = file.name ?: return@forEach
                if (name in ignored || name in setOf("local.properties", ".aster-workspace.json") || '/' in name || name == "..") return@forEach
                val path = if (relative.isEmpty()) name else "$relative/$name"
                // Preserve original projects; do not import ACS editor/session metadata.
                if (!WorkspaceTransferPolicy.shouldCopy(path)) return@forEach
                if (file.isDirectory) walk(file, path, depth + 1)
                else if (file.isFile) {
                    if (file.length() > settings.value.maxFileMB * 1024L * 1024) { skipped++; return@forEach }
                    check(++count <= 10000) { "项目超过 10000 个文件，请排除生成目录后重试" }
                    val dest = resolve(work, path)
                    dest.parentFile?.mkdirs()
                    context.contentResolver.openInputStream(file.uri)?.use { input ->
                        FileOutputStream(dest).use { out ->
                            val buffer = ByteArray(32768)
                            var size = 0L
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                size += n; bytes += n
                                check(size <= settings.value.maxFileMB * 1024L * 1024) { "$name 文件太大" }
                                check(bytes <= 256L * 1024 * 1024) { "导入文件总大小超过 256 MiB" }
                                out.write(buffer, 0, n)
                            }
                        }
                    } ?: error("无法读取 $name")
                }
            }
        }
        try {
            walk(tree, "", 0)
            metadata(work)
            select(work)
            work to skipped
        } catch (error: Exception) {
            work.directory.deleteRecursively()
            throw error
        }
    }

    suspend fun openDirectTree(uri: Uri): Workspace = withContext(Dispatchers.IO) {
        AgentStorageAccess.persist(context, uri)
        val source = uri.toString()
        val existing = recent().firstOrNull { it.direct && it.source == source }
        val id = existing?.id ?: UUID.nameUUIDFromBytes(source.toByteArray()).toString()
        val tree = DocumentFile.fromTreeUri(context, uri) ?: error("无法打开所选目录")
        check(tree.isDirectory && tree.canRead()) { "所选目录不可读取" }
        val local = AgentStorageAccess.directory(context, uri)
        val work = existing ?: Workspace(id, tree.name ?: local?.name ?: "已授权目录",
            local ?: File(mountRoot, id), source, direct = true, safMirror = local == null)
        nativeWorkspace(work)
        metadata(work)
        work
    }

    suspend fun nativeWorkspace(workspace: Workspace): Workspace = withContext(Dispatchers.IO) {
        if (workspace.id == "agent-chat") return@withContext workspace
        if (workspace.direct) refreshMount(workspace)
        check(workspace.directory.isDirectory && workspace.directory.canRead() && workspace.directory.canWrite()) { "项目目录不可读写" }
        PathRedirect.registerWorkspace(context, workspace.id, workspace.directory)
        workspace
    }

    private fun authorizedDirectory(workspace: Workspace): Uri {
        val uri = Uri.parse(checkNotNull(workspace.source))
        check(AgentStorageAccess.hasTreeAccess(context, uri)) { "原目录授权已失效，请重新绑定工作目录" }
        if (!workspace.safMirror) {
            check(AgentStorageAccess.directory(context, uri) == workspace.directory.canonicalFile) {
                "原目录没有直接文件权限，请恢复授权后重试"
            }
        }
        return uri
    }

    suspend fun checkAccess(workspace: Workspace): Unit = withContext(Dispatchers.IO) {
        if (workspace.direct) authorizedDirectory(workspace)
    }

    suspend fun refreshMount(workspace: Workspace): Unit = withContext(Dispatchers.IO) {
        if (!workspace.direct) return@withContext
        mounting.withLock {
            val uri = authorizedDirectory(workspace)
            check(mountRoot.canonicalFile == mountRoot && (mountRoot.mkdirs() || mountRoot.isDirectory)) { "无法建立挂载目录" }
            val node = File(mountRoot, workspace.id)
            if (workspace.safMirror) {
                check(workspace.directory == node)
                SafWorkspaceMount(context, uri, node, File(home, "${workspace.id}/.aster-mount-state.json"),
                    ignored, coroutineContext).sync()
            } else {
                val path = workspace.directory.canonicalFile
                if (node.canonicalFile != path) Os.symlink(path.path, node.path)
            }
        }
    }

    private fun localEntry(workspace: Workspace, path: String): File {
        require(path.isNotBlank() && path.split('/').all(::validDocumentName)) { "无效的相对路径" }
        if (workspace.direct) authorizedDirectory(workspace)
        val root = workspace.directory.canonicalFile
        return File(root, path)
    }

    fun resolve(workspace: Workspace, path: String): File {
        val root = workspace.directory.canonicalFile
        val file = localEntry(workspace, path).canonicalFile
        require(file.path.startsWith(root.path + File.separator)) { "路径超出项目目录" }
        return file
    }

    suspend fun files(workspace: Workspace, maxDepth: Int = 32, maxEntries: Int = 10000): List<FileNode> = withContext(Dispatchers.IO) {
        if (workspace.direct) authorizedDirectory(workspace)
        val current = coroutineContext
        WorkspaceFileTree.scan(workspace.directory, ignored, maxDepth, maxEntries) { current.ensureActive() }
    }

    suspend fun save(workspace: Workspace, path: String, text: String) = withContext(Dispatchers.IO) {
        val file = resolve(workspace, path)
        file.parentFile?.mkdirs()
        val temp = File.createTempFile(".aster-save-", ".tmp", file.parentFile)
        try {
            FileOutputStream(temp).use { out -> out.write(text.toByteArray(Charsets.UTF_8)); out.fd.sync() }
            check(temp.renameTo(file)) { "无法原子保存 $path" }
        } finally { temp.delete() }
        if (workspace.safMirror) refreshMount(workspace)
        else workspace.source?.takeUnless { workspace.direct }?.let { exportFile(workspace, path, text) }
    }

    private fun exportFile(workspace: Workspace, path: String, text: String) {
        var parent = DocumentFile.fromTreeUri(context, Uri.parse(workspace.source)) ?: error("原目录授权已失效；已保存应用内副本")
        val parts = path.split('/')
        parts.dropLast(1).forEach { name ->
            parent = parent.findFile(name) ?: parent.createDirectory(name) ?: error("无法在原目录创建 $name")
        }
        val document = parent.findFile(parts.last()) ?: parent.createFile("text/plain", parts.last()) ?: error("无法写回原目录")
        context.contentResolver.openOutputStream(document.uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("无法写回原目录；已保存应用内副本")
    }

    suspend fun newFile(workspace: Workspace, path: String, folder: Boolean = false) = withContext(Dispatchers.IO) {
        val target = resolve(workspace, path)
        check(!target.exists()) { "同名文件已存在" }
        if (folder) check(target.mkdirs()) { "创建目录失败" }
        else { target.parentFile?.mkdirs(); check(target.createNewFile()) { "创建文件失败" }; workspace.source?.takeUnless { workspace.direct }?.let { exportFile(workspace, path, "") } }
        if (workspace.safMirror) refreshMount(workspace)
    }

    suspend fun rename(workspace: Workspace, old: String, new: String) = withContext(Dispatchers.IO) {
        val from = resolve(workspace, old)
        val to = resolve(workspace, new)
        check(!to.exists()) { "目标路径已存在" }
        to.parentFile?.mkdirs()
        check(from.renameTo(to)) { "重命名失败" }
        workspace.source?.takeUnless { workspace.direct }?.let { source ->
            var doc = DocumentFile.fromTreeUri(context, Uri.parse(source))
            old.split('/').forEach { doc = doc?.findFile(it) }
            if (old.substringBeforeLast('/', "") == new.substringBeforeLast('/', "")) doc?.renameTo(new.substringAfterLast('/'))
        }
        if (workspace.safMirror) refreshMount(workspace)
    }

    suspend fun delete(workspace: Workspace, path: String) = withContext(Dispatchers.IO) {
        val entry = localEntry(workspace, path)
        require(entry.parentFile?.let { it.canonicalFile == it.absoluteFile } == true) { "不能通过符号链接目录删除文件" }
        fun delete(node: File) {
            coroutineContext.ensureActive()
            if (OsConstants.S_ISDIR(Os.lstat(node.path).st_mode)) node.listFiles()?.forEach(::delete)
            check(node.delete()) { "删除失败：${node.name}" }
        }
        delete(entry)
        workspace.source?.takeUnless { workspace.direct }?.let { source ->
            var doc = DocumentFile.fromTreeUri(context, Uri.parse(source))
            path.split('/').forEach { doc = doc?.findFile(it) }
            doc?.delete()
        }
        if (workspace.safMirror) refreshMount(workspace)
    }

    private suspend fun demo(): Workspace {
        val work = create("Hello, Aster")
        val samples = mapOf(
            "src/main.py" to """from dataclasses import dataclass

# Welcome to your pocket workspace.
@dataclass(frozen=True)
class Project:
    name: str
    language: str
    lines: int


def total_lines(projects: list[Project]) -> int:
    return sum(project.lines for project in projects)


if __name__ == "__main__":
    projects = [
        Project("Aster", "Kotlin", 2048),
        Project("Bridge", "Python", 512),
        Project("Search", "C++", 128),
    ]
    print(f"Ready to build. {total_lines(projects):,} lines.")
""",
            "src/hello.cpp" to """#include <iostream>
#include <vector>
#include <numeric>

int main() {
    const std::vector<int> values {1, 2, 3, 5, 8};
    const auto total = std::accumulate(values.begin(), values.end(), 0);
    std::cout << "Hello from Aster! sum = " << total << '\n';
    return 0;
}
""",
            "src/app.ts" to """interface Workspace {
  name: string;
  languages: string[];
}

const workspace: Workspace = {
  name: "Hello, Aster",
  languages: ["TypeScript", "Python", "C++"],
};

console.log(`Welcome to ${'$'}{workspace.name}`);
""",
            "README.md" to """# Hello, Aster

一个可以随身携带的代码工作台。

- 左侧文件按钮打开文件树；上方标签切换文件。
- 保存：Ctrl+S；命令面板：Ctrl+Shift+P；查找：Ctrl+F。
- 在设置 → 编译环境中连接 Termux，安装基础工具。
- 在设置 → 语言服务中安装当前语言服务器。
- 点击运行按钮选择编译配置，输出显示在下方面板。
- 导入目录使用 Android 系统文件选择器；保存会写回已授权目录。

首次连接：设置 → 编译环境 → 初始化并启动内置环境。Bootstrap 已打包，启动时先准备内置环境，再加载编辑器。Python 桥接使用中科大源安装；准备失败可重试或继续本地编辑。无需独立 Termux APK 或 Root。
""",
            "pyrightconfig.json" to "{\n  \"typeCheckingMode\": \"basic\",\n  \"include\": [\"src\"]\n}\n"
        )
        withContext(Dispatchers.IO) { samples.forEach { (path, text) -> resolve(work, path).apply { parentFile?.mkdirs(); writeText(text) } } }
        return work
    }
}
