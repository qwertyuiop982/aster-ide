/* ACS preference presentation adapted from Android Code Studio, GPL-3.0-or-later. */
package dev.aster.ide.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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

@Composable internal fun AcsLspOptions(vm: IdeViewModel, settings: IdeSettings) {
    val active = vm.activePath?.let(vm.catalog::forPath)
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.DataObject, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("语言服务", style = MaterialTheme.typography.titleMedium)
                    Text(active?.name ?: "打开文件后连接对应语言服务器", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusLabel(if (!vm.connected) "本地编辑" else "${vm.lsp.count} 个运行中")
            }
            Spacer(Modifier.height(12.dp))
            Text("本地补全无需安装服务器。类型分析、跳转和诊断由所选语言服务器提供。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Spacer(Modifier.height(12.dp))
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            LspSwitch("启用语言服务", "按文件语言启动现有服务器", settings.lspEnabled) { vm.settings.update { s -> s.copy(lspEnabled = it) }; vm.restartLsp() }
            LspSwitch("代码诊断", "显示错误、警告和编辑器标记", settings.diagnostics) { vm.settings.update { s -> s.copy(diagnostics = it) } }
            SettingsSlider("文档同步延迟", settings.lspDebounce, 100, 1000, 50, "ms") { vm.settings.update { s -> s.copy(lspDebounce = it) } }
            SettingsSlider("请求超时", settings.requestTimeout, 2, 30, 1, "s") { vm.settings.update { s -> s.copy(requestTimeout = it) } }
            TextButton(onClick = { vm.restartLsp() }, enabled = vm.connected) { Icon(Icons.Outlined.RestartAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("重启语言服务器") }
        }
    }
    SectionLabel("语言服务器 · ${vm.catalog.all.size}")
}

@Composable internal fun AcsServerSettings(vm: IdeViewModel, settings: IdeSettings, server: ServerSpec, onInstall: () -> Unit) {
    var expanded by remember(server.id) { mutableStateOf(false) }
    val pref = settings.servers[server.id] ?: ServerPreference()
    val status = if (!vm.connected) "未检测" else vm.serverStates[server.id] ?: if (vm.installed[server.id] == true) "已安装" else "未安装"
    val motion = LocalMotion.current
    Card(Modifier.fillMaxWidth().padding(vertical = 5.dp), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(9.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)) {
                    Text((server.languageIds.firstOrNull() ?: server.id).take(2).uppercase(), Modifier.padding(horizontal = 10.dp, vertical = 9.dp), style = MaterialTheme.typography.labelLarge, fontFamily = Mono)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).clickable { expanded = !expanded }) {
                    Text(server.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    StatusLabel(status, if (status in listOf("已安装", "就绪")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(pref.enabled, { enabled -> vm.settings.server(server.id) { it.copy(enabled = enabled) }; vm.restartLsp() })
            }
            Text(server.extensions.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onInstall, enabled = vm.connected && !vm.buildRunning) { Icon(Icons.Outlined.Download, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("安装 ${server.name}") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { expanded = !expanded }) { Text("配置"); Spacer(Modifier.width(4.dp)); Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(17.dp)) }
            }
            AnimatedVisibility(expanded, enter = fadeIn(tween(motion.quick)) + expandVertically(tween(motion.resize)), exit = fadeOut(tween(motion.quick)) + shrinkVertically(tween(motion.resize))) {
                Column {
                    FieldSetting("启动命令", pref.command.ifBlank { server.command.joinToString(" ") }, multiline = true) { command -> vm.settings.server(server.id) { it.copy(command = command) }; vm.restartLsp() }
                    FieldSetting("初始化参数 JSON", pref.initializationOptions, multiline = true) { value ->
                        runCatching { JSONObject(value) }.onSuccess { vm.settings.server(server.id) { it.copy(initializationOptions = value) }; vm.restartLsp() }.onFailure { vm.notify("JSON 格式错误：${it.message}") }
                    }
                    val context = LocalContext.current
                    TextButton(onClick = { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(server.source))) }) { Icon(Icons.Outlined.OpenInNew, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("上游项目") }
                }
            }
        }
    }
}

@Composable private fun LspSwitch(title: String, summary: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { change(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, change)
    }
}