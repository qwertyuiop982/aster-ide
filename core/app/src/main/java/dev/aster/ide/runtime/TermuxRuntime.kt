package dev.aster.ide.runtime

import android.content.Context
import dev.aster.ide.data.SettingsStore
import dev.aster.termux.PathRedirect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.Closeable
import java.io.File

// UI observes this state; installation and output processing never run on the main thread.
enum class RuntimePhase { IDLE, UNPACKING, CHECKING, UPDATING, INSTALLING, RETRYING, STARTING, READY, FAILED }
data class RuntimeState(
    val phase: RuntimePhase = RuntimePhase.IDLE,
    val message: String = "环境未启动",
    val bootstrapInstalled: Boolean = false,
    val error: String? = null
)

class TermuxRuntime(private val context: Context, private val settings: SettingsStore, private val bridge: BridgeClient, private val output: (String) -> Unit) : Closeable {
    private val starting = Mutex()
    private val mutableState = MutableStateFlow(RuntimeState())
    val state = mutableState.asStateFlow()
    @Volatile private var process: Process? = null
    private var processPort = 0
    val directory get() = PathRedirect.layout(context).root.absolutePath
    fun installed() = RuntimeInstaller.installed(context)
    private fun alive(child: Process) = runCatching { child.exitValue(); false }.getOrDefault(true)

    suspend fun start(): JSONObject = withContext(Dispatchers.IO) {
        starting.withLock {
            var child: Process? = null
            try {
                mutableState.value = RuntimeState(RuntimePhase.UNPACKING, "准备内置 Termux…", installed())
                RuntimeInstaller.install(context, settings.value.termuxMirror) { text ->
                    mutableState.value = mutableState.value.copy(message = text.trim())
                    output(text)
                }
                mutableState.value = RuntimeState(RuntimePhase.CHECKING, "检查 Python 桥接…", true)
                val paths = PathRedirect.layout(context)
                val base = File(paths.home, ".aster").apply { mkdirs() }
                process?.let { existing ->
                    if (alive(existing) && processPort == settings.value.port) {
                        val info = runCatching { bridge.info(1500) }.getOrNull()
                        if (info != null) {
                            mutableState.value = RuntimeState(RuntimePhase.READY, "内置环境已就绪", true)
                            return@withLock info
                        }
                    }
                    process = null
                    existing.destroy()
                }
                for ((asset, name) in listOf("bridge/bridge.py" to "bridge.py", "bridge/agent_runner.py" to "agent_runner.py", "bridge/agent_engines.py" to "agent_engines.py", "bridge/agent_codex.py" to "agent_codex.py", "bridge/agent_thinking.py" to "agent_thinking.py", "bridge/agent_skills.py" to "agent_skills.py", "bridge/agent_mcp.py" to "agent_mcp.py", "bridge/agent_mcp_stdio.py" to "agent_mcp_stdio.py", "bridge/agent_vision.py" to "agent_vision.py", "servers.json" to "servers.json", "bridge/setup_android.py" to "setup_android.py", "bridge/idesetup.py" to "idesetup.py", "bridge/toolchain_native.py" to "toolchain_native.py", "bridge/android_platform.py" to "android_platform.py", "bridge/start.sh" to "start.sh")) {
                    context.assets.open(asset).use { input -> File(base, name).outputStream().use { input.copyTo(it) } }
                }
                copyAssetTree("bridge/agent_vendor", File(base, "agent_vendor"))
                File(base, "token").writeText(bridge.token)
                val guest = "/data/data/com.termux/files/home/.aster"
                val command = PathRedirect.command(context, paths.home, "/data/data/com.termux/files/usr/bin/bash", "--noprofile", "--norc", "$guest/start.sh", guest, settings.value.port.toString())
                val builder = ProcessBuilder(command.toList()).directory(paths.home).redirectErrorStream(true)
                builder.environment().putAll(PathRedirect.environment(context))
                builder.environment()["ASTER_NATIVE_LIB_DIR"] = context.applicationInfo.nativeLibraryDir
                builder.environment().remove("LD_PRELOAD")
                val launched = builder.start()
                child = launched
                process = launched
                processPort = settings.value.port
                val ready = CompletableDeferred<Unit>()
                val logFile = File(base, "logs/runtime.log").apply { parentFile?.mkdirs() }
                Thread({
                    try {
                        logFile.bufferedWriter().use { log ->
                            launched.inputStream.bufferedReader().use { reader ->
                                while (true) {
                                    val line = reader.readLine() ?: break
                                    log.appendLine(line)
                                    if (process === launched) {
                                        if (line.startsWith("ASTER_STAGE:")) {
                                            stage(line.removePrefix("ASTER_STAGE:"))
                                            log.flush()
                                        } else {
                                            output(line + "\n")
                                            if (line.startsWith("Aster bridge ready on loopback port ")) { log.flush(); ready.complete(Unit) }
                                        }
                                    }
                                }
                            }
                        }
                        val code = launched.waitFor()
                        val error = IllegalStateException("内置环境退出（代码 $code），请查看安装日志后重试")
                        ready.completeExceptionally(error)
                        if (process === launched) mutableState.value = RuntimeState(RuntimePhase.FAILED, error.message.orEmpty(), true, error.message)
                    } catch (e: Exception) {
                        ready.completeExceptionally(e)
                        if (process === launched) mutableState.value = RuntimeState(RuntimePhase.FAILED, "读取环境输出失败", true, e.message)
                    }
                }, "Aster-runtime-output").apply { isDaemon = true; start() }
                // Readiness is emitted after the TCP listener is bound. No repeated port probing.
                withTimeout(420000) { ready.await() }
                val info = bridge.info(1500)
                check(alive(launched)) { "桥接进程已退出" }
                mutableState.value = RuntimeState(RuntimePhase.READY, "内置环境已就绪", true)
                info
            } catch (e: TimeoutCancellationException) {
                stop(child)
                val message = "环境准备超时，请查看安装日志并重试"
                mutableState.value = RuntimeState(RuntimePhase.FAILED, message, installed(), message)
                throw IllegalStateException(message, e)
            } catch (e: CancellationException) {
                stop(child)
                mutableState.value = RuntimeState(RuntimePhase.IDLE, "环境准备已取消", installed())
                throw e
            } catch (e: Exception) {
                stop(child)
                mutableState.value = RuntimeState(RuntimePhase.FAILED, e.message ?: "环境启动失败", installed(), e.message)
                throw e
            }
        }
    }
    private fun copyAssetTree(asset: String, destination: File) {
        val children = context.assets.list(asset).orEmpty()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            context.assets.open(asset).use { input -> destination.outputStream().use { input.copyTo(it) } }
        } else {
            destination.mkdirs()
            children.forEach { name -> copyAssetTree("$asset/$name", File(destination, name)) }
        }
    }
    private fun stage(name: String) {
        val (phase, message) = when (name) {
            "checking" -> RuntimePhase.CHECKING to "检查 Python 桥接…"
            "updating" -> RuntimePhase.UPDATING to "更新软件包索引…"
            "installing" -> RuntimePhase.INSTALLING to "安装 Python 桥接…"
            "retrying" -> RuntimePhase.RETRYING to "修复中断的安装并重试…"
            "starting" -> RuntimePhase.STARTING to "启动本地桥接…"
            else -> return
        }
        mutableState.value = RuntimeState(phase, message, true)
        output(message + "\n")
    }
    private fun stop(child: Process?) {
        if (child != null) {
            if (process === child) process = null
            child.destroy()
        }
    }
    override fun close() {
        val child = process
        process = null
        child?.destroy()
        mutableState.value = mutableState.value.copy(phase = RuntimePhase.IDLE, message = "环境已停止", error = null)
    }
}