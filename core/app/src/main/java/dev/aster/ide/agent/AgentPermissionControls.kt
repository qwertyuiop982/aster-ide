package dev.aster.ide.agent

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
    val context = LocalContext.current
    var message by remember { mutableStateOf("") }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(onDirectory) }
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (AgentStorageAccess.granted(context)) folder.launch(null) else message = "文件访问权限未授予，可再次授权。"
    }
    val storage = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (AgentStorageAccess.granted(context)) folder.launch(null) else message = "请开启“允许管理所有文件”，以便命令直接读写项目目录。"
    }
    TextButton(onClick = {
        message = ""
        if (AgentStorageAccess.granted(context)) folder.launch(null)
        else if (Build.VERSION.SDK_INT >= 30) {
            val intent = AgentStorageAccess.permissionIntent(context)
            runCatching { storage.launch(intent) }.onFailure {
                runCatching { storage.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                    .onFailure { message = "无法打开文件授权页面，请在系统设置中授予 Aster 文件访问权限。" }
            }
        } else legacy.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
    }, enabled = enabled) { Text("授权并打开原目录") }
    if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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
            Text("工具直接执行，项目在原目录修改，不创建项目副本或备份。", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("命令无审查", Modifier.weight(1f))
                Switch(settings.agentNativeCommandsUnchecked, onUnchecked, enabled = enabled)
            }
            Text(if (settings.agentNativeCommandsUnchecked) "命令及终端输入直接执行；主代理和子代理继承此设置。" else "命令及终端输入需确认；其他原生工具直接执行。", style = MaterialTheme.typography.bodySmall)
            AgentDirectoryAccessButton(enabled, onDirectory)
        } else Text("在工作副本中提出修改，应用前可预览；命令需要确认。", style = MaterialTheme.typography.bodySmall)
    }
}
