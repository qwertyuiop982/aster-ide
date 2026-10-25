package dev.aster.ide.agent

enum class AgentEngine(val id: String, val label: String, val version: String, val endpoint: String, val apiLabel: String) {
    PI("pi", "Pi", "1.3.0", "https://api.openai.com/v1", "OpenAI Chat Completions"),
    GEMINI("gemini", "Gemini CLI", "0.64.0", "https://generativelanguage.googleapis.com", "Gemini API"),
    CODEX("codex", "Codex", "0.163.0", "https://api.openai.com/v1", "OpenAI Responses"),
    OPENCLAUDE("openclaude", "OpenClaude", "0.33.0", "https://api.anthropic.com", "Anthropic Messages");

    val displayName get() = if (this == PI) "Pi Coding Agent $version" else "$label $version"
    internal val secretSlot get() = if (this == PI) "encrypted-key" else "encrypted-key-$id"
    companion object {
        fun fromId(value: String) = entries.firstOrNull { it.id == value } ?: PI
    }
}

data class AgentProfile(val endpoint: String, val model: String = "", val contextWindow: Int = 32768, val maxTokens: Int = 4096, val thinkingLevel: String = "auto", val thinkingMode: String = "legacy", val thinkingBudget: Int = 1024, val protocol: String = "gemini", val reviewEnabled: Boolean = false, val reviewModel: String = "", val enabledSkills: String = "", val mcpServers: String = "")

data class AgentEngineAvailability(val id: String, val available: Boolean, val reason: String, val version: String)
