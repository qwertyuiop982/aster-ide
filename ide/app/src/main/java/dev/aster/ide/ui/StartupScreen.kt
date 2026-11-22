package dev.aster.ide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aster.ide.IdeViewModel
import dev.aster.ide.InfoSheet
import dev.aster.ide.feature.R

@Composable fun StartupScreen(vm: IdeViewModel) {
    val context = LocalContext.current
    val runtime by vm.termux.state.collectAsStateWithLifecycle()
    val motion = LocalMotion.current
    val lines by vm.output.lines.collectAsStateWithLifecycle()
    val failure = vm.startupError ?: runtime.error
    val running = vm.connecting || vm.preparingEditor || !vm.workspaceLoaded
    val status = stringResource(when {
        !vm.workspaceLoaded -> R.string.startup_read_workspace
        vm.preparingEditor -> R.string.startup_prepare_editor
        failure != null -> R.string.startup_environment_incomplete
        else -> R.string.startup_prepare_workbench
    })
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Code, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("Aster", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.startup_opening_workspace), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(28.dp))
            if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            AnimatedContent(status, transitionSpec = { fadeIn(tween(motion.standard)) togetherWith fadeOut(tween(motion.quick)) }, label = "startupStage") { message ->
                Text(message, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf(R.string.startup_workspace to vm.workspaceLoaded, R.string.startup_runtime to (vm.connected && !vm.connecting), R.string.settings_editor to vm.editorReady).forEach { (label, done) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (done) { Icon(Icons.Outlined.CheckCircle, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(5.dp)) }
                        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.startup_apt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.startup_background_work), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (failure != null) {
                Spacer(Modifier.height(16.dp))
                Text(failure.take(500), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (lines.any { it.isNotBlank() }) {
                Spacer(Modifier.height(18.dp))
                Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                    Text(lines.filter(String::isNotBlank).takeLast(4).joinToString("\n"), modifier = Modifier.padding(12.dp), fontFamily = Mono, style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { vm.info = InfoSheet(context.getString(R.string.runtime_install_logs), vm.output.text.value.takeLast(18000)) }) { Text(stringResource(R.string.startup_view_logs)) }
            }
            Spacer(Modifier.height(16.dp))
            if (!running && vm.workspaceLoaded) Button(onClick = { vm.connectRuntime() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.startup_retry)) }
            TextButton(onClick = { vm.continueLocally() }, enabled = vm.workspaceLoaded && !vm.preparingEditor) { Text(stringResource(if (vm.connecting) R.string.startup_cancel_and_edit else R.string.startup_continue_editing)) }
        }
    }
}
