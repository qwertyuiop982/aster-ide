package dev.aster.agent

import org.json.JSONObject
import dev.aster.ide.agent.AgentThinkingPolicy

/** The web view can request only these UI actions; approvals and keys never cross it. */
object AgentWebProtocol {
    private val commands = setOf("send", "compact", "stop", "new", "history", "engine", "mode", "prompt", "settings", "workspaces", "review", "connect", "draft", "work_mode", "context_files", "copy", "delete", "panel", "diagnostics", "thinking", "browser", "device_permissions")
    fun validate(request: JSONObject, workspace: String, session: String, engine: String) {
        require(request.optInt("version") == 1) { "不支持的界面协议" }
        require(request.optString("command") in commands) { "未知界面命令" }
        require(workspace.isNotBlank() && session.isNotBlank()) { "会话尚未就绪" }
        require(request.optString("workspace") == workspace && request.optString("session") == session && request.optString("engine") == engine) { "界面已过期，请重试" }
        require(request.keys().asSequence().all { it in setOf("version", "command", "workspace", "session", "engine", "text", "id", "token") }) { "不支持的参数" }
        require(request.optString("text").length <= (if (request.optString("command") == "copy") 64000 else 12000) && request.optString("id").length <= 128) { "请求超出大小限制" }
        when (request.getString("command")) {
            "history", "delete" -> require(request.getString("id").matches(Regex("[A-Za-z0-9-]{1,128}")))
            "engine" -> require(request.getString("id") in setOf("pi", "codex", "gemini", "openclaude"))
            "mode" -> require(request.getString("id") in setOf("ask", "plan", "execute"))
            "thinking" -> require(request.getString("id") in AgentThinkingPolicy.levels)
            "panel" -> require(request.optString("id") in setOf("", "open"))
            "send", "prompt", "draft", "copy" -> require(request.get("text") is String)
        }
    }
}
