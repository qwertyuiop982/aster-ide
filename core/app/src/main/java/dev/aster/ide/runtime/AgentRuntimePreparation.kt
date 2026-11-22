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

/** Uses the existing shared Termux; Python RPC and model configuration are not prerequisites. */
object AgentRuntimePreparation {
    private val preparing = Mutex()
    private var readyFingerprint = ""
    private var readyReport: String? = null
    private fun fingerprint(context: Context): String {
        val paths = PathRedirect.layout(context)
        val files = listOf("bin/bash", "bin/node", "bin/git", "bin/rg", "bin/python", "lib/libc++_shared.so", "lib/libssl.so.3", "lib/libcrypto.so.3", "etc/tls/cert.pem", "var/lib/dpkg/status")
        return context.applicationInfo.sourceDir + ":" + files.joinToString(";") { name ->
            val file = File(paths.usr, name)
            val stat = runCatching { android.system.Os.stat(file.path) }.getOrNull()
            name + ":" + file.lastModified() + ":" + file.length() + ":" + (stat?.st_ino ?: -1)
        }
    }
    suspend fun run(context: Context, install: Boolean, output: (String) -> Unit): JSONObject = withContext(Dispatchers.IO) {
        preparing.withLock {
            val currentFingerprint = fingerprint(context)
            if (!install && readyReport != null && readyFingerprint == currentFingerprint) return@withLock JSONObject(checkNotNull(readyReport))
            readyReport = null
            RuntimeInstaller.install(context, output)
            val paths = PathRedirect.layout(context)
            val base = File(paths.home, ".aster").apply { mkdirs() }
            val script = context.assets.open("bridge/agent-runtime.sh").use { it.readBytes() }
            val digest = MessageDigest.getInstance("SHA-256").digest(script).joinToString("") { "%02x".format(it) }
            VerifiedRuntimeAsset.install(File(base, "agent-runtime.sh"), script.size.toLong(), digest) { script.inputStream() }
            val builder = PathRedirect.process(context, paths.home, File(paths.usr, "bin/bash").path, "--noprofile", "--norc",
                File(base, "agent-runtime.sh").path, if (install) "--install" else "--probe").redirectErrorStream(true)
            val rawLog = File(base, "logs/agent-environment.log").apply { parentFile?.mkdirs() }
            val child = builder.start()
            val fields = Collections.synchronizedMap(mutableMapOf<String, String>())
            val result = CompletableDeferred<Int>()
            Thread({
                try {
                    child.inputStream.bufferedReader().use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (RuntimeDiagnostics.isIgnored(line)) continue
                            rawLog.appendText(line + "\n")
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
                val code = result.await()
                val values = synchronized(fields) { fields.toMap() }
                val report = JSONObject().put("exitCode", code).put("ready", code == 0 && values["ready"] == "true")
                values.forEach { (key, value) -> if (key != "ready") report.put(key, value) }
                output("Agent 环境实际检查：Node ${values["nodeVersion"].orEmpty().ifBlank { "缺失或不可执行" }}；Git ${values["gitReady"]}；ripgrep ${values["rgReady"]}；OpenSSL ${values["opensslReady"]}；CA ${values["caReady"]}\n")
                if (install) check(report.getBoolean("ready")) { "Agent 工具未安装完整（退出码 $code）。请查看 Node.js、Git、ripgrep、OpenSSL 与 CA 的检查和安装日志。" }
                else check(code in 0..1) { "Agent 工具探测无法运行（退出码 $code），请查看日志。" }
                if (report.optBoolean("ready")) {
                    readyFingerprint = fingerprint(context)
                    readyReport = report.toString()
                }
                report
            } finally {
                // SIGTERM lets the shell stop the active command and its descendants.
                child.destroy()
            }
        }
    }
}
