package dev.aster.agent

import org.json.JSONObject
import dev.aster.ide.agent.AgentThinkingPolicy

/** The web view can request only these UI actions; approvals and keys never cross it. */
object AgentWebProtocol {
    private val commands = setOf("send", "compact", "stop", "new", "history", "engine", "model", "mode", "prompt", "settings", "appearance", "environment", "workspaces", "review", "connect", "draft", "work_mode", "context_files", "pick_images", "pick_documents", "remove_attachment", "bubble_hold", "bubble_position", "copy", "delete", "panel", "diagnostics", "thinking", "thinking_budget", "collaboration", "long_task", "long_task_info", "browser", "device_permissions")
    fun validate(request: JSONObject, workspace: String, session: String, engine: String, thinkingMode: String = "auto") {
        require(request.optInt("version") == 1) { "不支持的界面协议" }
        require(request.optString("command") in commands) { "未知界面命令" }
        require(workspace.isNotBlank() && session.isNotBlank()) { "会话尚未就绪" }
        require(request.optString("workspace") == workspace && request.optString("session") == session && request.optString("engine") == engine) { "界面已过期，请重试" }
        val allowed = setOf("version", "command", "workspace", "session", "engine", "text", "id", "token") +
            when (request.getString("command")) {
                "thinking_budget" -> setOf("value")
                "bubble_position" -> setOf("x", "y")
                else -> emptySet()
            }
        require(request.keys().asSequence().all { it in allowed }) { "不支持的参数" }
        require(request.optString("text").length <= (if (request.optString("command") == "copy") 64000 else 12000) && request.optString("id").length <= 128) { "请求超出大小限制" }
        when (request.getString("command")) {
            "history", "delete", "remove_attachment" -> require(request.getString("id").matches(Regex("[A-Za-z0-9-]{1,128}")))
            "bubble_position" -> require(listOf("x", "y").all { key -> ((request.get(key) as? Number)?.toDouble() ?: Double.NaN) in 0.0..1.0 })
            "engine" -> require(request.getString("id") in setOf("pi", "codex", "gemini", "openclaude"))
            "model" -> require(request.get("text") is String && request.getString("text").isNotBlank() && request.getString("text").length <= 512)
            "mode" -> require(request.getString("id") in setOf("ask", "plan", "execute"))
            "thinking_budget" -> { val value = request.get("value"); require(value is Number && value.toDouble() == value.toInt().toDouble() && value.toInt() in 1..32768) }
            "thinking" -> {
                val level = request.get("id")
                require(level is String) { "思考等级必须是文本" }
                require(level in AgentThinkingPolicy.levels ||
                    engine == "gemini" && thinkingMode == "manual" && AgentThinkingPolicy.isManualLevel(level)) { "无效的思考等级" }
            }
            "appearance" -> Unit // Layout selection is shared; nebula colors remain Codex-only.
            "collaboration" -> require(request.getString("id") in setOf("extreme", "team", "assist", "off"))
            "long_task" -> require(request.getString("id") in setOf("on", "off"))
            "panel" -> require(request.optString("id") in setOf("", "open"))
            "send", "prompt", "draft", "copy" -> require(request.get("text") is String)
        }
    }
}
