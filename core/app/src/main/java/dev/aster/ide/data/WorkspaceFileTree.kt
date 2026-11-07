package dev.aster.ide.data

import java.io.File

/** Bounded directory listing shared by startup and the full project explorer. */
object WorkspaceFileTree {
    fun scan(
        directory: File,
        ignored: Set<String>,
        maxDepth: Int = 32,
        maxEntries: Int = 10000,
        checkCancelled: () -> Unit = {}
    ): List<FileNode> {
        require(maxDepth >= 0 && maxEntries > 0)
        val root = directory.canonicalFile
        val prefix = root.path + File.separator
        val visited = HashSet<String>()
        val nodes = ArrayList<FileNode>()
        fun walk(dir: File, depth: Int) {
            checkCancelled()
            if (depth > maxDepth || nodes.size >= maxEntries || !visited.add(dir.canonicalPath)) return
            val entries = dir.listFiles().orEmpty()
                .filter { it.name !in ignored && it.name != ".aster-workspace.json" }
                .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            for (file in entries) {
                checkCancelled()
                if (nodes.size >= maxEntries) break
                val canonical = file.canonicalPath
                if (!canonical.startsWith(prefix)) continue
                val isDirectory = file.isDirectory
                nodes += FileNode(file.relativeTo(root).invariantSeparatorsPath, file.name, isDirectory, depth, if (isDirectory) 0 else file.length())
                if (isDirectory && depth < maxDepth) walk(file, depth + 1)
            }
        }
        walk(root, 0)
        return nodes
    }
}
