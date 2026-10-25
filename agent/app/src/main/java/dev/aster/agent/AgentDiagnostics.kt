package dev.aster.agent

/** Local diagnostic text may be copied by the user; never include known credentials. */
internal object AgentDiagnostics {
    fun redact(text: String, secrets: List<String> = emptyList()): String {
        var safe = text
        secrets.filter { it.length >= 4 }.sortedByDescending { it.length }.forEach { safe = safe.replace(it, "[已隐藏]") }
        safe = safe.replace(Regex("(?i)Bearer\\s+[^\\s\"']+"), "Bearer [已隐藏]")
        safe = safe.replace(Regex("(?i)((?:api[_-]?key|x-api-key|authorization|access_token|token)[\"']?\\s*[:=]\\s*[\"']?)[^\\s\"'&,}]+")) { it.groupValues[1] + "[已隐藏]" }
        return safe
    }
}
