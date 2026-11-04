package dev.aster.ide.agent

import dev.aster.ide.runtime.BridgeClient
import dev.aster.ide.runtime.BridgeConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Client-owned model selection, isolated sessions, scheduling and cancellation. */
internal data class SubagentModelChoice(
    val id: String, val engine: AgentEngine, val profile: AgentProfile, val configuration: JSONObject
)

internal class AgentSubagents(
    private val bridge: BridgeClient,
    private val workspace: String,
    private val parentJob: String,
    private val mode: AgentMode,
    private val parentConfiguration: JSONObject,
    choices: List<SubagentModelChoice>,
    private val event: (String, JSONObject) -> Unit,
    private val clientTool: suspend (String, JSONObject) -> JSONObject
) {
    private val models = choices.associateBy { it.id }
    private val unlimited = parentConfiguration.optBoolean("subagentsUnlimited") && parentConfiguration.optString("thinkingLevel") == "ultra"
    private val parallelism = if (unlimited) null else Semaphore(2)
    private val connections = ConcurrentHashMap<String, BridgeConnection>()
    @Volatile private var cancelled = false

    private data class Task(
        val choice: SubagentModelChoice, val prompt: String, val system: String,
        val configuration: JSONObject, val timeout: Int, val id: String = "sub-" + UUID.randomUUID().toString()
    )

    suspend fun execute(arguments: JSONObject): JSONObject {
        check(!cancelled) { "子代理任务已停止" }
        val action = arguments.optString("action", "run")
        if (action == "models") return JSONObject().put("ok", true).put("models", JSONArray(models.values.map { choice ->
            JSONObject().put("model_id", choice.id).put("engine", choice.engine.id).put("model", choice.profile.model)
                .put("thinking_levels", JSONArray(AgentThinkingPolicy.available(choice.engine, choice.profile)))
                .put("context_window", choice.profile.contextWindow)
        }))
        require(action in setOf("run", "parallel")) { "未知子代理操作" }
        val incoming = arguments.optJSONArray("tasks") ?: JSONArray().put(arguments)
        require(incoming.length() >= 1 && (unlimited || incoming.length() <= 4)) { if (unlimited) "至少需要一个子代理任务" else "每批需要 1–4 个子代理任务" }
        // Resolve and validate every task before starting any child or provider request.
        val tasks = (0 until incoming.length()).map { prepare(incoming.getJSONObject(it)) }
        tasks.forEach { task -> event(task.choice.profile.model + " · " + task.id.takeLast(8), JSONObject().put("kind", "queued").put("subagentId", task.id).put("engineId", task.choice.engine.id).put("model", task.choice.profile.model).put("prompt", task.prompt)) }
        val results = coroutineScope {
            tasks.map { task -> async(Dispatchers.IO) { if (parallelism == null) run(task) else parallelism.withPermit { run(task) } } }.awaitAll()
        }
        return JSONObject().put("ok", results.all { it.optBoolean("ok") }).put("results", JSONArray(results))
            .put("shared_environment", true).put("independent_contexts", true).put("max_parallel", if (unlimited) JSONObject.NULL else 2).put("unlimited", unlimited).put("orchestrator", "android_client")
    }

    private fun integer(value: JSONObject, key: String, default: Int, range: IntRange): Int {
        if (!value.has(key)) return default
        val raw = value.get(key)
        require(raw is Number && raw.toDouble() == raw.toInt().toDouble() && raw.toInt() in range) { "$key 需要 ${range.first}–${range.last} 的整数" }
        return raw.toInt()
    }

    private fun prepare(value: JSONObject): Task {
        val choice = models[value.optString("model_id", "current")] ?: error("子代理模型未配置；请先调用 subagent/models")
        val prompt = value.opt("prompt")
        val system = value.opt("system_prompt") ?: ""
        require(prompt is String && prompt.isNotBlank() && prompt.length <= 12000) { "子代理 Prompt 需要 1–12000 字符" }
        require(system is String && system.length <= 12000) { "子代理 system 提示词最多 12000 字符" }
        var profile = choice.profile
        if (value.has("thinking_level")) {
            val level = value.getString("thinking_level")
            profile = profile.copy(thinkingLevel = level, thinkingMode = if (level in setOf("auto", "off")) level else "legacy")
        }
        if (value.has("thinking_budget")) profile = profile.copy(thinkingMode = "budget", thinkingBudget = integer(value, "thinking_budget", 1024, 1..32768))
        AgentContextPolicy.validate(choice.engine, profile.contextWindow, profile.maxTokens)
        AgentThinkingPolicy.validate(choice.engine, profile)
        val config = JSONObject(choice.configuration.toString())
            .put("thinkingLevel", profile.thinkingLevel).put("thinkingMode", profile.thinkingMode).put("thinkingBudget", profile.thinkingBudget)
            .put("maxSteps", integer(value, "max_steps", 16, 1..64)).put("reviewEnabled", false)
            .put("permissionMode", parentConfiguration.optString("permissionMode", "review"))
            .put("nativeCommandsUnchecked", parentConfiguration.optBoolean("nativeCommandsUnchecked"))
            .put("longTaskMode", parentConfiguration.optBoolean("longTaskMode", false))
            .put("subagentsUnlimited", unlimited)
            .put("mcpServers", parentConfiguration.optString("mcpServers"))
            .put("enabledSkills", parentConfiguration.optJSONArray("enabledSkills") ?: JSONArray())
        return Task(choice, prompt, system, config, integer(value, "timeout", 300, 15..900))
    }

    private suspend fun run(task: Task): JSONObject {
        val identifier = task.id
        val label = task.choice.profile.model + " · " + identifier.takeLast(8)
        fun emit(value: JSONObject) = event(label, JSONObject(value.toString()).put("subagentId", identifier).put("engineId", task.choice.engine.id).put("model", task.choice.profile.model))
        emit(JSONObject().put("kind", "started").put("prompt", task.prompt))
        val request = JSONObject().put("job", identifier).put("sessionId", identifier).put("proposalRevision", "initial")
            .put("parentJob", parentJob).put("isSubagent", true).put("engineId", task.choice.engine.id)
            .put("mode", mode.id).put("task", task.prompt).put("systemPrompt", task.system)
            .put("context", "").put("history", JSONArray()).put("tasks", JSONArray()).put("overlays", JSONArray())
            .put("configuration", task.configuration).put("timeoutSeconds", task.timeout).put("options", bridge.options())
        try {
            check(!cancelled) { "子代理任务已停止" }
            return coroutineScope {
                bridge.open("agent", workspace, request, 10000).use { connection ->
                    connections[identifier] = connection
                    check(!cancelled) { "子代理任务已停止" }

                    val clientTasks = ConcurrentHashMap<String, Job>()
                    val reader = async(Dispatchers.IO) {
                        val ack = connection.receive()
                        check(ack.optBoolean("ok")) { ack.optString("error", "子代理未能启动") }
                        connection.socket.soTimeout = 0
                        var failure = ""
                        while (true) {
                            ensureActive()
                            val value = connection.receive()
                            if (value.optString("type") == "error") error(value.optString("error"))
                            when (value.optString("kind")) {
                                "client_tool" -> {
                                    val requestId = value.getString("requestId")
                                    val task = launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                                        try {
                                            val result = try {
                                                val name = value.getString("name")
                                                check(name != "subagent" && (name == "user_input" || parentConfiguration.optString("permissionMode") == "native" || mode == AgentMode.EXECUTE)) { "子代理不能创建子代理或越过主代理模式" }
                                                clientTool(name, value.getJSONObject("arguments").also { if (name == "user_input") it.put("_requestId", requestId) })
                                            } catch (e: CancellationException) { throw e }
                                            catch (e: Exception) { JSONObject().put("ok", false).put("error", e.message) }
                                            val reply = bridge.rpc("agent_client_result", workspace, JSONObject().put("job", identifier).put("requestId", requestId).put("result", result))
                                            check(reply.optBoolean("accepted") || !clientTasks.containsKey(requestId)) { "子代理交互结果未被接受" }
                                        } finally { clientTasks.remove(requestId) }
                                    }
                                    clientTasks[requestId] = task
                                    task.start()
                                }
                                "client_tool_end" -> {
                                    clientTasks.remove(value.getString("requestId"))?.cancel()
                                    emit(value)
                                }
                                "result" -> {
                                    emit(value)
                                    return@async JSONObject().put("ok", value.optString("status") == "Submitted").put("subagent_id", identifier)
                                        .put("model_id", task.choice.id).put("model", task.choice.profile.model).put("status", value.optString("status"))
                                        .put("summary", value.optString("summary").take(16000)).put("error", failure)
                                        .put("tokens", value.optInt("tokens")).put("steps", value.optInt("step"))
                                }
                                "approval" -> error("子代理审批未经过主任务路由")
                                "error" -> { failure = value.optString("text"); emit(value) }
                                "status", "tool", "tool_result", "command", "message", "message_start", "message_delta", "message_end" -> emit(value)
                            }
                        }
                        @Suppress("UNREACHABLE_CODE") JSONObject()
                    }
                    try {
                        withTimeout(task.timeout * 1000L + 15000) { reader.await() }
                    } finally {
                        withContext(NonCancellable) {
                            withTimeoutOrNull(2500) { runCatching { bridge.rpc("agent_cancel", workspace, JSONObject().put("job", identifier), 2000) } }
                            connection.close()
                            clientTasks.values.forEach { it.cancel() }
                            clientTasks.values.toList().joinAll()
                            reader.cancelAndJoin()
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            emit(JSONObject().put("kind", "result").put("status", "TimeExceeded"))
            return JSONObject().put("ok", false).put("subagent_id", identifier).put("model_id", task.choice.id).put("error", "子代理达到设置的时间上限")
        } catch (e: CancellationException) { emit(JSONObject().put("kind", "result").put("status", "Cancelled")); throw e }
        catch (e: Exception) {
            emit(JSONObject().put("kind", "error").put("text", AgentDiagnostics.redact(e.message ?: "子代理失败", models.values.map { it.configuration.optString("apiKey") }.filter(String::isNotBlank))))
            emit(JSONObject().put("kind", "result").put("status", "Failed"))
            return JSONObject().put("ok", false).put("subagent_id", identifier).put("model_id", task.choice.id)
                .put("error", AgentDiagnostics.redact(e.message ?: "子代理失败", models.values.map { it.configuration.optString("apiKey") }.filter(String::isNotBlank)))
        } finally {
            connections.remove(identifier)
        }
    }

    suspend fun cancel() {
        cancelled = true
        val active = connections.toMap()
        withContext(NonCancellable) {
            active.forEach { (id, connection) ->
                withTimeoutOrNull(2000) { runCatching { bridge.rpc("agent_cancel", workspace, JSONObject().put("job", id), 1500) } }
                connection.close()
            }
        }
    }
}