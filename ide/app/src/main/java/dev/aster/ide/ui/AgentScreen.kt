package dev.aster.ide.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aster.ide.IdeViewModel
import dev.aster.ide.agent.*
import dev.aster.ide.data.IdeSettings
import dev.aster.ide.data.UiStyle
import dev.aster.ide.editor.TextBlocks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class AgentPageState(
    val messages: List<AgentMessage>, val state: AgentState, val mode: AgentMode,
    val tasks: List<AgentTask>, val changes: List<AgentChange>, val context: AgentContextState,
    val conversation: AgentConversationInfo?, val conversations: List<AgentConversationInfo>,
    val saveError: String?, val busy: Boolean
) { val idle get() = !busy }
private data class AgentContentBlock(val message: AgentMessage, val text: String, val first: Boolean, val index: Int, val language: String? = null)

/** Agent is a full application destination. Each style owns its page composition. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AgentScreen(vm: IdeViewModel, settings: IdeSettings) {
    val messages by vm.agent.messages.collectAsStateWithLifecycle()
    val state by vm.agent.state.collectAsStateWithLifecycle()
    val mode by vm.agent.mode.collectAsStateWithLifecycle()
    val tasks by vm.agent.tasks.collectAsStateWithLifecycle()
    val changes by vm.agent.changes.collectAsStateWithLifecycle()
    val usage by vm.agent.contextUsage.collectAsStateWithLifecycle()
    val conversation by vm.agent.conversation.collectAsStateWithLifecycle()
    val conversations by vm.agent.conversations.collectAsStateWithLifecycle()
    val saveError by vm.agent.saveError.collectAsStateWithLifecycle()
    val page = AgentPageState(messages, state, mode, tasks, changes, usage, conversation, conversations, saveError,
        state.running || vm.agentPreparing || vm.agentStopping)
    var destination by rememberSaveable(conversation?.id) { mutableIntStateOf(0) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }
    ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = drawer.isOpen, drawerContent = {
        ModalDrawerSheet(Modifier.width(300.dp), drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)) {
            AgentHistory(vm, page, Modifier.fillMaxSize()) { scope.launch { drawer.close() } }
        }
    }) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 900.dp
            val compact = maxHeight < 420.dp
            if (settings.uiStyle == UiStyle.ACS) {
                AcsAgentWorkspace(vm, settings, page, destination, { destination = it }, wide, compact) { scope.launch { drawer.open() } }
            } else {
                AsterAgentWorkspace(vm, settings, page, destination, { destination = it }, wide, compact) { scope.launch { drawer.open() } }
            }
        }
    }
}

@Composable private fun AsterAgentWorkspace(vm: IdeViewModel, settings: IdeSettings, page: AgentPageState,
    destination: Int, onDestination: (Int) -> Unit, wide: Boolean, compact: Boolean, onHistory: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AgentPageHeader(vm, page, false, compact, onHistory)
        Line()
        Row(Modifier.weight(1f)) {
            if (wide) {
                AgentHistory(vm, page, Modifier.width(252.dp).fillMaxHeight()) {}
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (!compact) AgentSessionStrip(vm, settings, page, Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
                AgentDestinations(page, destination, onDestination, false)
                AgentPageContent(vm, settings, page, destination, false, Modifier.weight(1f))
                AgentApprovalCard(vm, page, false, compact)
                AgentComposer(vm, page, false, compact)
            }
        }
    }
}

/** ACS uses its own project header, inset workspace cards and lower tool shelf. */
@Composable private fun AcsAgentWorkspace(vm: IdeViewModel, settings: IdeSettings, page: AgentPageState,
    destination: Int, onDestination: (Int) -> Unit, wide: Boolean, compact: Boolean, onHistory: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = if (wide) 16.dp else 8.dp)) {
        AgentPageHeader(vm, page, true, compact, onHistory)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(if (compact) 12.dp else 24.dp), color = MaterialTheme.colorScheme.surface) {
                Column {
                    if (!compact) AgentSessionStrip(vm, settings, page, Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
                    AgentPageContent(vm, settings, page, destination, true, Modifier.weight(1f))
                    AgentApprovalCard(vm, page, true, compact)
                }
            }
            if (wide) Surface(Modifier.width(264.dp).fillMaxHeight(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)) {
                AgentHistory(vm, page, Modifier.fillMaxSize()) {}
            }
        }
        AgentDestinations(page, destination, onDestination, true)
        AgentComposer(vm, page, true, compact)
    }
}

@Composable private fun AgentPageHeader(vm: IdeViewModel, page: AgentPageState, acs: Boolean, compact: Boolean, onHistory: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(if (compact) 48.dp else if (acs) 76.dp else 60.dp)
        .then(if (!acs) Modifier.background(MaterialTheme.colorScheme.surface) else Modifier).padding(horizontal = if (acs) 2.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ToolButton(Icons.Outlined.ArrowBack, "返回编辑器", size = 40) { vm.showAgent = false }
        if (acs && !compact) Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(Icons.Outlined.SmartToy, null, Modifier.padding(12.dp).size(24.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Column(Modifier.weight(1f).padding(horizontal = if (acs) 12.dp else 8.dp)) {
            AgentEnginePicker(vm, Modifier.fillMaxWidth().height(32.dp), compact = true)
            if (!compact) Text(vm.workspace?.name ?: "选择项目开始", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ToolButton(Icons.Outlined.History, "Agent 会话历史", size = 40, onClick = onHistory)
        ToolButton(Icons.Outlined.AddComment, "新建 Agent 会话", enabled = page.idle, size = 40) { vm.newAgentConversation() }
        ToolButton(Icons.Outlined.Tune, "Agent 模型与设置", size = 40) { vm.settingsPage = "agent" }
    }
}

@Composable private fun AgentSessionStrip(vm: IdeViewModel, settings: IdeSettings, page: AgentPageState, modifier: Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(page.conversation?.title ?: "新会话", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (page.busy) CircularProgressIndicator(Modifier.padding(start = 10.dp).size(14.dp), strokeWidth = 2.dp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Outlined.Circle, null, Modifier.size(7.dp), tint = if (page.busy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(when { vm.agentStopping -> "正在停止"; vm.agentPreparing -> "准备运行环境"; else -> page.state.status }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (page.state.step > 0) Text("· ${page.state.step} 步", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(settings.agentProfile(vm.agent.selectedEngine.value).model.ifBlank { "配置模型" }, Modifier.widthIn(max = 170.dp).clickable { vm.settingsPage = "agent" },
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        page.saveError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable private fun AgentDestinations(page: AgentPageState, selected: Int, onSelect: (Int) -> Unit, acs: Boolean) {
    val titles = listOf("对话", "任务${if (page.tasks.isEmpty()) "" else " · ${page.tasks.size}"}", "修改${if (page.changes.isEmpty()) "" else " · ${page.changes.size}"}", "活动")
    val icons = listOf(Icons.Outlined.ChatBubbleOutline, Icons.Outlined.Checklist, Icons.Outlined.Difference, Icons.Outlined.Terminal)
    if (acs) {
        Surface(Modifier.fillMaxWidth().padding(top = 6.dp), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                titles.forEachIndexed { index, title ->
                    TextButton(onClick = { onSelect(index) }, shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.textButtonColors(containerColor = if (index == selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                        Icon(icons[index], null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text(title, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    } else {
        ScrollableTabRow(selectedTabIndex = selected, edgePadding = 8.dp, containerColor = MaterialTheme.colorScheme.surface) {
            titles.forEachIndexed { index, title -> Tab(selected == index, { onSelect(index) }, text = { Text(title) }) }
        }
    }
}

@Composable private fun AgentPageContent(vm: IdeViewModel, settings: IdeSettings, page: AgentPageState, destination: Int, acs: Boolean, modifier: Modifier) {
    Box(modifier.fillMaxWidth()) {
        when (destination) {
            1 -> AgentTaskBoard(vm, page, acs)
            2 -> AgentChangeBoard(vm, page, acs)
            3 -> AgentActivity(page, acs)
            else -> AgentTranscript(vm, settings, page, acs)
        }
    }
}

@Composable private fun AgentHistory(vm: IdeViewModel, page: AgentPageState, modifier: Modifier, onSelected: () -> Unit) {
    Column(modifier.padding(horizontal = 12.dp, vertical = 14.dp)) {
        Text("项目会话", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(8.dp))
        Text(vm.workspace?.name ?: "当前工作区", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { vm.newAgentConversation(); onSelected() }, enabled = page.idle, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("新建会话")
        }
        LazyColumn(Modifier.weight(1f).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(page.conversations, key = { it.id }) { entry ->
                var deleting by remember { mutableStateOf(false) }
                Surface(shape = RoundedCornerShape(12.dp), color = if (entry.id == page.conversation?.id) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else Color.Transparent) {
                    Row(Modifier.fillMaxWidth().clickable(enabled = page.idle) { vm.selectAgentConversation(entry.id); onSelected() }.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(entry.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(AgentEngine.fromId(entry.engineId).label + " · " + entry.mode.label + " · " + remember(entry.updated) { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(entry.updated)) }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (entry.changes > 0) Text("${entry.changes} 个文件待预览", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        ToolButton(Icons.Outlined.DeleteOutline, "删除会话 ${entry.title}", size = 32, enabled = page.idle) { deleting = true }
                    }
                }
                if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("删除此会话？") }, text = { Text(entry.title) },
                    confirmButton = { TextButton(onClick = { vm.deleteAgentConversation(entry.id); deleting = false }) { Text("删除") } },
                    dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } })
            }
        }
        Text("按项目自动保存", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
    }
}

private fun conversationBlocks(messages: List<AgentMessage>): List<AgentContentBlock> = messages.flatMap { message ->
    if (message.role == "tool" || message.role == "error") listOf(AgentContentBlock(message, message.text, true, 0))
    else {
        val parts = message.text.split("```")
        val output = mutableListOf<AgentContentBlock>()
        parts.forEachIndexed { partIndex, part ->
            val code = partIndex % 2 == 1
            val language = if (code) part.substringBefore('\n').take(30) else null
            val body = if (code) part.substringAfter('\n', part) else part
            TextBlocks.split(body).filter { it.isNotBlank() }.forEach { block ->
                output.add(AgentContentBlock(message, block, output.isEmpty(), output.size, language))
            }
        }
        output.ifEmpty { listOf(AgentContentBlock(message, "", true, 0)) }
    }
}

@Composable private fun AgentTranscript(vm: IdeViewModel, settings: IdeSettings, page: AgentPageState, acs: Boolean) {
    val blocks by produceState<List<AgentContentBlock>>(emptyList(), page.messages) {
        value = withContext(Dispatchers.Default) { conversationBlocks(page.messages) }
    }
    if (page.messages.isEmpty()) {
        AgentWelcome(vm, settings, page.mode, acs)
        return
    }
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var follow by remember(page.conversation?.id) { mutableStateOf(true) }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.isScrollInProgress to ((scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= scroll.layoutInfo.totalItemsCount - 3) }
            .collect { (scrolling, nearEnd) -> if (scrolling) follow = nearEnd }
    }
    LaunchedEffect(blocks.size, page.messages.lastOrNull()?.text?.length) {
        if (follow && blocks.isNotEmpty()) scroll.scrollToItem(blocks.lastIndex)
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(horizontal = if (acs) 18.dp else 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(blocks, key = { it.message.id + ":" + it.index }) { block ->
                if (block.message.role == "tool") AgentToolEvent(block.text, acs)
                else AgentMessageBlock(block, acs)
            }
            if (page.busy) item {
                Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(page.state.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (!follow) SmallFloatingActionButton(onClick = { follow = true; scope.launch { if (blocks.isNotEmpty()) scroll.animateScrollToItem(blocks.lastIndex) } }, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
            Icon(Icons.Outlined.ArrowDownward, "滚动到最新消息")
        }
    }
}

@Composable private fun AgentWelcome(vm: IdeViewModel, settings: IdeSettings, mode: AgentMode, acs: Boolean) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp), verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(if (acs) 38.dp else 32.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(18.dp))
        Text("从想法到代码", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text("围绕 ${vm.workspace?.name ?: "当前项目"} 提问、制定计划或完成开发任务。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(22.dp))
        listOf("理解项目" to "梳理项目结构、主要模块和构建方式", "制定计划" to "分析当前项目，制定可执行的改进计划", "实现与验证" to "检查当前项目，修复发现的问题并运行验证").forEachIndexed { index, (title, prompt) ->
            Surface(Modifier.fillMaxWidth().padding(bottom = 10.dp).clickable {
                vm.setAgentMode(AgentMode.entries[index]); vm.agentDraft = prompt
            }, shape = RoundedCornerShape(if (acs) 18.dp else 8.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (acs) 0.55f else 0.35f)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleSmall); Spacer(Modifier.height(4.dp)); Text(prompt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Icon(Icons.Outlined.NorthWest, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Text(mode.description + "。会话自动保存，可在下次继续。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (settings.agentProfile(vm.agent.selectedEngine.value).model.isBlank()) TextButton(onClick = { vm.settingsPage = "agent" }) { Text("连接模型服务") }
    }
}

@Composable private fun AgentMessageBlock(block: AgentContentBlock, acs: Boolean) {
    val user = block.message.role == "user"
    val error = block.message.role == "error"
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().padding(top = if (block.first) 10.dp else 0.dp)) {
        if (block.first) Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (user) "你" else if (error) "运行提示" else "Agent", style = MaterialTheme.typography.labelMedium,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            ToolButton(Icons.Outlined.ContentCopy, "复制消息", size = 26) { clipboard.setText(AnnotatedString(block.message.text)) }
        }
        Surface(shape = RoundedCornerShape(if (acs) 16.dp else 6.dp), color = when {
            error -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
            user -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (acs) 0.5f else 0.3f)
            block.language != null -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            else -> Color.Transparent
        }) {
            SelectionContainer {
                Column(Modifier.fillMaxWidth().padding(horizontal = if (user || error || block.language != null) 12.dp else 0.dp, vertical = 8.dp)) {
                    if (block.language != null) {
                        if (block.language.isNotBlank()) Text(block.language, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                        Text(block.text.trimEnd(), fontFamily = Mono, style = MaterialTheme.typography.bodySmall, modifier = Modifier.horizontalScroll(rememberScrollState()))
                    } else {
                        val rich = remember(block.text) { buildAnnotatedString {
                            val pattern = Regex("`([^`]+)`|\\*\\*([^*]+)\\*\\*")
                            var offset = 0
                            for (match in pattern.findAll(block.text)) {
                                append(block.text.substring(offset, match.range.first))
                                val start = length
                                append(match.groupValues[1].ifEmpty { match.groupValues[2] })
                                addStyle(if (match.groupValues[1].isNotEmpty()) SpanStyle(fontFamily = Mono) else SpanStyle(fontWeight = FontWeight.SemiBold), start, length)
                                offset = match.range.last + 1
                            }
                            append(block.text.substring(offset))
                        } }
                        Text(rich, style = MaterialTheme.typography.bodyMedium, color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

@Composable private fun AgentToolEvent(text: String, acs: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(if (acs) 14.dp else 6.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)) {
        Column(Modifier.clickable { expanded = !expanded }.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Terminal, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text.lineSequence().firstOrNull().orEmpty(), Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "收起工具记录" else "展开工具记录", Modifier.size(18.dp))
            }
            if (expanded) SelectionContainer { Text(text.take(16000), fontFamily = Mono, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp)) }
        }
    }
}

@Composable private fun AgentTaskBoard(vm: IdeViewModel, page: AgentPageState, acs: Boolean) {
    if (page.tasks.isEmpty()) {
        AgentEmptyState("把任务拆成可执行的步骤", "规划模式会生成任务清单，执行时会持续更新进度。", Icons.Outlined.Checklist) {
            TextButton(onClick = { vm.setAgentMode(AgentMode.PLAN); vm.agentDraft = "分析项目并制定开发计划" }, enabled = page.idle) { Text("开始规划") }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("任务进度 · ${page.tasks.count { it.status == "completed" }}/${page.tasks.size}", style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(progress = { page.tasks.count { it.status == "completed" }.toFloat() / page.tasks.size }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp))
        }
        items(page.tasks) { task ->
            Surface(shape = RoundedCornerShape(if (acs) 18.dp else 8.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(when (task.status) { "completed" -> Icons.Outlined.CheckCircle; "in_progress" -> Icons.Outlined.PlayCircleOutline; else -> Icons.Outlined.RadioButtonUnchecked }, null, Modifier.size(20.dp), tint = if (task.status == "in_progress") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Column { Text(task.task, style = MaterialTheme.typography.bodyMedium); Text(when (task.status) { "completed" -> "已完成"; "in_progress" -> "进行中"; else -> "待开始" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable private fun AgentChangeBoard(vm: IdeViewModel, page: AgentPageState, acs: Boolean) {
    if (page.changes.isEmpty()) {
        AgentEmptyState("在这里查看文件修改", "Agent 准备的修改会集中在这里，检查后可以应用到项目。", Icons.Outlined.Difference) {}
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("${page.changes.size} 个文件待检查", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::previewAgentChanges, enabled = page.idle, shape = RoundedCornerShape(if (acs) 18.dp else 8.dp)) { Text("检查并应用") }
                TextButton(onClick = { vm.agent.discardChanges() }, enabled = page.idle) { Text("丢弃修改") }
            }
        }
        items(page.changes, key = { it.path }) { change ->
            var expanded by remember(change.path) { mutableStateOf(false) }
            Surface(shape = RoundedCornerShape(if (acs) 18.dp else 8.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)) {
                Column(Modifier.padding(14.dp)) {
                    Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Description, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(change.path, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if (change.deleted) "删除" else if (!change.beforeExists) "新建" else "修改", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, "查看文件内容", Modifier.size(20.dp))
                    }
                    if (expanded) {
                        Text(if (change.deleted) "原内容" else "修改后的内容", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SelectionContainer { Text((if (change.deleted) change.before else change.after).lineSequence().take(100).joinToString("\n").take(12000), fontFamily = Mono, style = MaterialTheme.typography.bodySmall, modifier = Modifier.horizontalScroll(rememberScrollState())) }
                        TextButton(onClick = vm::previewAgentChanges, enabled = page.idle) { Text("打开完整差异预览") }
                    }
                }
            }
        }
    }
}

@Composable private fun AgentActivity(page: AgentPageState, acs: Boolean) {
    val records = remember(page.messages) { page.messages.filter { it.role == "tool" || it.role == "error" } }
    if (records.isEmpty()) {
        AgentEmptyState("跟踪每一步操作", "文件读取、命令和执行结果会显示在这里。", Icons.Outlined.Terminal) {}
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(records, key = { it.id }) { record ->
            if (record.role == "tool") AgentToolEvent(record.text, acs)
            else Text(record.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable private fun AgentEmptyState(title: String, detail: String, icon: androidx.compose.ui.graphics.vector.ImageVector, action: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp)); Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp)); Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp)); action()
    }
}

@Composable private fun AgentApprovalCard(vm: IdeViewModel, page: AgentPageState, acs: Boolean, compact: Boolean) {
    val approval = page.state.approval ?: return
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), shape = RoundedCornerShape(if (acs) 20.dp else 8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(14.dp)) {
            Text("运行命令 · ${approval.timeout} 秒", style = MaterialTheme.typography.titleSmall)
            Text(approval.command.take(1200), fontFamily = Mono, style = MaterialTheme.typography.bodySmall, maxLines = if (compact) 2 else 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.decideAgentCommand(true) }) { Text("允许本次") }
                TextButton(onClick = { vm.decideAgentCommand(false) }) { Text("拒绝") }
            }
        }
    }
}

@Composable private fun AgentComposer(vm: IdeViewModel, page: AgentPageState, acs: Boolean, compact: Boolean) {
    var modes by remember { mutableStateOf(false) }
    var contextInfo by remember { mutableStateOf(false) }
    val fraction = (page.context.tokens.toFloat() / page.context.window.coerceAtLeast(1)).coerceIn(0f, 1f)
    val shape = RoundedCornerShape(if (acs) 24.dp else 10.dp)
    Column(Modifier.fillMaxWidth().then(if (acs) Modifier.padding(top = 6.dp, bottom = 6.dp) else Modifier.padding(horizontal = 14.dp, vertical = 8.dp))) {
        Surface(shape = shape, color = if (acs) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface,
            border = if (acs) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = if (compact) 2.dp else 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(vm.agentDraft, { vm.agentDraft = it }, modifier = Modifier.weight(1f),
                        placeholder = { Text(when (page.mode) { AgentMode.ASK -> "询问项目或代码"; AgentMode.PLAN -> "描述要制定的计划"; AgentMode.EXECUTE -> "描述要完成的任务" }) },
                        minLines = 1, maxLines = if (compact) 2 else 5, textStyle = MaterialTheme.typography.bodyMedium,
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { if (page.idle && vm.agentDraft.isNotBlank()) vm.sendAgent() }))
                    FilledIconButton(onClick = { if (page.busy) vm.cancelAgent() else vm.sendAgent() }, enabled = page.busy || vm.agentDraft.isNotBlank(), shape = RoundedCornerShape(if (acs) 18.dp else 8.dp)) {
                        Icon(if (page.busy) Icons.Outlined.Stop else Icons.Outlined.ArrowUpward, if (page.busy) "停止 Agent" else "发送任务")
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        TextButton(onClick = { modes = true }, enabled = page.idle, contentPadding = PaddingValues(horizontal = 6.dp)) {
                            Icon(when (page.mode) { AgentMode.ASK -> Icons.Outlined.ChatBubbleOutline; AgentMode.PLAN -> Icons.Outlined.Checklist; AgentMode.EXECUTE -> Icons.Outlined.Code }, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp)); Text(page.mode.label, style = MaterialTheme.typography.labelMedium); Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp))
                        }
                        DropdownMenu(modes, { modes = false }) {
                            AgentMode.entries.forEach { mode -> DropdownMenuItem(text = { Column { Text(mode.label); Text(mode.description, style = MaterialTheme.typography.labelSmall) } }, onClick = { vm.setAgentMode(mode); modes = false }) }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { contextInfo = true }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Icon(Icons.Outlined.DonutSmall, null, Modifier.size(14.dp), tint = if (fraction > 0.8f) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(5.dp)); Text("${page.context.tokens / 1024}K / ${page.context.window / 1024}K", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
    if (contextInfo) AlertDialog(onDismissRequest = { contextInfo = false }, title = { Text("上下文") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("当前约 ${page.context.tokens} tokens · 窗口 ${page.context.window} tokens", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Text("已压缩 ${page.context.compactions} 次。接近模型窗口时会保存摘要并保留近期对话；完整工具记录保存在本地会话中。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { contextInfo = false; vm.compactAgentContext() }, enabled = page.idle && page.messages.isNotEmpty()) { Text("现在压缩") } },
        dismissButton = { TextButton(onClick = { contextInfo = false; vm.settingsPage = "agent" }) { Text("调整模型窗口") } })
}
