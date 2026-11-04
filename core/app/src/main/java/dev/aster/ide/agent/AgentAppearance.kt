package dev.aster.ide.agent

import java.util.Locale

/** Two endpoints shared by the Codex UI and its Ultra bubble. */
object AgentAppearance {
    const val DEFAULT_LEFT = "#713BC4"
    const val DEFAULT_RIGHT = "#365DB7"
    fun isColor(value: String): Boolean = Regex("#[0-9a-fA-F]{6}").matches(value.trim())
    fun color(value: String, fallback: String): String =
        value.trim().takeIf(::isColor)?.uppercase(Locale.ROOT) ?: fallback
}
