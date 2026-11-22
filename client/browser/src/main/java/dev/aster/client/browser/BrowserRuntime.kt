package dev.aster.client.browser

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI
import java.util.UUID

object BrowserRuntime {
    internal const val LANGUAGE_TAG = "dev.aster.client.browser.LANGUAGE_TAG"
    internal const val BACKEND = "android-webview"

    internal fun isWebUrl(url: String): Boolean {
        if (url.length !in 1..4096) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
            !uri.host.isNullOrBlank() && uri.rawUserInfo == null
    }

    fun open(context: Context, languageTag: String = "") {
        context.startActivity(
            Intent(context, BrowserActivity::class.java)
                .putExtra(LANGUAGE_TAG, languageTag)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    suspend fun search(context: Context, args: JSONObject): JSONObject = withContext(Dispatchers.Main.immediate) {
        WebViewSearch.execute(context.applicationContext, args).put("backend", BACKEND)
    }
}

class BrowserAutomation(context: Context) {
    private val context = context.applicationContext
    private val lock = Mutex()
    private var session: BrowserSession? = null
    @Volatile private var disposed = false

    suspend fun execute(operation: String, args: JSONObject): JSONObject = lock.withLock {
        withContext(Dispatchers.Main.immediate) {
            check(!disposed) { "The browser tool is closed" }
            when (operation) {
                "open" -> {
                    val url = args.getString("url").trim()
                    require(BrowserRuntime.isWebUrl(url)) { "Only HTTP(S) URLs without embedded credentials are supported" }
                    val current = session?.takeUnless { it.page.isClosed } ?: BrowserSession(context).also { session = it }
                    current.open(url)
                }
                "close" -> {
                    closeSession()
                    JSONObject().put("ok", true)
                }
                else -> {
                    val current = session?.takeUnless { it.page.isClosed } ?: error("Open a browser page first")
                    current.execute(operation, args)
                }
            }.put("backend", BrowserRuntime.BACKEND)
        }
    }

    fun dispose() {
        disposed = true
        if (Looper.myLooper() == Looper.getMainLooper()) closeSession()
        else Handler(Looper.getMainLooper()).post { closeSession() }
    }

    private fun closeSession() {
        session?.page?.close()
        session = null
    }
}

private class BrowserSession(context: Context) {
    val page = RenderedWebPage(context)
    private val script = browserScript(context, "browser-dom.js")
    private var snapshotId: String? = null
    private var snapshotNavigation = -1

    fun open(url: String): JSONObject {
        snapshotId = null
        page.load(url)
        return JSONObject().put("ok", true).put("loading", true).put("url", url)
    }

    suspend fun execute(operation: String, args: JSONObject): JSONObject {
        if (operation == "back") {
            snapshotId = null
            return JSONObject().put("ok", true).put("loading", page.goBack()).put("refreshRequired", true)
        }
        page.awaitReady()
        if (operation == "snapshot") {
            snapshotId = null
            val newId = UUID.randomUUID().toString()
            val navigation = page.navigationVersion
            val result = evaluate(operation, JSONObject().put("snapshotId", newId))
            check(navigation == page.navigationVersion) { "Page changed; take a fresh browser snapshot" }
            if (result.optBoolean("ok")) {
                snapshotId = newId
                snapshotNavigation = navigation
                result.put("snapshotId", newId)
            }
            return result
        }
        require(operation in setOf("click", "fill", "scroll")) { "Unsupported browser operation" }
        val expected = args.getString("snapshotId")
        require(expected == snapshotId && snapshotNavigation == page.navigationVersion) {
            "Page changed; take a fresh browser snapshot"
        }
        val request = JSONObject().put("snapshotId", expected)
        val isScroll = operation == "scroll"
        if (!isScroll || args.has("elementId")) {
            val id = args.getInt("elementId")
            require(id in 0..199 || (isScroll && id == -1)) { "Invalid browser element ID" }
            if (id >= 0) request.put("elementId", id)
        }
        if (isScroll) {
            val direction = args.getString("direction")
            val distance = if (args.has("distance")) args.getInt("distance") else 600
            require(direction in setOf("up", "down", "left", "right")) { "Unsupported scroll direction" }
            require(distance in 50..2000) { "Scroll distance must be 50..2000" }
            request.put("direction", direction).put("distance", distance)
        }
        if (operation == "fill") {
            val text = args.getString("text")
            require(text.length <= 12_000) { "Text is limited to 12000 characters" }
            request.put("text", text)
        }
        snapshotId = null
        return evaluate(operation, request).put("refreshRequired", true)
    }

    private suspend fun evaluate(operation: String, request: JSONObject): JSONObject =
        page.evaluate(script.call(request.put("action", operation)))
            ?: error("Browser did not return a structured result")
}

internal fun browserScript(context: Context, name: String): String =
    context.assets.open("aster-browser/$name").bufferedReader().use { it.readText().trim() }

internal fun String.call(args: JSONObject): String = "$this($args)"
