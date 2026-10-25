package dev.aster.ide.lsp

import dev.aster.ide.data.*
import dev.aster.ide.runtime.BridgeClient
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

class LspManager(
    private val bridge: BridgeClient,
    private val settings: SettingsStore,
    private val catalog: LanguageCatalog,
    private val scope: CoroutineScope,
    private val onDiagnostics: (String, List<LspDiagnostic>) -> Unit,
    private val onStatus: (String, String) -> Unit,
    private val onLog: (String) -> Unit
) : Closeable {
    private data class Server(val client: LspClient, @Volatile var used: Long)
    private class Document(val client: LspClient, val uri: String, var text: String) {
        var version = 1
        val mutex = Mutex()
    }
    private val servers = ConcurrentHashMap<String, Server>()
    private val documents = ConcurrentHashMap<String, Document>()
    private val starting = Mutex()
    private var workspace: Workspace? = null
    val count get() = servers.values.count { !it.client.closed }

    fun uri(path: String): String {
        val current = workspace ?: error("项目未打开")
        return URI("file", "", "${bridge.remoteRoot}/${current.id}/$path", null).toASCIIString()
    }
    fun relative(uri: String): String? = runCatching {
        val current = workspace ?: return null
        val path = URI(uri).path
        val root = "${bridge.remoteRoot}/${current.id}/"
        path.takeIf { it.startsWith(root) }?.removePrefix(root)
    }.getOrNull()

    suspend fun open(current: Workspace, doc: EditorDocument) {
        if (!settings.value.lspEnabled || doc.large) return
        val spec = catalog.forPath(doc.path) ?: return
        val pref = settings.value.servers[spec.id] ?: ServerPreference()
        if (!pref.enabled) return
        workspace = current
        starting.withLock {
            documents[doc.path]?.takeIf { !it.client.closed }?.let { return }
            val existing = servers[spec.id]?.takeIf { !it.client.closed }
            val client = if (existing != null) { existing.used = System.nanoTime(); existing.client }
            else {
                while (servers.size >= settings.value.maxServers) {
                    val victim = servers.minByOrNull { it.value.used } ?: break
                    victim.value.client.shutdown()
                    servers.remove(victim.key)
                    documents.entries.removeIf { it.value.client === victim.value.client }
                    onStatus(victim.key, "休眠")
                }
                onStatus(spec.id, "启动中")
                val connection = bridge.open("lsp", current.id, JSONObject().put("server", spec.id).put("command", pref.command))
                val ack = withContext(Dispatchers.IO) { connection.receive() }
                if (!ack.optBoolean("ok")) { connection.close(); error(ack.optString("error", "语言服务器启动失败")) }
                val config = JSONObject(pref.initializationOptions)
                if (spec.id == "python" && !config.has("python")) config.put("python", JSONObject().put("analysis", JSONObject().put("diagnosticMode", "openFilesOnly").put("typeCheckingMode", "basic").put("autoSearchPaths", true)))
                if (spec.id == "rust" && !config.has("rust-analyzer")) config.put("rust-analyzer", JSONObject().put("checkOnSave", false).put("cargo", JSONObject().put("buildScripts", JSONObject().put("enable", false))))
                val fresh = LspClient(connection, scope, settings.value.requestTimeout, config,
                    { method, params -> notification(spec.id, method, params) }, { onLog("${spec.id}: $it") })
                try {
                    val rootUri = URI("file", "", "${bridge.remoteRoot}/${current.id}", null).toASCIIString()
                    fresh.initialize(rootUri, current.name, JSONObject(pref.initializationOptions))
                    servers[spec.id] = Server(fresh, System.nanoTime())
                    onStatus(spec.id, "就绪")
                } catch (e: Exception) { fresh.close(); onStatus(spec.id, "启动失败"); throw e }
                fresh
            }
            val text = withContext(Dispatchers.Default) { doc.buffer?.toString() ?: doc.snapshot?.invoke() ?: doc.text }
            val opened = Document(client, uri(doc.path), text)
            documents[doc.path] = opened
            client.notify("textDocument/didOpen", JSONObject().put("textDocument", JSONObject().put("uri", opened.uri).put("languageId", spec.languageId(doc.path)).put("version", opened.version).put("text", text)))
        }
    }

    private fun notification(id: String, method: String, params: JSONObject) {
        when (method) {
            "textDocument/publishDiagnostics" -> {
                val uri = params.optString("uri")
                val path = relative(uri) ?: return
                val open = documents[path] ?: return
                if (params.has("version") && params.optInt("version") < open.version) return
                val values = params.optJSONArray("diagnostics")?.objects()?.take(2000)?.mapNotNull { d -> runCatching {
                    LspDiagnostic(uri, LspRange.from(d.getJSONObject("range")), d.optInt("severity", 1), d.getString("message"), d.optString("source", id), d)
                }.getOrNull() } ?: emptyList()
                onDiagnostics(path, values)
            }
            "window/logMessage", "window/showMessage" -> onLog("$id: ${params.optString("message")}")
            "$/progress" -> {
                val value = params.optJSONObject("value") ?: return
                if (value.optString("kind") == "begin") onLog("$id: ${value.optString("title")}")
            }
        }
    }

    suspend fun change(path: String, text: String) {
        val doc = documents[path] ?: return
        if (doc.client.closed) return
        doc.mutex.withLock {
            val delta = withContext(Dispatchers.Default) { TextCoordinates.change(doc.text, text) } ?: return
            doc.version++
            val changes = JSONArray().put(if (doc.client.syncKind == 2) delta else JSONObject().put("text", text))
            if (doc.client.syncKind != 0) doc.client.notify("textDocument/didChange", JSONObject().put("textDocument", JSONObject().put("uri", doc.uri).put("version", doc.version)).put("contentChanges", changes))
            doc.text = text
        }
    }
    suspend fun saved(path: String, text: String) {
        val doc = documents[path] ?: return
        change(path, text)
        val p = JSONObject().document(doc.uri)
        val save = doc.client.capabilities.optJSONObject("textDocumentSync")?.opt("save")
        if ((save as? JSONObject)?.optBoolean("includeText") == true) p.put("text", text)
        if (save != false && !doc.client.closed) doc.client.notify("textDocument/didSave", p)
    }
    suspend fun closeDocument(path: String) {
        documents.remove(path)?.let { if (!it.client.closed) it.client.notify("textDocument/didClose", JSONObject().document(it.uri)) }
        onDiagnostics(path, emptyList())
    }
    private suspend fun request(path: String, text: String, method: String, capability: String, parameters: JSONObject): Any? {
        val doc = documents[path] ?: error("请先连接 Termux，并在设置中安装当前语言服务器")
        check(!doc.client.closed) { "语言服务器已休眠；重新打开文件以启动" }
        check(doc.client.supports(capability)) { "当前语言服务器不支持这项操作" }
        change(path, text)
        catalog.forPath(path)?.id?.let { servers[it]?.used = System.nanoTime() }
        return doc.client.request(method, parameters.document(doc.uri))
    }

    suspend fun completions(path: String, text: String, position: LspPosition): List<CompletionEntry> {
        val result = request(path, text, "textDocument/completion", "completionProvider", JSONObject().at(position).put("context", JSONObject().put("triggerKind", 1)))
        val items = when (result) { is JSONArray -> result; is JSONObject -> result.optJSONArray("items"); else -> null } ?: return emptyList()
        val defaults = (result as? JSONObject)?.optJSONObject("itemDefaults")
        return items.objects().take(150).map { item ->
            val edit = item.optJSONObject("textEdit")
            val defaultRange = defaults?.optJSONObject("editRange")
            val range = (edit?.optJSONObject("range") ?: edit?.optJSONObject("replace") ?: defaultRange?.optJSONObject("replace") ?: defaultRange)?.let { runCatching { LspRange.from(it) }.getOrNull() }
            val insert = edit?.optString("newText") ?: item.optString("textEditText", item.optString("insertText", item.optString("label")))
            val plain = if (item.optInt("insertTextFormat", defaults?.optInt("insertTextFormat", 1) ?: 1) == 2) Snippets.plain(insert) else insert
            CompletionEntry(item.getString("label"), item.optString("detail"), plain, item.optInt("kind", 1), range,
                item.optJSONArray("additionalTextEdits")?.objects()?.mapNotNull { runCatching { LspEdit.from(it) }.getOrNull() } ?: emptyList(), item)
        }.sortedWith(compareBy { it.raw.optString("sortText", it.label) })
    }
    suspend fun hover(path: String, text: String, pos: LspPosition): String {
        val result = request(path, text, "textDocument/hover", "hoverProvider", JSONObject().at(pos)) as? JSONObject
        return markup(result?.opt("contents")).ifBlank { "此处没有类型说明" }
    }
    suspend fun signature(path: String, text: String, pos: LspPosition): String {
        val result = request(path, text, "textDocument/signatureHelp", "signatureHelpProvider", JSONObject().at(pos)) as? JSONObject
        val signatures = result?.optJSONArray("signatures")?.objects().orEmpty()
        return signatures.joinToString("\n\n") { it.optString("label") + "\n" + markup(it.opt("documentation")) }.ifBlank { "此处没有函数签名" }
    }
    suspend fun locations(path: String, text: String, pos: LspPosition, references: Boolean): List<Location> {
        val result = request(path, text, if (references) "textDocument/references" else "textDocument/definition", if (references) "referencesProvider" else "definitionProvider",
            JSONObject().at(pos).apply { if (references) put("context", JSONObject().put("includeDeclaration", true)) })
        val entries = when (result) { is JSONArray -> result.objects(); is JSONObject -> listOf(result); else -> emptyList() }
        return entries.mapNotNull { j -> runCatching {
            Location(j.optString("targetUri", j.optString("uri")), LspRange.from(j.optJSONObject("targetSelectionRange") ?: j.getJSONObject("range")))
        }.getOrNull() }
    }
    suspend fun formatting(path: String, text: String): List<LspEdit> {
        val result = request(path, text, "textDocument/formatting", "documentFormattingProvider", JSONObject().put("options", JSONObject().put("tabSize", settings.value.tabWidth).put("insertSpaces", !settings.value.useTabs))) as? JSONArray
        return result?.objects()?.map { LspEdit.from(it) }.orEmpty()
    }
    suspend fun rename(path: String, text: String, pos: LspPosition, name: String): JSONObject? =
        request(path, text, "textDocument/rename", "renameProvider", JSONObject().at(pos).put("newName", name)) as? JSONObject
    suspend fun symbols(path: String, text: String): List<JSONObject> =
        (request(path, text, "textDocument/documentSymbol", "documentSymbolProvider", JSONObject()) as? JSONArray)?.objects().orEmpty()
    suspend fun actions(path: String, text: String, pos: LspPosition, diagnostics: List<LspDiagnostic>): List<JSONObject> =
        (request(path, text, "textDocument/codeAction", "codeActionProvider", JSONObject().put("range", LspRange(pos, pos).json()).put("context", JSONObject().put("diagnostics", JSONArray(diagnostics.map { it.raw })))) as? JSONArray)?.objects().orEmpty()
    suspend fun execute(path: String, command: JSONObject) {
        val doc = documents[path] ?: error("语言服务器不可用")
        doc.client.request("workspace/executeCommand", command)
    }
    fun version(path: String) = documents[path]?.version
    fun ready(path: String) = documents[path]?.client?.closed == false
    suspend fun restart() = starting.withLock { servers.values.forEach { it.client.shutdown() }; servers.clear(); documents.clear() }
    override fun close() { servers.values.forEach { it.client.close() }; servers.clear(); documents.clear() }
}