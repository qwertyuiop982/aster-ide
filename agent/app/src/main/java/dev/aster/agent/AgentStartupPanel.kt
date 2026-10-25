package dev.aster.agent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** This native panel is independent of both JavaScript and the runtime installation. */
@Composable internal fun AgentStartupPanel(vm: AgentViewModel, chooseMode: () -> Unit) {
    val runtime by vm.core.termux.state.collectAsStateWithLifecycle()
    val output by vm.core.output.text.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 620.dp).fillMaxHeight().verticalScroll(scroll).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Spacer(Modifier.height(8.dp))
                Text("Aster Agent", style = MaterialTheme.typography.headlineSmall)
                Text(if (vm.webState.phase == AgentWebPhase.FAILED) "界面加载未完成" else vm.setupState.phase.title, style = MaterialTheme.typography.titleLarge)
                if (vm.busy || vm.webState.phase == AgentWebPhase.LOADING) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("环境 · ${vm.setupState.phase.title}")
                if (vm.setupState.phase == AgentSetupPhase.TERMUX) Text(runtime.message, style = MaterialTheme.typography.bodyMedium)
                if (vm.setupState.phase == AgentSetupPhase.NODE) Text("正在安装内置 Node.js 及 Agent 依赖。首次准备需要网络；已有环境会直接复用。")
                Text("界面 · ${vm.webState.message}", color = if (vm.webState.phase == AgentWebPhase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                vm.setupState.stoppedAt?.let { Text("停止于：${it.title}") }
                vm.setupState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (vm.busy) Button(onClick = vm::cancel, enabled = !vm.stopping) { Text(if (vm.stopping) "正在停止…" else "停止准备") }
                if (vm.webState.phase == AgentWebPhase.FAILED) Button(onClick = vm::reloadWebPage) { Text("重新加载界面") }
                if (!vm.busy && vm.setupState.phase != AgentSetupPhase.READY) Button(onClick = vm::initializeWorkspace) { Text("重试环境准备") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.showSettings = true }) { Text("模型设置") }
                    TextButton(onClick = chooseMode, enabled = !vm.locked) { Text("切换工作模式") }
                }
                TextButton(onClick = { vm.showDiagnostics = true }) { Text("查看运行详情") }
                TextButton(onClick = vm::copyStartupDiagnostics) { Text("复制脱敏启动诊断") }
                val details = AgentDiagnostics.redact((vm.webDiagnostics + "\n" + output.takeLast(12000)).trim())
                if (details.isNotBlank()) {
                    HorizontalDivider()
                    Text("启动日志", style = MaterialTheme.typography.titleSmall)
                    SelectionContainer {
                        Text(details, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()))
                    }
                }
                Text(AgentStartup.REVISION, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable internal fun AgentEnvironmentStatus(vm: AgentViewModel) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text("环境 · ${vm.setupState.phase.title}", style = MaterialTheme.typography.labelLarge)
            vm.setupState.error?.let { Text(it, color = MaterialTheme.colorScheme.error, maxLines = 2, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { vm.showDiagnostics = true }) { Text("运行详情") }
                if (vm.busy) TextButton(onClick = vm::cancel, enabled = !vm.stopping) { Text(if (vm.stopping) "正在停止…" else "停止准备") }
                else TextButton(onClick = vm::initializeWorkspace, enabled = !vm.locked) { Text("重试环境") }
            }
        }
    }
}
