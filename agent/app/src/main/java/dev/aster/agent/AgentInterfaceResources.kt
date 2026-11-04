package dev.aster.agent

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Small, immutable UI assets stay in memory for page and panel creation. */
internal object AgentInterfaceResources {
    private val contents = ConcurrentHashMap<String, ByteArray>()
    fun read(context: Context, name: String): ByteArray = contents[name] ?: synchronized(contents) {
        contents[name] ?: context.assets.open("agent/$name").use { it.readBytes() }.also { contents[name] = it }
    }
    fun preload(context: Context) {
        val app = context.applicationContext
        for (name in AgentWebAssets.files) read(app, name)
    }
}

fun preloadAgentInterface(context: Context, scope: CoroutineScope) {
    val app = context.applicationContext
    scope.launch(Dispatchers.IO) { runCatching { AgentInterfaceResources.preload(app) } }
}