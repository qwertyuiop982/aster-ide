package dev.aster.agent

import java.net.URI

/** Exact local-asset routing, shared by WebView and plain JVM tests. No networking. */
internal object AgentWebAssets {
    const val PAGE = "https://aster.local/agent/index.html"
    private val files = setOf("index.html", "base.css", "boot.js", "app.bundle.js", "app.js", "components.js", "choices.js", "patterns.js", "dom.js", "icons.js",
        "engines/pi.js", "engines/codex.js", "engines/gemini.js", "engines/openclaude.js")
    val headers = mapOf(
        "Content-Security-Policy" to "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'none'; connect-src 'none'; frame-src 'none'; worker-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'",
        "X-Content-Type-Options" to "nosniff", "Cache-Control" to "no-store")
    fun resolve(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.rawAuthority != "aster.local" || uri.rawQuery != null || uri.rawFragment != null) return null
        val path = uri.rawPath ?: return null
        if (!path.startsWith("/agent/")) return null
        return path.removePrefix("/agent/").takeIf { it in files }
    }
}
