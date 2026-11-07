package dev.aster.ide.agent

import java.util.UUID

enum class AgentMode(val id: String, val label: String, val description: String) {
    ASK("ask", "问答", "读取项目并解释代码"),
    PLAN("plan", "规划", "分析项目并制定任务清单"),
    EXECUTE("execute", "执行", "提出修改并请求运行验证");
    companion object { fun fromId(id: String) = entries.firstOrNull { it.id == id } ?: EXECUTE }
}

data class AgentMessage(val id: String = UUID.randomUUID().toString(), val role: String, val text: String)
data class AgentSubagentConversation(val id: String, val label: String, val engineId: String = "", val model: String = "", val status: String = "等待中", val running: Boolean = true, val messages: List<AgentMessage> = emptyList())

data class AgentChange(val path: String, val before: String, val after: String, val sha256: String, val beforeExists: Boolean = true, val deleted: Boolean = false, val diskSha256: String? = null)
data class AgentApproval(val requestId: String, val command: String, val timeout: Int)
data class AgentTask(val task: String, val status: String)
data class AgentContextState(val tokens: Int = 0, val window: Int = 32768, val compactions: Int = 0,
    val source: String = "unknown", val updatedAt: Long = 0)
data class AgentState(val running: Boolean = false, val status: String = "准备就绪", val step: Int = 0, val tokens: Int = 0, val approval: AgentApproval? = null, val userInput: AgentUserInput? = null)
data class AgentConversationInfo(val id: String, val title: String, val updated: Long, val mode: AgentMode, val changes: Int, val engineId: String = "pi")
data class AgentSnapshot(
    val id: String, val workspaceId: String, val title: String, val updated: Long,
    val mode: AgentMode = AgentMode.EXECUTE, val messages: List<AgentMessage> = emptyList(),
    val tasks: List<AgentTask> = emptyList(), val changes: List<AgentChange> = emptyList(),
    val status: String = "准备就绪", val step: Int = 0, val tokens: Int = 0, val wasRunning: Boolean = false,
    val runtimeSessionId: String = id, val context: AgentContextState = AgentContextState(), val proposalRevision: String = "initial", val engineId: String = "pi",
    val subagentConversations: List<AgentSubagentConversation> = emptyList()
)
