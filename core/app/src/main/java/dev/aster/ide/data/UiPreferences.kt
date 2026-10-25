package dev.aster.ide.data

import org.json.JSONObject

/** Presentation preference only; both styles use the same workspace and services. */
enum class UiStyle(val key: String, val title: String) {
    VSCODE("vscode", "原 UI · VS Code 样式"),
    ACS("acs", "ACS 样式");

    companion object {
        fun fromKey(key: String?): UiStyle? = entries.firstOrNull { it.key == key }
    }
}

data class UiPreferences(val style: UiStyle = UiStyle.VSCODE, val chosen: Boolean = false) {
    fun writeTo(json: JSONObject) {
        json.put("uiStyle", style.key)
        json.put("uiStyleChosen", chosen)
    }

    companion object {
        fun read(json: JSONObject): UiPreferences {
            val style = UiStyle.fromKey(json.optString("uiStyle"))
            return UiPreferences(style ?: UiStyle.VSCODE, style != null && json.optBoolean("uiStyleChosen", false))
        }
    }
}

object JdkVersions {
    const val DEFAULT = "17"
    val supported = listOf("17", "21")
    fun normalize(version: String?) = version?.takeIf { it in supported } ?: DEFAULT
}
