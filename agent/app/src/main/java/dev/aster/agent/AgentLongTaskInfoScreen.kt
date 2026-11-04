package dev.aster.agent

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** A full-screen destination; the conversation stays mounted underneath it. */
@Composable internal fun AgentLongTaskInfoScreen(vm: AgentViewModel) {
    val settings by vm.settings.flow.collectAsStateWithLifecycle()
    val enabled = settings.agentLongTaskMode
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val close = { vm.showLongTaskInfo = false }
    BackHandler(onBack = close)
    LaunchedEffect(Unit) {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }
    val brush = LocalCodexAppearanceBrush.current
    Surface(if (brush != null) Modifier.fillMaxSize().background(brush) else Modifier.fillMaxSize(), color = if (brush != null) Color.Transparent else MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconButton(onClick = close) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回对话") }
                Text("长任务模式", style = MaterialTheme.typography.titleLarge)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(horizontal = 28.dp, vertical = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(32.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(Icons.Outlined.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                        Text("选择适合你的工作节奏", style = MaterialTheme.typography.headlineSmall, lineHeight = 34.sp)
                        Text("你可以随时查看说明，在下一次任务开始前选择提问优先，或让 Agent 持续自主推进。", style = MaterialTheme.typography.bodyLarge, lineHeight = 28.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Surface(
                        color = if (brush != null) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("当前模式", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(if (enabled) "开启 · 无人守候" else "关闭 · 提问优先", style = MaterialTheme.typography.titleMedium)
                                }
                                Switch(checked = enabled, onCheckedChange = vm::setLongTaskMode, enabled = !vm.locked)
                            }
                            Text(if (vm.locked) "当前任务运行中，结束后可切换。此页面可随时返回。" else "选择会自动保存，并在下一次任务开始时生效。", style = MaterialTheme.typography.bodyMedium, lineHeight = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    LongTaskExplanation(
                        title = "关闭时：提问优先",
                        description = "提示词会注入“非长自主任务”。当需求、关键偏好或任务边界会影响结果时，Agent 优先使用提问工具向你澄清。",
                        detail = "已经明确的工作仍会直接完成，普通实现细节不会反复要求确认。适合你在屏幕前一起推进任务。"
                    )
                    LongTaskExplanation(
                        title = "开启时：无人守候",
                        description = "提示词会注入“长任务模式 / 无人守候”。Agent 使用合理默认值，持续推进分析、实现和验证，减少非必要打断。",
                        detail = "只有无法自行解决的关键阻塞，或确实需要你授权的决定，才会请求输入。适合暂时离开屏幕时使用。"
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                    LongTaskExplanation(
                        title = "使用与生效范围",
                        description = "输入栏的小按钮：点击开启或关闭，长按进入本页。主代理与子代理继承同一交互策略。",
                        detail = "此开关调整自主推进和提问的优先级。操作权限、命令审批、工具调用额度及运行时限仍按各自设置生效。"
                    )
                    FilledTonalButton(onClick = close, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("返回对话") }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable private fun LongTaskExplanation(title: String, description: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, lineHeight = 30.sp)
        Text(description, style = MaterialTheme.typography.bodyLarge, lineHeight = 28.sp)
        Text(detail, style = MaterialTheme.typography.bodyLarge, lineHeight = 28.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}