package dev.aster.ide.data

import android.content.Context
import org.json.JSONArray
import java.io.File

data class ServerSpec(
    val id: String, val name: String, val languageIds: List<String>, val extensions: List<String>,
    val filenames: List<String>, val command: List<String>, val source: String, val packages: List<String>, val npm: List<String>
) {
    fun languageId(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "js", "mjs", "cjs" -> "javascript"; "jsx" -> "javascriptreact"; "tsx" -> "typescriptreact"
            "c", "h" -> "c"; "scss" -> "scss"; "less" -> "less"; "jsonc" -> "jsonc"
            else -> languageIds.first()
        }
    }
}

class LanguageCatalog(context: Context) {
    val all: List<ServerSpec> = JSONArray(context.assets.open("servers.json").bufferedReader().use { it.readText() }).let { array ->
        (0 until array.length()).map { i -> array.getJSONObject(i).let { j ->
            ServerSpec(j.getString("id"), j.getString("name"), j.getJSONArray("languageIds").strings(), j.getJSONArray("extensions").strings(),
                j.optJSONArray("filenames")?.strings() ?: emptyList(), j.getJSONArray("command").strings(), j.getString("source"),
                j.optJSONArray("packages")?.strings() ?: emptyList(), j.optJSONArray("npm")?.strings() ?: emptyList())
        } }
    }
    fun forPath(path: String): ServerSpec? {
        val name = File(path).name
        val extension = name.substringAfterLast('.', "").lowercase()
        return all.firstOrNull { extension in it.extensions || name in it.filenames }
    }
}
fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }