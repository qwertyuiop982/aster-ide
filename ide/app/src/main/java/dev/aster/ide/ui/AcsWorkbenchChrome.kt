/* ACS layout adapted from Android Code Studio / AndroidIDE, GPL-3.0-or-later. */
package dev.aster.ide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aster.ide.IdeViewModel

@Composable internal fun AcsHeader(vm: IdeViewModel, compact: Boolean, onBuild: () -> Unit, menuContent: @Composable (dismiss: () -> Unit) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(if (compact) 48.dp else 64.dp).background(MaterialTheme.colorScheme.surface).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ToolButton(Icons.Outlined.Menu, "文件", vm.showSidebar && vm.sidebar == "files", size = 40) {
            vm.showSidebar = if (vm.sidebar == "files") !vm.showSidebar else true
            vm.sidebar = "files"
        }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp).clickable { vm.commandPalette = true }) {
            Text(vm.workspace?.name ?: "Aster", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!compact) Text(vm.active?.name ?: "Android Code Studio 样式", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ToolButton(Icons.Outlined.Save, "保存", enabled = vm.active != null, size = 36) { vm.save() }
        ToolButton(if (vm.buildRunning) Icons.Outlined.Stop else Icons.Outlined.PlayArrow, if (vm.buildRunning) "停止任务" else "构建与运行", tint = if (vm.buildRunning) Coral else MaterialTheme.colorScheme.primary, size = 36) { if (vm.buildRunning) vm.cancelBuild() else onBuild() }
        Box {
            ToolButton(Icons.Outlined.MoreVert, "项目菜单", size = 36) { menu = true }
            DropdownMenu(menu, { menu = false }, modifier = Modifier.widthIn(min = 210.dp)) { menuContent { menu = false } }
        }
    }
}

@Composable internal fun WorkbenchFeatureMenu(vm: IdeViewModel, dismiss: () -> Unit) {
    DropdownMenuItem(text = { Text("文件") }, leadingIcon = { Icon(Icons.Outlined.FolderCopy, null) }, onClick = { vm.sidebar = "files"; vm.showSidebar = true; dismiss() })
    DropdownMenuItem(text = { Text("项目搜索") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, onClick = { vm.sidebar = "search"; vm.showSidebar = true; dismiss() })
    DropdownMenuItem(text = { Text("Agent 工作空间") }, leadingIcon = { Icon(Icons.Outlined.SmartToy, null) }, onClick = { vm.openAgent(); dismiss() })
    WorkbenchPanels.forEach { panel ->
        DropdownMenuItem(text = { Text(panel.menuTitle) }, leadingIcon = { Icon(panel.icon, null) }, onClick = { vm.panel = panel.id; vm.showPanel = true; dismiss() })
    }
    DropdownMenuItem(text = { Text("语言服务") }, leadingIcon = { Icon(Icons.Outlined.DataObject, null) }, onClick = { vm.settingsPage = "lsp"; dismiss() })
    DropdownMenuItem(text = { Text("编译环境") }, leadingIcon = { Icon(Icons.Outlined.Terminal, null) }, onClick = { vm.settingsPage = "runtime"; dismiss() })
}

@Composable internal fun AcsToolShelf(vm: IdeViewModel, compact: Boolean) {
    val threshold = with(LocalDensity.current) { 22.dp.toPx() }
    var travel by remember { mutableFloatStateOf(0f) }
    Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(0.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Column {
            Box(Modifier.fillMaxWidth().height(12.dp).pointerInput(vm, threshold) {
                detectVerticalDragGestures(onDragStart = { travel = 0f }, onVerticalDrag = { change, delta -> change.consume(); travel += delta }, onDragEnd = {
                    if (travel < -threshold) vm.showPanel = true
                    if (travel > threshold) vm.showPanel = false
                })
            }.clickable { vm.showPanel = !vm.showPanel }, contentAlignment = Alignment.Center) {
                Box(Modifier.width(32.dp).height(3.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f), RoundedCornerShape(3.dp)))
            }
            Row(Modifier.fillMaxWidth().height(if (compact) 34.dp else 40.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = vm::openAgent, shape = RoundedCornerShape(4.dp), contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Icon(Icons.Outlined.SmartToy, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("Agent")
                }
                WorkbenchPanels.forEach { panel ->
                    val selected = vm.showPanel && vm.panel == panel.id
                    TextButton(onClick = { vm.showPanel = if (vm.panel == panel.id) !vm.showPanel else true; vm.panel = panel.id }, shape = RoundedCornerShape(4.dp), colors = ButtonDefaults.textButtonColors(containerColor = if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent, contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary), contentPadding = PaddingValues(horizontal = 10.dp), modifier = Modifier.fillMaxHeight()) {
                        Icon(panel.icon, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(panel.title, style = MaterialTheme.typography.labelMedium)
                    }
                }
                ToolButton(Icons.Outlined.Settings, "设置", size = 32) { vm.settingsPage = "editor" }
                ToolButton(Icons.Outlined.DataObject, "语言服务", size = 32) { vm.settingsPage = "lsp" }
            }
        }
    }
}
