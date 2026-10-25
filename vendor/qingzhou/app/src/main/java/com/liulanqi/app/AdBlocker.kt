package com.liulanqi.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

object AdBlocker {
private const val EASYLIST_URL = "https://easylist-downloads.adblockplus.org/easylist.txt"
private const val PREFS_NAME = "adblocker_prefs"
private const val KEY_LAST_UPDATE = "last_update"
private const val KEY_RULES_CACHE = "rules_cache"
private const val KEY_RULES_DOWNLOADED = "rules_downloaded"
private const val KEY_RULES_VERSION = "rules_version"
private const val KEY_AUTO_UPDATE_MODE = "auto_update_mode"

private val domainSuffixRules = mutableListOf<String>()
private val shortcutIndex = mutableMapOf<String, MutableList<Pair<String, Pattern>>>()

private var isEnabled = true

fun init(context: Context) {
val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
isEnabled = prefs.getBoolean("adblock_enabled", true)

val cachedRules = prefs.getString(KEY_RULES_CACHE, null)
if (cachedRules != null) {
parseRules(cachedRules)
}
}

fun needsRulesDownload(context: Context): Boolean {
val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
val hasDownloaded = prefs.getBoolean(KEY_RULES_DOWNLOADED, false)
val hasCache = prefs.getString(KEY_RULES_CACHE, null) != null
return !hasDownloaded && !hasCache
}

fun getRuleStatus(context: Context): Pair<String, Boolean> {
val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
val hasDownloaded = prefs.getBoolean(KEY_RULES_DOWNLOADED, false)
val hasCache = prefs.getString(KEY_RULES_CACHE, null) != null
val version = prefs.getString(KEY_RULES_VERSION, "")

if (!hasDownloaded && !hasCache) {
return Pair("未下载", false)
}

val versionText = if (version.isNullOrEmpty()) "" else " (v$version)"
return Pair("已下载$versionText", true)
}

fun getRuleVersion(context: Context): String {
val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
return prefs.getString(KEY_RULES_VERSION, "") ?: ""
}

fun getAutoUpdateMode(context: Context): Int {
return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
.getInt(KEY_AUTO_UPDATE_MODE, 1)
}

fun setAutoUpdateMode(context: Context, mode: Int) {
context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
putInt(KEY_AUTO_UPDATE_MODE, mode)
apply()
}

if (mode > 0) {
val meetsCondition = when (mode) {
1 -> isWifiConnected(context)
2 -> true
else -> false
}
if (meetsCondition) {
autoCheckUpdate(context)
}
}
}

fun autoCheckUpdate(context: Context) {
val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
val lastUpdate = prefs.getLong(KEY_LAST_UPDATE, 0)
val fourDaysMs = 4 * 24 * 60 * 60 * 1000L

if (System.currentTimeMillis() - lastUpdate < fourDaysMs) {
return
}

val mode = getAutoUpdateMode(context)
val meetsCondition = when (mode) {
1 -> isWifiConnected(context)
2 -> true
else -> false
}

if (!meetsCondition) return

updateRules(context)
}

fun updateRules(context: Context) {
Thread {
try {
val url = URL(EASYLIST_URL)
val connection = url.openConnection() as HttpURLConnection
connection.requestMethod = "GET"
connection.connectTimeout = 15000
connection.readTimeout = 15000

val reader = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8"))
val stringBuilder = StringBuilder()
var line: String?
while (reader.readLine().also { line = it } != null) {
stringBuilder.append(line).append("\n")
}
reader.close()
connection.disconnect()

val rulesText = stringBuilder.toString()
parseRules(rulesText)

val version = extractVersion(rulesText)

context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
putString(KEY_RULES_CACHE, rulesText)
putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
putBoolean(KEY_RULES_DOWNLOADED, true)
if (version != null) putString(KEY_RULES_VERSION, version)
apply()
}
} catch (e: Exception) {
e.printStackTrace()
}
}.start()
}

fun updateRulesWithCallback(context: Context, onComplete: () -> Unit) {
Thread {
try {
val url = URL(EASYLIST_URL)
val connection = url.openConnection() as HttpURLConnection
connection.requestMethod = "GET"
connection.connectTimeout = 15000
connection.readTimeout = 15000

val reader = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8"))
val stringBuilder = StringBuilder()
var line: String?
while (reader.readLine().also { line = it } != null) {
stringBuilder.append(line).append("\n")
}
reader.close()
connection.disconnect()

val rulesText = stringBuilder.toString()
parseRules(rulesText)

val version = extractVersion(rulesText)

context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
putString(KEY_RULES_CACHE, rulesText)
putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
putBoolean(KEY_RULES_DOWNLOADED, true)
if (version != null) putString(KEY_RULES_VERSION, version)
apply()
}
onComplete()
} catch (e: Exception) {
e.printStackTrace()
}
}.start()
}

private fun extractVersion(content: String): String? {
content.lineSequence().forEach { line ->
if (line.startsWith("! Version:")) {
return line.substringAfter("! Version:").trim()
}
}
return null
}

fun setEnabled(enabled: Boolean, context: Context) {
isEnabled = enabled
context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
putBoolean("adblock_enabled", enabled)
apply()
}
}

fun isEnabled(): Boolean = isEnabled

fun shouldBlock(url: String): Boolean {
if (!isEnabled) return false
return checkBlocked(url)
}

private fun checkBlocked(url: String): Boolean {
try {
val urlObj = java.net.URL(url)
val host = urlObj.host
if (domainSuffixRules.any { host.endsWith(it) }) {
return true
}
} catch (_: Exception) {
}

for (i in url.indices) {
for (j in i + 1..url.length) {
val sub = url.substring(i, j)
shortcutIndex[sub]?.forEach { (_, pattern) ->
if (pattern.matcher(url).find()) {
return true
}
}
}
}

return false
}

fun getStats(): Map<String, Int> {
return emptyMap()
}

fun resetStats() {
}

private fun isWifiConnected(context: Context): Boolean {
val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
val network = cm.activeNetwork ?: return false
val caps = cm.getNetworkCapabilities(network) ?: return false
return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
}

private fun parseRules(text: String) {
domainSuffixRules.clear()
shortcutIndex.clear()

val lines = text.lines()
for (line in lines) {
val trimmed = line.trim()
if (trimmed.isEmpty() || trimmed.startsWith("!") || trimmed.startsWith("##") || trimmed.startsWith("[")) {
continue
}

when {
trimmed.startsWith("||") -> {
val domain = trimmed.removePrefix("||")
.takeWhile { it != '^' && it != '/' && it != '*' && it != '?' }
if (domain.isNotEmpty()) {
domainSuffixRules.add(domain)
}
}
else -> {
val shortcut = extractShortcut(trimmed)
if (shortcut.isNotEmpty()) {
val pattern = Pattern.compile(wildcardToRegex(trimmed))
shortcutIndex.getOrPut(shortcut) { mutableListOf() }.add(trimmed to pattern)
}
}
}
}
}

private fun extractShortcut(rule: String): String {
var cleaned = rule.removePrefix("||")
cleaned = cleaned.replace("^", "")
val match = Regex("[a-zA-Z0-9]{4,}").find(cleaned)
return match?.value ?: ""
}

private fun wildcardToRegex(pattern: String): String {
var cleaned = pattern.removePrefix("||")
cleaned = java.util.regex.Pattern.quote(cleaned)
cleaned = cleaned.replace("\\*", ".*")
cleaned = cleaned.replace("\\^", "[/?&#]?")
return ".*$cleaned.*"
}
}