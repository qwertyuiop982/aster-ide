package dev.aster.ide.runtime

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.aster.ide.data.IdeSettings

/** Shared native settings: both work modes configure the same installed toolchain. */
@Composable fun BuildEnvironmentSettings(
    settings: IdeSettings, runtime: RuntimeState, connected: Boolean, connecting: Boolean,
    installing: Boolean, directory: String, tools: Map<String, String>,
    updateSettings: ((IdeSettings) -> IdeSettings) -> Unit,
    onConnect: (Boolean) -> Unit, onInstall: (String) -> Unit, onLogs: () -> Unit,
    onCopyDirectory: () -> Unit, onMessage: (String) -> Unit, port: Int,
    showAutoRuntime: Boolean = true
) {
    var showInventory by remember { mutableStateOf(false) }
    val canInstall = !connecting && !installing
    Column(Modifier.fillMaxWidth()) {
        SectionLabel("Termux")
        ValueRow("运行方式", "原生 · 应用私有路径")
        ValueRow("Bootstrap", if (runtime.bootstrapInstalled) "已解包" else "已内置 · 未检测")
        ValueRow("桥接状态", if (connected && !connecting) "已连接" else runtime.message)
        ValueRow("本地桥接端口", port.takeIf { it in 1..65535 }?.toString() ?: "系统分配")
        if (connecting || installing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
        if (showAutoRuntime) SettingSwitch("IDE 启动时准备编译环境", settings.autoRuntime) { updateSettings { s -> s.copy(autoRuntime = it) } }
        ActionRow(Icons.Outlined.Description, "查看环境安装日志") { onLogs() }
        ActionRow(Icons.Outlined.PowerSettingsNew, if (connected) "连接内置编译环境" else "初始化并启动内置环境", enabled = !connecting) { onConnect(true) }
        ActionRow(Icons.Outlined.ContentCopy, "复制私有运行目录") { onCopyDirectory() }
        ActionRow(Icons.Outlined.Refresh, "检测本地连接", enabled = !connecting) { onConnect(false) }
        SectionLabel("工具链")
        ActionRow(Icons.Outlined.InstallMobile, "基础编译工具", enabled = canInstall) { onInstall("base") }
        Line()
        BuildEnvironmentPresets.entries.forEach { preset ->
            val count = preset.executables.count { !tools[it].isNullOrBlank() }
            val status = when {
                tools.isEmpty() -> "未检查"
                count == preset.executables.size -> "已安装"
                count == 0 -> "未安装"
                else -> "已安装 $count / ${preset.executables.size}"
            }
            ActionRow(Icons.Outlined.Build, preset.title, enabled = canInstall, detail = status) { onInstall(preset.id) }
            Line()
        }
        if (tools.isNotEmpty()) {
            ActionRow(if (showInventory) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                if (showInventory) "收起已检测的命令" else "已检测的命令") { showInventory = !showInventory }
            if (showInventory) tools.toSortedMap().forEach { (tool, path) ->
                ValueRow(tool, if (path.isBlank()) "未安装" else "已安装")
            }
        }
        SectionLabel("Android 工具链")
        ValueRow("SDK / NDK 运行平台", "Android · Bionic · arm64")
        ChoiceRow("Build Tools", settings.sdkBuildTools, listOf("35.0.1" to "35.0.1", "34.0.4" to "34.0.4")) { value -> updateSettings { it.copy(sdkBuildTools = value) } }
        ChoiceRow("JDK", settings.sdkJdk, listOf("17" to "17 · Android 开发推荐", "21" to "21")) { value -> updateSettings { it.copy(sdkJdk = value) } }
        ChoiceRow("Android 原生 NDK", settings.sdkNdk, listOf("29.0.14206865" to "r29", "28.2.13676358" to "r28c", "27.3.13750724" to "r27d", "0" to "跳过")) { value -> updateSettings { it.copy(sdkNdk = value) } }
        SettingSwitch("安装命令行工具", settings.sdkCmdlineTools) { value -> updateSettings { it.copy(sdkCmdlineTools = value) } }
        ActionRow(Icons.Outlined.Android, "安装 Android 原生 SDK / NDK", enabled = canInstall) { onInstall("android") }
        FieldSetting("Termux 中的 Android SDK 路径", settings.sdkPath) { value -> if ('\n' in value || !value.startsWith('/')) onMessage("需要绝对路径") else updateSettings { it.copy(sdkPath = value) } }
        SectionLabel("构建资源")
        ChoiceRow("最大堆内存", settings.buildMemory.toString(), listOf(256,512,768,1024,1536,2048,3072,4096).map { it.toString() to "$it MiB" }) { value -> updateSettings { it.copy(buildMemory = value.toInt()) } }
        ChoiceRow("并行 worker", settings.buildWorkers.toString(), (1..8).map { it.toString() to it.toString() }) { value -> updateSettings { it.copy(buildWorkers = value.toInt()) } }
        SectionLabel("软件源")
        ValueRow("Termux APT", "使用现有源")
        FieldSetting("npm registry", settings.npmRegistry) { value -> if (value.startsWith("https://") && value.none(Char::isWhitespace)) updateSettings { it.copy(npmRegistry = value) } else onMessage("请输入 HTTPS registry 地址") }
        SettingSwitch("GitHub 文件下载加速", settings.githubProxy) { updateSettings { s -> s.copy(githubProxy = it) } }
        ValueRow("代理", "gh-proxy.org")
        SectionLabel("目录")
        Text("$directory/files", Modifier.fillMaxWidth().padding(bottom = 12.dp), fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun SectionLabel(text: String) { Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 24.dp, bottom = 10.dp)) }
@Composable private fun Line() { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }

@Composable private fun SettingSwitch(label: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { change(!value) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.bodyLarge)
        Switch(value, onCheckedChange = change)
    }
    Line()
}
@Composable private fun ValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
    Line()
}
@Composable private fun ChoiceRow(label: String, value: String, options: List<Pair<String, String>>, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(options.firstOrNull { it.first == value }?.second ?: value, Modifier.widthIn(max = 180.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.padding(start = 8.dp).size(18.dp))
        }
        if (expanded) options.forEach { (id, name) ->
            Row(Modifier.fillMaxWidth().clickable { change(id); expanded = false }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = value == id, onClick = null, modifier = Modifier.padding(10.dp))
                Text(name, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    Line()
}
@Composable private fun FieldSetting(label: String, value: String, multiline: Boolean = false, change: (String) -> Unit) {
    var draft by remember(value) { mutableStateOf(value) }
    OutlinedTextField(draft, { draft = it }, label = { Text(label) }, singleLine = !multiline, minLines = if (multiline) 2 else 1, maxLines = if (multiline) 6 else 1,
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), shape = RoundedCornerShape(6.dp),
        trailingIcon = { IconButton(onClick = { change(draft.trim()) }, enabled = draft != value) { Icon(Icons.Outlined.Save, "保存 $label") } })
}
@Composable private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean = true,
    detail: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = enabled, onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.3f)); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.4f))
            if (detail != null) Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
