package dev.aster.ide.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class Workspace(val id: String, val name: String, val directory: File, val source: String? = null)
data class FileNode(val path: String, val name: String, val directory: Boolean, val depth: Int, val size: Long)
class WorkspaceRepository(private val context: Context, private val settings: SettingsStore) {
    private val home = File(context.filesDir, "workspaces").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("workspace", Context.MODE_PRIVATE)
    private val ignored get() = settings.value.excludedDirs.split(',').map(String::trim).filter(String::isNotEmpty).toSet()

    suspend fun initialize(): Workspace = withContext(Dispatchers.IO) {
        val recent = prefs.getString("current", null)?.let { load(it) }
        recent ?: demo().also { select(it) }
    }

    private fun load(id: String): Workspace? = runCatching {
        val dir = File(home, id)
        val meta = JSONObject(File(dir, ".aster-workspace.json").readText())
        Workspace(id, meta.getString("name"), dir, meta.optString("source").takeIf(String::isNotBlank))
    }.getOrNull()

    suspend fun recent(): List<Workspace> = withContext(Dispatchers.IO) {
        home.listFiles()?.filter(File::isDirectory)?.mapNotNull { load(it.name) }?.sortedByDescending { it.directory.lastModified() } ?: emptyList()
    }

    fun select(workspace: Workspace) { prefs.edit().putString("current", workspace.id).apply() }

    private fun metadata(workspace: Workspace) {
        File(workspace.directory, ".aster-workspace.json").writeText(JSONObject().put("name", workspace.name).put("source", workspace.source ?: "").toString())
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

    fun resolve(workspace: Workspace, path: String): File {
        require(path.isNotBlank() && !path.startsWith('/') && path.split('/').none { it == ".." || it.isBlank() }) { "无效的相对路径" }
        val root = workspace.directory.canonicalFile
        val file = File(root, path).canonicalFile
        require(file.path.startsWith(root.path + File.separator)) { "路径超出项目目录" }
        return file
    }

    suspend fun files(workspace: Workspace): List<FileNode> = withContext(Dispatchers.IO) {
        val nodes = ArrayList<FileNode>()
        fun walk(dir: File, depth: Int) {
            if (depth > 32 || nodes.size >= 10000) return
            dir.listFiles()?.filter { it.name !in ignored && it.name != ".aster-workspace.json" }
                ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })?.forEach { f ->
                    if (f.canonicalPath.startsWith(workspace.directory.canonicalPath + "/")) {
                        nodes += FileNode(f.relativeTo(workspace.directory).invariantSeparatorsPath, f.name, f.isDirectory, depth, f.length())
                        if (f.isDirectory) walk(f, depth + 1)
                    }
                }
        }
        walk(workspace.directory, 0)
        nodes
    }

    suspend fun save(workspace: Workspace, path: String, text: String) = withContext(Dispatchers.IO) {
        val file = resolve(workspace, path)
        file.parentFile?.mkdirs()
        val temp = File.createTempFile(".aster-save-", ".tmp", file.parentFile)
        try {
            FileOutputStream(temp).use { out -> out.write(text.toByteArray(Charsets.UTF_8)); out.fd.sync() }
            check(temp.renameTo(file)) { "无法原子保存 $path" }
        } finally { temp.delete() }
        workspace.source?.let { exportFile(workspace, path, text) }
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
        else { target.parentFile?.mkdirs(); check(target.createNewFile()) { "创建文件失败" }; workspace.source?.let { exportFile(workspace, path, "") } }
    }

    suspend fun rename(workspace: Workspace, old: String, new: String) = withContext(Dispatchers.IO) {
        val from = resolve(workspace, old)
        val to = resolve(workspace, new)
        check(!to.exists()) { "目标路径已存在" }
        to.parentFile?.mkdirs()
        check(from.renameTo(to)) { "重命名失败" }
        workspace.source?.let { source ->
            var doc = DocumentFile.fromTreeUri(context, Uri.parse(source))
            old.split('/').forEach { doc = doc?.findFile(it) }
            if (old.substringBeforeLast('/', "") == new.substringBeforeLast('/', "")) doc?.renameTo(new.substringAfterLast('/'))
        }
    }

    suspend fun delete(workspace: Workspace, path: String) = withContext(Dispatchers.IO) {
        check(resolve(workspace, path).deleteRecursively()) { "删除失败" }
        workspace.source?.let { source ->
            var doc = DocumentFile.fromTreeUri(context, Uri.parse(source))
            path.split('/').forEach { doc = doc?.findFile(it) }
            doc?.delete()
        }
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