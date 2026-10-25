package dev.aster.ide.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.aster.ide.IdeViewModel
import dev.aster.ide.runtime.BuildProfiles
import dev.aster.ide.editor.TextBlocks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun BuildDialog(vm: IdeViewModel, dismiss: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var command by remember(vm.buildProfile, vm.activePath) { mutableStateOf(vm.defaultCommand()) }
    AlertDialog(onDismissRequest = dismiss, shape = RoundedCornerShape(8.dp), title = { Text("构建与运行") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box {
                OutlinedButton(onClick = { menu = true }, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(vm.buildProfile, Modifier.weight(1f)); Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
                }
                DropdownMenu(menu, { menu = false }) { BuildProfiles.names.forEach { name -> DropdownMenuItem(text = { Text(name) }, onClick = { vm.buildProfile = name; command = vm.defaultCommand(); menu = false }) } }
            }
            OutlinedTextField(command, { command = it }, label = { Text("命令") }, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp, max = 240.dp), minLines = 3)
            StatusLabel(if (vm.connected) "Termux · ${vm.workspace?.name.orEmpty()}" else "Termux 未连接", if (vm.connected) MaterialTheme.colorScheme.primary else Amber)
        } },
        confirmButton = { if (vm.connected) TextButton(onClick = { vm.build(command); dismiss() }, enabled = command.isNotBlank() && !vm.buildRunning) { Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp)); Text("运行") }
            else TextButton(onClick = { vm.settingsPage = "runtime"; dismiss() }) { Text("配置环境") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable fun CommandPalette(vm: IdeViewModel, onImport: () -> Unit, onBuild: () -> Unit, onNew: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val commands: List<Pair<String, () -> Unit>> = listOf(
        "保存当前文件" to { vm.save(); Unit }, "新建文件" to onNew, "打开项目" to onImport,
        "构建与运行" to onBuild, "查找与替换" to { vm.showFind = true }, "打开终端" to { vm.openTerminal(); Unit },
        "转到定义" to { vm.languageAction("definition"); Unit }, "查找引用" to { vm.languageAction("references"); Unit },
        "格式化文档" to { vm.languageAction("format"); Unit }, "类型与文档" to { vm.languageAction("hover"); Unit },
        "文档符号" to { vm.languageAction("symbols"); Unit }, "代码操作" to { vm.languageAction("actions"); Unit },
        "重启语言服务器" to { vm.restartLsp(); Unit }, "语言服务设置" to { vm.settingsPage = "lsp" },
        "打开 Agent 工作空间" to { vm.openAgent() }, "Agent 设置" to { vm.settingsPage = "agent" },
        "编译环境设置" to { vm.settingsPage = "runtime" }, "编辑器设置" to { vm.settingsPage = "editor" }
    )
    Dialog(onDismissRequest = { vm.commandPalette = false }) {
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().heightIn(max = 540.dp)) {
                TextField(query, { query = it }, singleLine = true, placeholder = { Text("命令或文件名") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(commands.filter { it.first.contains(query, true) }) { (name, action) ->
                        Row(Modifier.fillMaxWidth().clickable { vm.commandPalette = false; action() }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp)); Text(name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    items(vm.nodes.filter { !it.directory && query.isNotBlank() && it.path.contains(query, true) }.take(30)) { file ->
                        Row(Modifier.fillMaxWidth().clickable { vm.open(file.path); vm.commandPalette = false }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Description, null, Modifier.size(18.dp), tint = fileColor(file.path))
                            Spacer(Modifier.width(10.dp)); Text(file.path, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable fun LanguageDialogs(vm: IdeViewModel) {
    val clipboard = LocalClipboardManager.current
    vm.info?.let { info -> AlertDialog(onDismissRequest = { vm.info = null }, title = { Text(info.title) }, shape = RoundedCornerShape(8.dp),
        icon = { Icon(if (info.title == "函数签名") Icons.Outlined.Functions else Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.primary) },
        text = { Surface(shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)) { ChunkedText(info.content, Modifier.heightIn(max = 440.dp).padding(12.dp)) } },
        confirmButton = { TextButton(onClick = { vm.info = null }) { Text("关闭") } },
        dismissButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(info.content)) }) { Text("复制全文") } }) }
    if (vm.locations.isNotEmpty()) AlertDialog(onDismissRequest = { vm.locations = emptyList() }, title = { Text("位置 · ${vm.locations.size}") }, shape = RoundedCornerShape(8.dp),
        text = { LazyColumn(Modifier.heightIn(max = 420.dp)) { items(vm.locations) { location ->
            Column(Modifier.fillMaxWidth().clickable { vm.navigate(location); vm.locations = emptyList() }.padding(vertical = 12.dp)) {
                Text(vm.lsp.relative(location.uri) ?: location.uri, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${location.range.start.line + 1}:${location.range.start.character + 1}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        } } }, confirmButton = { TextButton(onClick = { vm.locations = emptyList() }) { Text("关闭") } })
    if (vm.codeActions.isNotEmpty()) AlertDialog(onDismissRequest = { vm.codeActions = emptyList() }, title = { Text("代码操作") }, shape = RoundedCornerShape(8.dp),
        text = { LazyColumn(Modifier.heightIn(max = 420.dp)) { items(vm.codeActions) { action ->
            Text(action.optString("title"), Modifier.fillMaxWidth().clickable(enabled = !action.has("disabled")) { vm.applyCodeAction(action) }.padding(vertical = 14.dp), style = MaterialTheme.typography.bodyMedium, color = if (action.has("disabled")) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        } } }, confirmButton = { TextButton(onClick = { vm.codeActions = emptyList() }) { Text("取消") } })
    vm.editPreview?.let { preview ->
        var selected by remember(preview) { mutableIntStateOf(0) }
        val change = preview.changes[selected.coerceIn(preview.changes.indices)]
        val blocks by produceState<Pair<List<String>, List<String>>>(emptyList<String>() to emptyList<String>(), change) {
            value = withContext(Dispatchers.Default) { TextBlocks.split(change.before) to TextBlocks.split(change.after) }
        }
        AlertDialog(onDismissRequest = { if (!vm.applyingEdits) vm.editPreview = null }, title = { Text("${preview.title} · ${preview.changes.size} 个文件") }, shape = RoundedCornerShape(8.dp),
            text = { SelectionContainer {
                LazyColumn(Modifier.heightIn(max = 450.dp)) {
                    items(preview.changes.size) { index ->
                        val file = preview.changes[index]
                        Row(Modifier.fillMaxWidth().clickable { selected = index }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (file.deleted) Icons.Outlined.DeleteOutline else Icons.Outlined.Description, null, Modifier.size(16.dp), tint = if (index == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Text(file.path + if (file.deleted) " · 删除" else if (!file.beforeExists) " · 新建" else "", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    item { SectionLabel(if (change.beforeExists) "修改前" else "新建文件") }
                    items(blocks.first.size) { index -> Text(blocks.first[index].removeSuffix("\n"), fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = Coral) }
                    item { SectionLabel(if (change.deleted) "将删除此文件" else "修改后") }
                    items(blocks.second.size) { index -> Text(blocks.second[index].removeSuffix("\n"), fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                }
            } }, confirmButton = { TextButton(onClick = { vm.commitEdits() }, enabled = !vm.applyingEdits) { Text(if (vm.applyingEdits) "应用中…" else "应用更改") } },
            dismissButton = { TextButton(onClick = { vm.editPreview = null }, enabled = !vm.applyingEdits) { Text("取消") } })
    }
}