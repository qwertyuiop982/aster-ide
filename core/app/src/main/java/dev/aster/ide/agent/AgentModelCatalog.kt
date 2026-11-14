package dev.aster.ide.agent

import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import org.json.JSONObject

/** Explicit, read-only discovery. Credentials never enter URLs, settings JSON or errors. */
object AgentModelCatalog {
    fun fetch(engine: AgentEngine, endpoint: String, protocol: String, key: String): List<String> {
        val base = URI(endpoint.trim().trimEnd('/'))
        require(base.scheme in setOf("https", "http") && !base.host.isNullOrBlank() && base.userInfo == null && base.rawQuery == null && base.rawFragment == null) { "API 基础地址无效" }
        val gemini = engine == AgentEngine.GEMINI && protocol != "openai"
        val anthropic = engine == AgentEngine.OPENCLAUDE
        val path = base.path.orEmpty().trimEnd('/')
        val prefix = when {
            gemini && !Regex("/v[0-9]+(?:beta[0-9]*|alpha[0-9]*)?$").containsMatchIn(path) -> "$base/v1beta"
            anthropic && !path.endsWith("/v1") -> "$base/v1"
            else -> base.toString()
        }
        val models = linkedSetOf<String>()
        val pages = mutableSetOf<String>()
        var cursor = ""
        do {
            check(!Thread.currentThread().isInterrupted) { "模型列表请求已取消" }
            check(pages.add(cursor) && pages.size <= 100) { "模型列表分页无效或过多" }
            val query = when {
                gemini -> "?pageSize=1000" + if (cursor.isBlank()) "" else "&pageToken=" + encode(cursor)
                anthropic -> "?limit=1000" + if (cursor.isBlank()) "" else "&after_id=" + encode(cursor)
                else -> ""
            }
            val response = get(URI("$prefix/models$query"), engine, gemini, key)
            val rows = response.optJSONArray(if (gemini) "models" else "data") ?: error("服务端未返回模型列表")
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                if (gemini) {
                    val methods = row.optJSONArray("supportedGenerationMethods")
                    if (methods != null && (0 until methods.length()).none { methods.optString(it) == "generateContent" }) continue
                }
                val id = row.optString(if (gemini) "name" else "id").removePrefix(if (gemini) "models/" else "").trim()
                if (id.isNotEmpty()) models.add(id)
            }
            cursor = when {
                gemini -> response.optString("nextPageToken")
                anthropic && response.optBoolean("has_more") -> response.optString("last_id").also { check(it.isNotBlank()) { "服务端缺少下一页标识" } }
                else -> ""
            }
        } while (cursor.isNotBlank())
        check(models.isNotEmpty()) { "服务端未返回可用模型，请手动填写" }
        return models.sorted()
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun get(uri: URI, engine: AgentEngine, gemini: Boolean, key: String): JSONObject {
        val connection = uri.toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false // Never forward a secret to a redirected origin.
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.setRequestProperty("Accept", "application/json")
            if (key.isNotBlank()) when {
                gemini -> connection.setRequestProperty("x-goog-api-key", key)
                engine == AgentEngine.OPENCLAUDE -> connection.setRequestProperty("x-api-key", key)
                else -> connection.setRequestProperty("Authorization", "Bearer $key")
            }
            if (engine == AgentEngine.OPENCLAUDE) connection.setRequestProperty("anthropic-version", "2023-06-01")
            val code = connection.responseCode
            check(code in 200..299) { "获取模型列表失败（HTTP $code）；请检查 API 地址、协议和密钥" }
            val text = StringBuilder()
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    check(text.length + count <= 4 * 1024 * 1024) { "服务端模型列表过大" }
                    text.append(buffer, 0, count)
                }
            }
            return try { JSONObject(text.toString()) } catch (_: org.json.JSONException) { error("服务端模型列表不是有效 JSON") }
        } finally { connection.disconnect() }
    }
}