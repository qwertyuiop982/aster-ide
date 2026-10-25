package dev.aster.ide.agent

/** Client-side budgets, not a promise about a remote provider's model capacity. */
object AgentContextPolicy {
    const val MAX_WINDOW = 1048576
    fun minimum(engine: AgentEngine) = if (engine == AgentEngine.OPENCLAUDE) 33000 else 8192
    fun defaultWindow(engine: AgentEngine) = if (engine == AgentEngine.OPENCLAUDE) 65536 else 32768
    fun outputConfigurable(engine: AgentEngine) = engine != AgentEngine.CODEX
    fun validate(engine: AgentEngine, window: Int, output: Int) {
        require(window in minimum(engine)..MAX_WINDOW) { "上下文窗口须为 ${minimum(engine)}–$MAX_WINDOW tokens" }
        if (outputConfigurable(engine)) {
            require(output in 64..65536) { "最大输出须为 64–65536 tokens" }
            require(output <= window - 1024) { "请至少预留 1024 tokens 给输入；最大输出不能超过 ${window - 1024}" }
        }
    }
    fun source(value: String) = value.takeIf { it in setOf("estimate", "hybrid", "engine") } ?: "unknown"
    fun description(engine: AgentEngine) = when (engine) {
        AgentEngine.PI -> "传入真实 Pi 的模型窗口和原生压缩。用量来自模型 usage 加尾部估算，并非精确账单。"
        AgentEngine.GEMINI -> "传入真实 Gemini CLI 的窗口预算；有效窗口取此值与引擎模型上限中的较小值。用量为历史内容估算。"
        AgentEngine.CODEX -> "传入真实 Codex 的 model_context_window；自动压缩阈值也随之调整。原生引擎可能预留额外空间，以其报告的有效窗口为准。"
        AgentEngine.OPENCLAUDE -> "仅供本地研究 SDK：使用上游公开的会话窗口设置，最低 33000 tokens；SDK 未安装时不能运行，不会回退为其他引擎。"
    }
}
