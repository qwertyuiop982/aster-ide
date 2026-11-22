package dev.aster.client.browser

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

internal object WebViewSearch {
    private const val BING = "https://www.bing.com"

    suspend fun execute(context: Context, args: JSONObject): JSONObject {
        val query = args.getString("query").trim()
        require(query.length in 1..300) { "Query must contain 1..300 characters" }
        val type = args.optString("type", "web")
        require(type == "web" || type == "image") { "Search type must be web or image" }
        val maxResults = if (args.has("maxResults")) args.getInt("maxResults") else 8
        require(maxResults in 1..15) { "maxResults must be 1..15" }
        val url = url(query, type)
        val response = JSONObject().put("engine", "bing").put("type", type).put("query", query).put("url", url)
        val script = browserScript(context, "bing-results.js").call(
            JSONObject().put("type", type).put("maxResults", maxResults)
        )
        val page = RenderedWebPage(context, allowNavigation = ::isSearchUrl, desktopViewport = true)
        try {
            page.load(url)
            page.awaitReady()
            // Results may be added by the page after the WebView's onPageFinished callback.
            val deadline = SystemClock.elapsedRealtime() + 6_000
            var best = JSONArray()
            do {
                val batch = page.evaluate(script)
                val status = batch?.optString("status")
                val results = batch?.optJSONArray("results")
                if (results != null && results.length() >= best.length()) best = results
                if (best.length() >= maxResults || status == "empty") break
                if (status == "blocked") {
                    return response.put("ok", false).put("results", best)
                        .put("error", "Bing requested interactive verification")
                }
                delay(300)
            } while (SystemClock.elapsedRealtime() < deadline)
            return response.put("ok", true).put("results", best)
                .put("count", best.length())
        } finally {
            page.close()
        }
    }

    internal fun url(query: String, type: String): String {
        val path = if (type == "image") "/images/search" else "/search"
        return "$BING$path?q=${URLEncoder.encode(query, Charsets.UTF_8.name())}"
    }

    private fun isSearchUrl(url: String): Boolean {
        if (!BrowserRuntime.isWebUrl(url)) return false
        val uri = URI(url)
        val host = uri.host.lowercase()
        return uri.scheme.equals("https", true) && (host == "bing.com" || host.endsWith(".bing.com"))
    }
}
