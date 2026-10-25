package com.liulanqi.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

object ScriptEngine {

private const val PREFS_NAME = "scripts"
private const val KEY_SCRIPTS = "script_list"

fun getScriptList(context: Context): List<ScriptInfo> {
val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
val json = prefs.getString(KEY_SCRIPTS, null) ?: return emptyList()
val list = mutableListOf<ScriptInfo>()
try {
val arr = JSONArray(json)
for (i in 0 until arr.length()) {
val obj = arr.getJSONObject(i)
val matchesArr = obj.getJSONArray("matches")
val matches = mutableListOf<String>()
for (j in 0 until matchesArr.length()) {
matches.add(matchesArr.getString(j))
}
val requiresArr = obj.optJSONArray("requires")
val requires = mutableListOf<String>()
if (requiresArr != null) {
for (j in 0 until requiresArr.length()) {
requires.add(requiresArr.getString(j))
}
}
list.add(
ScriptInfo(
id = obj.getString("id"),
name = obj.getString("name"),
version = obj.getString("version"),
namespace = obj.optString("namespace", ""),
matches = matches,
grants = obj.getString("grants").split(",").filter { it.isNotEmpty() },
requires = requires,
code = obj.getString("code"),
enabled = obj.getBoolean("enabled")
)
)
}
} catch (_: Exception) {}
return list
}

fun saveScript(context: Context, script: ScriptInfo) {
val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
val list = getScriptList(context).toMutableList()
val existing = list.indexOfFirst { it.id == script.id }
if (existing >= 0) {
list[existing] = script
} else {
list.add(script)
}
saveScriptList(prefs, list)
}

fun setScriptEnabled(context: Context, scriptId: String, enabled: Boolean) {
val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
val list = getScriptList(context).toMutableList()
val index = list.indexOfFirst { it.id == scriptId }
if (index >= 0) {
list[index] = list[index].copy(enabled = enabled)
saveScriptList(prefs, list)
}
}

fun deleteScript(context: Context, scriptId: String) {
val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
val list = getScriptList(context).toMutableList()
list.removeAll { it.id == scriptId }
saveScriptList(prefs, list)
}

fun parseMetadata(code: String): ScriptMetadata {
var name = ""
var namespace = ""
var version = ""
val matches = mutableListOf<String>()
val grants = mutableListOf<String>()
val requires = mutableListOf<String>()

val lines = code.lines()
var inHeader = false
for (line in lines) {
val trimmed = line.trim()
if (trimmed == "// ==UserScript==") {
inHeader = true
continue
}
if (trimmed == "// ==/UserScript==") {
break
}
if (!inHeader) continue
if (!trimmed.startsWith("// @")) continue

when {
trimmed.startsWith("// @name ") || trimmed == "// @name" -> {
val value = if (trimmed.startsWith("// @name ")) {
trimmed.substringAfter("// @name ").trim()
} else {
""
}
if (name.isEmpty()) name = value
}
trimmed.startsWith("// @namespace ") || trimmed == "// @namespace" -> {
val value = if (trimmed.startsWith("// @namespace ")) {
trimmed.substringAfter("// @namespace ").trim()
} else {
""
}
if (namespace.isEmpty()) namespace = value
}
trimmed.startsWith("// @version ") || trimmed == "// @version" -> {
val value = if (trimmed.startsWith("// @version ")) {
trimmed.substringAfter("// @version ").trim()
} else {
""
}
if (version.isEmpty()) version = value
}
trimmed.startsWith("// @match ") || trimmed == "// @match" -> {
val value = if (trimmed.startsWith("// @match ")) {
trimmed.substringAfter("// @match ").trim()
} else {
""
}
if (value.isNotEmpty()) matches.add(value)
}
trimmed.startsWith("// @grant ") || trimmed == "// @grant" -> {
val value = if (trimmed.startsWith("// @grant ")) {
trimmed.substringAfter("// @grant ").trim()
} else {
""
}
if (value.isNotEmpty()) grants.add(value)
}
trimmed.startsWith("// @require ") || trimmed == "// @require" -> {
val value = if (trimmed.startsWith("// @require ")) {
trimmed.substringAfter("// @require ").trim()
} else {
""
}
if (value.isNotEmpty()) requires.add(value)
}
}
}

return ScriptMetadata(
name = name.ifEmpty { "未命名脚本" },
namespace = namespace.ifEmpty { "http://tampermonkey.net/" },
version = version.ifEmpty { "1.0.0" },
matches = matches.ifEmpty { listOf("*://*/*") },
grants = grants.ifEmpty { listOf("none") },
requires = requires
)
}

fun scanCode(code: String): List<CodeWarning> {
val warnings = mutableListOf<CodeWarning>()
val suspicious = listOf("eval(", "atob(", "Function(", "setTimeout(", "setInterval(")
val lines = code.lines()
for (i in lines.indices) {
for (pattern in suspicious) {
if (lines[i].contains(pattern)) {
warnings.add(CodeWarning(i + 1, pattern, lines[i].trim()))
break
}
}
}
return warnings
}

fun installScript(context: Context, code: String): ScriptInfo {
val meta = parseMetadata(code)
val id = UUID.randomUUID().toString()
val validatedRequires = validateRequires(meta.requires)
val script = ScriptInfo(
id = id,
name = meta.name,
version = meta.version,
namespace = meta.namespace,
matches = meta.matches,
grants = meta.grants,
requires = validatedRequires,
code = code,
enabled = true
)
saveScript(context, script)
return script
}

fun validateRequires(requires: List<String>): List<String> {
val validated = mutableListOf<String>()
for (require in requires) {
if (!require.contains("#sha384-")) {
throw SRIException("外部资源必须提供 SRI 哈希校验值：$require")
}
val parts = require.split("#sha384-")
val url = parts[0]
val expectedHash = parts[1]
try {
val connection = URL(url).openConnection() as HttpURLConnection
connection.requestMethod = "GET"
connection.connectTimeout = 15000
connection.readTimeout = 15000
val reader = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8"))
val content = reader.readText()
reader.close()
connection.disconnect()
val actualHash = sha384(content)
if (actualHash != expectedHash) {
throw SRIException("外部资源校验失败，可能是文件被篡改：$url\n期望：$expectedHash\n实际：$actualHash")
}
validated.add(require)
} catch (e: SRIException) {
throw e
} catch (e: Exception) {
throw SRIException("下载外部资源失败：$url\n${e.message}")
}
}
return validated
}

private fun sha384(input: String): String {
val digest = MessageDigest.getInstance("SHA-384")
val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
return Base64.getEncoder().encodeToString(hash)
}

fun getStorage(context: Context, scriptId: String, key: String): String {
return context.getSharedPreferences("${PREFS_NAME}_${scriptId}", Context.MODE_PRIVATE)
.getString(key, "") ?: ""
}

fun setStorage(context: Context, scriptId: String, key: String, value: String) {
context.getSharedPreferences("${PREFS_NAME}_${scriptId}", Context.MODE_PRIVATE)
.edit().putString(key, value).apply()
}

private fun saveScriptList(prefs: SharedPreferences, list: List<ScriptInfo>) {
val arr = JSONArray()
for (script in list) {
val obj = JSONObject()
obj.put("id", script.id)
obj.put("name", script.name)
obj.put("version", script.version)
obj.put("namespace", script.namespace)
obj.put("matches", JSONArray(script.matches))
obj.put("grants", script.grants.joinToString(","))
obj.put("requires", JSONArray(script.requires))
obj.put("code", script.code)
obj.put("enabled", script.enabled)
arr.put(obj)
}
prefs.edit().putString(KEY_SCRIPTS, arr.toString()).apply()
}
}

data class ScriptInfo(
val id: String,
val name: String,
val version: String,
val namespace: String,
val matches: List<String>,
val grants: List<String>,
val requires: List<String>,
val code: String,
var enabled: Boolean
)

data class ScriptMetadata(
val name: String,
val namespace: String,
val version: String,
val matches: List<String>,
val grants: List<String>,
val requires: List<String>
)

data class CodeWarning(
val line: Int,
val type: String,
val content: String
)

class SRIException(message: String) : Exception(message)