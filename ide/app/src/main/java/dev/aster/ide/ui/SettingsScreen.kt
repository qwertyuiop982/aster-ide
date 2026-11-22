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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aster.ide.feature.BuildConfig
import dev.aster.ide.feature.R
import dev.aster.core.AppLanguages
import dev.aster.ide.IdeViewModel
import dev.aster.ide.InfoSheet
import dev.aster.ide.data.IdeSettings
import dev.aster.ide.data.UiStyle
import dev.aster.ide.runtime.RuntimePhase
import dev.aster.ide.runtime.BuildEnvironmentPresets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import dev.aster.core.R as CoreR

private val Categories = listOf(
    "editor" to R.string.settings_editor, "appearance" to R.string.settings_appearance,
    "lsp" to R.string.settings_lsp, "agent" to R.string.settings_agent,
    "runtime" to R.string.settings_runtime, "build" to R.string.settings_build,
    "performance" to R.string.settings_performance, "about" to R.string.settings_about
)

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
        val displayedCategory = category.takeIf { id -> Categories.any { it.first == id } } ?: "editor"
        val showIndex = indexVisible || category == "index"
        val back = {
            when {
                detail != null -> detail = null
                selectedServer != null -> selectedServer = null
                !wide && !showIndex -> indexVisible = true
                else -> vm.settingsPage = null
            }
        }
        BackHandler(onBack = back)
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).background(MaterialTheme.colorScheme.surface).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ToolButton(Icons.Outlined.ArrowBack, stringResource(CoreR.string.action_back)) { back() }
                Text(stringResource(if (!wide && !showIndex) Categories.first { it.first == displayedCategory }.second else CoreR.string.action_settings),
                    Modifier.weight(1f).padding(horizontal = 10.dp), style = MaterialTheme.typography.titleMedium)
                ToolButton(Icons.Outlined.Restore, stringResource(R.string.settings_reset)) { reset = true }
            }
            Line()
            Row(Modifier.weight(1f)) {
                if (wide || showIndex) {
                    Column((if (wide) Modifier.width(208.dp) else Modifier.fillMaxWidth()).fillMaxHeight().background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState())) {
                        Categories.forEach { (id, title) -> Row(Modifier.fillMaxWidth().heightIn(min = 54.dp)
                            .background(if (wide && displayedCategory == id) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent)
                            .clickable { vm.settingsPage = id; indexVisible = false }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            val icon = when (id) { "editor" -> Icons.Outlined.EditNote; "appearance" -> Icons.Outlined.Palette; "lsp" -> Icons.Outlined.DataObject; "agent" -> Icons.Outlined.SmartToy; "runtime" -> Icons.Outlined.Terminal; "build" -> Icons.Outlined.Build; "performance" -> Icons.Outlined.Speed; else -> Icons.Outlined.Info }
                            Icon(icon, null, Modifier.size(18.dp), tint = if (wide && category == id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(12.dp)); Text(stringResource(title), style = MaterialTheme.typography.bodyMedium, color = if (wide && category == id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                            if (!wide) Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        } }
                        HorizontalDivider()
                        TextButton(onClick = chooseWorkMode, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) { Text(stringResource(CoreR.string.action_switch_work_mode)) }
                        dev.aster.ide.account.AccountEntryButton()
                    }
                    if (wide) VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (wide || !showIndex) Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                    AnimatedContent(displayedCategory, transitionSpec = { (fadeIn(tween(motion.standard)) + slideInHorizontally(tween(motion.standard)) { motion.offset(it / 32) }) togetherWith (fadeOut(tween(motion.quick)) + slideOutHorizontally(tween(motion.standard)) { motion.offset(-it / 32) }) }, label = "settingsCategory", modifier = Modifier.widthIn(max = 820.dp).fillMaxSize()) { shownCategory -> categoryState.SaveableStateProvider(shownCategory) { LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = if (wide) 28.dp else 20.dp, vertical = 12.dp)) {
                        if (wide) item { Text(stringResource(Categories.first { it.first == shownCategory }.second), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp, bottom = 20.dp)) }
                        when (shownCategory) {
                            "editor" -> item {
                                SectionLabel(stringResource(R.string.settings_text))
                                SettingsSlider(stringResource(R.string.settings_font_size), settings.fontSize, 10, 28, 1, "sp") { vm.settings.update { s -> s.copy(fontSize = it) } }
                                SettingsSlider(stringResource(R.string.settings_indent_width), settings.tabWidth, 2, 8, 1, stringResource(R.string.settings_spaces)) { vm.settings.update { s -> s.copy(tabWidth = it) } }
                                SettingSwitch(stringResource(R.string.settings_use_tabs), settings.useTabs) { vm.settings.update { s -> s.copy(useTabs = it) } }
                                SettingSwitch(stringResource(R.string.settings_word_wrap), settings.wordWrap) { vm.settings.update { s -> s.copy(wordWrap = it) } }
                                SettingSwitch(stringResource(R.string.settings_line_numbers), settings.lineNumbers) { vm.settings.update { s -> s.copy(lineNumbers = it) } }
                                SettingSwitch(stringResource(R.string.settings_whitespace), settings.whitespace) { vm.settings.update { s -> s.copy(whitespace = it) } }
                                SettingSwitch(stringResource(R.string.settings_autocomplete), settings.autoComplete) { vm.settings.update { s -> s.copy(autoComplete = it) } }
                                SectionLabel(stringResource(CoreR.string.action_save))
                                SettingSwitch(stringResource(R.string.settings_autosave), settings.autoSave) { vm.settings.update { s -> s.copy(autoSave = it) } }
                                if (settings.autoSave) SettingsSlider(stringResource(R.string.settings_autosave_delay), settings.autoSaveDelay, 500, 5000, 100, "ms") { vm.settings.update { s -> s.copy(autoSaveDelay = it) } }
                                SettingSwitch(stringResource(R.string.settings_format_on_save), settings.formatOnSave) { vm.settings.update { s -> s.copy(formatOnSave = it) } }
                                SectionLabel(stringResource(R.string.settings_files))
                                FieldSetting(stringResource(R.string.settings_excluded_dirs), settings.excludedDirs, multiline = true) { vm.settings.update { s -> s.copy(excludedDirs = it) }; vm.refresh() }
                            }
                            "appearance" -> item {
                                AppLanguages.Picker(Modifier.padding(vertical = 8.dp))
                                Line()
                                SectionLabel(stringResource(R.string.settings_workbench_style))
                                ChoiceRow(stringResource(R.string.settings_interface_style), settings.uiStyle.key, UiStyle.entries.map { it.key to stringResource(it.titleResource) }) { value -> vm.settings.chooseUiStyle(UiStyle.fromKey(value) ?: UiStyle.VSCODE) }
                                StylePreview(settings.uiStyle, Modifier.fillMaxWidth().height(110.dp).padding(vertical = 8.dp))
                                SectionLabel(stringResource(R.string.settings_theme))
                                ChoiceRow(stringResource(R.string.settings_color_mode), settings.theme, listOf(
                                    "dark" to stringResource(R.string.settings_dark),
                                    "light" to stringResource(R.string.settings_light),
                                    "system" to stringResource(CoreR.string.app_language_system)
                                )) { vm.settings.update { s -> s.copy(theme = it) } }
                                SectionLabel(stringResource(R.string.settings_accent))
                                Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                    listOf("mint" to Mint, "blue" to Sky, "lilac" to Lilac, "amber" to Amber).forEach { (id, color) ->
                                        Box(Modifier.size(42.dp).background(color, CircleShape).border(if (settings.accent == id) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape).clickable { vm.settings.update { it.copy(accent = id) } }, contentAlignment = Alignment.Center) {
                                            if (settings.accent == id) Icon(Icons.Outlined.Check, null, Modifier.size(20.dp), tint = Color(0xFF11141D))
                                        }
                                    }
                                }
                                SectionLabel(stringResource(R.string.settings_motion))
                                SettingSwitch(stringResource(R.string.settings_animations), settings.animations) { vm.settings.update { s -> s.copy(animations = it) } }
                                SettingSwitch(stringResource(R.string.settings_reduced_motion), settings.reducedMotion) { vm.settings.update { s -> s.copy(reducedMotion = it) } }
                            }
                            "lsp" -> {
                                item { AcsLspOptions(vm, settings) }
                                vm.catalog.all.forEach { server -> item(key = server.id) { AcsServerSettings(vm, settings, server, onConfigure = { selectedServer = server.id }) } }
                            }
                            "agent" -> item { AgentSettings(vm, settings, onOpenStandaloneMode = chooseWorkMode) }
                            "runtime" -> item {
                                dev.aster.ide.runtime.BuildEnvironmentSettings(
                                    settings = settings, runtime = runtime, connected = vm.connected, connecting = vm.connecting,
                                    installing = vm.buildRunning, directory = vm.termux.directory, tools = vm.tools,
                                    updateSettings = { change -> vm.settings.update(change) },
                                    onConnect = { initialize -> vm.connectRuntime(initialize) }, onInstall = { install = it },
                                    onLogs = { detail = InfoSheet(context.getString(R.string.runtime_install_logs), vm.output.text.value.takeLast(18000).ifBlank { context.getString(R.string.settings_no_logs) }) },
                                    onCopyDirectory = { clipboard.setText(AnnotatedString(vm.termux.directory)); vm.notify(context.getString(R.string.settings_directory_copied)) },
                                    onMessage = { vm.notify(it) }, port = vm.termux.port
                                )
                            }
                            "build" -> item {
                                SectionLabel(stringResource(R.string.settings_custom_command))
                                FieldSetting(stringResource(R.string.settings_build_command), settings.buildCommand, multiline = true) { vm.settings.update { s -> s.copy(buildCommand = it) } }
                                ActionRow(Icons.Outlined.DeleteSweep, stringResource(R.string.settings_clear_output)) { vm.output.clear() }
                            }
                            "performance" -> item {
                                SectionLabel(stringResource(R.string.settings_memory_process))
                                SettingsSlider(stringResource(R.string.settings_max_tabs), settings.maxTabs, 4, 24, 1, "") { vm.settings.update { s -> s.copy(maxTabs = it) } }
                                SettingsSlider(stringResource(R.string.settings_max_servers), settings.maxServers, 1, 5, 1, "") { vm.settings.update { s -> s.copy(maxServers = it) } }
                                SettingsSlider(stringResource(R.string.settings_large_file), settings.largeFileKB, 128, 2048, 128, "KiB") { vm.settings.update { s -> s.copy(largeFileKB = it) } }
                                SettingsSlider(stringResource(R.string.settings_max_file), settings.maxFileMB, 1, 12, 1, "MiB") { vm.settings.update { s -> s.copy(maxFileMB = it) } }
                                SettingsSlider(stringResource(R.string.settings_scrollback), settings.scrollback, 500, 10000, 500, stringResource(R.string.settings_lines)) { vm.settings.update { s -> s.copy(scrollback = it) } }
                                ValueRow(stringResource(R.string.settings_open_tabs), vm.documents.size.toString())
                                ValueRow(stringResource(R.string.settings_language_servers), vm.lsp.count.toString())
                                SettingsSlider(stringResource(R.string.settings_editor_cache), settings.editorCacheSize, 1, 4, 1, "") { vm.settings.update { s -> s.copy(editorCacheSize = it) } }
                                ValueRow(stringResource(R.string.settings_editors_cached), vm.editorCachedCount.toString())
                                ValueRow(stringResource(R.string.settings_cache_hits), vm.editorCacheHits.toString())
                            }
                            "about" -> item {
                                Row(Modifier.padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Code, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(16.dp)); Column { Text("Aster", style = MaterialTheme.typography.titleLarge); Text("${BuildConfig.VERSION_NAME} · ${BuildConfig.BUILD_TYPE}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                                ValueRow(stringResource(R.string.settings_min_android), "7.0 · API 24")
                                ValueRow(stringResource(R.string.settings_interface), "Jetpack Compose")
                                ValueRow(stringResource(R.string.settings_editor_component), "Sora Editor 0.24.4")
                                ValueRow(stringResource(R.string.settings_terminal_component), "Termux 0.118.3")
                                ValueRow(stringResource(R.string.settings_agent_engine), vm.agent.engine)
                                ValueRow(stringResource(R.string.settings_native_module), "NDK r29 · C++17")
                                SectionLabel(stringResource(R.string.settings_open_source))
                                ActionRow(Icons.Outlined.Description, stringResource(R.string.settings_licenses)) { scope.launch { val text = withContext(Dispatchers.IO) { context.assets.open("NOTICE.txt").bufferedReader().use { it.readText() } }; detail = InfoSheet(context.getString(R.string.settings_licenses), text) } }
                                ActionRow(Icons.Outlined.OpenInNew, stringResource(R.string.settings_termux_upstream)) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/termux/termux-app"))) }
                                ActionRow(Icons.Outlined.OpenInNew, stringResource(R.string.settings_sora_upstream)) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Rosemoe/sora-editor"))) }
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
                    Text(if (page.title == stringResource(R.string.runtime_install_logs)) output.takeLast(60000).ifBlank { context.getString(R.string.settings_no_logs) } else page.content, fontFamily = Mono, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (reset) ConfirmDialog(stringResource(R.string.settings_reset_title), stringResource(R.string.settings_reset_detail), onDismiss = { reset = false }) { vm.settings.reset(); vm.restartLsp() }
    install?.let { id ->
        val server = vm.catalog.all.firstOrNull { it.id == id }
        val preset = BuildEnvironmentPresets.get(id)
        val title = server?.name ?: preset?.title ?: stringResource(if (id == "base") R.string.settings_base_tools else R.string.settings_android_tools)
        val packages = server?.let { (it.packages + it.npm).joinToString(", ") } ?: preset?.packages ?: if (id == "base") "python, nodejs-lts, git, ripgrep, clang, make, cmake, ninja, unzip, zip" else "JDK ${settings.sdkJdk}, Gradle, Android API 35, Bionic Build Tools ${settings.sdkBuildTools}" + if (settings.sdkNdk == "0") "" else ", Android NDK ${settings.sdkNdk}"
        val installDetail = "${stringResource(R.string.settings_install_detail)}${if (id == "android") "\n${stringResource(R.string.settings_android_install_detail)}" else ""}"
        ConfirmDialog(stringResource(R.string.settings_install_title, title), "$packages\n\n$installDetail", stringResource(R.string.settings_install), onDismiss = { install = null }) { vm.installServer(id); vm.settingsPage = null }
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
        trailingIcon = { ToolButton(Icons.Outlined.Save, stringResource(R.string.settings_save_field, label), enabled = draft != value, size = 34) { change(draft.trim()) } })
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
            Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                Row(Modifier.widthIn(max = 820.dp).fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    ToolButton(Icons.Outlined.ArrowBack, stringResource(CoreR.string.action_back)) { close() }
                    Text(title, Modifier.weight(1f).padding(horizontal = 10.dp), style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider()
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 820.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
            }
        }
    }
}
