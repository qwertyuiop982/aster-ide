package dev.aster.ide.ui

import android.graphics.Typeface
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import dev.aster.ide.Artifact
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.view.TerminalView
import dev.aster.ide.IdeViewModel
import dev.aster.ide.data.IdeSettings
import dev.aster.ide.lsp.LspRange
import dev.aster.ide.lsp.objects
import org.json.JSONObject
import dev.aster.ide.runtime.LogBuffer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.emptyFlow

@Composable fun Panels(vm: IdeViewModel, settings: IdeSettings, modifier: Modifier, side: Boolean = false) {
    val motion = LocalMotion.current
    val tabs = WorkbenchPanels.map { it.id to it.title }
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Line()
        Row(Modifier.height(if (side) 34.dp else 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                tabs.forEach { (id, title) -> Column(Modifier.fillMaxHeight().clickable { vm.panel = id }.padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text(title, style = MaterialTheme.typography.labelMedium, color = if (vm.panel == id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                    val indicator by animateFloatAsState(if (vm.panel == id) 1f else 0f, tween(motion.quick), label = "panelTab")
                    Box(Modifier.height(2.dp).width(24.dp).graphicsLayer { alpha = indicator }.background(MaterialTheme.colorScheme.primary))
                } }
            }
            ToolButton(if (side) (if (vm.dockExpanded) Icons.Outlined.ChevronRight else Icons.Outlined.ChevronLeft) else (if (vm.dockExpanded) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess), if (side) "调整副屏宽度" else "调整面板高度", size = 30) { vm.toggleDockExtent() }
            ToolButton(Icons.Outlined.Close, "关闭面板", size = 30) { vm.showPanel = false }
        }
        AnimatedContent(vm.panel, transitionSpec = { (fadeIn(tween(motion.standard)) togetherWith fadeOut(tween(motion.quick))).using(SizeTransform(clip = true)) }, label = "dockPanel", modifier = Modifier.weight(1f)) { shownPanel ->
        Column(Modifier.fillMaxSize()) {
        when (shownPanel) {
            "terminal" -> TerminalPanel(vm, settings, Modifier.weight(1f))
            "build" -> {
                Row(Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusLabel(when { vm.buildRunning -> "运行中 · ${vm.buildElapsed}s"; vm.buildExit == 0 -> "完成 · exit 0"; vm.buildExit != null -> "失败 · exit ${vm.buildExit}"; else -> "等待任务" }, when { vm.buildExit == 0 -> MaterialTheme.colorScheme.primary; vm.buildExit != null -> Coral; else -> MaterialTheme.colorScheme.onSurfaceVariant })
                    Spacer(Modifier.weight(1f))
                    if (vm.buildRunning) ToolButton(Icons.Outlined.Stop, "停止构建", size = 28, tint = Coral) { vm.cancelBuild() }
                    else ToolButton(Icons.Outlined.Replay, "重新运行", enabled = vm.lastCommand.isNotBlank(), size = 28) { vm.build(vm.lastCommand) }
                    ToolButton(Icons.Outlined.DeleteOutline, "清空构建输出", size = 28) { vm.output.clear() }
                }
                OutputView(vm.output, Modifier.weight(1f), vm.settingsPage == null)
            }
            "logs" -> OutputView(vm.logs, Modifier.weight(1f), vm.settingsPage == null)
            "problems" -> Problems(vm, Modifier.weight(1f))
            "symbols" -> Symbols(vm, Modifier.weight(1f))
            "artifacts" -> Artifacts(vm, Modifier.weight(1f))
        }
        }
        }
    }
}

@Composable private fun TerminalPanel(vm: IdeViewModel, settings: IdeSettings, modifier: Modifier) {
    val session by vm.terminal.session.collectAsState()
    val state by vm.terminal.state.collectAsState()
    val dark = LocalDark.current
    val acs = settings.uiStyle == dev.aster.ide.data.UiStyle.ACS
    val palette = dev.aster.ide.runtime.TerminalPalette.forStyle(acs, dark)
    var ctrl by remember { mutableStateOf(false) }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusLabel(state, if (session != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            if (session != null) {
                TextButton(onClick = { ctrl = !ctrl; vm.terminal.control = ctrl }, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.height(30.dp)) { Text("Ctrl", color = if (ctrl) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                ToolButton(Icons.Outlined.KeyboardTab, "终端 Tab", size = 28) { vm.terminal.input("\t") }
                ToolButton(Icons.Outlined.KeyboardArrowUp, "上一条命令", size = 28) { vm.terminal.input("\u001b[A") }
                ToolButton(Icons.Outlined.Close, "结束终端会话", size = 28) { vm.terminal.close() }
            }
            ToolButton(Icons.Outlined.Add, "新终端会话", size = 28, enabled = vm.connected) { vm.openTerminal() }
        }
        if (session == null) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            TextButton(onClick = { if (vm.connected) vm.openTerminal() else vm.settingsPage = "runtime" }) {
                Icon(if (vm.connected) Icons.Outlined.Terminal else Icons.Outlined.Link, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text(if (vm.connected) "打开终端" else "连接 Termux")
            }
        } else {
            val context = LocalContext.current
            val terminalView = remember(session, context) {
                TerminalView(context, null).apply {
                    setTextSize((12 * resources.displayMetrics.scaledDensity).toInt())
                    setTypeface(Typeface.MONOSPACE)
                    isFocusableInTouchMode = true
                }
            }
            DisposableEffect(terminalView) {
                vm.terminal.attach(terminalView)
                onDispose { vm.terminal.detach(terminalView) }
            }
            AndroidView(factory = { terminalView }, update = {
                vm.terminal.setPalette(palette)
                it.setBackgroundColor(palette.background)
            }, modifier = Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable private fun OutputView(buffer: LogBuffer, modifier: Modifier, active: Boolean) {
    val clipboard = LocalClipboardManager.current
    val state = rememberLazyListState(buffer.firstVisibleRow, buffer.firstVisibleOffset)
    var follow by remember(buffer) { mutableStateOf(buffer.followOutput) }
    val rowUpdates = remember(buffer, active) { if (active) buffer.rows else emptyFlow() }
    val revisions = remember(buffer, active) { if (active) buffer.revision else emptyFlow() }
    val rows by rowUpdates.collectAsStateWithLifecycle(initialValue = emptyList())
    val revision by revisions.collectAsStateWithLifecycle(initialValue = 0L)
    DisposableEffect(buffer, state) {
        onDispose {
            buffer.firstVisibleRow = state.firstVisibleItemIndex
            buffer.firstVisibleOffset = state.firstVisibleItemScrollOffset
        }
    }
    LaunchedEffect(revision, follow) { if (follow && rows.isNotEmpty()) state.scrollToItem(rows.lastIndex) }
    Box(modifier.fillMaxWidth()) {
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize().padding(start = 12.dp, end = 36.dp, bottom = 8.dp), state = state) {
                items(rows.size) { index ->
                    val row = rows[index]
                    Text(row.text.ifEmpty { " " }, fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = if (row.error) Coral else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        Column(Modifier.align(Alignment.TopEnd)) {
            ToolButton(Icons.Outlined.ContentCopy, "复制输出", size = 28, enabled = rows.any { it.text.isNotEmpty() }) { clipboard.setText(AnnotatedString(buffer.text.value)) }
            ToolButton(if (follow) Icons.Outlined.VerticalAlignBottom else Icons.Outlined.Pause, "跟随输出", follow, size = 28) { follow = !follow; buffer.followOutput = follow }
        }
    }
}

@Composable private fun Problems(vm: IdeViewModel, modifier: Modifier) {
    val values = remember(vm.diagnostics) { vm.diagnostics.flatMap { (path, diagnostics) -> diagnostics.map { path to it } }.sortedWith(compareBy({ it.second.severity }, { it.first }, { it.second.range.start.line })) }
    if (values.isEmpty()) Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (vm.connected) Icons.Outlined.CheckCircle else Icons.Outlined.SensorsOff, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (vm.connected) "暂无诊断" else "语言服务未连接", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else LazyColumn(modifier.fillMaxWidth()) {
        items(values) { (path, issue) -> Row(Modifier.fillMaxWidth().clickable { vm.navigate(path, issue.range.start) }.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (issue.severity == 1) Icons.Outlined.ErrorOutline else Icons.Outlined.WarningAmber, null, Modifier.size(16.dp), tint = if (issue.severity == 1) Coral else Amber)
            Column(Modifier.weight(1f)) {
                Text(issue.message, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text("$path:${issue.range.start.line + 1}:${issue.range.start.character + 1}  ·  ${issue.source}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } }
    }
}

@Composable private fun Symbols(vm: IdeViewModel, modifier: Modifier) {
    val rows = remember(vm.symbols) { buildList<Pair<Int, JSONObject>> {
        fun addSymbols(depth: Int, list: List<JSONObject>) { if (depth > 16 || size > 500) return; list.forEach { add(depth to it); addSymbols(depth + 1, it.optJSONArray("children")?.objects().orEmpty()) } }
        addSymbols(0, vm.symbols)
    } }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(30.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(vm.active?.name.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ToolButton(Icons.Outlined.Refresh, "刷新文档符号", size = 28) { vm.languageAction("symbols") }
        }
        LazyColumn(Modifier.weight(1f)) { items(rows) { (depth, symbol) ->
            Row(Modifier.fillMaxWidth().clickable {
                val range = symbol.optJSONObject("selectionRange") ?: symbol.optJSONObject("range") ?: symbol.optJSONObject("location")?.optJSONObject("range")
                range?.let { j -> vm.activePath?.let { vm.navigate(it, LspRange.from(j).start) } }
            }.padding(start = (12 + depth * 16).dp, end = 12.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.DataObject, null, Modifier.size(15.dp), tint = Lilac); Spacer(Modifier.width(8.dp))
                Text(symbol.optString("name"), fontFamily = Mono, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } }
    }
}

@Composable private fun Artifacts(vm: IdeViewModel, modifier: Modifier) {
    var exporting by remember { mutableStateOf<Artifact?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val artifact = exporting
        if (uri != null && artifact != null) vm.exportArtifact(artifact, uri)
        exporting = null
    }
    if (vm.artifacts.isEmpty()) Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text("暂无编译产物", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else LazyColumn(modifier.fillMaxWidth()) {
        items(vm.artifacts, key = { it.path }) { artifact ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Inventory2, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(artifact.path, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${artifact.size / 1024} KiB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ToolButton(Icons.Outlined.Download, "导出 ${artifact.path.substringAfterLast('/')}", enabled = !vm.buildRunning, size = 34) {
                    exporting = artifact
                    saver.launch(artifact.path.substringAfterLast('/'))
                }
            }
        }
    }
}
