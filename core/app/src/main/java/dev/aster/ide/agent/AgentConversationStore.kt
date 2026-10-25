package dev.aster.ide.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object AgentJson {
    fun tasks(tasks: List<AgentTask>) = JSONArray(tasks.map { JSONObject().put("task", it.task).put("status", it.status) })
    fun readTasks(values: JSONArray?) = (0 until (values?.length() ?: 0).coerceAtMost(20)).mapNotNull { index ->
        values?.optJSONObject(index)?.let { item ->
            val task = item.optString("task").take(200)
            val status = item.optString("status")
            if (task.isBlank() || status !in listOf("pending", "in_progress", "completed")) null else AgentTask(task, status)
        }
    }
    fun changes(changes: List<AgentChange>) = JSONArray(changes.map { item ->
        JSONObject().put("path", item.path).put("before", item.before).put("after", item.after).put("sha256", item.sha256)
            .put("beforeExists", item.beforeExists).put("deleted", item.deleted).put("diskSha256", item.diskSha256 ?: JSONObject.NULL)
    })
    fun readChanges(values: JSONArray?) = (0 until (values?.length() ?: 0).coerceAtMost(20)).map { index ->
        val item = requireNotNull(values).getJSONObject(index)
        AgentChange(item.getString("path"), item.getString("before"), item.getString("after"), item.getString("sha256"),
            item.optBoolean("beforeExists", true), item.optBoolean("deleted"), item.optString("diskSha256").takeIf { it.length == 64 })
    }
}

/** Local project-specific snapshots. API configuration and approvals are never serialized. */
class AgentConversationStore(private val root: File) {
    private fun safeId(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "会话路径无效" }
        return value
    }
    private fun folder(workspaceId: String) = File(root, safeId(workspaceId))
    private fun read(file: File, limit: Long): String {
        check(file.length() <= limit) { "会话文件超过读取限制" }
        return file.readText(Charsets.UTF_8)
    }
    private fun atomicWrite(file: File, text: String, limit: Int = 8 * 1024 * 1024) {
        val data = text.toByteArray(Charsets.UTF_8)
        check(data.size <= limit) { "会话超过保存限制" }
        check(file.parentFile?.mkdirs() == true || file.parentFile?.isDirectory == true) { "无法创建会话目录" }
        val temporary = File(file.parentFile, file.name + ".tmp")
        try {
            FileOutputStream(temporary).use { output -> output.write(data); output.fd.sync() }
            check(temporary.renameTo(file)) { "会话写入失败" }
        } finally { temporary.delete() }
    }
    private fun info(item: JSONObject) = AgentConversationInfo(item.getString("id"), item.optString("title", "新会话").take(64),
        item.optLong("updated"), AgentMode.fromId(item.optString("mode")), item.optInt("changes").coerceIn(0, 20), item.optString("engineId", "pi"))

    @Synchronized fun list(workspaceId: String): List<AgentConversationInfo> {
        val directory = folder(workspaceId)
        return directory.listFiles().orEmpty().filter { it.name.endsWith(".meta.json") }.mapNotNull { file ->
            runCatching {
                val metadata = JSONObject(read(file, 8192))
                val id = safeId(metadata.getString("id"))
                check(metadata.getString("workspaceId") == workspaceId && File(directory, "$id.json").isFile)
                info(metadata)
            }.getOrNull()
        }.sortedByDescending { it.updated }.take(30)
    }
    @Synchronized fun selected(workspaceId: String): String? = runCatching {
        val file = File(folder(workspaceId), "selected")
        safeId(read(file, 128).trim()).takeIf { id -> list(workspaceId).any { it.id == id } }
    }.getOrNull()
    @Synchronized fun select(workspaceId: String, id: String) { atomicWrite(File(folder(workspaceId), "selected"), safeId(id), 128) }

    @Synchronized fun save(snapshot: AgentSnapshot) {
        val directory = folder(snapshot.workspaceId)
        val id = safeId(snapshot.id)
        val metaFile = File(directory, "$id.meta.json")
        val previous = runCatching { JSONObject(read(metaFile, 8192)).optLong("updated") }.getOrDefault(0)
        if (previous > snapshot.updated) return
        check(snapshot.changes.size <= 20 && snapshot.changes.sumOf { it.before.toByteArray(Charsets.UTF_8).size.toLong() + it.after.toByteArray(Charsets.UTF_8).size } <= 4L * 1024 * 1024) { "会话修改预览超过限制" }
        val messages = boundedAgentMessages(snapshot.messages)
        val metadata = JSONObject().put("id", id).put("workspaceId", snapshot.workspaceId).put("title", snapshot.title.take(64))
            .put("updated", snapshot.updated).put("mode", snapshot.mode.id).put("engineId", snapshot.engineId).put("changes", snapshot.changes.size)
        val value = JSONObject(metadata.toString()).put("schema", 1).put("status", snapshot.status.take(200)).put("step", snapshot.step)
            .put("tokens", snapshot.tokens).put("wasRunning", snapshot.wasRunning)
            .put("runtimeSessionId", snapshot.runtimeSessionId).put("contextTokens", snapshot.context.tokens)
            .put("contextWindow", snapshot.context.window).put("contextSource", snapshot.context.source).put("contextUpdatedAt", snapshot.context.updatedAt).put("compactions", snapshot.context.compactions).put("proposalRevision", snapshot.proposalRevision)
            .put("messages", JSONArray(messages.map { JSONObject().put("id", it.id).put("role", it.role).put("text", it.text) }))
            .put("tasks", AgentJson.tasks(snapshot.tasks.take(20))).put("edits", AgentJson.changes(snapshot.changes))
        atomicWrite(File(directory, "$id.json"), value.toString())
        atomicWrite(metaFile, metadata.toString(), 8192)
        // Bound the private cache while retaining the newest thirty project sessions.
        directory.listFiles().orEmpty().filter { it.name.endsWith(".meta.json") }.mapNotNull { file ->
            runCatching { file to JSONObject(read(file, 8192)).getLong("updated") }.getOrNull()
        }.sortedByDescending { it.second }.drop(30).forEach { (file, _) ->
            val oldId = file.name.removeSuffix(".meta.json")
            File(directory, "$oldId.json").delete(); file.delete()
        }
    }
    @Synchronized fun load(workspaceId: String, id: String): AgentSnapshot? = runCatching {
        val value = JSONObject(read(File(folder(workspaceId), safeId(id) + ".json"), 8L * 1024 * 1024))
        check(value.getInt("schema") == 1 && value.getString("workspaceId") == workspaceId && value.getString("id") == id) { "会话不属于当前项目" }
        check(AgentEngine.entries.any { it.id == value.optString("engineId", "pi") }) { "会话的引擎无法识别" }
        val messages = value.optJSONArray("messages")
        val rows = (0 until (messages?.length() ?: 0).coerceAtMost(60)).mapNotNull { index ->
            messages?.optJSONObject(index)?.let { item ->
                val role = item.optString("role")
                if (role !in listOf("user", "assistant", "tool", "error")) null else AgentMessage(item.getString("id"), role, item.optString("text").take(24000))
            }
        }
        val edits = AgentJson.readChanges(value.optJSONArray("edits"))
        check(edits.sumOf { it.before.toByteArray(Charsets.UTF_8).size.toLong() + it.after.toByteArray(Charsets.UTF_8).size } <= 4L * 1024 * 1024)
        AgentSnapshot(id, workspaceId, value.optString("title", "新会话").take(64), value.getLong("updated"), AgentMode.fromId(value.optString("mode")),
            boundedAgentMessages(rows), AgentJson.readTasks(value.optJSONArray("tasks")), edits, value.optString("status", "准备就绪"),
            value.optInt("step").coerceIn(0, 64), value.optInt("tokens").coerceAtLeast(0), value.optBoolean("wasRunning"),
            value.optString("runtimeSessionId", id).takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,80}")) } ?: id,
            AgentContextState(value.optInt("contextTokens").coerceAtLeast(0), value.optInt("contextWindow", 32768).coerceIn(1, 1048576), value.optInt("compactions").coerceAtLeast(0), AgentContextPolicy.source(value.optString("contextSource")), value.optLong("contextUpdatedAt").coerceAtLeast(0)), value.optString("proposalRevision", "initial"), value.optString("engineId", "pi"))
    }.getOrNull()
    @Synchronized fun delete(workspaceId: String, id: String) {
        val directory = folder(workspaceId)
        val safe = safeId(id)
        File(directory, "$safe.json").delete(); File(directory, "$safe.meta.json").delete()
    }
}
