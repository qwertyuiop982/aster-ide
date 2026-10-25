package dev.aster.agent

import org.json.JSONObject

/** A token-authenticated latest snapshot; no model credentials or unbounded event queue. */
internal class AgentUiMailbox(private val token: String) {
    private var serialized = ""
    private var revision = 0L
    private var readRevision = 0L
    @Synchronized fun offer(value: String): Boolean {
        if (value.length > 600000) return false
        val state = runCatching { JSONObject(value) }.getOrNull() ?: return false
        if (state.optString("workspace").isBlank() || state.optString("session").isBlank() ||
            state.optString("engine") !in setOf("pi", "gemini", "codex", "openclaude")) return false
        val next = state.put("token", token).toString()
        if (next != serialized) { serialized = next; revision++ }
        return true
    }
    @Synchronized fun read(providedToken: String, after: String): String {
        if (providedToken != token || after.length > 20 || serialized.isEmpty()) return ""
        val last = after.toLongOrNull() ?: return ""
        if (last < 0 || last >= revision) return ""
        readRevision = revision
        return JSONObject().put("version", 1).put("revision", revision).put("state", JSONObject(serialized)).toString()
    }
    @Synchronized fun wasRead(value: Long): Boolean = value > 0 && value <= readRevision
}
