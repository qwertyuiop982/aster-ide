package dev.aster.ide.agent

import java.util.Locale

/** Options exposed by pinned transports, not a guarantee of provider support. */
object AgentThinkingPolicy {
    val modes = setOf("auto", "off", "budget", "strength", "legacy")
    fun supportsBudget(engine: AgentEngine, profile: AgentProfile): Boolean =
        engine == AgentEngine.OPENCLAUDE ||
        engine == AgentEngine.GEMINI && profile.protocol != "openai" && profile.model.contains("gemini-2.5", true) && available(engine, profile).size > 1
    fun supportsStrength(engine: AgentEngine, profile: AgentProfile): Boolean = when (engine) {
        AgentEngine.PI, AgentEngine.CODEX -> true
        AgentEngine.OPENCLAUDE -> true
        AgentEngine.GEMINI -> profile.protocol == "openai" || profile.model.contains("gemini-3", true) && available(engine, profile).size > 1
    }
    fun budgetRange(engine: AgentEngine, profile: AgentProfile): IntRange {
        val minimum = if (engine == AgentEngine.OPENCLAUDE) 1 else if (profile.model.contains("flash-lite", true)) 512 else 128
        val limit = if (engine == AgentEngine.OPENCLAUDE) 32000 else if (profile.model.contains("pro", true)) 32768 else 24576
        val outputLimit = if (engine == AgentEngine.OPENCLAUDE) profile.maxTokens - 1 else profile.maxTokens - 256
        return minimum..minOf(outputLimit, limit)
    }
    fun effectiveMode(engine: AgentEngine, profile: AgentProfile): String =
        if (profile.thinkingMode != "legacy") profile.thinkingMode
        else if (profile.thinkingLevel in setOf("auto", "off")) profile.thinkingLevel
        else if (supportsStrength(engine, profile)) "strength" else "budget"
    fun summary(profile: AgentProfile): String = when (profile.thinkingMode) {
        "budget" -> "预算 " + profile.thinkingBudget
        "strength" -> "强度 " + label(profile.thinkingLevel)
        "off" -> "关闭"
        "legacy" -> label(profile.thinkingLevel)
        else -> "自动"
    }
    val levels = setOf("auto", "off", "minimal", "low", "medium", "high", "xhigh")
    fun normalize(value: String) = value.takeIf { it in levels } ?: "auto"
    fun label(value: String) = when (value) {
        "off" -> "关闭"; "minimal" -> "极低"; "low" -> "低"; "medium" -> "中"; "high" -> "高"; "xhigh" -> "极高"; else -> "自动"
    }
    fun available(engine: AgentEngine, profile: AgentProfile): List<String> {
        val model = profile.model.lowercase(Locale.ROOT)
        if (engine == AgentEngine.GEMINI && profile.protocol == "openai") return listOf("auto", "off", "minimal", "low", "medium", "high", "xhigh")
        return when (engine) {
            AgentEngine.PI, AgentEngine.CODEX -> listOf("auto", "off", "minimal", "low", "medium", "high", "xhigh")
            AgentEngine.OPENCLAUDE -> listOf("auto", "off") + if (profile.maxTokens >= 2048 && !model.contains("claude-3-")) listOf("low", "medium", "high") else emptyList()
            AgentEngine.GEMINI -> when {
                Regex("gemini-3(?:[.][0-9]+)?-pro").containsMatchIn(model) -> listOf("auto", "low", "high")
                Regex("gemini-3(?:[.][0-9]+)?-flash").containsMatchIn(model) && !model.contains("flash-lite") -> listOf("auto", "minimal", "low", "medium", "high")
                Regex("(?:^|/)gemini-2[.]5-(?:pro|flash)(?:-|$)").containsMatchIn(model) -> listOf("auto") + (if (model.contains("pro")) emptyList() else listOf("off")) +
                    (if (profile.maxTokens >= (if (model.contains("flash-lite")) 768 else 384)) listOf("low", "medium", "high") else emptyList())
                else -> listOf("auto")
            }
        }
    }
    fun validate(engine: AgentEngine, profile: AgentProfile) {
        require(profile.thinkingMode in modes) { "未知思考模式" }
        when (profile.thinkingMode) {
            "auto" -> Unit
            "legacy" -> if (engine == AgentEngine.OPENCLAUDE) require(profile.thinkingLevel in listOf("auto", "off", "minimal", "low", "medium", "high", "xhigh")) else require(profile.thinkingLevel in available(engine, profile)) { "当前模型不支持此思考等级" }
            "off" -> if (engine != AgentEngine.OPENCLAUDE) require("off" in available(engine, profile)) { "当前模型不支持关闭思考" }
            "budget" -> {
                require(supportsBudget(engine, profile)) { "当前模型不支持固定思考预算" }
                if (engine != AgentEngine.OPENCLAUDE) require(profile.thinkingBudget in budgetRange(engine, profile)) { "思考预算超出模型或输出上限：" + budgetRange(engine, profile) }
                else require(profile.thinkingBudget in budgetRange(engine, profile)) { "思考预算必须在 SDK 输出限制内：" + budgetRange(engine, profile) }
            }
            "strength" -> {
                require(supportsStrength(engine, profile)) { "当前模型不支持思考强度" }
                val validLevels = if (engine == AgentEngine.OPENCLAUDE) listOf("minimal", "low", "medium", "high", "xhigh") else available(engine, profile)
                require(profile.thinkingLevel in validLevels) { "请选择有效的思考强度" }
            }
        }
    }
    fun budget(engine: AgentEngine, profile: AgentProfile, level: String): Int? {
        if (level !in listOf("low", "medium", "high")) return null
        if (engine != AgentEngine.OPENCLAUDE && !(engine == AgentEngine.GEMINI && profile.model.contains("gemini-2.5", true))) return null
        val minimum = if (engine == AgentEngine.OPENCLAUDE) 1024 else if (profile.model.contains("flash-lite", true)) 512 else 128
        val cap = minOf(profile.maxTokens - 256, if (engine == AgentEngine.OPENCLAUDE) 32000 else if (profile.model.contains("pro", true)) 32768 else 24576)
        return when (level) { "low" -> minimum; "medium" -> (minimum + cap) / 2; else -> cap }
    }
    fun detail(engine: AgentEngine, profile: AgentProfile, level: String): String = when (level) {
        "auto" -> "引擎默认"
        "off" -> "请求关闭思考"
        else -> budget(engine, profile, level)?.let { if (engine == AgentEngine.OPENCLAUDE) label(level) + "强度 · 固定预算模型：$it tokens" else "思考预算 $it tokens" } ?: (label(level) + "强度")
    }
    fun note(engine: AgentEngine, profile: AgentProfile): String = when {
        engine == AgentEngine.GEMINI && available(engine, profile) == listOf("auto") -> "未识别此模型的思考参数，使用引擎默认。"
        engine == AgentEngine.OPENCLAUDE -> "按所选模式直接发送预算或强度参数；服务端若拒绝会显示请求错误。"
        else -> "下一次任务使用 · 服务端模型支持范围可能更小。"
    }
}
