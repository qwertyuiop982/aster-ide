package dev.aster.agent

import androidx.compose.ui.res.stringResource
import dev.aster.core.R as CoreR

import android.view.HapticFeedbackConstants
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
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
    val appView = LocalView.current
    val images = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(AgentAttachments.MAX_IMAGES)) {
        vm.receiveAttachments(it, photos = true)
    }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        vm.receiveAttachments(it, photos = false)
    }
    val enterPage = if (motion) fadeIn(tween(175)) else EnterTransition.None
    CompositionLocalProvider(LocalCodexAppearanceBrush provides nebulaBrush, dev.aster.core.LocalAsterDialogMotion provides motion) {
        MaterialTheme(colorScheme = colors, shapes = Shapes(extraSmall = RoundedCornerShape(12.dp), small = RoundedCornerShape(18.dp), medium = RoundedCornerShape(24.dp), large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(36.dp))) {
            Surface(if (nebulaBrush != null) Modifier.fillMaxSize().background(nebulaBrush) else Modifier.fillMaxSize(), color = if (nebulaBrush != null) Color.Transparent else MaterialTheme.colorScheme.surface) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                        val hasWorkspace = vm.workspace != null && conversation != null
                        key(vm.webRevision) {
                            AgentWebView(page, { request ->
                                vm.handle(request, chooser, pickAttachments = { photos ->
                                    if (photos) images.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    else documents.launch(arrayOf("*/*"))
                                }, bubbleHaptic = {
                                    appView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                })
                            }, Modifier.fillMaxSize(), vm.webPanelOpen, dark, vm::updateWebState, vm::noteWebDiagnostic)
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
                    AnimatedVisibility(vm.showEnvironment, enter = enterPage, exit = ExitTransition.None) { AgentEnvironmentSettings(vm) }
                    AnimatedVisibility(vm.showDiagnostics, enter = enterPage, exit = ExitTransition.None) { AgentDiagnosticsPanel(vm) }
                    AnimatedVisibility(vm.showWorkspaces, enter = enterPage, exit = ExitTransition.None) { WorkspaceChooser(vm) }
                    AnimatedVisibility(vm.reviewChanges, enter = enterPage, exit = ExitTransition.None) { ReviewChanges(vm, changes) }
                    AnimatedVisibility(vm.showContextFiles, enter = enterPage, exit = ExitTransition.None) { ContextFilePicker(vm) }
                }
            }
            state.userInput?.let { input ->
                dev.aster.ide.agent.AgentQuestionDialog(input, frame = { content, confirm, dismiss ->
                    AgentDialog(stringResource(CoreR.string.agent_question_title), onDismissRequest = {}, confirmButton = confirm, dismissButton = dismiss, content = content)
                }) { id, answers, dismissed -> vm.agent.answerQuestions(id, answers, dismissed) }
            }
            state.approval?.let { approval ->
                AgentDialog(stringResource(R.string.agent_approve_title), onDismissRequest = {},
                    confirmButton = { Button(onClick = { vm.decide(approval.requestId, true) }) { Text(stringResource(R.string.agent_approve_once)) } },
                    dismissButton = { TextButton(onClick = { vm.decide(approval.requestId, false) }) { Text(stringResource(R.string.agent_reject)) } }) {
                    Column {
                        Text("${engine.label} · ${vm.workspace?.name.orEmpty()}")
                        Spacer(Modifier.height(12.dp))
                        Text(approval.command, fontFamily = FontFamily.Monospace)
                        Text(if (approval.timeout == 0) stringResource(R.string.agent_approve_pty) else stringResource(R.string.agent_approve_timeout, approval.timeout), Modifier.padding(top = 12.dp))
                    }
                }
            }
            vm.pendingDelete?.let { conversation ->
                AgentDialog(stringResource(R.string.agent_delete_title), onDismissRequest = { vm.pendingDelete = null },
                    confirmButton = { TextButton(onClick = vm::confirmDelete, enabled = !vm.locked) { Text(stringResource(CoreR.string.action_delete), color = MaterialTheme.colorScheme.error) } },
                    dismissButton = { TextButton(onClick = { vm.pendingDelete = null }) { Text(stringResource(CoreR.string.action_cancel)) } }) {
                    Text(stringResource(R.string.agent_delete_explainer, conversation.title))
                }
            }
            vm.error?.let { message ->
                AgentDialog(stringResource(R.string.agent_message_title), onDismissRequest = { vm.error = null },
                    confirmButton = { TextButton(onClick = { vm.error = null }) { Text(stringResource(CoreR.string.action_ok)) } }) { Text(message) }
            }
        }
    }
}

@Composable private fun WorkspaceChooser(vm: AgentViewModel) {
    var name by remember { mutableStateOf("") }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::importWorkspace) }
    AgentConfigurationPage(stringResource(R.string.agent_workspace), { vm.showWorkspaces = false }) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = vm::detachWorkspace, enabled = !vm.locked, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.agent_workspace_unbound)) }
            dev.aster.ide.agent.AgentDirectoryAccessButton(!vm.locked, vm::openNativeWorkspace)
            if (vm.workspace?.safMirror == true)
                TextButton(onClick = vm::syncBoundWorkspace, enabled = !vm.locked) { Text(stringResource(R.string.agent_workspace_sync)) }
            Text(stringResource(R.string.agent_workspace_recent), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 24.dp))
            vm.workspaces.forEach { workspace ->
                TextButton(onClick = { vm.selectWorkspace(workspace.id) }, enabled = !vm.locked, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(workspace.name)
                        if (workspace.direct) Text(if (workspace.safMirror) stringResource(R.string.agent_workspace_saf) else stringResource(R.string.agent_workspace_direct),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (workspace.id == vm.workspace?.id) Text(stringResource(R.string.agent_workspace_current), style = MaterialTheme.typography.labelSmall)
                }
                HorizontalDivider()
            }
            if (vm.workspaces.isEmpty()) Text(stringResource(R.string.agent_workspace_empty), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.agent_workspace_new_import), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp))
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.agent_workspace_new_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { vm.createWorkspace(name) }, enabled = name.isNotBlank() && !vm.locked) { Text(stringResource(R.string.agent_workspace_create)) }
            TextButton(onClick = { import.launch(null) }, enabled = !vm.locked) { Text(stringResource(R.string.agent_workspace_import)) }
            Spacer(Modifier.height(24.dp))
        }
    }
}
