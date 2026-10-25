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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aster.ide.IdeViewModel
import dev.aster.ide.InfoSheet
import dev.aster.ide.data.IdeSettings

@Composable fun StartupScreen(vm: IdeViewModel, settings: IdeSettings) {
    val runtime by vm.termux.state.collectAsStateWithLifecycle()
    val motion = LocalMotion.current
    val lines by vm.output.lines.collectAsStateWithLifecycle()
    val failure = vm.startupError ?: runtime.error
    val running = vm.connecting || vm.preparingEditor || !vm.workspaceLoaded
    val status = when {
        !vm.workspaceLoaded -> "读取工作区…"
        vm.preparingEditor -> "准备编辑器…"
        failure != null -> "环境准备未完成"
        else -> runtime.message
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Code, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("Aster", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text("先准备运行环境，再进入工作台", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(28.dp))
            if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            AnimatedContent(status, transitionSpec = { fadeIn(tween(motion.standard)) togetherWith fadeOut(tween(motion.quick)) }, label = "startupStage") { message ->
                Text(message, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("工作区" to vm.workspaceLoaded, "运行环境" to (vm.connected && !vm.connecting), "编辑器" to vm.editorReady).forEach { (label, done) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (done) { Icon(Icons.Outlined.CheckCircle, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(5.dp)) }
                        Text(label, style = MaterialTheme.typography.labelSmall, color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(if (settings.termuxMirror == "ustc") "软件源：中科大 USTC" else "软件源：当前配置", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text("首次启动需要安装 Python 桥接。完成后会复用已安装的环境。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (failure != null) {
                Spacer(Modifier.height(16.dp))
                Text(failure.take(500), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (lines.any { it.isNotBlank() }) {
                Spacer(Modifier.height(18.dp))
                Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                    Text(lines.filter(String::isNotBlank).takeLast(4).joinToString("\n"), modifier = Modifier.padding(12.dp), fontFamily = Mono, style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { vm.info = InfoSheet("环境安装日志", vm.output.text.value.takeLast(18000)) }) { Text("查看安装日志") }
            }
            Spacer(Modifier.height(16.dp))
            if (!running && vm.workspaceLoaded) Button(onClick = { vm.connectRuntime() }, modifier = Modifier.fillMaxWidth()) { Text("重试环境准备") }
            TextButton(onClick = { vm.continueLocally() }, enabled = vm.workspaceLoaded && !vm.preparingEditor) { Text(if (vm.connecting) "取消准备，继续本地编辑" else "继续本地编辑") }
        }
    }
}