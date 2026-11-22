package dev.aster.ide.runtime

import android.content.Context
import android.os.Build
import dev.aster.termux.PathRedirect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
import java.util.concurrent.atomic.AtomicLong

// UI observes this state; installation and output processing never run on the main thread.
enum class RuntimePhase { IDLE, UNPACKING, CHECKING, UPDATING, INSTALLING, RETRYING, STARTING, READY, FAILED }
data class RuntimeState(
    val phase: RuntimePhase = RuntimePhase.IDLE,
    val message: String = "环境未启动",
    val bootstrapInstalled: Boolean = false,
    val error: String? = null
)

class TermuxRuntime(private val context: Context, private val bridge: BridgeClient, private val output: (String) -> Unit) : Closeable {
    private val starting = Mutex()
    private val processLock = Any()
    private val generation = AtomicLong()
    private val mutableState = MutableStateFlow(RuntimeState())
    val state = mutableState.asStateFlow()
    @Volatile private var process: Process? = null
    @Volatile private var stopping: Process? = null
    @Volatile private var startupSignal: CompletableDeferred<Int>? = null
    @Volatile private var readyInfo: JSONObject? = null
    val port get() = bridge.port

    fun isReady(): Boolean = synchronized(processLock) {
        val child = process
        stopping == null && child != null && alive(child) && bridge.port in 1..65535 && readyInfo != null
    }
    val directory get() = PathRedirect.layout(context).root.absolutePath
    fun installed() = RuntimeInstaller.installed(context)
    private fun alive(child: Process) = runCatching { child.exitValue(); false }.getOrDefault(true)

    suspend fun start(): JSONObject = withContext(Dispatchers.IO) {
        starting.withLock {
            val requestedGeneration = synchronized(processLock) { generation.get() }
            // Register before install/launch so stop() can interrupt every startup phase.
            val readySignal = CompletableDeferred<Int>()
            startupSignal = readySignal
            var child: Process? = null
            try {
                val existingReady = synchronized(processLock) {
                    check(requestedGeneration == generation.get()) { "环境启动已取消" }
                    val current = process
                    if (stopping == null && current != null && alive(current) && bridge.port in 1..65535 && readyInfo != null) {
                        JSONObject(readyInfo!!.toString())
                    } else {
                        readyInfo = null
                        null
                    }
                }
                if (existingReady != null) return@withLock existingReady
                (synchronized(processLock) { process ?: stopping })?.let { stopProcess(it) }
                checkGeneration(requestedGeneration)

                mutableState.value = RuntimeState(RuntimePhase.UNPACKING, "准备内置 Termux…", installed())
                RuntimeInstaller.install(context) { text ->
                    mutableState.value = mutableState.value.copy(message = text.trim())
                    output(text)
                }
                check(requestedGeneration == generation.get()) { "环境启动已取消" }
                mutableState.value = RuntimeState(RuntimePhase.CHECKING, "检查 Python 桥接…", true)
                val paths = PathRedirect.layout(context)
                val base = File(paths.home, ".aster").apply { mkdirs() }
                // Install the complete bridge tree so newly added Python modules cannot be omitted.
                copyAssetTree("bridge", base)
                context.assets.open("servers.json").use { input ->
                    File(base, "servers.json").outputStream().use { input.copyTo(it) }
                }
                File(base, "token").writeText(bridge.token)
                bridge.resetRuntime()
                val launched = PathRedirect.process(context, paths.home, File(paths.usr, "bin/bash").path,
                    "--noprofile", "--norc", File(base, "start.sh").path, base.path, "0")
                    .redirectErrorStream(true).start()
                child = launched
                synchronized(processLock) {
                    check(requestedGeneration == generation.get()) { "环境启动已取消" }
                    process = launched
                    stopping = null
                }
                val logFile = File(base, "logs/runtime.log").apply { parentFile?.mkdirs() }
                Thread({
                    try {
                        logFile.bufferedWriter().use { log ->
                            launched.inputStream.bufferedReader().use { reader ->
                                while (true) {
                                    val line = reader.readLine() ?: break
                                    if (RuntimeDiagnostics.isIgnored(line)) continue
                                    log.appendLine(line)
                                    if (process === launched) {
                                        if (line.startsWith("ASTER_STAGE:")) {
                                            stage(line.removePrefix("ASTER_STAGE:"))
                                            log.flush()
                                        } else {
                                            output(line + "\n")
                                            parseBridgePort(line)?.let { port ->
                                                bridge.activatePort(port)
                                                log.flush()
                                                readySignal.complete(port)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        val code = launched.waitFor()
                        val error = IllegalStateException("内置环境退出（代码 $code），请查看安装日志后重试")
                        readySignal.completeExceptionally(error)
                        if (process === launched) {
                            bridge.resetRuntime()
                            mutableState.value = RuntimeState(RuntimePhase.FAILED, error.message.orEmpty(), true, error.message)
                        }
                    } catch (e: Exception) {
                        readySignal.completeExceptionally(e)
                        if (process === launched) mutableState.value = RuntimeState(RuntimePhase.FAILED, "读取环境输出失败", true, e.message)
                    }
                }, "Aster-runtime-output").apply { isDaemon = true; start() }

                val announcedPort = withTimeout(300_000) { readySignal.await() }
                checkGeneration(requestedGeneration)
                check(bridge.port == announcedPort) { "桥接端口未就绪" }
                val info = bridge.info(4000)
                check(alive(launched)) { "桥接进程已退出" }
                synchronized(processLock) {
                    check(requestedGeneration == generation.get() && process === launched && stopping == null) { "环境启动已取消" }
                    readyInfo = JSONObject(info.toString())
                }
                mutableState.value = RuntimeState(RuntimePhase.READY, "内置环境已就绪", true)
                info
            } catch (e: TimeoutCancellationException) {
                stopProcess(child)
                val message = "环境准备超时，请查看安装日志并重试"
                mutableState.value = RuntimeState(RuntimePhase.FAILED, message, installed(), message)
                throw IllegalStateException(message, e)
            } catch (e: CancellationException) {
                stopProcess(child)
                mutableState.value = RuntimeState(RuntimePhase.IDLE, "环境准备已取消", installed())
                throw e
            } catch (e: Exception) {
                if (requestedGeneration != generation.get()) {
                    stopProcess(child)
                    mutableState.value = RuntimeState(RuntimePhase.IDLE, "环境启动已取消", installed())
                    throw CancellationException("环境启动已取消", e)
                }
                stopProcess(child)
                mutableState.value = RuntimeState(RuntimePhase.FAILED, e.message ?: "环境启动失败", installed(), e.message)
                throw e
            } finally {
                startupSignal = null
            }
        }
    }

    private fun checkGeneration(expected: Long) {
        check(synchronized(processLock) { generation.get() == expected }) { "环境启动已取消" }
    }

    private fun copyAssetTree(asset: String, destination: File) {
        val children = context.assets.list(asset).orEmpty().filterNot { it == "__pycache__" || it.endsWith(".pyc") }
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

    private suspend fun stopProcess(child: Process?) = withContext(Dispatchers.IO + NonCancellable) {
        if (child != null) {
            synchronized(processLock) {
                if (process === child) process = null
                stopping = child
            }
        }
        readyInfo = null
        bridge.resetRuntime()
        if (child != null) {
            stopRuntimeProcess(child, forceKill = { process ->
                if (Build.VERSION.SDK_INT >= 26) process.destroyForcibly()
            })
            synchronized(processLock) { if (stopping === child) stopping = null }
        }
        mutableState.value = mutableState.value.copy(phase = RuntimePhase.IDLE, message = "环境已停止", error = null)
    }

    /** Stop the current bridge and wait until its process has exited. */
    suspend fun stop() = withContext(Dispatchers.IO + NonCancellable) {
        synchronized(processLock) {
            generation.incrementAndGet()
            readyInfo = null
        }
        startupSignal?.completeExceptionally(CancellationException("环境启动已取消"))
        starting.withLock {
            val child = synchronized(processLock) {
                val current = process ?: stopping
                process = null
                if (current != null) stopping = current
                current
            }
            bridge.resetRuntime()
            stopProcess(child)
        }
    }

    override fun close() {
        val child = synchronized(processLock) {
            generation.incrementAndGet()
            val current = process ?: stopping
            process = null
            if (current != null) stopping = current
            readyInfo = null
            current
        }
        startupSignal?.completeExceptionally(CancellationException("环境启动已取消"))
        bridge.resetRuntime()
        child?.destroy()
        mutableState.value = mutableState.value.copy(phase = RuntimePhase.IDLE, message = "环境已停止", error = null)
    }
}

internal fun parseBridgePort(line: String): Int? {
    val prefix = "Aster bridge ready on loopback port "
    if (!line.startsWith(prefix)) return null
    return line.removePrefix(prefix).toIntOrNull()?.takeIf { it in 1..65535 }
}
