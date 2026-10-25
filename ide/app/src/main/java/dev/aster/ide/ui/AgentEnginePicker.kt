package dev.aster.ide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aster.ide.IdeViewModel
import dev.aster.ide.agent.AgentEngine

@Composable internal fun AgentEnginePicker(vm: IdeViewModel, modifier: Modifier = Modifier, compact: Boolean = false) {
    val engine by vm.agent.selectedEngine.collectAsStateWithLifecycle()
    val state by vm.agent.state.collectAsStateWithLifecycle()
    val availability by vm.agent.availableEngines.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    val idle = !state.running && !vm.agentPreparing && !vm.agentStopping
    LaunchedEffect(vm.connected) { if (vm.connected) vm.refreshAgentEngines() }
    Box(modifier) {
        TextButton(onClick = { expanded = true; vm.refreshAgentEngines() }, enabled = idle, contentPadding = PaddingValues(horizontal = if (compact) 0.dp else 8.dp)) {
            if (!compact) { Icon(Icons.Outlined.SmartToy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
            Text(engine.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, false))
            Icon(Icons.Outlined.ExpandMore, "选择 Agent 引擎", Modifier.size(18.dp))
        }
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.widthIn(min = 240.dp, max = 320.dp)) {
            AgentEngine.entries.forEach { candidate ->
                val runtime = availability.firstOrNull { it.id == candidate.id }
                DropdownMenuItem(text = {
                    Column {
                        Text(candidate.label + " · " + candidate.version)
                        Text(candidate.apiLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (runtime != null) Text(if (runtime.available) "运行环境已就绪" else runtime.reason, style = MaterialTheme.typography.labelSmall,
                            color = if (runtime.available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    }
                }, leadingIcon = { if (candidate == engine) Icon(Icons.Outlined.Check, "当前引擎") else Spacer(Modifier.size(24.dp)) },
                    enabled = idle, onClick = { expanded = false; vm.selectAgentEngine(candidate) })
            }
        }
    }
}
