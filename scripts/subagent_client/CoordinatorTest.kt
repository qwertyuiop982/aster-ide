package dev.aster.ide.agent

import dev.aster.ide.runtime.BridgeClient
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

fun main(args: Array<String>) = runBlocking {
    val bridge = BridgeClient(args[0].toInt())
    val profile = AgentProfile(args[1], "fixture-primary", maxTokens = 2048, thinkingLevel = "off", thinkingMode = "off")
    val configuration = JSONObject().put("endpoint", profile.endpoint).put("model", profile.model).put("apiKey", "fixture-child-secret")
        .put("contextWindow", 32768).put("maxTokens", 2048).put("stream", false).put("mcpServers", "terminal").put("enabledSkills", JSONArray().put("fixture"))
    val alternate = profile.copy(model = "fixture-secondary")
    val choices = listOf(SubagentModelChoice("current", AgentEngine.PI, profile, configuration),
        SubagentModelChoice("pi:secondary", AgentEngine.PI, alternate, JSONObject(configuration.toString()).put("model", alternate.model)))
    val events = Collections.synchronizedList(mutableListOf<JSONObject>())
    fun coordinator() = AgentSubagents(bridge, "project", "parent", AgentMode.EXECUTE, configuration, choices,
        { _, event -> events.add(event) }, { _, _ -> JSONObject().put("ok", true) })
    val active = coordinator()
    val catalog = active.execute(JSONObject().put("action", "models"))
    check(catalog.getJSONArray("models").length() == 2)
    check(!catalog.toString().contains("fixture-child-secret"))
    val tasks = JSONArray()
    repeat(4) { i ->
        tasks.put(JSONObject().put("model_id", if (i % 2 == 0) "current" else "pi:secondary")
            .put("prompt", "task-$i read shared.txt and write result-$i.txt")
            .put("system_prompt", "SYSTEM-$i: complete the assigned fixture only")
            .put("thinking_level", "low").put("max_steps", 8).put("timeout", 30))
    }
    val result = active.execute(JSONObject().put("action", "parallel").put("tasks", tasks))
    check(result.optBoolean("ok")) { result.toString() }
    val results = result.getJSONArray("results")
    check(results.length() == 4)
    check((0 until 4).map { results.getJSONObject(it).getString("subagent_id") }.distinct().size == 4)
    check(result.getString("orchestrator") == "android_client")
    check(events.any { it.optString("kind") == "tool" })
    for (invalid in listOf(
        JSONObject().put("action", "parallel").put("tasks", JSONArray((0..4).map { tasks.getJSONObject(0) })),
        JSONObject().put("action", "run").put("model_id", "missing").put("prompt", "no request"),
        JSONObject().put("action", "run").put("prompt", "invalid thinking").put("thinking_level", "unsupported"),
        JSONObject().put("action", "run").put("prompt", "invalid timeout").put("timeout", 14),
        JSONObject().put("action", "parallel").put("tasks", JSONArray().put(tasks.getJSONObject(0)).put(JSONObject().put("prompt", " ")))
    )) {
        var rejected = false
        try { active.execute(invalid) } catch (error: IllegalArgumentException) { rejected = true }
        catch (error: IllegalStateException) { rejected = true }
        check(rejected) { "Invalid request was not rejected" }
    }
    val failure = active.execute(JSONObject().put("action", "run").put("prompt", "fail-provider"))
    check(!failure.optBoolean("ok"))
    check(!failure.toString().contains("fixture-child-secret"))
    val cancelling = coordinator()
    val pending = async(Dispatchers.IO) { cancelling.execute(JSONObject().put("action", "run").put("prompt", "slow-cancel")) }
    delay(800)
    cancelling.cancel()
    withTimeout(8000) { pending.await() }
    var rejectedAfterCancel = false
    try { cancelling.execute(JSONObject().put("action", "models")) } catch (error: IllegalStateException) { rejectedAfterCancel = true }
    check(rejectedAfterCancel)
    active.cancel()
    println(JSONObject().put("success", true).put("parallel_tasks", 4).put("models", 2)
        .put("independent_sessions", true).put("all_tasks_validated_before_start", true)
        .put("cancellation", true).put("redacted_provider_failure", true).put("tool_events", true))
}