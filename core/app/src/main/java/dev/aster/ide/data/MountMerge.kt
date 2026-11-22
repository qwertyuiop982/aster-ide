package dev.aster.ide.data

internal object MountMerge {
    const val DIRECTORY = "-"

    fun merge(base: Map<String, String>, local: Map<String, String>, remote: Map<String, String>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for (path in base.keys + local.keys + remote.keys) {
            val previous = base[path]
            val left = local[path]
            val right = remote[path]
            val value = when {
                left == right -> left
                left == previous -> right
                right == previous -> left
                else -> error("挂载同步冲突：$path 在本地和原目录均已更改")
            }
            check(value == null || (left == null || (left == DIRECTORY) == (value == DIRECTORY)) &&
                (right == null || (right == DIRECTORY) == (value == DIRECTORY))) {
                "挂载同步冲突：$path 的文件类型发生变化"
            }
            if (value != null) result[path] = value
        }
        for (path in result.keys) {
            val parent = path.substringBeforeLast('/', "")
            check(parent.isEmpty() || result[parent] == DIRECTORY) { "挂载同步冲突：$path 的父目录被删除或替换" }
        }
        return result
    }
}
