package dev.aster.agent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** This native panel is independent of both JavaScript and the runtime installation. */
@Composable internal fun AgentStartupPanel(vm: AgentViewModel, chooseMode: () -> Unit) {
    val failed = vm.webState.phase == AgentWebPhase.FAILED || vm.setupState.phase in setOf(AgentSetupPhase.FAILED, AgentSetupPhase.CANCELLED)
    Surface(LocalCodexAppearanceBrush.current?.let { Modifier.fillMaxSize().background(it) } ?: Modifier.fillMaxSize(), color = if (LocalCodexAppearanceBrush.current != null) Color.Transparent else MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Aster Agent", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { vm.showDiagnostics = true }) { Text("日志") }
                TextButton(onClick = { vm.showSettings = true }) { Text("设置") }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (!failed) CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 2.dp)
                    Text(if (failed) "暂未就绪" else "正在准备…", style = MaterialTheme.typography.titleLarge)
                    Text(when {
                        vm.webState.phase == AgentWebPhase.FAILED -> "界面未能打开，请重新加载。详情可在顶部日志查看。"
                        vm.setupState.error != null -> "环境准备未完成，可重试或查看顶部日志。"
                        vm.setupState.phase == AgentSetupPhase.CANCELLED -> "环境准备已停止"
                        vm.webState.phase == AgentWebPhase.LOADING -> vm.webState.message
                        else -> vm.setupState.phase.title
                    }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    if (vm.webState.phase != AgentWebPhase.READY) TextButton(onClick = vm::reloadWebPage) { Text("重新加载界面") }
                    if (!vm.busy && vm.setupState.phase != AgentSetupPhase.READY) Button(onClick = vm::initializeWorkspace) { Text("重试环境准备") }
                    if (vm.busy) TextButton(onClick = vm::cancel, enabled = !vm.stopping) { Text(if (vm.stopping) "正在停止…" else "停止准备") }
                    TextButton(onClick = chooseMode, enabled = !vm.locked) { Text("切换工作模式") }
                }
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
