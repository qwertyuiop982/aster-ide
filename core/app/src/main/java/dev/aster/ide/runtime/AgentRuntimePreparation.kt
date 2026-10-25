package dev.aster.ide.runtime

import android.content.Context
import dev.aster.termux.PathRedirect
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID

/** Uses the existing shared Termux; Python RPC and model configuration are not prerequisites. */
object AgentRuntimePreparation {
    private val preparing = Mutex()
    suspend fun run(context: Context, mirror: String, install: Boolean, output: (String) -> Unit): JSONObject = withContext(Dispatchers.IO) {
        preparing.withLock {
            RuntimeInstaller.install(context, mirror, output)
            val paths = PathRedirect.layout(context)
            val base = File(paths.home, ".aster").apply { mkdirs() }
            val script = context.assets.open("bridge/agent-runtime.sh").use { it.readBytes() }
            val digest = MessageDigest.getInstance("SHA-256").digest(script).joinToString("") { "%02x".format(it) }
            VerifiedRuntimeAsset.install(File(base, "agent-runtime.sh"), script.size.toLong(), digest) { script.inputStream() }
            val cancel = File(base, "agent-environment-cancel-${UUID.randomUUID()}")
            val guest = "/data/data/com.termux/files/home/.aster"
            val command = PathRedirect.command(context, paths.home, "/data/data/com.termux/files/usr/bin/bash", "--noprofile", "--norc",
                "$guest/agent-runtime.sh", if (install) "--install" else "--probe", "$guest/${cancel.name}")
            val builder = ProcessBuilder(command.toList()).directory(paths.home).redirectErrorStream(true)
            builder.environment().putAll(PathRedirect.environment(context))
            builder.environment().remove("LD_PRELOAD")
            val child = builder.start()
            val fields = Collections.synchronizedMap(mutableMapOf<String, String>())
            val result = CompletableDeferred<Int>()
            Thread({
                try {
                    child.inputStream.bufferedReader().use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.startsWith("ASTER_AGENT_FIELD:")) {
                                val parts = line.removePrefix("ASTER_AGENT_FIELD:").split('=', limit = 2)
                                if (parts.size == 2) fields[parts[0]] = parts[1]
                            } else output(line + "\n")
                        }
                    }
                    result.complete(child.waitFor())
                } catch (error: Exception) { result.completeExceptionally(error) }
            }, "Aster-agent-environment").apply { isDaemon = true; start() }
            try {
                val code = try { withTimeout(1080000) { result.await() } }
                    catch (error: TimeoutCancellationException) { throw IllegalStateException("Agent 环境准备超时，请查看安装日志后重试", error) }
                val values = synchronized(fields) { fields.toMap() }
                val report = JSONObject().put("exitCode", code).put("ready", code == 0 && values["ready"] == "true")
                values.forEach { (key, value) -> if (key != "ready") report.put(key, value) }
                output("Agent 环境实际检查：Node ${values["nodeVersion"].orEmpty().ifBlank { "缺失或不可执行" }}；Git ${values["gitReady"]}；ripgrep ${values["rgReady"]}；OpenSSL ${values["opensslReady"]}；CA ${values["caReady"]}\n")
                if (install) check(report.getBoolean("ready")) { "Agent 工具未安装完整（退出码 $code）。请查看 Node.js、Git、ripgrep、OpenSSL 与 CA 的检查和安装日志。" }
                else check(code in 0..1) { "Agent 工具探测无法运行（退出码 $code），请查看日志。" }
                report
            } finally {
                // The shell cancels the active timeout process group, including apt/dpkg children.
                if (!result.isCompleted) {
                    runCatching { cancel.writeText("cancel\n") }
                    withContext(NonCancellable) { withTimeoutOrNull(8000) { runCatching { result.await() } } }
                }
                child.destroy()
                cancel.delete()
            }
        }
    }
}
