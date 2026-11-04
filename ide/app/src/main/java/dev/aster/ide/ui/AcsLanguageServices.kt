/* ACS preference presentation adapted from Android Code Studio, GPL-3.0-or-later. */
package dev.aster.ide.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aster.ide.IdeViewModel
import dev.aster.ide.data.IdeSettings
import dev.aster.ide.data.ServerPreference
import dev.aster.ide.data.ServerSpec
import org.json.JSONObject

/** Settings only: the ACS workbench and its services are intentionally unchanged. */
@Composable internal fun AcsLspOptions(vm: IdeViewModel, settings: IdeSettings) {
    val active = vm.activePath?.let(vm.catalog::forPath)
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(active?.name ?: "语言服务", style = MaterialTheme.typography.titleMedium)
        Text(if (!vm.connected) "本地编辑" else "${vm.lsp.count} 个运行中", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("本地补全无需安装服务器。类型分析、跳转和诊断由所选语言服务器提供。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
    LspSwitch("启用语言服务", "按文件语言启动现有服务器", settings.lspEnabled) { vm.settings.update { s -> s.copy(lspEnabled = it) }; vm.restartLsp() }
    LspSwitch("代码诊断", "显示错误、警告和编辑器标记", settings.diagnostics) { vm.settings.update { s -> s.copy(diagnostics = it) } }
    SettingsSlider("文档同步延迟", settings.lspDebounce, 100, 1000, 50, "ms") { vm.settings.update { s -> s.copy(lspDebounce = it) } }
    SettingsSlider("请求超时", settings.requestTimeout, 2, 30, 1, "s") { vm.settings.update { s -> s.copy(requestTimeout = it) } }
    TextButton(onClick = { vm.restartLsp() }, enabled = vm.connected) { Text("重启语言服务器") }
    SectionLabel("语言服务器 · ${vm.catalog.all.size}")
}

@Composable internal fun AcsServerSettings(vm: IdeViewModel, settings: IdeSettings, server: ServerSpec, onConfigure: () -> Unit) {
    val pref = settings.servers[server.id] ?: ServerPreference()
    val status = if (!vm.connected) "未检测" else vm.serverStates[server.id] ?: if (vm.installed[server.id] == true) "已安装" else "未安装"
    Row(Modifier.fillMaxWidth().clickable(onClick = onConfigure).padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(server.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("$status · ${server.extensions.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(if (pref.enabled) "开启" else "关闭", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.Outlined.ChevronRight, null, Modifier.padding(start = 12.dp).size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}

@Composable internal fun AcsServerConfiguration(vm: IdeViewModel, settings: IdeSettings, server: ServerSpec, onInstall: () -> Unit) {
    val pref = settings.servers[server.id] ?: ServerPreference()
    val context = LocalContext.current
    Text(server.extensions.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    LspSwitch("启用 ${server.name}", "按文件语言自动连接", pref.enabled) { enabled -> vm.settings.server(server.id) { it.copy(enabled = enabled) }; vm.restartLsp() }
    FieldSetting("启动命令", pref.command.ifBlank { server.command.joinToString(" ") }, multiline = true) { command -> vm.settings.server(server.id) { it.copy(command = command) }; vm.restartLsp() }
    FieldSetting("初始化参数 JSON", pref.initializationOptions, multiline = true) { value ->
        runCatching { JSONObject(value) }.onSuccess { vm.settings.server(server.id) { it.copy(initializationOptions = value) }; vm.restartLsp() }.onFailure { vm.notify("JSON 格式错误：${it.message}") }
    }
    TextButton(onClick = onInstall, enabled = vm.connected && !vm.buildRunning) { Text("安装 ${server.name}") }
    TextButton(onClick = { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(server.source))) }) { Text("上游项目") }
}

@Composable private fun LspSwitch(title: String, summary: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { change(!checked) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, change)
    }
    HorizontalDivider()
}
