package dev.aster.agent

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
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
    val appearance by vm.settings.flow.collectAsStateWithLifecycle()
    val nebula = engine == AgentEngine.CODEX && appearance.agentCodexNebulaAll
    val dark = engine == AgentEngine.PI || nebula && codexAppearanceDark(appearance.agentCodexNebulaLeft, appearance.agentCodexNebulaRight)
    val accent = when (engine) { AgentEngine.OPENCLAUDE -> Color(0xFFA94E25); AgentEngine.CODEX -> Color(0xFF007A83); AgentEngine.GEMINI -> Color(0xFF245BCC); else -> Color.White }
    val colors = if (nebula) codexAppearanceColors(appearance.agentCodexNebulaLeft, appearance.agentCodexNebulaRight) else if (dark) darkColorScheme(primary = Color.White, onPrimary = Color.Black, surface = Color.Black, background = Color.Black)
        else lightColorScheme(primary = accent, onPrimary = Color.White, surface = Color.White, background = Color.White)
    val nebulaBrush = if (nebula) Brush.horizontalGradient(listOf(codexAppearanceColor(appearance.agentCodexNebulaLeft), codexAppearanceColor(appearance.agentCodexNebulaRight, dev.aster.ide.agent.AgentAppearance.DEFAULT_RIGHT))) else null
    val preferences by vm.core.settings.flow.collectAsStateWithLifecycle()
    val motion = preferences.animations && !preferences.reducedMotion
    CompositionLocalProvider(LocalCodexAppearanceBrush provides nebulaBrush) {
        MaterialTheme(colorScheme = colors, shapes = Shapes(extraSmall = RoundedCornerShape(12.dp), small = RoundedCornerShape(18.dp), medium = RoundedCornerShape(24.dp), large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(36.dp))) {
            Surface(if (nebulaBrush != null) Modifier.fillMaxSize().background(nebulaBrush) else Modifier.fillMaxSize(), color = if (nebulaBrush != null) Color.Transparent else MaterialTheme.colorScheme.surface) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                        val hasWorkspace = vm.workspace != null && conversation != null
                        key(vm.webRevision) {
                            AgentWebView(page, { vm.handle(it, chooser) }, Modifier.fillMaxSize(), vm.webPanelOpen, dark, vm::updateWebState, vm::noteWebDiagnostic)
                        }
                        val startupVisible = vm.webState.phase != AgentWebPhase.READY || (!hasWorkspace && vm.setupState.phase in setOf(AgentSetupPhase.FAILED, AgentSetupPhase.CANCELLED))
                        if (startupVisible) {
                            BackHandler { if (vm.locked) vm.cancel() else chooser() }
                            AgentStartupPanel(vm, chooser)
                        }
                    }
                    AnimatedVisibility(visible = vm.showSettings, enter = if (motion) fadeIn(tween(160)) + slideInHorizontally(tween(200)) { it / 12 } else EnterTransition.None, exit = if (motion) fadeOut(tween(120)) else ExitTransition.None) { ModelSettings(vm, engine) }
                    AnimatedVisibility(visible = vm.showAppearance, enter = if (motion) fadeIn(tween(160)) + slideInHorizontally(tween(200)) { it / 12 } else EnterTransition.None, exit = if (motion) fadeOut(tween(120)) else ExitTransition.None) { AgentAppearanceSettings(vm) }
                    AnimatedVisibility(visible = vm.showLongTaskInfo, enter = if (motion) fadeIn(tween(160)) + slideInHorizontally(tween(200)) { it / 12 } else EnterTransition.None, exit = if (motion) fadeOut(tween(120)) else ExitTransition.None) { AgentLongTaskInfoScreen(vm) }
                    if (vm.showEnvironment) AgentEnvironmentSettings(vm)
                    if (vm.showDiagnostics) AgentDiagnosticsPanel(vm)
                    if (vm.showWorkspaces) WorkspaceChooser(vm)
                    if (vm.reviewChanges) ReviewChanges(vm, changes)
                    if (vm.showContextFiles) ContextFilePicker(vm)
                }
            }
            state.userInput?.let { input ->
                dev.aster.ide.agent.AgentQuestionDialog(input) { id, answers, dismissed -> vm.agent.answerQuestions(id, answers, dismissed) }
            }
            state.approval?.let { approval ->
                AlertDialog(onDismissRequest = {}, title = { Text("允许本次操作？") }, text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text("${engine.label} · ${vm.workspace?.name.orEmpty()}")
                        Spacer(Modifier.height(12.dp))
                        Text(approval.command, fontFamily = FontFamily.Monospace)
                        Text(if (approval.timeout == 0) "持久交互终端 · 会话保留至关闭或环境停止" else "最长 ${approval.timeout} 秒", Modifier.padding(top = 12.dp))
                    }
                }, confirmButton = { Button(onClick = { vm.decide(approval.requestId, true) }) { Text("允许本次") } },
                    dismissButton = { TextButton(onClick = { vm.decide(approval.requestId, false) }) { Text("拒绝") } })
            }
            vm.pendingDelete?.let { conversation ->
                AlertDialog(onDismissRequest = { vm.pendingDelete = null }, title = { Text("删除会话？") },
                    text = { Text(conversation.title + "\n此操作将删除本地会话记录。") },
                    confirmButton = { TextButton(onClick = vm::confirmDelete, enabled = !vm.locked) { Text("删除", color = MaterialTheme.colorScheme.error) } },
                    dismissButton = { TextButton(onClick = { vm.pendingDelete = null }) { Text("取消") } })
            }
            vm.error?.let { message -> AlertDialog(onDismissRequest = { vm.error = null }, title = { Text("提示") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { vm.error = null }) { Text("知道了") } }) }
        }
    }
}

@Composable private fun WorkspaceChooser(vm: AgentViewModel) {
    var name by remember { mutableStateOf("") }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::importWorkspace) }
    AgentConfigurationPage("工作区", { vm.showWorkspaces = false }) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("最近工作区", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 24.dp))
            vm.workspaces.forEach { workspace ->
                TextButton(onClick = { vm.selectWorkspace(workspace.id) }, enabled = !vm.locked, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 12.dp)) {
                    Text(workspace.name, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Start)
                    if (workspace.id == vm.workspace?.id) Text("当前", style = MaterialTheme.typography.labelSmall)
                }
                HorizontalDivider()
            }
            if (vm.workspaces.isEmpty()) Text("还没有工作区", style = MaterialTheme.typography.bodySmall)
            Text("创建与导入", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp))
            OutlinedTextField(name, { name = it }, label = { Text("新工作区名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { vm.createWorkspace(name) }, enabled = name.isNotBlank() && !vm.locked) { Text("创建工作区") }
            TextButton(onClick = { import.launch(null) }, enabled = !vm.locked) { Text("导入项目副本") }
            dev.aster.ide.agent.AgentDirectoryAccessButton(!vm.locked, vm::openNativeWorkspace)
            Spacer(Modifier.height(24.dp))
        }
    }
}
