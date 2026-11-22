package dev.aster.ide.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RuntimeProcessStopperTest {
    @Test fun gracefulExitDoesNotForceKill() = runBlocking {
        val process = FakeProcess(exitOnDestroy = true)
        val forceKilled = AtomicBoolean(false)

        stopRuntimeProcess(process, { forceKilled.set(true) }, gracefulTimeoutMillis = 20, pollIntervalMillis = 1)

        assertFalse(forceKilled.get())
    }

    @Test fun forceKillsProcessThatIgnoresDestroy() = runBlocking {
        val process = FakeProcess(exitOnDestroy = false)

        stopRuntimeProcess(process, { (it as FakeProcess).forceKill() }, gracefulTimeoutMillis = 5,
            forcedTimeoutMillis = 20, pollIntervalMillis = 1)

        assertTrue(process.forceKilled)
    }

    @Test(expected = IllegalStateException::class)
    fun refusesToContinueWhenProcessCannotBeStopped() = runBlocking {
        stopRuntimeProcess(FakeProcess(exitOnDestroy = false), {}, gracefulTimeoutMillis = 5,
            forcedTimeoutMillis = 10, pollIntervalMillis = 1)
    }

    @Test fun cancellationCannotInterruptProcessCleanup() = runBlocking {
        val destroyed = CountDownLatch(1)
        val process = FakeProcess(exitOnDestroy = false, onDestroy = { destroyed.countDown() })
        val job = launch(Dispatchers.Default) {
            stopRuntimeProcess(process, { (it as FakeProcess).forceKill() }, gracefulTimeoutMillis = 20,
                forcedTimeoutMillis = 20, pollIntervalMillis = 1)
        }

        assertTrue("stop did not start", destroyed.await(2, TimeUnit.SECONDS))
        job.cancelAndJoin()

        assertTrue(process.forceKilled)
        assertFalse(process.running)
    }

    private class FakeProcess(
        private val exitOnDestroy: Boolean,
        private val onDestroy: () -> Unit = {}
    ) : Process() {
        private val exited = AtomicBoolean(false)
        @Volatile var forceKilled = false
            private set
        val running get() = !exited.get()

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int {
            while (running) Thread.sleep(1)
            return 0
        }
        override fun exitValue(): Int = if (running) throw IllegalThreadStateException() else 0
        override fun destroy() {
            onDestroy()
            if (exitOnDestroy) exited.set(true)
        }
        override fun destroyForcibly(): Process {
            forceKill()
            return this
        }
        fun forceKill() {
            forceKilled = true
            exited.set(true)
        }
    }
}
