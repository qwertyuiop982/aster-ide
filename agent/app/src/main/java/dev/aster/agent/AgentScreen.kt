package dev.aster.agent

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aster.core.LocalWorkModeChooser
import dev.aster.ide.agent.AgentEngine

@Composable fun AgentScreen(vm: AgentViewModel) {
    val engine by vm.agent.selectedEngine.collectAsStateWithLifecycle()
    val state by vm.agent.state.collectAsStateWithLifecycle()
    val page by vm.page.collectAsStateWithLifecycle()
    val conversation by vm.agent.conversation.collectAsStateWithLifecycle()
    val changes by vm.agent.changes.collectAsStateWithLifecycle()
    val chooser = LocalWorkModeChooser.current
    val dark = engine == AgentEngine.PI
    val accent = when (engine) { AgentEngine.OPENCLAUDE -> Color(0xFFA94E25); AgentEngine.CODEX -> Color(0xFF007A83); AgentEngine.GEMINI -> Color(0xFF245BCC); else -> Color.White }
    val colors = if (dark) darkColorScheme(primary = Color.White, onPrimary = Color.Black, surface = Color.Black, background = Color.Black)
        else lightColorScheme(primary = accent, onPrimary = Color.White, surface = Color.White, background = Color.White)
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                val hasWorkspace = vm.workspace != null && conversation != null
                Column(Modifier.fillMaxSize()) {
                    if (hasWorkspace && vm.webState.phase == AgentWebPhase.READY && vm.setupState.phase != AgentSetupPhase.READY) {
                        AgentEnvironmentStatus(vm)
                    }
                    if (hasWorkspace) key(vm.webRevision) {
                        AgentWebView(page, { vm.handle(it, chooser) }, Modifier.fillMaxWidth().weight(1f), vm.webPanelOpen, dark, vm::updateWebState, vm::noteWebDiagnostic)
                    }
                }
                val startupVisible = !hasWorkspace || vm.webState.phase != AgentWebPhase.READY
                if (startupVisible) {
                    BackHandler { if (vm.locked) vm.cancel() else chooser() }
                    AgentStartupPanel(vm, chooser)
                }
            }
        }
        state.approval?.let { approval ->
            AlertDialog(onDismissRequest = {}, title = { Text("允许运行命令？") }, text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("${engine.label} · ${vm.workspace?.name.orEmpty()}")
                    Spacer(Modifier.height(12.dp))
                    Text(approval.command, fontFamily = FontFamily.Monospace)
                    Text("最长 ${approval.timeout} 秒", Modifier.padding(top = 12.dp))
                }
            }, confirmButton = { Button(onClick = { vm.decide(approval.requestId, true) }) { Text("允许本次") } },
                dismissButton = { TextButton(onClick = { vm.decide(approval.requestId, false) }) { Text("拒绝") } })
        }
        if (vm.showSettings) ModelSettings(vm, engine)
        if (vm.showDiagnostics) AgentDiagnosticsPanel(vm)
        if (vm.showWorkspaces) WorkspaceChooser(vm)
        if (vm.reviewChanges) ReviewChanges(vm, changes)
        if (vm.showContextFiles) ContextFilePicker(vm)
        vm.pendingDelete?.let { conversation ->
            AlertDialog(onDismissRequest = { vm.pendingDelete = null }, title = { Text("删除会话？") },
                text = { Text(conversation.title + "\n此操作将删除本地会话记录。") },
                confirmButton = { TextButton(onClick = vm::confirmDelete, enabled = !vm.locked) { Text("删除", color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TextButton(onClick = { vm.pendingDelete = null }) { Text("取消") } })
        }
        vm.error?.let { message -> AlertDialog(onDismissRequest = { vm.error = null }, title = { Text("提示") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { vm.error = null }) { Text("知道了") } }) }
    }
}

@Composable private fun WorkspaceChooser(vm: AgentViewModel) {
    var name by remember { mutableStateOf("") }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::importWorkspace) }
    AlertDialog(onDismissRequest = { vm.showWorkspaces = false }, title = { Text("工作区") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.workspaces.forEach { workspace -> TextButton(onClick = { vm.selectWorkspace(workspace.id) }, enabled = !vm.locked) { Text(workspace.name + if (workspace.id == vm.workspace?.id) " · 当前" else "") } }
            HorizontalDivider()
            OutlinedTextField(name, { name = it }, label = { Text("新工作区名称") }, singleLine = true)
            TextButton(onClick = { import.launch(null) }, enabled = !vm.locked) { Text("从设备目录导入") }
        }
    }, confirmButton = { Button(onClick = { vm.createWorkspace(name) }, enabled = name.isNotBlank() && !vm.locked) { Text("创建") } },
        dismissButton = { TextButton(onClick = { vm.showWorkspaces = false }) { Text("关闭") } })
}
