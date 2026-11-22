package dev.aster.ide.agent

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.aster.ide.data.AgentStorageAccess
import dev.aster.ide.data.IdeSettings

@Composable
fun AgentDirectoryAccessButton(enabled: Boolean, onDirectory: (Uri) -> Unit) {
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(onDirectory) }
    TextButton(onClick = { folder.launch(null) }, enabled = enabled) { Text("绑定工作目录") }
}

@Composable
private fun SharedStorageAccess(enabled: Boolean) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(AgentStorageAccess.granted(context)) }
    var error by remember { mutableStateOf(false) }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        granted = AgentStorageAccess.granted(context)
    }
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = AgentStorageAccess.granted(context)
    }
    if (!granted) TextButton(enabled = enabled, onClick = {
        error = false
        if (Build.VERSION.SDK_INT >= 30)
            runCatching { settings.launch(AgentStorageAccess.permissionIntent(context)) }.onFailure { error = true }
        else legacy.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
    }) { Text("允许直接访问设备存储") }
    if (error) Text("无法打开系统授权页面", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

@Composable
fun AgentPermissionControls(settings: IdeSettings, enabled: Boolean,
    onMode: (String) -> Unit, onUnchecked: (Boolean) -> Unit, onDirectory: (Uri) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("工具权限", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(settings.agentPermissionMode == "review", { onMode("review") }, enabled = enabled, label = { Text("审阅") })
            FilterChip(settings.agentPermissionMode == "native", { onMode("native") }, enabled = enabled, label = { Text("原生") })
        }
        if (settings.agentPermissionMode == "native") {
            Text("工具从项目目录启动。只能通过文件选择器访问的目录使用挂载镜像，任务结束后写回。", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("命令无审查", Modifier.weight(1f))
                Switch(settings.agentNativeCommandsUnchecked, onUnchecked, enabled = enabled)
            }
            Text(if (settings.agentNativeCommandsUnchecked) "命令及终端输入直接执行；主代理和子代理继承此设置。" else "命令及终端输入需确认；其他原生工具直接执行。", style = MaterialTheme.typography.bodySmall)
            AgentDirectoryAccessButton(enabled, onDirectory)
            SharedStorageAccess(enabled)
        } else Text("在工作副本中提出修改，应用前可预览；命令需要确认。", style = MaterialTheme.typography.bodySmall)
    }
}
