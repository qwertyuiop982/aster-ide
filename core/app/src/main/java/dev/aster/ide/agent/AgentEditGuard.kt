package dev.aster.ide.agent

import java.security.MessageDigest

/** Validate both the retained editor buffer and the file before applying a proposal. */
object AgentEditGuard {
    fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun verify(beforeExists: Boolean, before: String, currentExists: Boolean, current: String, diskBaseline: String? = null, diskCurrent: String? = null) {
        check(currentExists == beforeExists) { "文件已创建或删除，请重新生成修改" }
        check(current == before) { "文档内容已变化，请重新生成修改" }
        if (beforeExists && diskBaseline != null) {
            // Autosave can persist the original unsaved overlay while the Agent is working.
            check(diskCurrent == diskBaseline || diskCurrent == sha256(before)) { "磁盘文件已变化，请重新生成修改" }
        }
    }
}