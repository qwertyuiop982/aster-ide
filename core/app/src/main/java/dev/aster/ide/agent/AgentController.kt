package dev.aster.ide.agent

import android.content.Context
import dev.aster.ide.data.SettingsStore
import dev.aster.ide.data.Workspace
import dev.aster.ide.runtime.BridgeClient
import dev.aster.ide.runtime.BridgeConnection
import dev.aster.ide.runtime.LogBuffer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@OptIn(FlowPreview::class)
class AgentController(context: Context, private val bridge: BridgeClient, private val settings: SettingsStore, scope: CoroutineScope, storageDirectory: String = "agent-conversations") {
    private val clientTools = dev.aster.client.automation.ClientTools(context.applicationContext)
    private val account = dev.aster.ide.account.AccountRepository.get(context)
    private val accountSettings = dev.aster.core.CoreRuntime.get(context).settings
    val secrets = AgentSecretStore(context)
    private val store = AgentConversationStore(File(context.filesDir, storageDirectory))
    private val persistence = Mutex()
    private val selecting = Mutex()
    private val clock = AtomicLong(System.currentTimeMillis())
    private val deleted = ConcurrentHashMap.newKeySet<String>()
    private val saves = MutableStateFlow<AgentSnapshot?>(null)
    private val mutableMessages = MutableStateFlow<List<AgentMessage>>(emptyList())
    val messages = mutableMessages.asStateFlow()
    private val mutableState = MutableStateFlow(AgentState())
    val state = mutableState.asStateFlow()
    private val mutableChanges = MutableStateFlow<List<AgentChange>>(emptyList())
    val changes = mutableChanges.asStateFlow()
    private val mutableTasks = MutableStateFlow<List<AgentTask>>(emptyList())
    val tasks = mutableTasks.asStateFlow()
    private val mutableContext = MutableStateFlow(AgentContextState())
    val contextUsage = mutableContext.asStateFlow()
    private val mutableMode = MutableStateFlow(AgentMode.fromId(settings.value.agentMode))
    val mode = mutableMode.asStateFlow()
    private val mutableConversations = MutableStateFlow<List<AgentConversationInfo>>(emptyList())
    val conversations = mutableConversations.asStateFlow()
    private val mutableConversation = MutableStateFlow<AgentConversationInfo?>(null)
    val conversation = mutableConversation.asStateFlow()
    private val mutableSaveError = MutableStateFlow<String?>(null)
    val saveError = mutableSaveError.asStateFlow()
    val log = LogBuffer(scope, 120000)
    @Volatile private var connection: BridgeConnection? = null
    @Volatile private var job = ""
    @Volatile private var workspaceId = ""
    @Volatile private var conversationId = ""
    @Volatile private var runtimeSessionId = ""
    @Volatile private var proposalRevision = "initial"
    @Volatile private var title = "新会话"
    @Volatile private var cancelRequested = false
    val projectId get() = workspaceId
    val running get() = mutableState.value.running
    private val mutableEngine = MutableStateFlow(AgentEngine.fromId(settings.value.agentEngine))
    val selectedEngine = mutableEngine.asStateFlow()
    private val mutableEngines = MutableStateFlow<List<AgentEngineAvailability>>(emptyList())
    val availableEngines = mutableEngines.asStateFlow()
    val engine get() = mutableEngine.value.displayName
    val profile get() = if (accountSettings.value.agentUseFree && mutableEngine.value == AgentEngine.PI) dev.aster.ide.account.FreeAgentProfile.value(accountSettings.value.agentFreeTier) else settings.value.agentProfile(mutableEngine.value)

    suspend fun refreshEngines() = withContext(Dispatchers.IO) {
        val result = bridge.rpc("agent_engines", workspaceId.ifBlank { "default" }, JSONObject())
        check(result.optBoolean("ok")) { result.optString("error") }
        val list = result.optJSONArray("engines") ?: JSONArray()
        mutableEngines.value = (0 until list.length()).map { i -> list.getJSONObject(i).let {
            AgentEngineAvailability(it.getString("id"), it.optBoolean("available"), it.optString("reason"), it.optString("version"))
        } }
    }

    init {
        secrets.select(mutableEngine.value)
        scope.launch(Dispatchers.IO) {
            saves.debounce(400).filterNotNull().collect { snapshot ->
                try { save(snapshot) } catch (e: CancellationException) { throw e } catch (_: Exception) { mutableSaveError.value = "会话保存失败，请检查可用存储空间" }
            }
        }
    }
    private fun snapshot(): AgentSnapshot? {
        if (workspaceId.isBlank() || conversationId.isBlank()) return null
        val updated = clock.updateAndGet { previous -> maxOf(previous + 1, System.currentTimeMillis()) }
        val state = mutableState.value
        return AgentSnapshot(conversationId, workspaceId, title, updated, mutableMode.value, mutableMessages.value, mutableTasks.value,
            mutableChanges.value, state.status, state.step, state.tokens, state.running, runtimeSessionId, mutableContext.value, proposalRevision, mutableEngine.value.id)
    }
    private fun checkpoint() {
        snapshot()?.let { snapshot ->
            mutableConversation.value = AgentConversationInfo(snapshot.id, snapshot.title, snapshot.updated, snapshot.mode, snapshot.changes.size, snapshot.engineId)
            saves.value = snapshot
        }
    }
    private suspend fun save(snapshot: AgentSnapshot) = persistence.withLock {
        if (snapshot.workspaceId + ":" + snapshot.id in deleted) return@withLock
        store.save(snapshot)
        if (snapshot.workspaceId == workspaceId) mutableConversations.value = store.list(workspaceId)
        mutableSaveError.value = null
    }
    suspend fun persistNow() = withContext(Dispatchers.IO) { saves.value?.let { save(it) } }

    private fun restore(snapshot: AgentSnapshot) {
        clock.updateAndGet { maxOf(it, snapshot.updated) }
        conversationId = snapshot.id; title = snapshot.title; runtimeSessionId = snapshot.runtimeSessionId
        mutableContext.value = snapshot.context; proposalRevision = snapshot.proposalRevision
        mutableEngine.value = AgentEngine.fromId(snapshot.engineId)
        secrets.select(mutableEngine.value)
        settings.update { it.copy(agentEngine = snapshot.engineId) }
        mutableMode.value = snapshot.mode
        mutableMessages.value = snapshot.messages; mutableChanges.value = snapshot.changes; mutableTasks.value = snapshot.tasks
        mutableState.value = AgentState(status = if (snapshot.wasRunning) "上次任务已中断，可继续发送" else snapshot.status, step = snapshot.step, tokens = snapshot.tokens)
        mutableConversation.value = AgentConversationInfo(snapshot.id, title, snapshot.updated, snapshot.mode, snapshot.changes.size, snapshot.engineId)
        log.clear(); checkpoint()
    }
    private suspend fun createConversation() {
        conversationId = UUID.randomUUID().toString(); title = "新会话"; runtimeSessionId = conversationId; proposalRevision = "initial"
        mutableContext.value = AgentContextState(window = profile.contextWindow)
        mutableMessages.value = emptyList(); mutableChanges.value = emptyList(); mutableTasks.value = emptyList()
        mutableState.value = AgentState(); log.clear()
        store.select(workspaceId, conversationId)
        checkpoint(); persistNow()
    }
    suspend fun selectWorkspace(identifier: String) = withContext(Dispatchers.IO) {
        selecting.withLock {
            if (workspaceId == identifier && conversationId.isNotBlank()) return@withLock
            check(!running) { "请先停止 Agent" }
            persistNow()
            workspaceId = identifier
            val entries = store.list(identifier)
            mutableConversations.value = entries
            val selected = store.selected(identifier) ?: entries.firstOrNull()?.id
            val restored = selected?.let { store.load(identifier, it) }
            if (restored != null) { restore(restored); store.select(identifier, restored.id) } else createConversation()
        }
    }
    suspend fun newConversation() = withContext(Dispatchers.IO) {
        selecting.withLock {
            check(!running && workspaceId.isNotBlank()) { "请等待 Agent 完成" }
            persistNow(); createConversation()
        }
    }
    suspend fun selectEngine(engine: AgentEngine) = withContext(Dispatchers.IO) {
        selecting.withLock {
            check(!running && workspaceId.isNotBlank()) { "请等待 Agent 完成" }
            if (mutableEngine.value == engine) return@withLock
            persistNow()
            mutableEngine.value = engine; secrets.select(engine)
            settings.update { it.copy(agentEngine = engine.id) }
            val selected = store.list(workspaceId).firstOrNull { it.engineId == engine.id }
            val saved = selected?.let { store.load(workspaceId, it.id) }
            if (saved == null) createConversation() else { restore(saved); store.select(workspaceId, saved.id) }
        }
    }
    suspend fun selectConversation(identifier: String) = withContext(Dispatchers.IO) {
        selecting.withLock {
            check(!running) { "请等待 Agent 完成" }
            if (identifier == conversationId) return@withLock
            persistNow()
            val snapshot = store.load(workspaceId, identifier) ?: error("会话无法恢复")
            restore(snapshot); store.select(workspaceId, identifier)
        }
    }
    suspend fun deleteConversation(identifier: String) = withContext(Dispatchers.IO) {
        selecting.withLock {
            check(!running) { "请等待 Agent 完成" }
            deleted.add(workspaceId + ":" + identifier)
            persistence.withLock { store.delete(workspaceId, identifier) }
            if (identifier == conversationId) createConversation()
            mutableConversations.value = store.list(workspaceId)
        }
    }
    fun setMode(mode: AgentMode) {
        check(!running) { "请等待 Agent 完成" }
        mutableMode.value = mode
        settings.update { it.copy(agentMode = mode.id) }
        checkpoint()
    }
    private fun append(role: String, text: String) {
        if (text.isBlank()) return
        mutableMessages.update { boundedAgentMessages(it + AgentMessage(role = role, text = text.take(24000))) }
        checkpoint()
    }
    private fun streamed(identifier: String, text: String, replace: Boolean = false) {
        mutableMessages.update { messages ->
            val existing = messages.firstOrNull { it.id == identifier }
            val row = AgentMessage(identifier, "assistant", if (replace) text.take(24000) else ((existing?.text ?: "") + text).take(24000))
            boundedAgentMessages(if (existing == null) messages + row else messages.map { if (it.id == identifier) row else it })
        }
        checkpoint()
    }
    suspend fun run(workspace: Workspace, task: String, context: String, overlays: JSONArray, compactOnly: Boolean = false, onAccepted: () -> Unit = {}) = withContext(Dispatchers.IO) {
        check(!running) { "Agent 已在运行" }
        selectWorkspace(workspace.id)
        val preferences = settings.value
        val selected = mutableEngine.value
        val usingFree = accountSettings.value.agentUseFree && selected == AgentEngine.PI
        val tier = accountSettings.value.agentFreeTier
        val configuration = if (usingFree) dev.aster.ide.account.FreeAgentProfile.value(tier) else preferences.agentProfile(selected)
        AgentContextPolicy.validate(selected, configuration.contextWindow, configuration.maxTokens)
        AgentThinkingPolicy.validate(selected, configuration)
        check(configuration.endpoint.isNotBlank() && configuration.model.isNotBlank()) { "请先在设置 → Agent 中配置 API 地址和模型名称" }
        val apiKey = if (usingFree) account.freeToken().also { account.requireFreeAvailable(tier) } else secrets.read(selected)
        val previousChanges = mutableChanges.value
        cancelRequested = false
        val history = JSONArray(mutableMessages.value.filter { it.role == "user" || it.role == "assistant" }.takeLast(60).map { JSONObject().put("role", it.role).put("content", it.text) })
        if (!compactOnly && title == "新会话") title = task.replace('\n', ' ').take(64)
        if (!compactOnly) append("user", task)
        log.clear()
        val identifier = UUID.randomUUID().toString()
        job = identifier
        mutableState.value = AgentState(running = true, status = "连接 Agent")
        checkpoint()
        val request = JSONObject().put("job", identifier).put("engineId", selected.id).put("sessionId", runtimeSessionId).put("proposalRevision", proposalRevision).put("compactOnly", compactOnly).put("task", task).put("context", context).put("overlays", overlays).put("history", history)
            .put("mode", mutableMode.value.id).put("tasks", AgentJson.tasks(mutableTasks.value)).put("stagedChanges", AgentJson.changes(previousChanges))
            .put("options", bridge.options()).put("excludedDirs", JSONArray(preferences.excludedDirs.split(",").map(String::trim).filter(String::isNotEmpty)))
            .put("configuration", JSONObject().put("endpoint", configuration.endpoint).put("model", configuration.model)
                .put("apiKey", apiKey).put("freeMode", usingFree).put("protocol", configuration.protocol).put("reviewEnabled", configuration.reviewEnabled).put("reviewModel", configuration.reviewModel).put("mcpServers", configuration.mcpServers).put("enabledSkills", JSONArray(configuration.enabledSkills.split(",").map { it.trim() }.filter { it.isNotEmpty() })).put("thinkingLevel", configuration.thinkingLevel).put("thinkingMode", configuration.thinkingMode).put("thinkingBudget", configuration.thinkingBudget).put("maxSteps", if (usingFree) dev.aster.ide.account.FreeAgentProfile.MAX_TOOL_CALLS else preferences.agentMaxSteps).put("maxTokens", configuration.maxTokens).put("contextWindow", configuration.contextWindow).put("stream", preferences.agentStreaming))
        try {
            bridge.open("agent", workspace.id, request, 10000).use { active ->
                connection = active
                if (cancelRequested) throw CancellationException("Agent 已停止")
                val acknowledgment = active.receive()
                check(acknowledgment.optBoolean("ok")) { acknowledgment.optString("error", "Agent 启动失败") }
                check(acknowledgment.optString("engineId", "pi") == selected.id && acknowledgment.optString("engine") == selected.displayName) { "Agent 引擎需要更新，请重启内置环境" }
                onAccepted()
                active.socket.soTimeout = 0
                while (true) {
                    ensureActive()
                    val event = active.receive()
                    if (event.optString("type") == "error") error(event.optString("error"))
                    when (event.optString("kind")) {
                        "status" -> mutableState.value = mutableState.value.copy(status = event.optString("text"), step = event.optInt("step"), approval = null)
                        "context" -> mutableContext.value = AgentContextState(event.optInt("tokens").coerceAtLeast(0), event.optInt("contextWindow", configuration.contextWindow).coerceAtLeast(1), event.optInt("compactions").coerceAtLeast(0), AgentContextPolicy.source(event.optString("source")), System.currentTimeMillis())
                        "message" -> append(event.optString("role", "assistant"), event.optString("text"))
                        "message_start" -> streamed(event.getString("messageId"), "", true)
                        "message_delta" -> streamed(event.getString("messageId"), event.optString("text"))
                        "message_end" -> streamed(event.getString("messageId"), event.optString("text"), true)
                        "plan" -> mutableTasks.value = AgentJson.readTasks(event.optJSONArray("tasks"))
                        "tool", "tool_result" -> {
                            val name = event.optString("name")
                            val callId = event.optString("callId")
                            val result = event.optString("kind") == "tool_result"
                            val status = if (!result) "运行中" else if (event.optString("status") == "failed") "失败" else "完成"
                            val duration = if (result && event.has("durationMs")) " · ${event.optLong("durationMs")} ms" else ""
                            val heading = "$name · ${toolLabel(name)} · $status$duration"
                            val text = event.optString("text")
                            if (callId.isBlank()) append("tool", "$heading\n$text") else {
                                val id = "tool-$callId"
                                mutableMessages.update { rows ->
                                    val previous = rows.firstOrNull { it.id == id }
                                    val arguments = previous?.text?.substringAfter("\n参数\n")?.substringBefore("\n结果\n")
                                    val body = if (result) "参数\n${arguments ?: ""}\n结果\n$text" else "参数\n$text"
                                    val row = AgentMessage(id, "tool", (heading + "\n" + body).take(24000))
                                    boundedAgentMessages(if (previous == null) rows + row else rows.map { if (it.id == id) row else it })
                                }
                            }
                            log.append("$heading\n$text\n")
                        }
                        "client_tool" -> {
                            val result = try {
                                check(mutableMode.value == AgentMode.EXECUTE) { "Client tools require execute mode" }
                                clientTools.execute(event.getString("name"), event.getJSONObject("arguments"))
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { JSONObject().put("ok", false).put("error", e.message ?: "Client operation failed") }
                            bridge.rpc("agent_client_result", workspace.id, JSONObject().put("job", identifier).put("requestId", event.getString("requestId")).put("result", result))
                        }
                        "approval" -> {
                            val approval = AgentApproval(event.getString("requestId"), event.getString("command"), event.optInt("timeout", 30))
                            mutableState.value = mutableState.value.copy(status = "等待运行确认", approval = approval)
                        }
                        "command" -> {
                            val text = "$ ${event.optString("command")}\n${event.optString("text")}\n[exit ${event.optInt("code")}]\n"
                            log.append(text); append("tool", text)
                            mutableState.value = mutableState.value.copy(approval = null)
                        }
                        "result" -> {
                            val status = event.optString("status")
                            val summary = event.optString("summary")
                            if (summary.isNotBlank() && mutableMessages.value.lastOrNull { it.role == "assistant" }?.text != summary) append("assistant", summary)
                            val incoming = AgentJson.readChanges(event.optJSONArray("changes"))
                            mutableChanges.value = if ((status == "Failed" && incoming.isEmpty())) previousChanges else incoming
                            mutableTasks.value = AgentJson.readTasks(event.optJSONArray("tasks"))
                            mutableState.value = AgentState(status = when (status) {
                                "Submitted" -> "完成"; "Cancelled" -> "已停止"; "LimitsExceeded" -> "已达到步骤上限"; "TimeExceeded" -> "已达到时间上限"; "Failed" -> "失败"; else -> status
                            }, step = event.optInt("step"), tokens = event.optInt("tokens"))
                            checkpoint(); break
                        }
                        "error" -> {
                            append("error", event.optString("text", "Agent 运行失败"))
                            if (!event.optBoolean("recoverable")) error(event.optString("text", "Agent 运行失败"))
                        }
                    }
                    checkpoint()
                }
            }
        } catch (e: CancellationException) {
            mutableState.value = mutableState.value.copy(running = false, status = "已停止", approval = null); throw e
        } catch (e: Exception) {
            if (cancelRequested) { mutableState.value = mutableState.value.copy(running = false, status = "已停止", approval = null); throw CancellationException("Agent 已停止", e) }
            mutableState.value = mutableState.value.copy(running = false, status = "失败", approval = null)
            append("error", e.message ?: "Agent 连接中断"); throw e
        } finally {
            connection = null
            if (mutableState.value.running) mutableState.value = mutableState.value.copy(running = false, approval = null)
            job = ""; checkpoint()
            withContext(NonCancellable) { runCatching { persistNow() }.onFailure { mutableSaveError.value = "会话保存失败，请检查可用存储空间" } }
        }
    }
    suspend fun decide(accepted: Boolean) {
        val approval = mutableState.value.approval ?: return
        val result = bridge.rpc("agent_decision", workspaceId, JSONObject().put("job", job).put("requestId", approval.requestId).put("accepted", accepted))
        check(result.optBoolean("accepted")) { "运行请求已结束" }
        mutableState.update { current ->
            if (current.running && current.approval?.requestId == approval.requestId) current.copy(approval = null, status = if (accepted) "正在运行命令" else "已拒绝运行") else current
        }
        checkpoint()
    }
    suspend fun cancel() {
        cancelRequested = true
        val identifier = job
        if (identifier.isNotEmpty()) runCatching { bridge.rpc("agent_cancel", workspaceId, JSONObject().put("job", identifier), 1500) }
        withTimeoutOrNull(1500) { mutableState.first { !it.running } }
        connection?.close()
        mutableState.value = mutableState.value.copy(running = false, status = "已停止", approval = null)
        checkpoint()
    }
    fun clear() {
        check(!running)
        runtimeSessionId = UUID.randomUUID().toString(); mutableContext.value = AgentContextState(window = profile.contextWindow)
        mutableMessages.value = emptyList(); mutableChanges.value = emptyList(); mutableTasks.value = emptyList()
        mutableState.value = AgentState(); title = "新会话"; log.clear(); checkpoint()
    }
    fun discardChanges() { proposalRevision = UUID.randomUUID().toString(); mutableChanges.value = emptyList(); checkpoint() }
    fun close() { cancelRequested = true; connection?.close(); connection = null; checkpoint() }

    private fun toolLabel(name: String) = when (name) {
        "list_files" -> "浏览项目"; "read_file" -> "读取文件"; "read_files" -> "批量读取"; "search_project" -> "搜索项目"
        "project_info" -> "检查项目"; "get_changes" -> "检查修改"; "write_file" -> "准备文件"; "replace_text" -> "精确替换"
        "write_files" -> "批量写入"; "apply_edits" -> "批量编辑"; "delete_file" -> "准备删除"; "move_file" -> "准备移动"
        "update_plan" -> "更新任务清单"; "run_command" -> "请求运行验证"; "finish" -> "完成"; else -> name
    }
}
