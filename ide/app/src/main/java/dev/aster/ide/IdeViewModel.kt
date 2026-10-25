package dev.aster.ide

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.aster.ide.agent.AgentMode
import dev.aster.ide.agent.AgentController
import dev.aster.ide.agent.AgentEditGuard
import dev.aster.ide.data.*
import dev.aster.ide.editor.Highlighter
import dev.aster.ide.editor.NativeSearch
import dev.aster.ide.lsp.*
import dev.aster.ide.runtime.*
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.regex.Pattern

data class Artifact(val path: String, val size: Long)
data class SearchHit(val path: String, val line: Int, val column: Int, val preview: String)
data class DocumentEdit(val path: String, val before: String, val after: String, val beforeExists: Boolean = true, val deleted: Boolean = false, val diskSha256: String? = null)
data class EditPreview(val title: String, val changes: List<DocumentEdit>, val workspaceId: String? = null, val source: String? = null)
data class InfoSheet(val title: String, val content: String)
data class Jump(val path: String, val position: LspPosition, val id: String = UUID.randomUUID().toString())

class IdeViewModel(application: Application) : AndroidViewModel(application) {
    val core = dev.aster.core.CoreRuntime.get(application)
    val settings = core.settings
    val repo = core.repo
    val catalog = LanguageCatalog(application)
    val bridge = core.bridge
    val output = core.output
    val logs = core.logs
    val termux = core.termux
    val terminal = RemoteTerminal(application, bridge, viewModelScope, ::log)
    val agent = AgentController(application, bridge, settings, viewModelScope)
    var agentDraft by mutableStateOf("")
    var agentPreparing by mutableStateOf(false)
    var applyingEdits by mutableStateOf(false)
    private var agentJob: Job? = null
    var agentStopping by mutableStateOf(false)
        private set
    private var agentRuntimeInstalling = false
    val dockExpanded get() = if (panel == "agent") settings.value.agentDockExpanded else panelExpanded
    fun toggleDockExtent() {
        if (panel == "agent") settings.update { it.copy(agentDockExpanded = !it.agentDockExpanded) }
        else panelExpanded = !panelExpanded
    }
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    var workspace by mutableStateOf<Workspace?>(null)
    var recent by mutableStateOf<List<Workspace>>(emptyList())
    var nodes by mutableStateOf<List<FileNode>>(emptyList())
    val documents = mutableStateListOf<EditorDocument>()
    var activePath by mutableStateOf<String?>(null)
    val active get() = documents.firstOrNull { it.path == activePath }
    var editor: CodeEditor? = null
    var sidebar by mutableStateOf("files")
    var showSidebar by mutableStateOf(false)
    var panel by mutableStateOf("terminal")
    var showPanel by mutableStateOf(false)
    var panelExpanded by mutableStateOf(false)
    var settingsPage by mutableStateOf<String?>(null)
    var showAgent by mutableStateOf(false)
    var commandPalette by mutableStateOf(false)
    var showFind by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var connected by mutableStateOf(false)
    var connecting by mutableStateOf(false)
    var workspaceLoaded by mutableStateOf(false)
    var preparingEditor by mutableStateOf(false)
    var editorReady by mutableStateOf(false)
    var startupError by mutableStateOf<String?>(null)
    var loadingDocument by mutableStateOf(false)
    var editorInstancesCreated by mutableStateOf(0)
    var editorCacheHits by mutableStateOf(0)
    var editorCachedCount by mutableStateOf(0)
    var installed by mutableStateOf<Map<String, Boolean>>(emptyMap())
    var tools by mutableStateOf<Map<String, String>>(emptyMap())
    var caret by mutableStateOf(LspPosition(0, 0))
    var jump by mutableStateOf<Jump?>(null)
    var buildRunning by mutableStateOf(false)
    var buildJob by mutableStateOf<String?>(null)
    var buildExit by mutableStateOf<Int?>(null)
    var buildElapsed by mutableStateOf(0L)
    var buildProfile by mutableStateOf("自动")
    var lastCommand by mutableStateOf("")
    var diagnostics by mutableStateOf<Map<String, List<LspDiagnostic>>>(emptyMap())
    var serverStates by mutableStateOf<Map<String, String>>(emptyMap())
    var info by mutableStateOf<InfoSheet?>(null)
    var locations by mutableStateOf<List<Location>>(emptyList())
    var symbols by mutableStateOf<List<JSONObject>>(emptyList())
    var codeActions by mutableStateOf<List<JSONObject>>(emptyList())
    var editPreview by mutableStateOf<EditPreview?>(null)
    var artifacts by mutableStateOf<List<Artifact>>(emptyList())
    var workspaceHits by mutableStateOf<List<SearchHit>>(emptyList())
    var searching by mutableStateOf(false)
    var findQuery by mutableStateOf("")
    var replaceQuery by mutableStateOf("")
    var matchCase by mutableStateOf(false)
    var regexSearch by mutableStateOf(false)
    var matches by mutableStateOf<List<IntRange>>(emptyList())
    var matchIndex by mutableStateOf(0)
    private val changeJobs = mutableMapOf<String, Job>()
    private val saveJobs = mutableMapOf<String, Job>()
    private val saveLocks = mutableMapOf<String, Mutex>()
    private val syncing = Mutex()
    private var findJob: Job? = null
    private var searchJob: Job? = null
    private var runtimeJob: Job? = null
    private var openingJob: Job? = null
    private var languageJob: Job? = null
    private var openingGeneration = 0
    private var syncedWorkspace: String? = null
    private val preparing = Mutex()
    val lsp = LspManager(bridge, settings, catalog, viewModelScope,
        { path, values -> viewModelScope.launch { diagnostics = diagnostics + (path to values) } },
        { id, state -> viewModelScope.launch { serverStates = serverStates + (id to state) } }, ::log)

    init {
        task {
            try {
                workspace = repo.initialize(); refreshFiles(); recent = repo.recent()
                workspace?.let { agent.selectWorkspace(it.id) }
                workspaceLoaded = true
                settings.flow.first { it.uiStyleChosen }
                if (settings.value.autoRuntime) connectRuntime() else prepareEditor()
            } catch (e: Exception) { startupError = e.message ?: "项目准备失败"; throw e }
        }
        viewModelScope.launch {
            termux.state.collect { state ->
                if (state.phase == RuntimePhase.FAILED && connected && !connecting) {
                    connected = false
                    notify(state.error ?: "内置环境已停止")
                }
            }
        }
    }
    fun notify(message: String) { messages.tryEmit(message.take(1200)) }
    fun log(message: String) { logs.append(message + "\n") }
    private fun task(block: suspend () -> Unit): Job = viewModelScope.launch {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { notify(e.message ?: "操作失败"); log(e.stackTraceToString().take(3000)) }
    }
    suspend fun snapshot(doc: EditorDocument): String = withContext(Dispatchers.Default) { (doc.buffer?.toString() ?: doc.snapshot?.invoke() ?: doc.text).also { doc.text = it } }
    suspend fun refreshFiles() { workspace?.let { nodes = repo.files(it) } }
    fun refresh() = task { refreshFiles(); if (connected) refreshRuntime() }

    fun open(path: String): Job {
        val generation = ++openingGeneration
        openingJob?.cancel()
        return task {
            loadingDocument = true
            try { openInternal(path) }
            finally { if (generation == openingGeneration) loadingDocument = false }
        }.also { openingJob = it }
    }
    private suspend fun openInternal(path: String) {
        val current = workspace ?: return
        if (activePath == path && active != null) return
        if (documents.none { it.path == path }) {
            if (documents.size >= settings.value.maxTabs) {
                val disposable = documents.firstOrNull { !it.dirty && it.path != activePath } ?: error("打开的标签已达上限，请先保存并关闭部分文件")
                closeInternal(disposable, false)
            }
            val document = repo.open(current, path, settings.value)
            check(documents.sumOf { (it.buffer?.length ?: it.text.length).toLong() * 2 } + (document.buffer?.length ?: document.text.length).toLong() * 2 < 48L * 1024 * 1024) { "已打开文件超过 48 MiB 内存预算，请先关闭部分标签" }
            documents += document
        }
        activePath = path
        val doc = active ?: return
        caret = LspPosition(doc.line, doc.column)
        if (connected && editorReady) scheduleLsp(doc)
    }
    fun closeTab(doc: EditorDocument, discard: Boolean = false) = task { closeInternal(doc, !discard) }
    private suspend fun closeInternal(doc: EditorDocument, save: Boolean) {
        if (save && doc.dirty) saveDocument(doc)
        changeJobs.remove(doc.path)?.cancel(); saveJobs.remove(doc.path)?.cancel()
        lsp.closeDocument(doc.path)
        val index = documents.indexOf(doc)
        documents.remove(doc)
        doc.snapshot = null
        if (activePath == doc.path) activePath = documents.getOrNull((index - 1).coerceAtLeast(0))?.path
    }
    fun edited(doc: EditorDocument) {
        doc.dirty = true; doc.revision++
        changeJobs.remove(doc.path)?.cancel()
        changeJobs[doc.path] = task {
            delay(settings.value.lspDebounce.toLong())
            if (connected && lsp.ready(doc.path)) lsp.change(doc.path, snapshot(doc))
            if (showFind && doc.path == activePath) searchInFile()
        }
        saveJobs.remove(doc.path)?.cancel()
        if (settings.value.autoSave) saveJobs[doc.path] = task { delay(settings.value.autoSaveDelay.toLong()); saveDocument(doc) }
    }
    fun save() = task { active?.let { saveDocument(it); notify("已保存 ${it.name}") } }
    private suspend fun saveDocument(doc: EditorDocument) {
        val current = workspace ?: return
        saveLocks.getOrPut(doc.path) { Mutex() }.withLock {
            if (settings.value.formatOnSave && lsp.ready(doc.path) && doc.path == activePath) {
                runCatching { formatInternal(doc) }.onFailure { log("自动格式化: ${it.message}") }
            }
            val revision = doc.revision
            val text = snapshot(doc)
            repo.save(current, doc.path, text)
            doc.savedRevision = revision
            doc.dirty = doc.revision != revision
            bridge.invalidate(current.id, doc.path)
            syncedWorkspace = null
            if (connected) runCatching { lsp.saved(doc.path, text) }.onFailure { log("LSP save: ${it.message}") }
        }
    }
    suspend fun saveAll() { documents.filter { it.dirty }.toList().forEach { saveDocument(it) } }
    fun persist() = task { saveAll(); agent.persistNow() }

    fun importProject(uri: Uri) = task {
        busy = true
        try { saveAll(); val (project, skipped) = repo.importTree(uri); switchInternal(project); if (skipped > 0) notify("已跳过 $skipped 个超出大小限制的文件") }
        finally { busy = false }
    }
    fun newProject(name: String) = task { saveAll(); switchInternal(repo.create(name)) }
    fun switchProject(project: Workspace) = task { saveAll(); switchInternal(project) }
    private suspend fun switchInternal(project: Workspace) {
        stopAgentInternal()
        agentDraft = ""; editPreview = null
        agent.selectWorkspace(project.id)
        changeJobs.values.forEach { it.cancel() }; saveJobs.values.forEach { it.cancel() }
        changeJobs.clear(); saveJobs.clear(); documents.clear(); activePath = null
        terminal.close(); lsp.restart(); diagnostics = emptyMap(); symbols = emptyList(); serverStates = emptyMap()
        syncedWorkspace = null
        languageJob?.cancel()
        workspace = project; repo.select(project); refreshFiles(); recent = repo.recent()
        nodes.firstOrNull { !it.directory }?.let { openInternal(it.path) }
    }
    fun createFile(path: String, folder: Boolean) = task { workspace?.let { repo.newFile(it, path, folder); syncedWorkspace = null; refreshFiles(); if (!folder) openInternal(path) } }
    fun renameFile(path: String, name: String) = task {
        workspace?.let { current ->
            val affected = documents.filter { it.path == path || it.path.startsWith("$path/") }.toList()
            affected.forEach { closeInternal(it, true) }
            repo.rename(current, path, path.substringBeforeLast('/', "").let { if (it.isBlank()) name else "$it/$name" })
            refreshFiles()
        }
    }
    fun deleteFile(path: String) = task {
        workspace?.let { current ->
            documents.filter { it.path == path || it.path.startsWith("$path/") }.toList().forEach { closeInternal(it, false) }
            repo.delete(current, path); refreshFiles()
            if (connected) syncWorkspace()
        }
    }

    suspend fun syncWorkspace() = syncing.withLock {
        val current = workspace ?: return@withLock 0
        bridge.sync(current, repo).also { if (workspace?.id == current.id) syncedWorkspace = current.id }
    }
    suspend fun refreshRuntime() {
        applyRuntimeInfo(bridge.info())
    }
    private fun applyRuntimeInfo(state: JSONObject) {
        connected = true
        installed = state.getJSONObject("installed").let { j -> j.keys().asSequence().associateWith { j.optBoolean(it) } }
        tools = state.getJSONObject("tools").let { j -> j.keys().asSequence().associateWith { j.optString(it).takeUnless { v -> v == "null" } ?: "" } }
    }
    fun connectRuntime(start: Boolean = true): Job {
        runtimeJob?.takeIf { it.isActive }?.let { return it }
        return task {
            connecting = true
            startupError = null
            try {
                if (start) {
                    if (editorReady) { panel = "build"; showPanel = true }
                    output.clear()
                    BuildKeepAliveService.start(getApplication())
                }
                connected = false
                applyRuntimeInfo(if (start) termux.start() else bridge.info(1500))
                syncWorkspace()
                if (!editorReady) prepareEditor()
                notify("内置环境已就绪")
            } catch (e: CancellationException) { connected = false; throw e }
            catch (e: Exception) {
                connected = false
                startupError = e.message ?: "环境启动失败"
                output.append("\n${startupError}\n")
                throw e
            } finally { connecting = false; releaseForegroundIfIdle() }
            if (connected) active?.let { scheduleLsp(it) }
        }.also { runtimeJob = it }
    }
    private suspend fun prepareEditor() = preparing.withLock {
        if (editorReady) return@withLock
        preparingEditor = true
        try {
            withContext(Dispatchers.IO) { Highlighter.initialize(getApplication()) }
            if (active == null) {
                nodes.firstOrNull { it.path == "src/main.py" }?.let { openInternal(it.path) }
                    ?: nodes.firstOrNull { !it.directory }?.let { openInternal(it.path) }
            }
            editorReady = true
            startupError = null
        } finally { preparingEditor = false }
    }
    fun continueLocally() = task {
        runtimeJob?.cancelAndJoin()
        termux.close()
        connected = false
        startupError = null
        try { prepareEditor() } catch (e: Exception) { startupError = e.message; throw e }
    }
    fun changeRuntimePort(port: Int) {
        runtimeJob?.cancel()
        termux.close()
        connected = false
        settings.update { it.copy(port = port) }
        restartLsp()
    }
    private fun scheduleLsp(doc: EditorDocument) {
        languageJob?.cancel()
        languageJob = task { delay(300); if (active === doc && !connecting) ensureLsp(doc) }
    }
    private suspend fun ensureLsp(doc: EditorDocument) {
        if (!connected || connecting || !editorReady || doc.large || !settings.value.lspEnabled || lsp.ready(doc.path)) return
        val current = workspace ?: return
        val server = catalog.forPath(doc.path) ?: return
        if (installed[server.id] != true && settings.value.servers[server.id]?.command.isNullOrBlank()) { serverStates = serverStates + (server.id to "未安装"); return }
        if (syncedWorkspace != current.id) syncWorkspace()
        lsp.open(current, doc)
    }
    fun restartLsp() = task { lsp.restart(); diagnostics = emptyMap(); serverStates = emptyMap(); if (connected) active?.let { ensureLsp(it) } }
    fun openTerminal() = task {
        showPanel = true; panel = "terminal"
        check(connected) { "请先在设置中连接 Termux" }
        saveAll(); syncWorkspace()
        terminal.connect(workspace?.id ?: return@task, settings.value.scrollback)
    }
    fun installServer(id: String) = task {
        check(connected) { "请先连接 Termux" }
        check(!buildRunning) { "请等待当前任务结束" }
        executeStream("install", JSONObject().put("server", id), "安装: $id")
        refreshRuntime(); if (buildExit == 0) { lsp.restart(); active?.let { ensureLsp(it) } }
    }
    fun build(command: String) = task {
        check(command.isNotBlank()) { "请填写编译命令" }
        check(connected) { "请先在设置中连接 Termux" }
        check(!buildRunning) { "已有任务在运行" }
        saveAll(); syncWorkspace()
        val project = workspace ?: return@task
        if (command.contains("gradle") && settings.value.sdkPath.isNotBlank()) {
            val data = "sdk.dir=${settings.value.sdkPath}\n".toByteArray()
            bridge.rpc("put", project.id, JSONObject().put("path", "local.properties").put("data", Base64.encodeToString(data, Base64.NO_WRAP)))
        }
        lastCommand = command
        executeStream("run", JSONObject().put("command", command), command)
    }
    private suspend fun executeStream(op: String, params: JSONObject, title: String) {
        val project = workspace ?: return
        val job = UUID.randomUUID().toString()
        buildJob = job; buildRunning = true; buildExit = null; buildElapsed = 0
        panel = "build"; showPanel = true; output.clear(); output.append("$ $title\n\n")
        val start = System.nanoTime()
        val timer = viewModelScope.launch { while (isActive) { buildElapsed = (System.nanoTime() - start) / 1000000000; delay(1000) } }
        try {
            BuildKeepAliveService.start(getApplication())
            val code = bridge.stream(op, project.id, job, params) { output.append(it) }
            buildExit = code; output.append("\n[exit $code · ${(System.nanoTime() - start) / 1000000} ms]\n")
            runCatching { refreshArtifacts() }.onFailure { log("编译产物: ${it.message}") }
        } catch (e: Exception) { buildExit = -1; output.append("\n${e.message}\n"); throw e }
        finally { timer.cancel(); buildRunning = false; buildJob = null; releaseForegroundIfIdle() }
    }
    suspend fun refreshArtifacts() {
        val current = workspace ?: return
        artifacts = bridge.rpc("artifacts", current.id).getJSONArray("artifacts").objects().map { Artifact(it.getString("path"), it.getLong("size")) }
    }
    fun exportArtifact(artifact: Artifact, destination: Uri) = task {
        val current = workspace ?: return@task
        withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openOutputStream(destination, "wt")?.use { output ->
                var offset = 0L
                var version: Long? = null
                while (true) {
                    ensureActive()
                    val packet = bridge.rpc("readChunk", current.id, JSONObject().put("path", artifact.path).put("offset", offset))
                    check(packet.getLong("size") == artifact.size) { "编译产物已变化，请重新导出" }
                    val modified = packet.getLong("modified")
                    check(version == null || version == modified) { "编译产物正在更新，请等待构建结束" }
                    version = modified
                    val chunk = Base64.decode(packet.getString("data"), Base64.NO_WRAP)
                    output.write(chunk); offset += chunk.size
                    if (packet.optBoolean("eof")) break
                    check(chunk.isNotEmpty()) { "产物读取中断" }
                }
            } ?: error("无法写入目标文件")
        }
        notify("已导出 ${artifact.path.substringAfterLast('/')}")
    }
    fun cancelBuild() = task { buildJob?.let { bridge.cancel(it); output.append("\n[已请求停止]\n") } }
    fun defaultCommand() = workspace?.let { BuildProfiles.command(buildProfile, it, activePath, settings.value.buildCommand) }.orEmpty()

    private fun releaseForegroundIfIdle() {
        if (!buildRunning && !connecting && !agentPreparing && !agent.running) BuildKeepAliveService.stop(getApplication())
    }
    fun openAgent() { showAgent = true }
    fun compactAgentContext() = sendAgent(compactOnly = true)
    fun sendAgent(compactOnly: Boolean = false) {
        if (agentPreparing || agentStopping || agent.running || agentJob?.isActive == true) return
        val prompt = if (compactOnly) "压缩当前会话上下文" else agentDraft.trim()
        if (prompt.isEmpty()) return
        if (agent.profile.model.isBlank() || agent.profile.endpoint.isBlank()) {
            settingsPage = "agent"; notify("请先配置 Agent 模型"); return
        }
        val current = workspace ?: return
        if (!connected) { notify("请先连接内置编译环境"); return }
        if (prompt.length > 12000) { notify("任务描述请控制在 12000 字符以内"); return }
        val editorContext = buildString {
            append("Project: ").append(current.name)
            active?.let { doc -> append("\nFile: ").append(doc.path).append("\nCursor: ").append(caret.line + 1).append(':').append(caret.character + 1) }
            editor?.let { view ->
                val cursor = view.cursor
                if (cursor.isSelected) append("\nSelection:\n").append(view.text.subSequence(cursor.left, minOf(cursor.right, cursor.left + 4000)))
            }
        }
        val dirtyDocuments = documents.filter { it.dirty }.toList()
        agentPreparing = true
        openAgent()
        agentJob = task {
            try {
                BuildKeepAliveService.start(getApplication())
                check(workspace?.id == current.id) { "项目已切换" }
                if (agent.selectedEngine.value != dev.aster.ide.agent.AgentEngine.CODEX && tools["node"].isNullOrBlank()) {
                    check(!buildRunning) { "请等待构建结束后安装 Agent 运行环境" }
                    agentRuntimeInstalling = true
                    try {
                        executeStream("install", JSONObject().put("server", "agent"), "安装 Agent 运行环境")
                        check(buildExit == 0) { "Agent 运行环境安装失败，请查看构建输出后重试" }
                        refreshRuntime(); openAgent()
                    } finally { agentRuntimeInstalling = false }
                }
                val overlays = JSONArray()
                var bytes = 0L
                for (doc in dirtyDocuments) {
                    val text = snapshot(doc)
                    val data = withContext(Dispatchers.Default) { text.toByteArray(Charsets.UTF_8) }
                    bytes += data.size
                    check(bytes <= 12L * 1024 * 1024) { "未保存文件超过 Agent 的 12 MiB 上限，请先保存部分文件" }
                    overlays.put(JSONObject().put("path", doc.path).put("data", Base64.encodeToString(data, Base64.NO_WRAP)))
                }
                syncWorkspace()
                check(workspace?.id == current.id) { "项目已切换" }
                if (!compactOnly) agentDraft = ""
                agentPreparing = false
                agent.run(current, prompt, editorContext, overlays, compactOnly || prompt == "/compact")
            } finally { agentPreparing = false; releaseForegroundIfIdle() }
        }
    }
    private suspend fun stopAgentInternal() {
        agentStopping = true
        try {
            if (agentRuntimeInstalling) buildJob?.let { runCatching { bridge.cancel(it) } }
            agent.cancel()
            agentJob?.cancelAndJoin()
            agentJob = null; agentPreparing = false
        } finally { agentStopping = false; releaseForegroundIfIdle() }
    }
    fun setAgentMode(mode: AgentMode) {
        if (!agentPreparing && !agentStopping && !agent.running && agentJob?.isActive != true) agent.setMode(mode)
    }
    private fun agentConversationAction(action: suspend () -> Unit) = task {
        check(!agentPreparing && !agentStopping && !agent.running && agentJob?.isActive != true) { "请等待 Agent 完成" }
        val current = workspace ?: return@task
        agentPreparing = true
        try { agent.selectWorkspace(current.id); action(); agentDraft = ""; if (editPreview?.source == "agent") editPreview = null }
        finally { agentPreparing = false }
    }
    fun selectAgentEngine(engine: dev.aster.ide.agent.AgentEngine) = agentConversationAction { agent.selectEngine(engine) }
    fun refreshAgentEngines() = task { if (connected) agent.refreshEngines() }
    fun newAgentConversation() = agentConversationAction { agent.newConversation() }
    fun selectAgentConversation(identifier: String) = agentConversationAction { agent.selectConversation(identifier) }
    fun deleteAgentConversation(identifier: String) = agentConversationAction { agent.deleteConversation(identifier) }
    fun cancelAgent() = task { stopAgentInternal() }
    fun decideAgentCommand(accepted: Boolean) = task { agent.decide(accepted) }
    fun previewAgentChanges() = task {
        val current = workspace ?: return@task
        check(!agent.running && !agentPreparing) { "请等待 Agent 完成" }
        check(agent.projectId == current.id) { "Agent 修改属于另一个项目" }
        val changes = agent.changes.value
        if (changes.isEmpty()) return@task
        val edits = changes.map { change ->
            repo.resolve(current, change.path)
            check(change.path.split('/').none { it == ".aster-workspace.json" || it in settings.value.excludedDirs.split(',').map(String::trim) }) { "修改包含排除目录" }
            val hash = withContext(Dispatchers.Default) { AgentEditGuard.sha256(change.before) }
            check(hash == change.sha256) { "Agent 修改校验失败" }
            DocumentEdit(change.path, change.before, change.after, change.beforeExists, change.deleted, change.diskSha256)
        }
        edits.forEach { validateEdit(current, it) }
        editPreview = EditPreview("Agent 修改", edits, current.id, "agent")
    }

    fun searchInFile() {
        findJob?.cancel()
        val doc = active ?: return
        val query = findQuery
        findJob = task {
            delay(100)
            val text = snapshot(doc)
            val found = withContext(Dispatchers.Default) {
                if (query.isEmpty()) emptyList()
                else if (regexSearch) {
                    val matcher = Pattern.compile(query, if (matchCase) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE).matcher(text)
                    val result = ArrayList<IntRange>()
                    while (result.size < 1000 && matcher.find()) result += matcher.start() until matcher.end()
                    result
                } else NativeSearch.find(text, query, !matchCase).map { it until it + query.length }
            }
            if (activePath == doc.path && findQuery == query) { matches = found; matchIndex = 0; selectMatch() }
        }
    }
    fun nextMatch(delta: Int) { if (matches.isNotEmpty()) { matchIndex = Math.floorMod(matchIndex + delta, matches.size); selectMatch() } }
    private fun selectMatch() {
        val match = matches.getOrNull(matchIndex) ?: return
        editor?.let { e ->
            val left = e.text.indexer.getCharPosition(match.first.coerceIn(0, e.text.length))
            val right = e.text.indexer.getCharPosition((match.last + 1).coerceIn(0, e.text.length))
            e.setSelectionRegion(left.line, left.column, right.line, right.column)
        }
    }
    fun replace(all: Boolean) = task {
        val doc = active ?: return@task
        val before = snapshot(doc)
        if (all) {
            check(findQuery.isNotEmpty())
            val after = withContext(Dispatchers.Default) {
                if (regexSearch) Pattern.compile(findQuery, if (matchCase) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE).matcher(before).replaceAll(replaceQuery)
                else before.replace(findQuery, replaceQuery, !matchCase)
            }
            applyText(doc, before, after)
        } else matches.getOrNull(matchIndex)?.let { range ->
            val old = before.substring(range.first, range.last + 1)
            val value = if (regexSearch) Pattern.compile(findQuery, if (matchCase) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE).matcher(old).replaceFirst(replaceQuery) else replaceQuery
            applyText(doc, before, before.replaceRange(range.first, range.last + 1, value))
        }
        searchInFile()
    }
    fun searchWorkspace(query: String) {
        searchJob?.cancel()
        searchJob = task {
            searching = true
            try {
                delay(250)
                val current = workspace ?: return@task
                workspaceHits = withContext(Dispatchers.Default) {
                    if (query.isBlank()) emptyList() else buildList {
                        for (file in nodes.filter { !it.directory && it.size < settings.value.largeFileKB * 1024L }) {
                            ensureActive()
                            val text = runCatching { repo.resolve(current, file.path).readText() }.getOrNull() ?: continue
                            if ('\u0000' in text.take(1024)) continue
                            for (offset in NativeSearch.find(text, query, true, 20)) {
                                val pos = TextCoordinates.position(text, offset)
                                add(SearchHit(file.path, pos.line, pos.character, text.substring(text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)) + 1, text.indexOf('\n', offset).let { if (it < 0) text.length else it }).take(180)))
                                if (size >= 300) return@buildList
                            }
                        }
                    }
                }
            } finally { searching = false }
        }
    }
    fun navigate(path: String, position: LspPosition) = task { openInternal(path); jump = Jump(path, position) }
    fun navigate(location: Location) { val path = lsp.relative(location.uri); if (path == null) notify("目标位于项目之外：${location.uri}") else navigate(path, location.range.start) }

    fun languageAction(action: String) = task {
        val doc = active ?: return@task
        val text = snapshot(doc)
        when (action) {
            "hover" -> info = InfoSheet("类型与文档", lsp.hover(doc.path, text, caret))
            "signature" -> info = InfoSheet("函数签名", lsp.signature(doc.path, text, caret))
            "definition", "references" -> {
                val result = lsp.locations(doc.path, text, caret, action == "references")
                if (result.size == 1) navigate(result.first()) else if (result.isEmpty()) notify("没有找到结果") else locations = result
            }
            "format" -> { formatInternal(doc); notify("格式化完成") }
            "symbols" -> { symbols = lsp.symbols(doc.path, text); panel = "symbols"; showPanel = true }
            "actions" -> { codeActions = lsp.actions(doc.path, text, caret, diagnostics[doc.path].orEmpty()); if (codeActions.isEmpty()) notify("此处没有可用的代码操作") }
        }
    }
    private suspend fun formatInternal(doc: EditorDocument) {
        val before = snapshot(doc)
        val edits = lsp.formatting(doc.path, before)
        if (edits.isNotEmpty()) applyText(doc, before, withContext(Dispatchers.Default) { TextCoordinates.apply(before, edits) })
    }
    private suspend fun applyText(doc: EditorDocument, before: String, after: String) {
        check(snapshot(doc) == before) { "文档已改变，请重试这项操作" }
        if (before == after) return
        if (doc.path == activePath && editor != null) {
            val e = editor!!
            e.text.beginBatchEdit()
            try { e.text.replace(0, e.text.length, after) } finally { e.text.endBatchEdit() }
        } else {
            val revision = doc.revision
            doc.buffer?.let { buffer -> buffer.beginBatchEdit(); try { buffer.replace(0, buffer.length, after) } finally { buffer.endBatchEdit() } }
            doc.text = after
            if (doc.revision == revision) edited(doc)
        }
    }
    fun renameSymbol(name: String) = task {
        require(name.isNotBlank())
        val doc = active ?: return@task
        val result = lsp.rename(doc.path, snapshot(doc), caret, name) ?: return@task
        prepareEdits("重命名为 $name", result)
    }
    fun applyCodeAction(action: JSONObject) = task {
        action.optJSONObject("edit")?.let { prepareEdits(action.optString("title", "代码操作"), it) }
            ?: action.optJSONObject("command")?.let { lsp.execute(activePath ?: return@task, it) }
            ?: if (action.opt("command") is String) lsp.execute(activePath ?: return@task, action) else notify("这项操作需要服务器提供编辑结果")
        codeActions = emptyList()
    }
    private suspend fun prepareEdits(title: String, edit: JSONObject) {
        val edits = LinkedHashMap<String, MutableList<LspEdit>>()
        edit.optJSONObject("changes")?.let { changes -> changes.keys().forEach { uri ->
            val path = lsp.relative(uri) ?: error("编辑目标超出项目目录")
            edits.getOrPut(path) { ArrayList() }.addAll(changes.getJSONArray(uri).objects().map(LspEdit::from))
        } }
        edit.optJSONArray("documentChanges")?.objects()?.forEach { change ->
            check(!change.has("kind")) { "暂不自动应用文件创建、移动或删除操作" }
            val document = change.getJSONObject("textDocument")
            val path = lsp.relative(document.getString("uri")) ?: error("编辑目标超出项目目录")
            if (!document.isNull("version")) check(lsp.version(path) == document.getInt("version")) { "文档版本已变化" }
            edits.getOrPut(path) { ArrayList() }.addAll(change.getJSONArray("edits").objects().map(LspEdit::from))
        }
        val current = workspace ?: return
        val changes = edits.map { (path, changes) ->
            val doc = documents.firstOrNull { it.path == path } ?: repo.open(current, path, settings.value)
            val before = snapshot(doc)
            DocumentEdit(path, before, withContext(Dispatchers.Default) { TextCoordinates.apply(before, changes) })
        }.filter { it.before != it.after }
        if (changes.isEmpty()) notify("没有需要应用的更改") else editPreview = EditPreview(title, changes, current.id)
    }
    private suspend fun validateEdit(current: Workspace, change: DocumentEdit): EditorDocument? {
        val file = withContext(Dispatchers.IO) { repo.resolve(current, change.path) }
        val (exists, diskHash) = withContext(Dispatchers.IO) {
            val exists = file.exists()
            val hash = if (exists && change.diskSha256 != null) {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                file.inputStream().use { stream ->
                    val buffer = ByteArray(32768)
                    while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            } else null
            exists to hash
        }
        val doc = documents.firstOrNull { it.path == change.path }
            ?: if (exists) repo.open(current, change.path, settings.value) else null
        val text = doc?.let { snapshot(it) } ?: ""
        withContext(Dispatchers.Default) {
            try { AgentEditGuard.verify(change.beforeExists, change.before, exists, text, change.diskSha256, diskHash) }
            catch (e: IllegalStateException) { error("${change.path}: ${e.message}") }
            check(change.after.toByteArray(Charsets.UTF_8).size <= settings.value.maxFileMB * 1024 * 1024) { "${change.path} 超过文件大小上限" }
        }
        return doc
    }
    fun commitEdits() {
        if (applyingEdits) return
        val preview = editPreview ?: return
        val current = workspace ?: return
        applyingEdits = true
        task {
            try {
                check(preview.workspaceId == null || preview.workspaceId == current.id) { "修改预览属于另一个项目" }
                val staged = preview.changes.map { change -> validateEdit(current, change) to change }
                check(workspace?.id == current.id) { "项目已切换" }
                staged.forEach { (doc, change) ->
                    check(workspace?.id == current.id) { "项目已切换" }
                    validateEdit(current, change)
                    if (change.deleted) {
                        if (doc != null && doc in documents) closeInternal(doc, false)
                        repo.delete(current, change.path)
                    } else if (doc != null && doc in documents) {
                        applyText(doc, change.before, change.after); saveDocument(doc)
                    } else repo.save(current, change.path, change.after)
                    bridge.invalidate(current.id, change.path)
                }
                syncedWorkspace = null
                if (preview.source == "agent") agent.discardChanges()
                editPreview = null; refreshFiles(); notify("已应用 ${staged.size} 个文件的修改")
            } finally { applyingEdits = false }
        }
    }
    override fun onCleared() { agent.close(); terminal.close(); lsp.close(); BuildKeepAliveService.stop(getApplication()); editor = null }
}