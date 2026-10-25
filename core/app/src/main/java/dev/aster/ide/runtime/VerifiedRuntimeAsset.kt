package dev.aster.ide.runtime

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/** Installs a checked asset without replacing a working copy on a partial/corrupt read. */
internal object VerifiedRuntimeAsset {
    fun requireContainedDirectory(base: File, directory: File) {
        val canonicalBase = base.canonicalFile
        val resolved = directory.canonicalFile
        check(resolved.path.startsWith(canonicalBase.path + File.separator)) { "OpenClaude 资源目录超出运行环境" }
        var cursor: File? = directory.absoluteFile
        while (cursor != null && cursor != canonicalBase) {
            check(cursor.canonicalFile == File(cursor.parentFile!!.canonicalFile, cursor.name)) { "OpenClaude 资源目录不能指向符号链接" }
            cursor = cursor.parentFile
        }
    }
    fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32768)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        hex(digest.digest())
    }

    fun install(target: File, bytes: Long, expectedSha256: String, source: () -> InputStream): Boolean {
        require(bytes in 1..(64L * 1024 * 1024)) { "本地研究资源大小无效" }
        require(expectedSha256.matches(Regex("[0-9a-f]{64}"))) { "本地研究资源校验值无效" }
        val parent = checkNotNull(target.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "无法创建本地研究资源目录" }
        check(target.canonicalFile == File(parent.canonicalFile, target.name)) { "本地研究资源不能指向符号链接" }
        if (target.isFile && target.length() == bytes && sha256(target) == expectedSha256) return false
        val temporary = File.createTempFile(".aster-research-", ".tmp", parent)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            source().use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(32768)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= bytes) { "本地研究资源超出声明大小" }
                        if (count > 0) {
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                        }
                    }
                    output.fd.sync()
                }
            }
            check(total == bytes && hex(digest.digest()) == expectedSha256) { "OpenClaude 本地研究资源完整性校验失败" }
            check(temporary.renameTo(target)) { "无法安装已校验的本地研究资源" }
            return true
        } finally {
            temporary.delete()
        }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
