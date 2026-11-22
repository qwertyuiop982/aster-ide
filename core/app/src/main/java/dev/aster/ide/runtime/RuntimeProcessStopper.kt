package dev.aster.ide.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
internal suspend fun stopRuntimeProcess(
    process: Process,
    forceKill: (Process) -> Unit,
    gracefulTimeoutMillis: Long = 2500,
    forcedTimeoutMillis: Long = 1500,
    pollIntervalMillis: Long = 50
) = withContext(Dispatchers.IO + NonCancellable) {
    fun alive() = runCatching { process.exitValue(); false }.getOrDefault(true)
    suspend fun waitForExit(timeoutMillis: Long) {
        withTimeoutOrNull(timeoutMillis) {
            while (alive()) delay(pollIntervalMillis)
        }
    }

    if (alive()) process.destroy()
    waitForExit(gracefulTimeoutMillis)
    if (alive()) runCatching { forceKill(process) }
    waitForExit(forcedTimeoutMillis)
    check(!alive()) { "旧桥接进程未能退出，已阻止启动新进程" }
}
