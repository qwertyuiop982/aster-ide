package dev.aster.agent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable internal fun AgentDiagnosticsPanel(vm: AgentViewModel) {
    val output by vm.core.output.text.collectAsStateWithLifecycle()
    val engines by vm.agent.availableEngines.collectAsStateWithLifecycle()
    val context by vm.agent.contextUsage.collectAsStateWithLifecycle()
    val runtime by vm.core.termux.state.collectAsStateWithLifecycle()
    val settings by vm.settings.flow.collectAsStateWithLifecycle()
    val text = remember(output, engines, context, runtime, settings, vm.setupState, vm.webState, vm.webDiagnostics, vm.runtimeInventory) { vm.diagnosticsText() }
    AgentConfigurationPage("运行详情与诊断", { vm.showDiagnostics = false }, actions = {
        TextButton(onClick = vm::copyStartupDiagnostics) { Text("复制") }
    }) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("仅在本机显示；复制为脱敏文本，不上传服务器。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer { Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
