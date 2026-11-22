package dev.aster.agent

import androidx.compose.ui.res.stringResource
import dev.aster.core.R as CoreR

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
                TextButton(onClick = { vm.showDiagnostics = true }) { Text(stringResource(R.string.agent_logs)) }
                TextButton(onClick = { vm.showSettings = true }) { Text(stringResource(CoreR.string.action_settings)) }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (!failed) CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 2.dp)
                    Text(if (failed) stringResource(R.string.agent_not_ready) else stringResource(R.string.agent_preparing), style = MaterialTheme.typography.titleLarge)
                    Text(when {
                        vm.webState.phase == AgentWebPhase.FAILED -> stringResource(R.string.agent_web_retry_prompt)
                        vm.setupState.error != null -> stringResource(R.string.agent_setup_retry_prompt)
                        vm.setupState.phase == AgentSetupPhase.CANCELLED -> stringResource(R.string.agent_setup_stopped)
                        vm.webState.phase == AgentWebPhase.LOADING -> vm.webState.message.ifBlank { stringResource(R.string.agent_web_loading) }
                        else -> stringResource(vm.setupState.phase.titleResource)
                    }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    if (vm.webState.phase != AgentWebPhase.READY) TextButton(onClick = vm::reloadWebPage) { Text(stringResource(R.string.agent_web_reload)) }
                    if (!vm.busy && vm.setupState.phase != AgentSetupPhase.READY) Button(onClick = vm::initializeWorkspace) { Text(stringResource(R.string.agent_setup_retry)) }
                    if (vm.busy) TextButton(onClick = vm::cancel, enabled = !vm.stopping) { Text(if (vm.stopping) stringResource(R.string.agent_stopping) else stringResource(R.string.agent_stop_setup)) }
                    TextButton(onClick = chooseMode, enabled = !vm.locked) { Text(stringResource(CoreR.string.action_switch_work_mode)) }
                }
            }
        }
    }
}

@Composable internal fun AgentEnvironmentStatus(vm: AgentViewModel) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(stringResource(R.string.agent_environment_status, stringResource(vm.setupState.phase.titleResource)), style = MaterialTheme.typography.labelLarge)
            vm.setupState.error?.let { Text(it, color = MaterialTheme.colorScheme.error, maxLines = 2, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { vm.showDiagnostics = true }) { Text(stringResource(R.string.agent_runtime_details)) }
                if (vm.busy) TextButton(onClick = vm::cancel, enabled = !vm.stopping) { Text(if (vm.stopping) stringResource(R.string.agent_stopping) else stringResource(R.string.agent_stop_setup)) }
                else TextButton(onClick = vm::initializeWorkspace, enabled = !vm.locked) { Text(stringResource(R.string.agent_runtime_retry)) }
            }
        }
    }
}
