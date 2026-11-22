package dev.aster.ide.agent

import org.json.JSONObject
import java.util.UUID

private fun AgentToolTrace.render(status: String, result: String? = null, duration: Long? = null): String = buildString {
    append(name)
    if (label != name) append(" · ").append(label)
    append(" · ").append(status)
    if (duration != null) append(" · ").append(duration).append(" ms")
    if (arguments != null) append("\n参数\n").append(arguments)
    if (output.isNotEmpty()) append("\n输出\n").append(output)
    if (result != null) {
        append("\n结果\n")
        append(result.take((24000 - length).coerceAtLeast(0)))
    }
}.take(24000)

internal data class AgentToolEvent(
    val id: String, val kind: String, val name: String, val label: String, val text: String,
    val status: String, val duration: Long?
) {
    companion object {
        fun from(job: String, event: JSONObject, toolLabel: (String) -> String): AgentToolEvent {
            val callId = event.optString("callId").ifBlank { UUID.randomUUID().toString() }
            val name = event.optString("name").lineSequence().first().ifBlank { "工具" }.take(160)
            val turn = event.optString("turnId")
            return AgentToolEvent(
                "tool-$job:${turn.length}:$turn$callId", event.optString("kind"), name, toolLabel(name),
                event.optString("text"), event.optString("status"),
                event.optLong("durationMs").coerceAtLeast(0).takeIf { event.has("durationMs") }
            )
        }
    }

    val statusLabel: String
        get() = if (kind != "tool_result") "运行中" else when (status) {
            "failed", "declined" -> "失败"
            "cancelled", "interrupted" -> "已中断"
            else -> "完成"
        }

    fun applyTo(messages: List<AgentMessage>): List<AgentMessage> {
        val previous = messages.firstOrNull { it.id == id }
        // A duplicate start or late output must not restart a finished call.
        if (previous != null && previous.toolTrace == null) return messages
        val trace = previous?.toolTrace ?: AgentToolTrace(name, label)
        if (trace.interrupted && kind != "tool_result") return messages
        val next = when (kind) {
            "tool" -> {
                val started = trace.copy(name = name, label = label, arguments = text.take(8000))
                AgentMessage(id, "tool", started.render(statusLabel), started)
            }
            "tool_delta" -> {
                val updated = trace.copy(output = (trace.output + text.takeLast(8000)).takeLast(8000))
                AgentMessage(id, "tool", updated.render(statusLabel), updated)
            }
            "tool_result" -> AgentMessage(id, "tool", trace.render(statusLabel, text, duration))
            else -> return messages
        }
        return boundedAgentMessages(if (previous == null) messages + next else messages.map { if (it.id == id) next else it })
    }
}

internal fun interruptAgentTools(messages: List<AgentMessage>, allowLateResults: Boolean = false): List<AgentMessage> {
    if (messages.none { it.isRunningTool() }) return messages
    return messages.map { message ->
        if (!message.isRunningTool()) message else {
            val text = message.toolTrace?.render("已中断") ?: message.text.substringBefore('\n').let { heading ->
                heading.removeSuffix(" · 运行中") + " · 已中断" + message.text.substring(heading.length)
            }
            message.copy(text = text, toolTrace = message.toolTrace?.takeIf { allowLateResults }?.copy(interrupted = true))
        }
    }
}

private fun AgentMessage.isRunningTool(): Boolean =
    role == "tool" && id.startsWith("tool-") && (toolTrace != null || text.substringBefore('\n').endsWith(" · 运行中"))
