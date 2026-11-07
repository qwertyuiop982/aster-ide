package dev.aster.agent

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.aster.core.CoreRuntime
import dev.aster.ide.agent.*
import dev.aster.ide.data.*
import dev.aster.ide.runtime.BuildKeepAliveService
import dev.aster.ide.runtime.AgentRuntimePreparation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Independent presentation state. No Sora, LSP, IDE view model or editor initialization. */
@OptIn(FlowPreview::class)
class AgentViewModel(application: Application) : AndroidViewModel(application) {
    val core = CoreRuntime.get(application)
    val settings = SettingsStore(application, "aster-agent-mode-settings", core.settings.value)
    val agent = AgentController(application, core.bridge, settings, viewModelScope, "standalone-agent-conversations")
    var workspace by mutableStateOf<Workspace?>(null); private set
    var workspaces by mutableStateOf<List<Workspace>>(emptyList()); private set
    var busy by mutableStateOf(false); private set
    var setupState by mutableStateOf(AgentSetupState()); private set
    var setupDismissed by mutableStateOf(false); private set
    var webState by mutableStateOf(AgentWebState()); private set
    var webRevision by mutableStateOf(0); private set
    var webDiagnostics by mutableStateOf(""); private set
    fun updateWebState(state: AgentWebState) { webState = state }
    fun noteWebDiagnostic(text: String) {
        webDiagnostics = (webDiagnostics + text + "\n").takeLast(8000)
        core.logs.append("Agent UI: $text\n")
    }
    fun reloadWebPage() { webState = AgentWebState(); webDiagnostics = ""; webPanelOpen = false; webRevision++ }
    fun dismissSetup() { setupDismissed = true }
    var showDiagnostics by mutableStateOf(false)
    var runtimeInventory by mutableStateOf("尚未检查本地工具"); private set
    fun diagnosticsText(): String = AgentDiagnostics.redact(buildString {
        val engine = agent.selectedEngine.value
        val profile = agent.profile
        val usage = agent.contextUsage.value
        append("Aster ${AgentStartup.REVISION} / Android API ${android.os.Build.VERSION.SDK_INT}\n")
        append("环境阶段：${setupState.phase.title}\n")
        setupState.stoppedAt?.let { append("停止步骤：${it.title}\n") }
        setupState.error?.let { append("环境错误：$it\n") }
        append("界面：${webState.phase} ${webState.message}\n")
        append("本地共享 Termux：${core.termux.directory}\n$runtimeInventory\n")
        append("\n当前底层：${engine.displayName} · ${engine.apiLabel}\n")
        append("模型：${profile.model.ifBlank { "未配置" }}\n")
        append("工具权限：${settings.value.agentPermissionMode}；命令无审查：${settings.value.agentNativeCommandsUnchecked}\n")
        append("项目目录：${workspace?.directory?.absolutePath.orEmpty()}\n")
        val host = runCatching { java.net.URI(profile.endpoint).host }.getOrNull()
        append("模型服务主机：${host ?: "未配置"}（不显示 Key）\n")
        append("已保存窗口：${profile.contextWindow} tokens；下一次任务使用\n")
        append("最近用量：${if (usage.source == "unknown") "暂无有效报告" else "${usage.tokens} / ${usage.window} tokens · ${usage.source}"}\n")
        if (usage.updatedAt > 0) append("报告时间：${java.util.Date(usage.updatedAt)}；不是实时账单\n")
        append("已压缩：${usage.compactions} 次\n${AgentContextPolicy.description(engine)}\n")
        append("\n引擎资源检查（不等于远端模型请求成功）：\n")
        AgentEngine.entries.forEach { item ->
            val found = agent.availableEngines.value.firstOrNull { it.id == item.id }
            append("${item.displayName}：${when { found == null -> "未检查"; found.available -> "本地资源检查通过"; else -> "不可用" }}")
            if (!found?.reason.isNullOrBlank()) append(" · ${found?.reason}")
            append("\n")
        }
        append("本发布源码不包含 OpenClaude SDK；如自行配置该引擎，请单独准备其运行时文件。\n")
        append("\n显式上下文：最多 8 个文件，单文件 64 KiB，总注入 12000 字符；这与模型 token 窗口不同。\n")
        append("界面缓存：最多 60 条、合计 200000 字符、单条 24000；引擎会话与原生压缩另行持久化。\n")
        append("\n界面诊断：\n$webDiagnostics\n运行日志（尾部）：\n${core.output.text.value.takeLast(12000)}")
    })
    suspend fun runtimeRawDiagnostics(): String = withContext(Dispatchers.IO) {
        val root = dev.aster.termux.PathRedirect.layout(getApplication()).home
        listOf("runtime.log", "agent-environment.log").joinToString("\n") { name ->
            val file = java.io.File(root, ".aster/logs/$name")
            name + "\n" + if (file.isFile()) file.readText().takeLast(12000) else "暂无原始日志"
        }
    }
    fun copyStartupDiagnostics() {
        viewModelScope.launch {
            val keys = AgentEngine.entries.mapNotNull { runCatching { agent.secrets.read(it) }.getOrNull()?.takeIf(String::isNotBlank) }
            val text = AgentDiagnostics.redact(diagnosticsText() + "\n\n原始兼容性诊断：\n" + runtimeRawDiagnostics(), keys)
            val clipboard = getApplication<Application>().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Aster 启动诊断", text))
        }
    }
    var error by mutableStateOf<String?>(null)
    var showSettings by mutableStateOf(false)
    var showAppearance by mutableStateOf(false)
    var showEnvironment by mutableStateOf(false)
    var showLongTaskInfo by mutableStateOf(false)
    var environmentInstalling by mutableStateOf(false); private set
    var environmentStatus by mutableStateOf(""); private set
    var environmentTools by mutableStateOf<Map<String, String>>(emptyMap()); private set
    @Volatile private var environmentJobId = ""

    var showWorkspaces by mutableStateOf(false)
    var reviewChanges by mutableStateOf(false)
    var showContextFiles by mutableStateOf(false)
    var contextCandidates by mutableStateOf<List<FileNode>>(emptyList()); private set
    var contextFiles by mutableStateOf<List<String>>(emptyList()); private set
    var pendingDelete by mutableStateOf<AgentConversationInfo?>(null)
    var webPanelOpen by mutableStateOf(false)
    var stopping by mutableStateOf(false); private set
    private var acceptance = 0L
    private var acceptedText = ""
    var prompt by mutableStateOf(""); private set
    private val preferences = application.getSharedPreferences("agent-prompts", 0)
    private var activeJob: Job? = null
    private var warming: Job? = null
    private fun prewarmWorkspace() {
        if (warming?.isActive == true || agent.running || setupState.phase != AgentSetupPhase.READY) return
        val current = workspace ?: return
        warming = viewModelScope.launch {
            try {
                val prepared = prepareProjectAccess()
                core.bridge.sync(prepared, core.repo)
                if (workspace?.id == prepared.id && !agent.running) agent.prewarm(prepared)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { core.logs.append("后台预加载：${e.message}\n") }
        }
    }
    private suspend fun prepareProjectAccess(): Workspace {
        var current = checkNotNull(workspace) { "工作区尚未就绪" }
        if (settings.value.agentPermissionMode == "native" || current.direct) {
            current = core.repo.nativeWorkspace(current)
            workspace = current
            if (current.id != "agent-chat") core.repo.select(current)
            workspaces = core.repo.recent()
            core.termux.start()
        }
        return current
    }
    fun openNativeWorkspace(uri: Uri) = action {
        warming?.join()
        val opened = core.repo.openDirectTree(uri)
        settings.update { it.copy(agentPermissionMode = "native") }
        val mounted = core.repo.nativeWorkspace(opened)
        agent.selectWorkspace(mounted.id); workspace = mounted; core.repo.select(mounted)
        workspaces = core.repo.recent(); showWorkspaces = false; showSettings = false; webPanelOpen = false
        loadContextFiles(); core.termux.start(); prewarmWorkspace()
    }
    private val refresh = MutableStateFlow(0L)
    private val mutablePage = MutableStateFlow("{}")
    val page = mutablePage.asStateFlow()
    val locked get() = busy || stopping || agent.running || activeJob?.isActive == true
    init {
        prompt = preferences.getString("prompt", "").orEmpty()
        viewModelScope.launch {
            merge(agent.subagentConversations.map { Unit }, agent.messages.map { Unit }, agent.state.map { Unit }, agent.changes.map { Unit },
                agent.tasks.map { Unit }, agent.contextUsage.map { Unit }, agent.conversations.map { Unit },
                agent.conversation.map { Unit }, agent.selectedEngine.map { Unit }, agent.mode.map { Unit },
                agent.availableEngines.map { Unit }, agent.saveError.map { Unit }, core.termux.state.map { Unit },
                settings.flow.map { Unit }, core.settings.flow.map { Unit }, refresh.map { Unit })
                .sample(50).collect {
                    try { mutablePage.value = snapshot().toString() }
                    catch (e: Exception) {
                        noteWebDiagnostic("页面状态构建失败：${e.message}")
                        webState = AgentWebState(AgentWebPhase.FAILED, "会话状态无法显示：${e.message}")
                    }
                }
        }
        preloadAgentInterface(application, viewModelScope)
        initializeWorkspace()
    }
    fun initializeWorkspace() = action {
        setupDismissed = false
        try {
            withForeground {
                AgentStartup.prepare(
                    workspace = {
                        if (workspace == null) workspace = core.repo.conversationWorkspace()
                        workspaces = core.repo.recent()
                        agent.selectWorkspace(checkNotNull(workspace).id)
                        loadContextFiles(); changed()
                    },
                    runtime = { !prepareEnvironment(false).optBoolean("ready") },
                    installNode = { installAgentRuntime() },
                    bridge = { core.termux.start() },
                    engines = { checkSelectedEngine() },
                    stage = { setupState = AgentSetupState(it); core.output.append("Agent 启动：${it.title}\n"); changed() }
                )
            }
            prewarmWorkspace()
        } catch (e: CancellationException) {
            setupState = AgentSetupState(AgentSetupPhase.CANCELLED, stoppedAt = setupState.phase); throw e
        } catch (e: Exception) {
            setupState = AgentSetupState(AgentSetupPhase.FAILED, e.message ?: "运行环境准备失败", setupState.phase)
            core.logs.append("Agent setup: ${e.message}\n")
        }
    }
    private fun changed() { refresh.value++ }
    private fun action(block: suspend () -> Unit) {
        if (locked) { error = "请先停止或等待当前任务完成"; return }
        busy = true; changed()
        activeJob = viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "操作失败" }
            finally { busy = false; changed() }
        }
    }
    fun savePrompt(value: String) {
        require(value.length <= 8000)
        prompt = value; preferences.edit().putString("prompt", value).apply(); changed()
    }
    fun selectEngine(id: String) = action {
        warming?.join()
        agent.selectEngine(AgentEngine.entries.firstOrNull { it.id == id } ?: error("未知引擎")); webPanelOpen = false
        prewarmWorkspace()
    }
    fun detachWorkspace() = action {
        check(!locked) { "请先停止当前任务" }
        warming?.join()
        val chat = core.repo.conversationWorkspace()
        agent.selectWorkspace(chat.id)
        workspace = chat
        contextFiles = emptyList()
        showWorkspaces = false
        webPanelOpen = false
        changed()
    }
    fun selectWorkspace(id: String) = action {
        warming?.join()
        val selected = workspaces.firstOrNull { it.id == id } ?: error("工作区不存在")
        agent.selectWorkspace(selected.id); workspace = selected; core.repo.select(selected)
        showWorkspaces = false; webPanelOpen = false; loadContextFiles(); prewarmWorkspace()
    }
    fun createWorkspace(name: String) = action {
        warming?.join()
        require(name.trim().length in 1..100)
        val created = core.repo.create(name)
        agent.selectWorkspace(created.id); workspace = created; workspaces = core.repo.recent(); showWorkspaces = false; webPanelOpen = false; loadContextFiles(); prewarmWorkspace()
    }
    fun importWorkspace(uri: Uri) = action {
        warming?.join()
        val (imported, skipped) = core.repo.importTree(uri)
        agent.selectWorkspace(imported.id); workspace = imported; workspaces = core.repo.recent(); showWorkspaces = false; webPanelOpen = false; loadContextFiles()
        if (skipped > 0) error = "已导入工作区，跳过 $skipped 个不支持或超出限制的文件"
    }
    private fun draftKey() = "draft-${workspace?.id}-${agent.selectedEngine.value.id}-${agent.conversation.value?.id}"
    private fun draft() = preferences.getString(draftKey(), "").orEmpty()
    private fun saveDraft(text: String) { require(text.length <= 12000); preferences.edit().putString(draftKey(), text).apply() }
    private fun loadContextFiles() { contextFiles = preferences.getStringSet("files-${workspace?.id}", emptySet()).orEmpty().filter { it.length <= 512 }.sorted().take(8) }
    fun openContextFiles() = action {
        val current = checkNotNull(workspace)
        contextCandidates = core.repo.files(current).filter { !it.directory && it.size <= 65536 && it.path.length <= 512 && it.name != ".aster-workspace.json" }.sortedBy { it.path }
        showContextFiles = true
    }
    fun toggleContextFile(path: String) {
        if (locked) return
        if (contextCandidates.none { it.path == path }) return
        if (path !in contextFiles && contextFiles.size >= 8) { error = "最多选择 8 个上下文文件"; return }
        contextFiles = if (path in contextFiles) contextFiles - path else contextFiles + path
        preferences.edit().putStringSet("files-${workspace?.id}", contextFiles.toSet()).apply(); changed()
    }
    private suspend fun taskContext(current: Workspace): String = withContext(Dispatchers.IO) {
        val excluded = core.settings.value.excludedDirs.split(',').map(String::trim).toSet()
        val texts = contextFiles.map { path ->
            check(path.split('/').none { it in excluded || it == ".aster-workspace.json" }) { "上下文文件已被排除：$path" }
            val file = core.repo.resolve(current, path)
            check(file.isFile && file.length() <= AgentExplicitContext.FILE_BYTES) { "上下文文件不可读取或超过 64 KiB：$path" }
            val text = file.inputStream().use { AgentExplicitContext.readUtf8(it) }
            AgentExplicitContext.FileText(path, text)
        }
        if (current.id == "agent-chat") "No project workspace is bound. This is a general conversation. Do not assume a project or inspect project files unless the user asks.\n" + prompt
        else AgentExplicitContext.compose(current.name, prompt, texts)
    }
    fun confirmDelete() {
        val selected = pendingDelete ?: return
        action { agent.deleteConversation(selected.id); pendingDelete = null }
    }
    fun newConversation() = action { warming?.join(); agent.newConversation(); webPanelOpen = false; prewarmWorkspace() }
    fun selectConversation(id: String) = action { warming?.join(); agent.selectConversation(id); webPanelOpen = false; prewarmWorkspace() }
    fun deleteConversation(id: String) = action { warming?.join(); agent.deleteConversation(id) }
    fun setMode(id: String) = action {
        warming?.join()
        agent.setMode(AgentMode.entries.firstOrNull { it.id == id } ?: error("未知操作模式"))
    }
    fun setThinking(id: String) = action {
        val engine = agent.selectedEngine.value
        val thinkingMode = when {
            id == "auto" || id == "off" -> id
            id in setOf("none", "max", "ultra") -> "strength"
            AgentThinkingPolicy.supportsStrength(engine, agent.profile) -> "strength"
            else -> "legacy"
        }
        val profile = agent.profile.copy(thinkingLevel = id, thinkingMode = thinkingMode)
        AgentThinkingPolicy.validate(engine, profile)
        settings.updateAgentProfile(engine) { profile }
    }

    fun saveCodexAppearance(left: String, right: String, wholeInterface: Boolean, layout: String) {
        require(layout in setOf("chat", "classic")) { "未知界面样式" }
        require(AgentAppearance.isColor(left) && AgentAppearance.isColor(right)) { "请输入 #RRGGBB 格式的颜色" }
        settings.update { it.copy(
            agentCodexNebulaLeft = AgentAppearance.color(left, AgentAppearance.DEFAULT_LEFT),
            agentCodexNebulaRight = AgentAppearance.color(right, AgentAppearance.DEFAULT_RIGHT),
            agentCodexNebulaAll = wholeInterface, agentUiLayout = layout
        ) }
        showAppearance = false
    }
    fun setThinkingBudget(value: Int) = action {
        val engine = agent.selectedEngine.value
        val profile = agent.profile.copy(thinkingMode = "budget", thinkingBudget = value)
        AgentThinkingPolicy.validate(engine, profile)
        settings.updateAgentProfile(engine) { profile }
    }
    fun setCollaborationMode(value: String) {
        check(!locked) { "请先停止当前任务" }
        require(value in setOf("extreme", "team", "assist", "off"))
        settings.update { it.copy(agentCollaborationMode = value) }
        changed()
    }
    fun setLongTaskMode(enabled: Boolean) {
        check(!locked) { "请先停止或等待当前任务完成" }
        settings.update { it.copy(agentLongTaskMode = enabled) }
        changed()
    }
    fun connect() = initializeWorkspace()
    fun openEnvironmentSettings() {
        showSettings = false
        showEnvironment = true
        if (!locked) checkBuildEnvironment()
    }
    private suspend fun refreshEnvironmentInventory() {
        val report = core.bridge.info()
        val tools = report.optJSONObject("tools") ?: JSONObject()
        environmentTools = tools.keys().asSequence().associateWith { key -> if (tools.isNull(key)) "" else tools.optString(key) }
    }
    fun checkBuildEnvironment() = action {
        withForeground {
            environmentStatus = "正在检查编译工具…"
            core.termux.start()
            refreshEnvironmentInventory()
            environmentStatus = "本地连接与工具检查完成"
        }
    }
    fun changeEnvironmentPort(port: Int) {
        if (locked) { error = "请先停止当前任务"; return }
        require(port in 1024..65535)
        core.termux.close()
        core.settings.update { it.copy(port = port) }
        environmentTools = emptyMap()
        checkBuildEnvironment()
    }
    fun installBuildEnvironment(kind: String) = action {
        require(kind in setOf("base", "android"))
        val label = if (kind == "android") "Android SDK / NDK" else "基础编译工具"
        val identifier = "environment-" + UUID.randomUUID().toString()
        environmentInstalling = true
        environmentStatus = "正在安装 $label"
        environmentJobId = identifier
        core.output.append("\n开始安装 $label（与 IDE 共用）\n")
        try {
            withForeground {
                core.termux.start()
                val code = withTimeout(3600000) {
                    core.bridge.stream("install", workspace?.id ?: "environment", identifier,
                        JSONObject().put("server", kind)) { core.output.append(it) }
                }
                check(code == 0) { "$label 安装失败（退出码 $code），请查看日志并重试" }
                refreshEnvironmentInventory()
                environmentStatus = "$label 安装完成"
                core.output.append("$label 安装成功，工具列表已更新。\n")
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { withTimeoutOrNull(3000) { runCatching { core.bridge.cancel(identifier) } } }
            environmentStatus = "$label 安装已停止，可重新安装"
            throw e
        } catch (e: Exception) {
            environmentStatus = e.message ?: "$label 安装失败，请查看日志并重试"
            core.output.append(environmentStatus + "\n")
            throw e
        } finally {
            environmentJobId = ""
            environmentInstalling = false
            changed()
        }
    }

    private suspend fun prepareEnvironment(install: Boolean): JSONObject {
        val report = AgentRuntimePreparation.run(getApplication(), core.settings.value.termuxMirror, install) { core.output.append(it) }
        runtimeInventory = "Node：${report.optString("nodePath")} ${report.optString("nodeVersion")}\nGit：${report.optString("gitReady")}；ripgrep：${report.optString("rgReady")}\nOpenSSL：${report.optString("opensslReady")}；CA：${report.optString("caReady")}"
        return report
    }
    private suspend fun installAgentRuntime() { prepareEnvironment(true) }
    private suspend fun checkSelectedEngine() {
        agent.refreshEngines()
        val engine = agent.selectedEngine.value
        val available = agent.availableEngines.value.firstOrNull { it.id == engine.id }
        check(available?.available == true) { engine.label + "：" + (available?.reason ?: "引擎检查未返回结果") }
    }
    private suspend fun ensureRuntime() {
        if (core.termux.isReady() && setupState.phase == AgentSetupPhase.READY && agent.availableEngines.value.any { it.id == agent.selectedEngine.value.id && it.available }) return
        setupState = AgentSetupState(AgentSetupPhase.TERMUX); changed()
        if (!prepareEnvironment(false).optBoolean("ready")) {
            setupState = AgentSetupState(AgentSetupPhase.NODE); changed()
            installAgentRuntime()
        }
        setupState = AgentSetupState(AgentSetupPhase.BRIDGE); changed()
        core.termux.start()
        setupState = AgentSetupState(AgentSetupPhase.ENGINES); changed()
        try {
            checkSelectedEngine()
            setupState = AgentSetupState(AgentSetupPhase.READY); changed()
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            setupState = AgentSetupState(AgentSetupPhase.FAILED, e.message, AgentSetupPhase.ENGINES); changed()
            throw e
        }
    }
    private suspend fun withForeground(block: suspend () -> Unit) {
        BuildKeepAliveService.start(getApplication())
        try { block() } finally { BuildKeepAliveService.stop(getApplication()) }
    }
    fun send(text: String, compact: Boolean = false) = action {
        val task = text.trim()
        require(compact || task.isNotEmpty()) { "请输入任务" }
        require(task.length <= 12000) { "任务超过 12000 字符" }
        var current = checkNotNull(workspace) { "工作区尚未就绪" }
        if (agent.profile.model.isBlank() || agent.profile.endpoint.isBlank()) {
            showSettings = true
            error = "请先配置模型"
            return@action
        }
        val compactRequested = compact || task == "/compact"
        check(!compactRequested || agent.selectedEngine.value != AgentEngine.OPENCLAUDE) { "此引擎暂不支持手动压缩" }
        val sentDraftKey = draftKey()
        withForeground {
            warming?.join()
            current = prepareProjectAccess()
            val context = taskContext(current)
            ensureRuntime()
            val availability = agent.availableEngines.value.firstOrNull { it.id == agent.selectedEngine.value.id }
            check(availability?.available == true) { availability?.reason ?: "引擎尚未就绪" }
            core.bridge.sync(current, core.repo)
            agent.run(current, if (compactRequested) "压缩当前会话上下文" else task, context, JSONArray(), compactRequested,
                onAccepted = {
                    viewModelScope.launch {
                        if (!compactRequested) {
                            if (preferences.getString(sentDraftKey, "").orEmpty().trim() == task) preferences.edit().remove(sentDraftKey).apply()
                            acceptedText = task; acceptance++; changed()
                        }
                    }
                })
        }
    }
    fun cancel() {
        if (stopping) return
        stopping = true; changed()
        viewModelScope.launch {
            try {
                environmentJobId.takeIf { it.isNotBlank() }?.let { core.bridge.cancel(it) }
                agent.cancel()
            } catch (e: Exception) { error = e.message }
            finally { activeJob?.cancelAndJoin(); stopping = false; busy = false; changed() }
        }
    }
    fun decide(requestId: String, accepted: Boolean) {
        viewModelScope.launch {
            try {
                check(agent.state.value.approval?.requestId == requestId) { "审批请求已过期" }
                agent.decide(accepted)
            } catch (e: Exception) { error = e.message }
        }
    }
    fun discardChanges() = action { agent.discardChanges(); agent.persistNow(); reviewChanges = false }
    fun applyChanges() = action {
        val current = checkNotNull(workspace)
        check(agent.projectId == current.id)
        val changes = agent.changes.value.toList()
        withContext(Dispatchers.IO) {
            fun validate(change: AgentChange) {
                check(change.path.split('/').none { it == ".aster-workspace.json" || it in core.settings.value.excludedDirs.split(',').map(String::trim) }) { "修改包含排除目录" }
                check(AgentEditGuard.sha256(change.before) == change.sha256) { "修改校验失败" }
                val file = core.repo.resolve(current, change.path)
                check(!file.exists() || file.length() <= 1024 * 1024) { "文件过大，无法安全审阅" }
                val text = if (file.exists()) file.readText() else ""
                AgentEditGuard.verify(change.beforeExists, change.before, file.exists(), text, change.diskSha256, if (file.exists()) AgentEditGuard.sha256(text) else null)
            }
            changes.forEach(::validate)
            changes.forEach { change ->
                validate(change)
                if (change.deleted) core.repo.delete(current, change.path) else core.repo.save(current, change.path, change.after)
                core.bridge.invalidate(current.id, change.path)
            }
        }
        agent.discardChanges(); agent.persistNow(); reviewChanges = false
    }
    suspend fun prepareExit() {
        check(!locked) { "请先停止或等待当前 Agent 任务完成" }
        agent.persistNow()
    }
    fun persist() { viewModelScope.launch { runCatching { agent.persistNow() } } }
    fun handle(request: JSONObject, chooseWorkMode: () -> Unit = {}) {
        try {
            val current = workspace ?: error("工作区尚未就绪")
            // A closing WebView may deliver final UI-only events after a session switch.
            if (request.optString("command") in setOf("panel", "draft") &&
                (request.optString("workspace") != current.id || request.optString("session") != agent.conversation.value?.id || request.optString("engine") != agent.selectedEngine.value.id)) return
            AgentWebProtocol.validate(request, current.id, agent.conversation.value?.id.orEmpty(), agent.selectedEngine.value.id)
            when (request.getString("command")) {
                "send" -> send(request.getString("text"))
                "compact" -> send("", true)
                "stop" -> cancel()
                "new" -> newConversation()
                "history" -> selectConversation(request.getString("id"))
                "engine" -> selectEngine(request.getString("id"))
                "mode" -> setMode(request.getString("id"))
                "thinking" -> setThinking(request.getString("id"))
                "thinking_budget" -> setThinkingBudget(request.getInt("value"))
                "collaboration" -> setCollaborationMode(request.getString("id"))
                "long_task" -> setLongTaskMode(request.getString("id") == "on")
                "long_task_info" -> { webPanelOpen = false; showLongTaskInfo = true }
                "prompt" -> savePrompt(request.getString("text"))
                "settings" -> { check(!locked); showSettings = true }
                "appearance" -> { webPanelOpen = false; showAppearance = true }
                "environment" -> openEnvironmentSettings()
                "diagnostics" -> { showDiagnostics = true }
                "browser" -> dev.aster.client.browser.BrowserRuntime.open(getApplication())
                "device_permissions" -> getApplication<Application>().startActivity(android.content.Intent(getApplication(), dev.aster.client.shizuku.PermissionActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                "workspaces" -> { check(!locked); showWorkspaces = true }
                "review" -> { check(!locked); reviewChanges = true }
                "connect" -> connect()
                "draft" -> saveDraft(request.getString("text"))
                "panel" -> webPanelOpen = request.optString("id") == "open"
                "context_files" -> openContextFiles()
                "work_mode" -> { check(!locked) { "请先停止当前任务" }; chooseWorkMode() }
                "delete" -> {
                    check(!locked) { "请先停止当前任务" }
                    pendingDelete = agent.conversations.value.firstOrNull { it.id == request.getString("id") && it.engineId == agent.selectedEngine.value.id } ?: error("会话不存在")
                }
                "copy" -> {
                    val clipboard = getApplication<Application>().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Aster", request.getString("text")))
                }
            }
        } catch (e: Exception) { error = e.message ?: "请求无效" }
    }
    private fun snapshot(): JSONObject {
        val state = agent.state.value; val engine = agent.selectedEngine.value; val usage = agent.contextUsage.value
        val conversation = agent.conversation.value
        if (workspace?.id != agent.projectId || (conversation != null && conversation.engineId != engine.id)) {
            return JSONObject(mutablePage.value).put("busy", true)
        }
        return JSONObject().put("workspace", workspace?.id.orEmpty()).put("workspaceName", workspace?.name.orEmpty())
            .put("session", agent.conversation.value?.id.orEmpty()).put("title", agent.conversation.value?.title ?: "新会话")
            .put("engine", engine.id).put("model", agent.profile.model).put("mode", agent.mode.value.id)
            .put("permissionMode", settings.value.agentPermissionMode).put("commandsUnchecked", settings.value.agentPermissionMode == "native" && settings.value.agentNativeCommandsUnchecked)
            .put("reducedMotion", core.settings.value.reducedMotion || !core.settings.value.animations)
            .put("longTaskMode", settings.value.agentLongTaskMode)
            .put("collaborationMode", settings.value.agentCollaborationMode)
            .put("uiLayout", settings.value.agentUiLayout)
            .put("thinkingLevel", agent.profile.thinkingLevel).put("thinkingLabel", if (engine == AgentEngine.CODEX) AgentThinkingPolicy.label(engine, agent.profile.thinkingLevel) else AgentThinkingPolicy.summary(agent.profile))
            .put("codexAppearance", JSONObject().put("leftColor", settings.value.agentCodexNebulaLeft).put("rightColor", settings.value.agentCodexNebulaRight).put("wholeInterface", settings.value.agentCodexNebulaAll))
            .put("thinkingMode", agent.profile.thinkingMode).put("thinkingBudget", agent.profile.thinkingBudget)
            .put("thinkingBudgetSupported", AgentThinkingPolicy.supportsBudget(engine, agent.profile))
            .put("thinkingBudgetRange", AgentThinkingPolicy.budgetRange(engine, agent.profile).let { JSONArray(listOf(it.first, it.last)) })
            .put("thinkingNote", AgentThinkingPolicy.note(engine, agent.profile))
            .put("thinkingChoices", JSONArray(AgentThinkingPolicy.available(engine, agent.profile).map { level ->
                JSONObject().put("id", level).put("label", AgentThinkingPolicy.label(engine, level))
                    .put("detail", AgentThinkingPolicy.detail(engine, agent.profile, level))
            }))
            .put("draft", draft()).put("acceptance", acceptance).put("acceptedText", acceptedText)
            .put("contextFiles", JSONArray(contextFiles)).put("stopping", stopping)
            .put("compactSupported", engine != AgentEngine.OPENCLAUDE)
            .put("busy", locked).put("running", state.running).put("status", if (busy && !state.running) "准备中" else state.status)
            .put("runtime", if (setupState.error != null) core.termux.state.value.message else "").put("runtimeError", setupState.error ?: "").put("saveError", agent.saveError.value ?: "")
            .put("approval", state.approval != null).put("steps", state.step)
            .put("tokens", usage.tokens).put("window", usage.window).put("compactions", usage.compactions)
            .put("configuredWindow", agent.profile.contextWindow).put("contextSource", usage.source).put("contextUpdatedAt", usage.updatedAt)
            .put("contextPolicy", AgentContextPolicy.description(engine)).put("engineVersion", engine.version).put("setupPhase", setupState.phase.title)
            .put("prompt", prompt).put("messages", JSONArray(agent.messages.value.map { JSONObject().put("id", it.id).put("role", it.role).put("text", it.text) }))
            .put("subagents", AgentJson.subagents(agent.subagentConversations.value))
            .put("tasks", JSONArray(agent.tasks.value.map { JSONObject().put("task", it.task).put("status", it.status) }))
            .put("changes", JSONArray(agent.changes.value.map { JSONObject().put("path", it.path).put("deleted", it.deleted) }))
            .put("history", JSONArray(agent.conversations.value.filter { it.engineId == engine.id }.map { JSONObject().put("id", it.id).put("title", it.title).put("updated", it.updated) }))
            .put("engines", JSONArray(AgentEngine.entries.map { item ->
                val available = agent.availableEngines.value.firstOrNull { it.id == item.id }
                JSONObject().put("id", item.id).put("label", item.label).put("available", available?.available ?: false)
                    .put("checked", available != null).put("reason", available?.reason ?: "连接环境后检查")
            }))
    }
    override fun onCleared() { warming?.cancel(); agent.close() }
}
