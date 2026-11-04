package dev.aster.ide.agent

/** Prevent configured provider credentials from appearing in client errors. */
internal object AgentDiagnostics {
    fun redact(message: String, secrets: List<String>): String =
        secrets.filter(String::isNotBlank).distinct().sortedByDescending(String::length)
            .fold(message) { text, secret -> text.replace(secret, "[hidden]") }.take(1600)
}
