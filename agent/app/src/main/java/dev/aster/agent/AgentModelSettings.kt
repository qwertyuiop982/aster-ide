package dev.aster.agent

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aster.ide.agent.AgentContextPolicy
import dev.aster.ide.agent.AgentEngine
import dev.aster.ide.agent.AgentThinkingPolicy

@Composable internal fun ModelSettings(vm: AgentViewModel, engine: AgentEngine) {
    val accountSettings by vm.core.settings.flow.collectAsStateWithLifecycle()
    val permissionSettings by vm.settings.flow.collectAsStateWithLifecycle()
    if (engine == AgentEngine.PI && accountSettings.agentUseFree) {
        ModelSettingsPage(if (accountSettings.agentFreeTier == "go") "Aster-free go" else "Aster-free", { vm.showSettings = false }, { vm.showSettings = false }, "完成") {
            ModelSettingsSection("账号与服务", "免费服务的模型和额度由账号方案决定。") {
            dev.aster.ide.account.AccountSourceControls(engine)
                dev.aster.ide.agent.AgentPermissionControls(permissionSettings, !vm.locked,
                    { mode -> vm.settings.update { it.copy(agentPermissionMode = mode) } },
                    { unchecked -> vm.settings.update { it.copy(agentNativeCommandsUnchecked = unchecked) } }, vm::openNativeWorkspace)
                dev.aster.ide.agent.AgentToolConfigurationControls(permissionSettings, permissionSettings.agentProfile(engine), !vm.locked,
                    { commands -> vm.settings.update { it.copy(terminalAllowedCommands = commands) } },
                    { names, definitions -> vm.settings.updateAgentProfile(engine) { it.copy(mcpServers = names, mcpDefinitions = definitions) } })
                TextButton(onClick = vm::openEnvironmentSettings) { Text("环境安装设置 · 编译工具 / SDK / NDK") }
                Text("128000 token 上下文上限 · 每轮任务最多 64 次工具调用")
                Text(if (accountSettings.agentFreeTier == "go") "固定推理，只显示最终内容 · 1000 token = 50 积分（含推理输出）" else "无思考 · 算力正常 · 1000 token = 1 积分")
            }
        }
        return
    }
    val profile = vm.settings.value.agentProfile(engine)
    var subagentModels by remember(engine) { mutableStateOf(profile.subagentModels) }
    var enabledSkills by remember(engine) { mutableStateOf(profile.enabledSkills) }
    var reviewEnabled by remember(engine) { mutableStateOf(profile.reviewEnabled) }
    var reviewModel by remember(engine) { mutableStateOf(profile.reviewModel) }
    var protocol by remember(engine) { mutableStateOf(profile.protocol) }
    var endpoint by remember(engine) { mutableStateOf(profile.endpoint) }
    var model by remember(engine) { mutableStateOf(profile.model) }
    var key by remember(engine) { mutableStateOf("") }
    var context by remember(engine) { mutableStateOf(profile.contextWindow.toString()) }
    var tokens by remember(engine) { mutableStateOf(profile.maxTokens.toString()) }
    var thinking by remember(engine) { mutableStateOf(profile.thinkingLevel) }
    var thinkingMode by remember(engine) { mutableStateOf(AgentThinkingPolicy.effectiveMode(engine, profile)) }
    var thinkingBudget by remember(engine) { mutableStateOf((if (profile.thinkingMode == "legacy") AgentThinkingPolicy.budget(engine, profile, profile.thinkingLevel) ?: profile.thinkingBudget else profile.thinkingBudget).toString()) }
    var streaming by remember { mutableStateOf(vm.settings.value.agentStreaming) }
    var steps by remember { mutableStateOf(vm.settings.value.agentMaxSteps.toString()) }
    var validationError by remember { mutableStateOf<String?>(null) }
    val hasKey by vm.agent.secrets.hasKey.collectAsStateWithLifecycle()
    val outputConfigurable = AgentContextPolicy.outputConfigurable(engine)
    val minimum = AgentContextPolicy.minimum(engine)
    val save: () -> Unit = {
        runCatching {
            val uri = java.net.URI(endpoint.trim())
            require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.rawFragment == null && uri.rawQuery == null) { "请输入不含查询参数、账号和片段的完整 API 基础地址" }
            val window = context.trim().toIntOrNull() ?: error("上下文窗口必须是整数 tokens")
            val output = if (outputConfigurable) tokens.trim().toIntOrNull() ?: error("最大输出必须是整数 tokens") else profile.maxTokens
            AgentContextPolicy.validate(engine, window, output)
            val maxSteps = steps.trim().toIntOrNull() ?: error("任务上限必须是整数")
            require(maxSteps in 4..64) { "任务上限须为 4–64" }
            val nextProfile = profile.copy(subagentModels = subagentModels.trim(), mcpServers = vm.settings.value.agentProfile(engine).mcpServers, mcpDefinitions = vm.settings.value.agentProfile(engine).mcpDefinitions, enabledSkills = enabledSkills.trim(), reviewEnabled = reviewEnabled, reviewModel = reviewModel.trim(), protocol = protocol, endpoint = endpoint.trim(), model = model.trim(), contextWindow = window, maxTokens = output, thinkingLevel = thinking, thinkingMode = if (engine in setOf(AgentEngine.GEMINI, AgentEngine.OPENCLAUDE)) thinkingMode else if (thinking in setOf("auto", "off")) thinking else "strength", thinkingBudget = if (thinkingMode == "budget") thinkingBudget.toIntOrNull() ?: error("思考预算必须是整数") else thinkingBudget.toIntOrNull() ?: profile.thinkingBudget)
            AgentThinkingPolicy.validate(engine, nextProfile)
            vm.settings.updateAgentProfile(engine) { nextProfile }
            vm.settings.update { it.copy(agentStreaming = streaming, agentMaxSteps = maxSteps) }
            if (key.isNotBlank()) vm.agent.secrets.save(key.trim(), engine)
            key = ""; vm.showSettings = false
        }.onFailure { validationError = it.message ?: "配置无效" }
    }
    ModelSettingsPage("${engine.label} · 模型配置", { key = ""; vm.showSettings = false }, save, message = validationError) {
        ModelSettingsSection("账号与工具", "管理账号来源、运行权限和环境。") {
            dev.aster.ide.account.AccountSourceControls(engine)
                dev.aster.ide.agent.AgentPermissionControls(permissionSettings, !vm.locked,
                    { mode -> vm.settings.update { it.copy(agentPermissionMode = mode) } },
                    { unchecked -> vm.settings.update { it.copy(agentNativeCommandsUnchecked = unchecked) } }, vm::openNativeWorkspace)
                dev.aster.ide.agent.AgentToolConfigurationControls(permissionSettings, permissionSettings.agentProfile(engine), !vm.locked,
                    { commands -> vm.settings.update { it.copy(terminalAllowedCommands = commands) } },
                    { names, definitions -> vm.settings.updateAgentProfile(engine) { it.copy(mcpServers = names, mcpDefinitions = definitions) } })
            TextButton(onClick = vm::openEnvironmentSettings) { Text("环境安装设置 · 编译工具 / SDK / NDK") }
        }
        ModelSettingsSection("界面配置", "布局与配色单独管理。") {
            TextButton(onClick = { vm.showAppearance = true }) { Text("打开界面配置") }
        }
        ModelSettingsSection("连接模型", "地址、模型名称和密钥按引擎分别保存。") {
            Text("真实底层 ${engine.displayName} · ${engine.apiLabel}", style = MaterialTheme.typography.labelLarge)
            if (engine == AgentEngine.GEMINI) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("gemini" to "Gemini API", "openai" to "OpenAI API").forEach { (id, label) ->
                        FilterChip(selected = protocol == id, onClick = { protocol = id; if (id == "openai" && thinkingMode == "budget") thinkingMode = "auto"; validationError = null }, label = { Text(label) })
                    }
                }
                Text(if (protocol == "openai") "使用 Chat Completions 基础地址；文本与工具调用，回复整段显示。" else "使用 Gemini generateContent 基础地址。", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(endpoint, { endpoint = it }, label = { Text("API 地址") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(model, { model = it }, label = { Text("模型名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(key, { key = it }, label = { Text(if (hasKey) "API Key（留空保留）" else "API Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
        }
        ModelSettingsSection("上下文与输出", "根据模型实际能力设置任务预算。") {
            OutlinedTextField(context, { context = it; validationError = null }, label = { Text("自定义上下文窗口（tokens）") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                supportingText = { Text("$minimum–${AgentContextPolicy.MAX_WINDOW}；按引擎分别保存") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(32768 to "32K", 65536 to "64K", 131072 to "128K", 262144 to "256K", 1048576 to "1M").filter { it.first >= minimum }.forEach { (value, label) ->
                    FilterChip(selected = context == value.toString(), onClick = { context = value.toString(); validationError = null }, label = { Text(label) })
                }
            }
            Text(AgentContextPolicy.description(engine), style = MaterialTheme.typography.bodySmall)
            Text("这是客户端预算，不会扩大服务端模型能力。保存后从下一次任务生效；若引擎采用更小窗口，工作信息会显示实际报告。", style = MaterialTheme.typography.bodySmall)
            if (outputConfigurable) {
                OutlinedTextField(tokens, { tokens = it; validationError = null }, label = { Text("最大输出（tokens）") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                if (engine == AgentEngine.OPENCLAUDE) Text("本地 SDK 使用上游输出选项；模型自身上限仍可能更低。完整任务循环尚待验证。", style = MaterialTheme.typography.bodySmall)
            } else Text("Codex 最大输出仍由原生引擎/模型决定；未验证的输出上限不会伪装成已生效设置。上下文窗口可以独立修改。", style = MaterialTheme.typography.bodySmall)
        }
        ModelSettingsSection("协作与审阅", "配置子代理候选模型、审阅和项目 Skill。") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("完成修改后自动模型审阅")
                Switch(reviewEnabled, { reviewEnabled = it })
            }
            if (reviewEnabled) {
                OutlinedTextField(reviewModel, { reviewModel = it }, label = { Text("审阅模型（留空使用当前模型）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Text("使用当前引擎、API 地址和密钥；审阅仅可读取与检查修改。关闭此项不改变命令批准和修改应用流程。", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(subagentModels, { subagentModels = it }, modifier = Modifier.fillMaxWidth(), label = { Text("子代理候选模型（逗号或换行分隔）") }, supportingText = { Text("沿用此供应商和 Key，每个引擎最多 8 个候选；主模型可以选择模型、思考强度、system 和 Prompt。留空时使用当前模型。") })
            OutlinedTextField(enabledSkills, { enabledSkills = it }, modifier = Modifier.fillMaxWidth(), label = { Text("启用的项目 Skill（逗号分隔）") }, supportingText = { Text("读取 .aster/skills/名称/SKILL.md 或 .agents/skills/名称/SKILL.md；每个引擎分别保存。") })
        }
        ModelSettingsSection("思考", "滑动选择当前模型支持的思考等级。") {
            val dualThinking = engine in setOf(AgentEngine.GEMINI, AgentEngine.OPENCLAUDE)
            if (dualThinking) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("auto" to "自动", "budget" to "思考预算", "strength" to "思考强度", "off" to "关闭").filter { (id, _) -> val candidate = profile.copy(protocol = protocol, model = model.trim(), maxTokens = tokens.toIntOrNull() ?: profile.maxTokens); when (id) { "budget" -> AgentThinkingPolicy.supportsBudget(engine, candidate); "strength" -> AgentThinkingPolicy.supportsStrength(engine, candidate); "off" -> "off" in AgentThinkingPolicy.available(engine, candidate); else -> true } }.forEach { (id, label) ->
                        FilterChip(selected = thinkingMode == id, onClick = { thinkingMode = id; validationError = null }, label = { Text(label) })
                    }
                }
                if (thinkingMode == "budget") OutlinedTextField(thinkingBudget, { thinkingBudget = it; validationError = null }, label = { Text("思考预算（tokens）") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            val currentProfile = profile.copy(protocol = protocol, model = model.trim(), maxTokens = tokens.toIntOrNull() ?: profile.maxTokens)
            val levels = AgentThinkingPolicy.available(engine, currentProfile).filter { !dualThinking || thinkingMode == "strength" && it !in setOf("auto", "off") }
            if (levels.isNotEmpty()) {
                val selected = levels.indexOf(thinking).coerceAtLeast(0)
                Text(AgentThinkingPolicy.label(engine, levels[selected]), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Slider(value = selected.toFloat(), onValueChange = { thinking = levels[it.roundToInt().coerceIn(0, levels.lastIndex)]; validationError = null }, valueRange = 0f..levels.lastIndex.coerceAtLeast(1).toFloat(), steps = (levels.size - 2).coerceAtLeast(0), enabled = levels.size > 1, colors = if (engine == AgentEngine.CODEX && !permissionSettings.agentCodexNebulaAll) SliderDefaults.colors(thumbColor = if (thinking == "ultra") Color(0xFF9259E8) else Color(0xFF3976E8), activeTrackColor = if (thinking == "ultra") Color(0xFF9259E8) else Color(0xFF3976E8)) else if (engine == AgentEngine.CODEX && permissionSettings.agentCodexNebulaAll) SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.onSurface, activeTrackColor = MaterialTheme.colorScheme.outline, inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant) else SliderDefaults.colors())
                if (engine == AgentEngine.CODEX) levels.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        row.forEach { level ->
                            TextButton(onClick = { thinking = level; validationError = null }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)) {
                                Text(AgentThinkingPolicy.label(engine, level), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                    }
                } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(AgentThinkingPolicy.label(engine, levels.first())); Text(AgentThinkingPolicy.label(engine, levels.last())) }
            }
            Text("预算与强度互斥；只发送当前模式参数。模型不支持时会明确报错。", style = MaterialTheme.typography.bodySmall)
            Text(AgentThinkingPolicy.note(engine, currentProfile), style = MaterialTheme.typography.bodySmall)
        }
        ModelSettingsSection("任务运行", "新配置从下一次任务生效。") {
            OutlinedTextField(steps, { steps = it }, label = { Text(if (engine == AgentEngine.CODEX) "宿主工具步骤上限" else "任务轮次上限") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            if (engine == AgentEngine.PI) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Text("流式输出"); Switch(streaming, { streaming = it }) }
            else Text("此引擎使用原生事件流。", style = MaterialTheme.typography.bodySmall)
            if (model.isBlank()) Text("可以先保存预算；发送任务前仍需填写模型名称。", style = MaterialTheme.typography.bodySmall)
            validationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (hasKey) TextButton(onClick = { vm.agent.secrets.save("", engine) }) { Text("清除已保存的 Key") }
        }
    }
}

private data class SettingsSectionNavigation(val selected: String?, val open: (String?) -> Unit)
private val LocalSettingsSectionNavigation = staticCompositionLocalOf { SettingsSectionNavigation(null, {}) }

@Composable internal fun ModelSettingsPage(title: String, close: () -> Unit, save: () -> Unit, action: String = "保存", message: String? = null, content: @Composable ColumnScope.() -> Unit) {
    var selected by remember(title) { mutableStateOf<String?>(null) }
    val back = { if (selected != null) selected = null else close() }
    val sectionState = rememberSaveableStateHolder()
    AgentConfigurationPage(selected ?: title, back, actions = { TextButton(onClick = save) { Text(action) } }) {
        message?.let { Text(it, Modifier.fillMaxWidth().padding(20.dp, 12.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            CompositionLocalProvider(LocalSettingsSectionNavigation provides SettingsSectionNavigation(selected) { selected = it }) {
                sectionState.SaveableStateProvider(selected ?: "settings-index") {
                    key(selected) { Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
                }
            }
        }
    }
}

@Composable internal fun ModelSettingsSection(title: String, description: String, content: @Composable ColumnScope.() -> Unit) {
    val navigation = LocalSettingsSectionNavigation.current
    if (navigation.selected == null) {
        Column {
            Row(Modifier.fillMaxWidth().clickable { navigation.open(title) }.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.padding(start = 16.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
        }
    } else if (navigation.selected == title) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}
