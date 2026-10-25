package dev.aster.agent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.aster.ide.agent.AgentChange

@Composable internal fun ContextFilePicker(vm: AgentViewModel) {
    var search by remember { mutableStateOf("") }
    Dialog(onDismissRequest = { vm.showContextFiles = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.showContextFiles = false }) { Icon(Icons.Outlined.Close, "关闭") }
                    Text("上下文文件", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.showContextFiles = false }) { Text("完成（${vm.contextFiles.size}/8）") }
                }
                OutlinedTextField(search, { search = it }, placeholder = { Text("搜索工作区文件") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
                Text("选择的文本会随任务发送。单文件最多 64 KiB；指令和文件摘要共 12,000 字符，剩余内容可由 Agent 读取。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp, 12.dp))
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f)) {
                    val entries = vm.contextCandidates.filter { it.path.contains(search, ignoreCase = true) }
                    items(entries, key = { it.path }) { file ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = !vm.locked) { vm.toggleContextFile(file.path) }.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = file.path in vm.contextFiles, onCheckedChange = { vm.toggleContextFile(file.path) }, enabled = !vm.locked)
                            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                Text(file.name, style = MaterialTheme.typography.bodyMedium)
                                Text(file.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (entries.isEmpty()) item { Text("没有匹配的文件", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable internal fun ReviewChanges(vm: AgentViewModel, changes: List<AgentChange>) {
    var selected by remember(changes) { mutableStateOf(changes.firstOrNull()?.path) }
    var after by remember { mutableStateOf(true) }
    var confirm by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    val change = changes.firstOrNull { it.path == selected }
    Dialog(onDismissRequest = { if (!vm.locked) vm.reviewChanges = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.reviewChanges = false }, enabled = !vm.locked) { Icon(Icons.Outlined.Close, "返回对话") }
                    Column(Modifier.weight(1f)) {
                        Text("修改审阅", style = MaterialTheme.typography.titleMedium)
                        Text(vm.workspace?.name.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${changes.size} 个文件", style = MaterialTheme.typography.labelMedium)
                }
                HorizontalDivider()
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val wide = maxWidth >= 840.dp
                    @Composable fun FileList(modifier: Modifier) {
                        LazyColumn(modifier) {
                            items(changes, key = { it.path }) { row ->
                                val active = row.path == selected
                                Surface(color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
                                    Row(Modifier.fillMaxWidth().clickable { selected = row.path }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp))
                                        Column(Modifier.padding(start = 10.dp).weight(1f)) {
                                            Text(row.path, style = MaterialTheme.typography.bodySmall)
                                            Text(if (row.deleted) "删除文件" else if (!row.beforeExists) "新建文件" else "修改文件", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    @Composable fun Contents(modifier: Modifier) {
                        Column(modifier) {
                            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { after = false }) { Text("修改前", color = if (!after) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                                TextButton(onClick = { after = true }) { Text("修改后", color = if (after) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
                                Text(change?.let { if (after) { if (it.deleted) "（删除文件）" else it.after } else it.before }.orEmpty(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (wide) Row(Modifier.fillMaxSize()) { FileList(Modifier.width(260.dp).fillMaxHeight()); VerticalDivider(); Contents(Modifier.weight(1f).fillMaxHeight()) }
                    else Column(Modifier.fillMaxSize()) { FileList(Modifier.fillMaxWidth().heightIn(max = 150.dp)); HorizontalDivider(); Contents(Modifier.weight(1f).fillMaxWidth()) }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = { discard = true }, enabled = !vm.locked && changes.isNotEmpty()) { Text("丢弃修改", color = MaterialTheme.colorScheme.error) }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { confirm = true }, enabled = !vm.locked && changes.isNotEmpty()) { Text(if (vm.busy) "正在应用…" else "应用 ${changes.size} 个文件") }
                }
            }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("应用这些修改？") }, text = { Text("将写入 ${vm.workspace?.name} 中的 ${changes.size} 个文件。应用前会检查文件是否已经变化。") }, confirmButton = { Button(onClick = { confirm = false; vm.applyChanges() }) { Text("确认应用") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("返回审阅") } })
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("丢弃待应用修改？") }, text = { Text("工作区现有文件不会改变。") }, confirmButton = { TextButton(onClick = { discard = false; vm.discardChanges() }) { Text("丢弃") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("取消") } })
}
