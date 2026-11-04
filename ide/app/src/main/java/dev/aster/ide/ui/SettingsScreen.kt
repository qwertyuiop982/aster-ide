package dev.aster.ide.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aster.ide.feature.BuildConfig
import dev.aster.ide.IdeViewModel
import dev.aster.ide.InfoSheet
import dev.aster.ide.data.IdeSettings
import dev.aster.ide.data.UiStyle
import dev.aster.ide.runtime.RuntimePhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val Categories = listOf("editor" to "编辑器", "appearance" to "外观", "lsp" to "语言服务", "agent" to "Agent", "runtime" to "编译环境", "build" to "构建", "performance" to "性能", "about" to "关于")

@Composable fun SettingsScreen(vm: IdeViewModel, settings: IdeSettings, category: String = vm.settingsPage ?: "editor") {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val runtime by vm.termux.state.collectAsState()
    var reset by remember { mutableStateOf(false) }
    var indexVisible by rememberSaveable { mutableStateOf(false) }
    val categoryState = rememberSaveableStateHolder()
    var selectedServer by remember(category) { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<InfoSheet?>(null) }
    var install by remember { mutableStateOf<String?>(null) }
    val chooseWorkMode = dev.aster.core.LocalWorkModeChooser.current
    val motion = LocalMotion.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        val back = {
            when {
                detail != null -> detail = null
                selectedServer != null -> selectedServer = null
                !wide && !indexVisible -> indexVisible = true
                else -> vm.settingsPage = null
            }
        }
        BackHandler(onBack = back)
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).background(MaterialTheme.colorScheme.surface).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ToolButton(Icons.Outlined.ArrowBack, "返回") { back() }
                Text(if (!wide && !indexVisible) Categories.firstOrNull { it.first == category }?.second ?: "设置" else "设置", Modifier.weight(1f).padding(horizontal = 10.dp), style = MaterialTheme.typography.titleMedium)
                ToolButton(Icons.Outlined.Restore, "恢复默认设置") { reset = true }
            }
            Line()
            Row(Modifier.weight(1f)) {
                if (wide || indexVisible) {
                    Column((if (wide) Modifier.width(208.dp) else Modifier.fillMaxWidth()).fillMaxHeight().background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState())) {
                        Categories.forEach { (id, title) -> Row(Modifier.fillMaxWidth().background(if (wide && category == id) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent).clickable { vm.settingsPage = id; indexVisible = false }.padding(horizontal = 22.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                            val icon = when (id) { "editor" -> Icons.Outlined.EditNote; "appearance" -> Icons.Outlined.Palette; "lsp" -> Icons.Outlined.DataObject; "agent" -> Icons.Outlined.SmartToy; "runtime" -> Icons.Outlined.Terminal; "build" -> Icons.Outlined.Build; "performance" -> Icons.Outlined.Speed; else -> Icons.Outlined.Info }
                            Icon(icon, null, Modifier.size(18.dp), tint = if (wide && category == id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(12.dp)); Text(title, style = MaterialTheme.typography.bodyMedium, color = if (wide && category == id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                            if (!wide) Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        } }
                        HorizontalDivider()
                        TextButton(onClick = chooseWorkMode, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) { Text("切换工作模式") }
                        dev.aster.ide.account.AccountEntryButton()
                    }
                    if (wide) VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (wide || !indexVisible) Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                    AnimatedContent(category, transitionSpec = { (fadeIn(tween(motion.standard)) + slideInHorizontally(tween(motion.standard)) { motion.offset(it / 32) }) togetherWith (fadeOut(tween(motion.quick)) + slideOutHorizontally(tween(motion.standard)) { motion.offset(-it / 32) }) }, label = "settingsCategory", modifier = Modifier.fillMaxSize()) { shownCategory -> categoryState.SaveableStateProvider(shownCategory) { LazyColumn(Modifier.widthIn(max = 820.dp).fillMaxSize(), contentPadding = PaddingValues(horizontal = if (wide) 32.dp else 20.dp, vertical = 12.dp)) {
                        if (wide) item { Text(Categories.first { it.first == shownCategory }.second, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp, bottom = 20.dp)) }
                        when (shownCategory) {
                            "editor" -> item {
                                SectionLabel("文本")
                                SettingsSlider("字号", settings.fontSize, 10, 28, 1, "sp") { vm.settings.update { s -> s.copy(fontSize = it) } }
                                SettingsSlider("缩进宽度", settings.tabWidth, 2, 8, 1, "空格") { vm.settings.update { s -> s.copy(tabWidth = it) } }
                                SettingSwitch("使用 Tab 缩进", settings.useTabs) { vm.settings.update { s -> s.copy(useTabs = it) } }
                                SettingSwitch("自动换行", settings.wordWrap) { vm.settings.update { s -> s.copy(wordWrap = it) } }
                                SettingSwitch("显示行号", settings.lineNumbers) { vm.settings.update { s -> s.copy(lineNumbers = it) } }
                                SettingSwitch("显示空白字符", settings.whitespace) { vm.settings.update { s -> s.copy(whitespace = it) } }
                                SettingSwitch("自动补全", settings.autoComplete) { vm.settings.update { s -> s.copy(autoComplete = it) } }
                                SectionLabel("保存")
                                SettingSwitch("自动保存", settings.autoSave) { vm.settings.update { s -> s.copy(autoSave = it) } }
                                SettingsSlider("自动保存延迟", settings.autoSaveDelay, 500, 5000, 100, "ms") { vm.settings.update { s -> s.copy(autoSaveDelay = it) } }
                                SettingSwitch("保存时格式化", settings.formatOnSave) { vm.settings.update { s -> s.copy(formatOnSave = it) } }
                                SectionLabel("文件")
                                FieldSetting("排除目录", settings.excludedDirs, multiline = true) { vm.settings.update { s -> s.copy(excludedDirs = it) }; vm.refresh() }
                                ValueRow("默认编码", "UTF-8"); ValueRow("目录访问", "Android 文件选择器")
                            }
                            "appearance" -> item {
                                SectionLabel("工作台样式")
                                ChoiceRow("界面风格", settings.uiStyle.key, UiStyle.entries.map { it.key to it.title }) { value -> vm.settings.chooseUiStyle(UiStyle.fromKey(value) ?: UiStyle.VSCODE) }
                                StylePreview(settings.uiStyle, Modifier.fillMaxWidth().height(110.dp).padding(vertical = 8.dp))
                                ValueRow("语言服务界面", "ACS · 两种风格共用")
                                SectionLabel("主题")
                                ChoiceRow("配色模式", settings.theme, listOf("dark" to "深色", "light" to "浅色", "system" to "跟随系统")) { vm.settings.update { s -> s.copy(theme = it) } }
                                SectionLabel("强调色")
                                Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                    listOf("mint" to Mint, "blue" to Sky, "lilac" to Lilac, "amber" to Amber).forEach { (id, color) ->
                                        Box(Modifier.size(42.dp).background(color, CircleShape).border(if (settings.accent == id) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape).clickable { vm.settings.update { it.copy(accent = id) } }, contentAlignment = Alignment.Center) {
                                            if (settings.accent == id) Icon(Icons.Outlined.Check, null, Modifier.size(20.dp), tint = Color(0xFF11141D))
                                        }
                                    }
                                }
                                SectionLabel("动态效果")
                                SettingSwitch("界面过渡动画", settings.animations) { vm.settings.update { s -> s.copy(animations = it) } }
                                SettingSwitch("减少动态效果", settings.reducedMotion) { vm.settings.update { s -> s.copy(reducedMotion = it) } }
                                SectionLabel("字体")
                                ValueRow("编辑器", "System Monospace"); ValueRow("界面", "System Sans"); ValueRow("字符间距", "0")
                            }
                            "lsp" -> {
                                item { AcsLspOptions(vm, settings) }
                                vm.catalog.all.forEach { server -> item(key = server.id) { AcsServerSettings(vm, settings, server, onConfigure = { selectedServer = server.id }) } }
                            }
                            "agent" -> item { AgentSettings(vm, settings) }
                            "runtime" -> item {
                                dev.aster.ide.runtime.BuildEnvironmentSettings(
                                    settings = settings, runtime = runtime, connected = vm.connected, connecting = vm.connecting,
                                    installing = vm.buildRunning, directory = vm.termux.directory, tools = vm.tools,
                                    updateSettings = { change -> vm.settings.update(change) },
                                    onConnect = { initialize -> vm.connectRuntime(initialize) }, onInstall = { install = it },
                                    onLogs = { detail = InfoSheet("环境安装日志", vm.output.text.value.takeLast(18000).ifBlank { "暂无日志" }) },
                                    onCopyDirectory = { clipboard.setText(AnnotatedString(vm.termux.directory)); vm.notify("运行目录已复制") },
                                    onPort = { vm.changeRuntimePort(it) }, onMessage = { vm.notify(it) }
                                )
                            }
                            "build" -> item {
                                SectionLabel("Gradle")
                                SettingsSlider("最大堆内存", settings.buildMemory, 256, 4096, 256, "MiB") { vm.settings.update { s -> s.copy(buildMemory = it) } }
                                SettingsSlider("并行 worker", settings.buildWorkers, 1, 8, 1, "") { vm.settings.update { s -> s.copy(buildWorkers = it) } }
                                ValueRow("Gradle daemon", "按任务退出")
                                ValueRow("aapt2", "Android 原生 SDK Build Tools")
                                SectionLabel("自定义命令")
                                FieldSetting("编译或运行命令", settings.buildCommand, multiline = true) { vm.settings.update { s -> s.copy(buildCommand = it) } }
                                SectionLabel("输出")
                                ValueRow("日志缓冲", "180000 字符 / 2000 行")
                                ValueRow("构建目录", "Termux 内部工作区")
                                ActionRow(Icons.Outlined.DeleteSweep, "清空任务输出") { vm.output.clear() }
                            }
                            "performance" -> item {
                                SectionLabel("内存与进程")
                                SettingsSlider("最多打开标签", settings.maxTabs, 4, 24, 1, "") { vm.settings.update { s -> s.copy(maxTabs = it) } }
                                SettingsSlider("并发语言服务器", settings.maxServers, 1, 5, 1, "") { vm.settings.update { s -> s.copy(maxServers = it) } }
                                SettingsSlider("大文件模式阈值", settings.largeFileKB, 128, 2048, 128, "KiB") { vm.settings.update { s -> s.copy(largeFileKB = it) } }
                                SettingsSlider("单文件大小限制", settings.maxFileMB, 1, 12, 1, "MiB") { vm.settings.update { s -> s.copy(maxFileMB = it) } }
                                SettingsSlider("终端回滚行数", settings.scrollback, 500, 10000, 500, "行") { vm.settings.update { s -> s.copy(scrollback = it) } }
                                SectionLabel("当前会话")
                                ValueRow("打开标签", vm.documents.size.toString()); ValueRow("语言服务器", vm.lsp.count.toString())
                                SettingsSlider("缓存编辑器", settings.editorCacheSize, 1, 4, 1, "个") { vm.settings.update { s -> s.copy(editorCacheSize = it) } }
                                ValueRow("已缓存编辑器", vm.editorCachedCount.toString())
                                ValueRow("缓存命中", vm.editorCacheHits.toString())
                                ValueRow("文档内存预算", "48 MiB")
                                ValueRow("搜索引擎", "NDK · UTF-16 KMP")
                                ValueRow("屏幕更新", "构建输出合并 200 ms · 后台处理")
                            }
                            "about" -> item {
                                Row(Modifier.padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Code, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(16.dp)); Column { Text("Aster", style = MaterialTheme.typography.titleLarge); Text("${BuildConfig.VERSION_NAME} · ${BuildConfig.BUILD_TYPE}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                                ValueRow("最低 Android 版本", "7.0 · API 24")
                                ValueRow("界面", "Jetpack Compose")
                                ValueRow("编辑组件", "Sora Editor 0.24.4")
                                ValueRow("终端组件", "Termux 0.118.3")
                                ValueRow("Agent 引擎", vm.agent.engine)
                                ValueRow("原生模块", "NDK r29 · C++17")
                                SectionLabel("开源")
                                ActionRow(Icons.Outlined.Description, "开源许可") { scope.launch { val text = withContext(Dispatchers.IO) { context.assets.open("NOTICE.txt").bufferedReader().use { it.readText() } }; detail = InfoSheet("开源许可", text) } }
                                ActionRow(Icons.Outlined.OpenInNew, "Termux 上游") { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/termux/termux-app"))) }
                                ActionRow(Icons.Outlined.OpenInNew, "Sora Editor 上游") { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Rosemoe/sora-editor"))) }
                            }
                        }
                        item { Spacer(Modifier.height(40.dp)) }
                    } } }
                }
            }
        }
        selectedServer?.let { id ->
            vm.catalog.all.firstOrNull { it.id == id }?.let { server ->
                SettingsDetailPage(server.name, { selectedServer = null }) {
                    AcsServerConfiguration(vm, settings, server) { install = server.id }
                }
            }
        }
        detail?.let { page ->
            SettingsDetailPage(page.title, { detail = null }) {
                val output by vm.output.text.collectAsState()
                SelectionContainer {
                    Text(if (page.title == "环境安装日志") output.takeLast(60000).ifBlank { "暂无日志" } else page.content, fontFamily = Mono, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (reset) ConfirmDialog("恢复默认设置？", "项目文件、Termux 工具和访问授权不会被删除。", onDismiss = { reset = false }) { vm.settings.reset(); vm.restartLsp() }
    install?.let { id ->
        val server = vm.catalog.all.firstOrNull { it.id == id }
        val title = server?.name ?: if (id == "base") "基础编译工具" else "Android 工具链"
        val packages = server?.let { (it.packages + it.npm).joinToString(", ") } ?: if (id == "base") "python, nodejs-lts, git, ripgrep, clang, make, cmake, ninja, unzip, zip" else "JDK ${settings.sdkJdk}, Gradle, Android API 35, Bionic Build Tools ${settings.sdkBuildTools}" + if (settings.sdkNdk == "0") "" else ", Android 原生 NDK ${settings.sdkNdk}"
        ConfirmDialog("安装 $title？", "$packages\n\n安装到应用内置 Termux 目录。${if (settings.termuxMirror == "ustc") "APT 源会切换到中科大 USTC，并保留原配置备份。" else "保留当前 APT 源。"}${if (id == "android") "\n通过内嵌 idesetup.py 从 AndroidIDE-RV2 / ACS 下载 Android 原生工具，安装后校验 Bionic 和 ARM64。SDK/NDK 资源适用其原有许可条款。" else ""}", "安装", onDismiss = { install = null }) { vm.installServer(id); vm.settingsPage = null }
    }
}

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
@Composable internal fun SettingsSlider(label: String, value: Int, min: Int, max: Int, step: Int, unit: String, change: (Int) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row { Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge); Text("${draft.roundToInt()} $unit", fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        Slider(draft, { draft = ((it / step).roundToInt() * step).toFloat().coerceIn(min.toFloat(), max.toFloat()) }, valueRange = min.toFloat()..max.toFloat(), steps = ((max - min) / step - 1).coerceIn(0, 48), onValueChangeFinished = { change(draft.roundToInt()) })
    }
    Line()
}
@Composable private fun ChoiceRow(label: String, value: String, options: List<Pair<String, String>>, change: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 8.dp))
        options.forEach { (id, name) ->
            Row(Modifier.fillMaxWidth().clickable { change(id) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = value == id, onClick = null, modifier = Modifier.padding(10.dp))
                Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
    Line()
}
@Composable internal fun FieldSetting(label: String, value: String, multiline: Boolean = false, change: (String) -> Unit) {
    var draft by rememberSaveable(value) { mutableStateOf(value) }
    OutlinedTextField(draft, { draft = it }, label = { Text(label) }, singleLine = !multiline, minLines = if (multiline) 2 else 1, maxLines = if (multiline) 6 else 1,
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), shape = RoundedCornerShape(6.dp),
        trailingIcon = { ToolButton(Icons.Outlined.Save, "保存 $label", enabled = draft != value, size = 34) { change(draft.trim()) } })
}
@Composable private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = enabled, onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.3f)); Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.weight(1f))
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun SettingsDetailPage(title: String, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = close)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ToolButton(Icons.Outlined.ArrowBack, "返回设置") { close() }
                Text(title, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.titleMedium)
            }
            HorizontalDivider()
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 820.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
            }
        }
    }
}
