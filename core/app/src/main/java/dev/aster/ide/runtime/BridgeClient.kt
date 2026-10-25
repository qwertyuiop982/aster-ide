package dev.aster.ide.runtime

import android.content.Context
import android.util.Base64
import dev.aster.ide.data.SettingsStore
import dev.aster.ide.data.Workspace
import dev.aster.ide.data.WorkspaceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

class BridgeConnection(val socket: Socket, private val onClosed: () -> Unit = {}) : Closeable {
    val input = BufferedInputStream(socket.getInputStream(), 32768)
    val output = BufferedOutputStream(socket.getOutputStream(), 32768)
    fun send(value: JSONObject) = synchronized(output) {
        output.write((value.toString() + "\n").toByteArray(Charsets.UTF_8)); output.flush()
    }
    fun receive(): JSONObject = JSONObject(readLine(input, 24 * 1024 * 1024))
    override fun close() { runCatching { socket.close() }; onClosed() }
}

fun readLine(input: java.io.InputStream, limit: Int = 8192): String {
    val result = ByteArrayOutputStream()
    while (result.size() <= limit) {
        val b = input.read()
        if (b < 0) throw EOFException("连接已关闭")
        if (b == 10) return result.toString(Charsets.UTF_8.name()).removeSuffix("\r")
        result.write(b)
    }
    error("响应超过允许的大小")
}

class BridgeClient(context: Context, private val settings: SettingsStore) : Closeable {
    private val prefs = context.getSharedPreferences("runtime", Context.MODE_PRIVATE)
    val token: String = prefs.getString("token", null) ?: ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }.also { prefs.edit().putString("token", it).apply() }
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private data class Signature(val modified: Long, val length: Long, val hash: String)
    private val signatures = ConcurrentHashMap<String, Signature>()
    @Volatile var remoteRoot: String = "/data/data/com.termux/files/home/.aster/workspaces"

    suspend fun open(op: String, workspace: String? = null, extra: JSONObject = JSONObject(), timeoutMillis: Int = settings.value.requestTimeout * 1000): BridgeConnection = withContext(Dispatchers.IO) {
        val socket = Socket()
        sockets += socket
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress("127.0.0.1", settings.value.port), minOf(2500, timeoutMillis))
            socket.soTimeout = timeoutMillis
            val connection = BridgeConnection(socket) { sockets.remove(socket) }
            extra.put("op", op).put("token", token)
            workspace?.let { extra.put("workspace", it) }
            connection.send(extra)
            connection
        } catch (e: Exception) { sockets -= socket; runCatching { socket.close() }; throw e }
    }

    suspend fun rpc(op: String, workspace: String? = null, extra: JSONObject = JSONObject(), timeoutMillis: Int = settings.value.requestTimeout * 1000): JSONObject = withContext(Dispatchers.IO) {
        open(op, workspace, extra, timeoutMillis).use { connection ->
            val response = connection.receive()
            check(response.optBoolean("ok")) { response.optString("error", "编译环境返回错误") }
            response
        }
    }

    suspend fun info(timeoutMillis: Int = settings.value.requestTimeout * 1000): JSONObject = rpc("info", extra = JSONObject().put("options", options()), timeoutMillis = timeoutMillis).also { remoteRoot = it.getString("root") }
    fun invalidate(workspace: String, path: String) { signatures.remove("$workspace:$path") }

    suspend fun sync(workspace: Workspace, repo: WorkspaceRepository): Int = withContext(Dispatchers.IO) {
        val files = repo.files(workspace).filter { !it.directory && it.name != "local.properties" }
        var updated = 0
        val seen = HashSet<String>()
        for (node in files) {
            coroutineContext.ensureActive()
            val key = "${workspace.id}:${node.path}"
            seen += key
            val file = repo.resolve(workspace, node.path)
            val previous = signatures[key]
            if (previous != null && previous.modified == file.lastModified() && previous.length == file.length()) continue
            check(file.length() <= 12L * 1024 * 1024) { "${node.path} 超过桥接文件限制 12 MiB" }
            val data = file.readBytes()
            val hash = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
            if (previous?.hash != hash) {
                rpc("put", workspace.id, JSONObject().put("path", node.path).put("sha256", hash).put("data", Base64.encodeToString(data, Base64.NO_WRAP)))
                updated++
            }
            signatures[key] = Signature(file.lastModified(), file.length(), hash)
        }
        signatures.keys.filter { it.startsWith(workspace.id + ":") && it !in seen }.forEach { key ->
            rpc("remove", workspace.id, JSONObject().put("path", key.substringAfter(':')))
            signatures.remove(key)
        }
        updated
    }

    fun options(): JSONObject = settings.value.let { s -> JSONObject().put("registry", s.npmRegistry).put("mirror", s.termuxMirror)
        .put("proxy", s.githubProxy).put("sdk", s.sdkPath).put("sdkBuildTools", s.sdkBuildTools).put("sdkJdk", s.sdkJdk).put("sdkNdk", s.sdkNdk).put("sdkCmdlineTools", s.sdkCmdlineTools).put("memory", s.buildMemory).put("workers", s.buildWorkers) }

    suspend fun stream(op: String, workspace: String, job: String, extra: JSONObject, onOutput: (String) -> Unit): Int = withContext(Dispatchers.IO) {
        extra.put("job", job).put("options", options())
        open(op, workspace, extra).use { connection ->
            val ack = connection.receive()
            check(ack.optBoolean("ok")) { ack.optString("error") }
            connection.socket.soTimeout = 0
            while (true) {
                coroutineContext.ensureActive()
                val p = connection.receive()
                when (p.optString("type")) {
                    "output" -> onOutput(p.optString("text"))
                    "exit" -> return@withContext p.getInt("code")
                    "error" -> error(p.optString("error"))
                }
            }
            @Suppress("UNREACHABLE_CODE") -1
        }
    }

    suspend fun cancel(job: String) { rpc("cancel", extra = JSONObject().put("job", job)) }
    override fun close() { sockets.forEach { runCatching { it.close() } }; sockets.clear() }
}