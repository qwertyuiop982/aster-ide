package dev.aster.ide.lsp

import dev.aster.ide.feature.BuildConfig
import dev.aster.ide.runtime.BridgeConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class LspClient(
    private val connection: BridgeConnection,
    parent: CoroutineScope,
    private val timeoutSeconds: Int,
    private val configuration: JSONObject,
    private val onNotification: (String, JSONObject) -> Unit,
    private val onLog: (String) -> Unit
) : Closeable {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]) + Dispatchers.IO)
    private val outgoing = Channel<JSONObject>(64)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Any?>>()
    private val ids = AtomicInteger()
    @Volatile var closed = false
        private set
    var capabilities: JSONObject = JSONObject()
        private set
    var syncKind = 1
        private set

    init {
        connection.socket.soTimeout = 0
        scope.launch {
            try { for (message in outgoing) LspFraming.write(connection.output, message) }
            catch (e: Exception) { if (!closed) onLog("LSP write: ${e.message}"); close() }
        }
        scope.launch {
            try {
                while (isActive) {
                    val message = LspFraming.read(connection.input)
                    val method = message.optString("method")
                    if (method.isNotEmpty()) {
                        val params = message.optJSONObject("params") ?: JSONObject()
                        if (message.has("id")) answer(message, method, params)
                        else onNotification(method, params)
                    } else if (message.has("id")) {
                        val deferred = pending.remove(message.optInt("id")) ?: continue
                        message.optJSONObject("error")?.let { deferred.completeExceptionally(IllegalStateException(it.optString("message", "LSP request failed"))) }
                            ?: deferred.complete(message.opt("result").takeUnless { it == JSONObject.NULL })
                    }
                }
            } catch (e: Exception) { if (!closed) onLog("LSP disconnected: ${e.message}") }
            finally { close() }
        }
    }

    private suspend fun answer(message: JSONObject, method: String, params: JSONObject) {
        val result: Any = when (method) {
            "workspace/configuration" -> JSONArray().apply {
                params.optJSONArray("items")?.objects()?.forEach { item ->
                    val section = item.optString("section")
                    var current: Any = configuration
                    if (section.isNotEmpty()) for (part in section.split('.')) current = (current as? JSONObject)?.opt(part) ?: JSONObject.NULL
                    put(current)
                }
            }
            "workspace/workspaceFolders" -> workspaceFolders
            "workspace/applyEdit" -> JSONObject().put("applied", false).put("failureReason", "Use Aster's previewable editor actions")
            "window/workDoneProgress/create", "client/registerCapability", "client/unregisterCapability" -> JSONObject.NULL
            else -> JSONObject.NULL
        }
        val response = JSONObject().put("jsonrpc", "2.0").put("id", message.get("id"))
        if (method in setOf("workspace/configuration", "workspace/workspaceFolders", "workspace/applyEdit", "window/workDoneProgress/create", "client/registerCapability", "client/unregisterCapability")) response.put("result", result)
        else response.put("error", JSONObject().put("code", -32601).put("message", "Method not supported"))
        outgoing.send(response)
    }
    private var workspaceFolders = JSONArray()

    suspend fun initialize(rootUri: String, name: String, initialization: JSONObject) {
        workspaceFolders = JSONArray().put(JSONObject().put("uri", rootUri).put("name", name))
        val documentCapabilities = JSONObject()
            .put("synchronization", JSONObject().put("didSave", true).put("dynamicRegistration", false))
            .put("completion", JSONObject().put("completionItem", JSONObject().put("snippetSupport", false).put("documentationFormat", JSONArray(listOf("markdown", "plaintext"))).put("resolveSupport", JSONObject().put("properties", JSONArray(listOf("documentation", "detail", "additionalTextEdits"))))))
            .put("hover", JSONObject().put("contentFormat", JSONArray(listOf("markdown", "plaintext"))))
            .put("signatureHelp", JSONObject().put("signatureInformation", JSONObject().put("documentationFormat", JSONArray(listOf("markdown", "plaintext"))).put("parameterInformation", JSONObject().put("labelOffsetSupport", true))))
            .put("definition", JSONObject().put("linkSupport", true))
            .put("references", JSONObject())
            .put("documentSymbol", JSONObject().put("hierarchicalDocumentSymbolSupport", true))
            .put("publishDiagnostics", JSONObject().put("versionSupport", true).put("relatedInformation", true))
            .put("formatting", JSONObject())
            .put("rename", JSONObject().put("prepareSupport", false))
            .put("codeAction", JSONObject().put("codeActionLiteralSupport", JSONObject().put("codeActionKind", JSONObject().put("valueSet", JSONArray(listOf("quickfix", "refactor", "source.organizeImports"))))))
        val caps = JSONObject().put("general", JSONObject().put("positionEncodings", JSONArray(listOf("utf-16"))))
            .put("offsetEncoding", JSONArray(listOf("utf-16")))
            .put("textDocument", documentCapabilities)
            .put("workspace", JSONObject().put("configuration", true).put("workspaceFolders", true).put("applyEdit", false).put("workspaceEdit", JSONObject().put("documentChanges", true)))
            .put("window", JSONObject().put("workDoneProgress", true))
        val result = request("initialize", JSONObject().put("processId", JSONObject.NULL).put("rootUri", rootUri)
            .put("clientInfo", JSONObject().put("name", "Aster IDE").put("version", BuildConfig.VERSION_NAME))
            .put("capabilities", caps).put("workspaceFolders", workspaceFolders).put("initializationOptions", initialization), 45000) as? JSONObject ?: error("Invalid initialize result")
        capabilities = result.optJSONObject("capabilities") ?: JSONObject()
        check(capabilities.optString("positionEncoding", "utf-16") == "utf-16") { "This server requires an unsupported position encoding" }
        val sync = capabilities.opt("textDocumentSync")
        syncKind = when (sync) { is Number -> sync.toInt(); is JSONObject -> sync.optInt("change", 1); else -> 1 }
        notify("initialized", JSONObject())
        notify("workspace/didChangeConfiguration", JSONObject().put("settings", configuration))
    }

    fun supports(capability: String): Boolean = capabilities.opt(capability).let { it != null && it != JSONObject.NULL && it != false }
    suspend fun notify(method: String, params: JSONObject) {
        check(!closed) { "语言服务器已关闭" }
        outgoing.send(JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params))
    }
    suspend fun request(method: String, params: JSONObject, timeout: Long = timeoutSeconds * 1000L): Any? {
        check(!closed) { "语言服务器已关闭" }
        check(pending.size < 64) { "过多的 LSP 请求" }
        val id = ids.incrementAndGet()
        val response = CompletableDeferred<Any?>()
        pending[id] = response
        try {
            outgoing.send(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params))
            return withTimeout(timeout) { response.await() }
        } catch (e: CancellationException) {
            outgoing.trySend(JSONObject().put("jsonrpc", "2.0").put("method", "$/cancelRequest").put("params", JSONObject().put("id", id)))
            throw e
        } finally { pending.remove(id) }
    }
    suspend fun shutdown() {
        if (!closed) {
            runCatching { request("shutdown", JSONObject(), 1500); notify("exit", JSONObject()) }
            delay(50)
        }
        close()
    }
    override fun close() {
        if (closed) return
        closed = true
        connection.close()
        outgoing.close()
        pending.values.forEach { it.completeExceptionally(IllegalStateException("语言服务器连接已断开")) }
        pending.clear()
        scope.cancel()
    }
}