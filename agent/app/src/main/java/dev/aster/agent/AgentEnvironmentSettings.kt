package dev.aster.agent

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    AgentConfigurationPage(if (showLogs) "环境安装日志" else "编译环境", { if (showLogs) showLogs = false else vm.showEnvironment = false }, actions = {
        TextButton(onClick = { showLogs = !showLogs }) { Text(if (showLogs) "安装设置" else "安装日志") }
    }) {
        message?.let { text ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { message = null }) { Text("关闭") }
            }
        }
                if (vm.environmentInstalling) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(vm.environmentStatus, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = vm::cancel, enabled = !vm.stopping) { Text(if (vm.stopping) "正在停止" else "停止安装") }
                    }
                } else if (vm.environmentStatus.isNotBlank()) {
                    Text(vm.environmentStatus, Modifier.padding(horizontal = 20.dp, vertical = 10.dp), style = MaterialTheme.typography.bodySmall)
                }
                if (showLogs) {
                    val scroll = rememberScrollState()
                    LaunchedEffect(logs) { scroll.scrollTo(scroll.maxValue) }
                    SelectionContainerCompat(logs.ifBlank { "尚无安装日志。选择基础编译工具或 Android SDK / NDK 后开始安装。" }, Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(20.dp))
                    TextButton(onClick = { clipboard.setText(AnnotatedString(logs)); message = "环境日志已复制" }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("复制日志") }
                } else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                    Text("与 IDE 共用已安装工具、SDK 路径和软件源。编译前可在这里准备环境。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp))
                    BuildEnvironmentSettings(
                        settings = settings, runtime = runtime, connected = runtime.phase == RuntimePhase.READY,
                        connecting = vm.busy && !vm.environmentInstalling, installing = vm.locked,
                        directory = vm.core.termux.directory, tools = vm.environmentTools,
                        updateSettings = { change -> if (!vm.locked) vm.core.settings.update(change) },
                        onConnect = { vm.checkBuildEnvironment() }, onInstall = { pending = it }, onLogs = { showLogs = true },
                        onCopyDirectory = { clipboard.setText(AnnotatedString(vm.core.termux.directory)); message = "运行目录已复制" },
                        onPort = { vm.changeEnvironmentPort(it) }, onMessage = { message = it }
                    )
                    Spacer(Modifier.height(32.dp))
                }
    }
    pending?.let { id ->
        val android = id == "android"
        val title = if (android) "Android 原生 SDK / NDK" else "基础编译工具"
        val packages = if (android) "JDK ${settings.sdkJdk}、Gradle、Android API 35、Build Tools ${settings.sdkBuildTools}" + if (settings.sdkNdk == "0") "（跳过 NDK）" else "、NDK ${settings.sdkNdk}"
            else "Python、Node.js、Git、ripgrep、OpenSSL、CA、Clang、Make、CMake、Ninja、unzip、zip"
        AlertDialog(onDismissRequest = { pending = null }, title = { Text("安装 $title？") }, text = {
            Text("$packages\n\n安装到与 IDE 共用的应用私有 Termux 目录。" + if (android) "\nSDK / NDK 工具适用其原有许可条款。" else "")
        }, confirmButton = { Button(onClick = { pending = null; showLogs = true; vm.installBuildEnvironment(id) }, enabled = !vm.locked) { Text("安装") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("取消") } })
    }
}

@Composable private fun SelectionContainerCompat(text: String, modifier: Modifier) {
    androidx.compose.foundation.text.selection.SelectionContainer(modifier) {
        Text(text.takeLast(60000), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}