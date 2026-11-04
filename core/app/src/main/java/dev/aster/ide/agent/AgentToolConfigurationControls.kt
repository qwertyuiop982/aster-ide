package dev.aster.ide.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.aster.ide.data.IdeSettings
import org.json.JSONObject

/** Explicit saves keep partially entered commands and JSON out of active tasks. */
@Composable fun AgentToolConfigurationControls(
    settings: IdeSettings,
    profile: AgentProfile,
    enabled: Boolean,
    onCommandsSave: (String) -> Unit,
    onMcpSave: (String, String) -> Unit
) {
    var commands by rememberSaveable(settings.terminalAllowedCommands) { mutableStateOf(settings.terminalAllowedCommands) }
    var servers by rememberSaveable(profile.mcpServers) { mutableStateOf(profile.mcpServers) }
    var definitions by rememberSaveable(profile.mcpDefinitions) { mutableStateOf(profile.mcpDefinitions) }
    var commandError by remember { mutableStateOf<String?>(null) }
    var mcpError by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(commands, { commands = it; commandError = null },
            modifier = Modifier.fillMaxWidth(), enabled = enabled, minLines = 3, maxLines = 6,
            label = { Text("终端允许指令（每行一条）") },
            supportingText = { Text("按命令参数前缀匹配，最多 100 条；匹配后免审批，管道与重定向等仍需确认。") },
            isError = commandError != null)
        commandError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        TextButton(enabled = enabled && commands != settings.terminalAllowedCommands, onClick = {
            runCatching {
                val rules = commands.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
                require(rules.size <= 100 && commands.length <= 16000) { "最多保存 100 条、16000 字符的终端规则" }
                onCommandsSave(rules.joinToString("\n"))
            }.onFailure { commandError = it.message ?: "终端规则保存失败" }
        }) { Text("保存终端允许指令") }
        OutlinedTextField(servers, { servers = it; mcpError = null },
            modifier = Modifier.fillMaxWidth(), enabled = enabled,
            label = { Text("启用的 MCP 服务器（逗号分隔）") })
        OutlinedTextField(definitions, { definitions = it; mcpError = null },
            modifier = Modifier.fillMaxWidth(), enabled = enabled, minLines = 4, maxLines = 10,
            label = { Text("MCP 配置 JSON") },
            supportingText = { Text("填写 mcpServers 对象，支持 stdio 和 HTTP；启用的定义在下次任务前合并写入项目 .aster/mcp.json。留空可沿用项目配置。凭据会随配置写入该文件。") },
            isError = mcpError != null)
        mcpError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        TextButton(enabled = enabled && (servers != profile.mcpServers || definitions != profile.mcpDefinitions), onClick = {
            runCatching {
                val selected = servers.split(',').map(String::trim).filter(String::isNotEmpty).distinct()
                require(selected.size <= 16 && selected.all { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }) { "最多启用 16 个 MCP；名称只允许字母、数字、下划线和连字符" }
                require(definitions.toByteArray(Charsets.UTF_8).size <= 32000) { "MCP 配置不能超过 32 KB" }
                val document = JSONObject(definitions.ifBlank { "{}" })
                require(!document.has("mcpServers") || document.opt("mcpServers") is JSONObject) { "mcpServers 必须是 JSON 对象" }
                val entries = document.optJSONObject("mcpServers") ?: document
                entries.keys().forEach { name ->
                    require(name.matches(Regex("[A-Za-z0-9_-]{1,64}")) && entries.opt(name) is JSONObject) { "MCP 条目必须使用有效名称和 JSON 对象" }
                }
                onMcpSave(selected.joinToString(","), JSONObject().put("mcpServers", entries).toString(2))
            }.onFailure { mcpError = it.message ?: "MCP 配置无效" }
        }) { Text("保存 MCP 配置") }
    }
}
