package dev.aster.ide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import dev.aster.ide.agent.AgentPermissionControls
import dev.aster.ide.agent.AgentMode
import dev.aster.ide.agent.AgentEngine
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aster.ide.IdeViewModel
import dev.aster.ide.data.IdeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun AgentSettings(vm: IdeViewModel, settings: IdeSettings) {
    val scope = rememberCoroutineScope()
    val engine by vm.agent.selectedEngine.collectAsStateWithLifecycle()
    val availability by vm.agent.availableEngines.collectAsStateWithLifecycle()
    val profile = settings.agentProfile(engine)
    val mode by vm.agent.mode.collectAsStateWithLifecycle()
    val state by vm.agent.state.collectAsStateWithLifecycle()
    val hasKey by vm.agent.secrets.hasKey.collectAsStateWithLifecycle()
    var endpoint by remember(engine, profile.endpoint) { mutableStateOf(profile.endpoint) }
    var model by remember(engine, profile.model) { mutableStateOf(profile.model) }
    var secret by remember(engine) { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("底层引擎")
        AgentEngine.entries.forEach { candidate ->
            Row(Modifier.fillMaxWidth().clickable(enabled = !state.running && !vm.agentPreparing && !vm.agentStopping) { vm.selectAgentEngine(candidate) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = candidate == engine, onClick = null, modifier = Modifier.padding(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(candidate.label, style = MaterialTheme.typography.bodyMedium)
                    Text(candidate.apiLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        HorizontalDivider()
        SectionLabel("账号与工具")
        dev.aster.ide.account.AccountSourceControls(engine)
        AgentPermissionControls(settings, !state.running && !vm.agentPreparing && !vm.agentStopping,
            { mode -> vm.settings.update { it.copy(agentPermissionMode = mode) } },
            { unchecked -> vm.settings.update { it.copy(agentNativeCommandsUnchecked = unchecked) } }, vm::openNativeProject)
        dev.aster.ide.agent.AgentToolConfigurationControls(settings, profile, !state.running && !vm.agentPreparing && !vm.agentStopping,
            { commands -> vm.settings.update { it.copy(terminalAllowedCommands = commands) } },
            { names, definitions -> vm.settings.updateAgentProfile(engine) { it.copy(mcpServers = names, mcpDefinitions = definitions) } })
        if (engine == AgentEngine.PI && settings.agentUseFree) {
            Text(if (settings.agentFreeTier == "go") "Aster-free go · 固定无思考" else "Aster-free · 固定无思考", style = MaterialTheme.typography.titleMedium)
            Text(if (settings.agentFreeTier == "go") "128000 token · 1000 token = 50 积分" else "128000 token · 1000 token = 1 积分", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AgentMode.entries.forEach { candidate -> FilterChip(mode == candidate, { vm.setAgentMode(candidate) }, enabled = !state.running, label = { Text(candidate.label) }) }
            }
            TextButton(onClick = { vm.openAgent(); vm.settingsPage = null }) { Text("打开 Agent 工作空间") }
            return@Column
        }
        SectionLabel("模型连接")
        Text(engine.apiLabel, style = MaterialTheme.typography.labelMedium)
        availability.firstOrNull { it.id == engine.id }?.let { runtime ->
            if (!runtime.available || runtime.reason.isNotBlank()) Text(runtime.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Text("独立的 Agent 工作空间，按项目保存完整工具会话并自动压缩上下文。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(endpoint, { endpoint = it }, label = { Text(engine.apiLabel + " API 地址") }, singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
            trailingIcon = { ToolButton(Icons.Outlined.Save, "保存 Agent API 地址", enabled = endpoint != profile.endpoint, size = 30) {
                val value = endpoint.trim().trimEnd('/')
                if ((value.startsWith("https://") || value.startsWith("http://")) && value.none(Char::isWhitespace)) vm.settings.updateAgentProfile(engine) { it.copy(endpoint = value) } else vm.notify("请输入有效的 HTTP 或 HTTPS API 地址")
            } })
        OutlinedTextField(model, { model = it }, label = { Text("模型名称") }, singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
            trailingIcon = { ToolButton(Icons.Outlined.Save, "保存 Agent 模型", enabled = model != profile.model, size = 30) { vm.settings.updateAgentProfile(engine) { it.copy(model = model.trim()) } } })
        OutlinedTextField(secret, { secret = it }, label = { Text(if (hasKey) "API Key · 已加密保存" else "API Key · 可选") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
            trailingIcon = { ToolButton(Icons.Outlined.Save, "加密保存 API Key", enabled = secret.isNotBlank() && !saving, size = 30) {
                scope.launch {
                    saving = true
                    try { withContext(Dispatchers.IO) { vm.agent.secrets.save(secret.trim(), engine) }; secret = ""; vm.notify("API Key 已加密保存") }
                    catch (_: Exception) { vm.notify("API Key 保存失败，请重试") }
                    finally { saving = false }
                }
            } })
        if (hasKey) TextButton(onClick = { scope.launch { try { withContext(Dispatchers.IO) { vm.agent.secrets.save("", engine) }; vm.notify("API Key 已删除") } catch (_: Exception) { vm.notify("API Key 删除失败") } } }) { Text("删除已保存的 API Key") }
        Text(if (settings.agentPermissionMode == "native") "原生权限：文件直接修改；命令按“命令无审查”开关执行。" else "Agent 使用你配置的模型服务；文件修改先预览后应用，验证命令逐次确认。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionLabel("任务运行")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AgentMode.entries.forEach { candidate -> FilterChip(mode == candidate, { vm.setAgentMode(candidate) }, enabled = !state.running && !vm.agentPreparing && !vm.agentStopping, label = { Text(candidate.label) }) }
        }
        Text("问答用于解释代码；规划会生成任务清单；执行会提出文件修改并请求验证。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("流式回复", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(settings.agentStreaming, { value -> vm.settings.update { it.copy(agentStreaming = value) } })
        }
        Text("会话按项目自动保存，保留最近 30 个。任务清单与待预览修改可在重启后恢复；运行审批需要重新确认。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { if (engine == AgentEngine.CODEX) vm.refreshAgentEngines() else vm.installServer("agent") }, enabled = vm.connected && !vm.buildRunning && !state.running && !vm.agentPreparing && !vm.agentStopping) { Text(if (engine == AgentEngine.CODEX) "检查原生 Codex 运行环境" else "安装／更新 Node.js 运行环境") }
        if (engine != AgentEngine.CODEX && vm.tools["node"].isNullOrBlank()) Text("首次发送任务时会自动安装内置 Node LTS。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("工具调用与模型轮次不限次数。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionLabel("协作与预算")
        var subagentModels by remember(engine, profile.subagentModels) { mutableStateOf(profile.subagentModels) }
        OutlinedTextField(subagentModels, { subagentModels = it }, label = { Text("子代理候选模型（逗号分隔）") }, modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("沿用当前供应商与 Key；主模型可自行选择模型、思考强度、system 提示词和 Prompt。") },
            trailingIcon = { ToolButton(Icons.Outlined.Save, "保存子代理候选模型", enabled = subagentModels != profile.subagentModels, size = 30) { vm.settings.updateAgentProfile(engine) { it.copy(subagentModels = subagentModels.trim()) } } })
        SettingsSlider("回复上限", profile.maxTokens, 512, 8192, 512, "tokens") { value -> vm.settings.updateAgentProfile(engine) { it.copy(maxTokens = value) } }
        SettingsSlider("模型上下文窗口", profile.contextWindow / 1024, 8, 256, 8, "K tokens") { value -> vm.settings.updateAgentProfile(engine) { it.copy(contextWindow = value * 1024) } }
        Text("请按模型实际支持的窗口设置；接近上限时生成摘要并保留近期工具交换。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { vm.openAgent(); vm.settingsPage = null }) { Text("打开 Agent 工作空间") }
    }
}