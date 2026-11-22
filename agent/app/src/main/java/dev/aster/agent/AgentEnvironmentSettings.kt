package dev.aster.agent

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aster.ide.runtime.BuildEnvironmentPresets
import dev.aster.ide.runtime.BuildEnvironmentSettings
import dev.aster.ide.runtime.RuntimePhase

/** Same build controls and private Termux installation used by the IDE. */
@Composable internal fun AgentEnvironmentSettings(vm: AgentViewModel) {
    val settings by vm.core.settings.flow.collectAsStateWithLifecycle()
    val runtime by vm.core.termux.state.collectAsStateWithLifecycle()
    val logs by vm.core.output.text.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var pending by remember { mutableStateOf<String?>(null) }
    var showLogs by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val motion = if (settings.animations && !settings.reducedMotion) 160 else 0
    AgentConfigurationPage(if (showLogs) "环境安装日志" else "编译环境", { if (showLogs) showLogs = false else vm.showEnvironment = false }, actions = {
        TextButton(onClick = { showLogs = !showLogs }) { Text(if (showLogs) "安装设置" else "安装日志") }
    }) {
        message?.let { text ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { message = null }) { Text("关闭") }
            }
        }
        if (vm.environmentInstalling) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (vm.environmentStatus.isNotBlank()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(vm.environmentStatus, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                if (vm.environmentInstalling)
                    TextButton(onClick = vm::cancel, enabled = !vm.stopping) { Text(if (vm.stopping) "正在停止" else "停止安装") }
            }
        }
        Crossfade(showLogs, Modifier.weight(1f).fillMaxWidth(), animationSpec = tween(motion), label = "environment") { logsVisible ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                if (logsVisible) {
                    Column(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
                        val scroll = rememberScrollState()
                        LaunchedEffect(logs) { if (vm.environmentInstalling) scroll.scrollTo(scroll.maxValue) }
                        SelectionContainerCompat(logs.ifBlank { "尚无安装日志" }, Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(20.dp))
                        TextButton(onClick = { clipboard.setText(AnnotatedString(logs)); message = "环境日志已复制" },
                            enabled = logs.isNotBlank(), modifier = Modifier.padding(horizontal = 12.dp)) { Text("复制日志") }
                    }
                } else Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)) {
                    BuildEnvironmentSettings(settings = settings, runtime = runtime, connected = runtime.phase == RuntimePhase.READY,
                        connecting = vm.busy && !vm.environmentInstalling, installing = vm.locked, directory = vm.core.termux.directory,
                        tools = vm.environmentTools, updateSettings = { change -> if (!vm.locked) vm.core.settings.update(change) },
                        onConnect = { vm.checkBuildEnvironment() }, onInstall = { pending = it }, onLogs = { showLogs = true },
                        onCopyDirectory = { clipboard.setText(AnnotatedString(vm.core.termux.directory)); message = "运行目录已复制" },
                        onMessage = { message = it }, port = vm.core.termux.port, showAutoRuntime = false)
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
    pending?.let { id ->
        val android = id == "android"
        val preset = BuildEnvironmentPresets.get(id)
        val title = preset?.title ?: if (android) "Android 原生 SDK / NDK" else "基础编译工具"
        val packages = if (android) "JDK ${settings.sdkJdk}、Gradle、Android API 35、Build Tools ${settings.sdkBuildTools}" + if (settings.sdkNdk == "0") "（跳过 NDK）" else "、NDK ${settings.sdkNdk}"
            else preset?.packages ?: "Python、Node.js、Git、ripgrep、OpenSSL、CA、Clang、Make、CMake、Ninja、unzip、zip"
        AgentDialog("安装 $title？", { pending = null },
            confirmButton = { Button(onClick = { pending = null; showLogs = true; vm.installBuildEnvironment(id) }, enabled = !vm.locked) { Text("安装") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("取消") } }) {
            Text("$packages\n\n安装到与 IDE 共用的应用私有 Termux 目录。" + if (android) "\nSDK / NDK 工具适用其原有许可条款。" else "")
        }
    }
}

@Composable private fun SelectionContainerCompat(text: String, modifier: Modifier) {
    androidx.compose.foundation.text.selection.SelectionContainer(modifier) {
        Text(text.takeLast(60000), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}
