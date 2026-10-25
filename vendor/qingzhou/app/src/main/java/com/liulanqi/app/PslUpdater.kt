package com.liulanqi.app

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object PslUpdater {
private const val PSL_URL = "https://publicsuffix.org/list/public_suffix_list.dat"
private const val PREFS_NAME = "psl_prefs"
private const val KEY_VERSION = "psl_version"
private const val KEY_LAST_UPDATE = "psl_last_update"

fun isDownloaded(context: Context): Boolean {
val file = File(context.filesDir, "public_suffix_list.dat")
return file.exists()
}

fun getVersion(context: Context): String {
val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
val version = prefs.getString(KEY_VERSION, null)
return if (version != null) "v$version" else "版本未知"
}

fun getUpdateTime(context: Context): String {
val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
val lastUpdate = prefs.getLong(KEY_LAST_UPDATE, 0)
if (lastUpdate > 0) {
val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
return sdf.format(java.util.Date(lastUpdate))
}
return "未知"
}

fun update(context: Context, onComplete: (() -> Unit)? = null) {
Thread {
try {
val url = URL(PSL_URL)
val connection = url.openConnection() as HttpURLConnection
connection.requestMethod = "GET"
connection.connectTimeout = 15000
connection.readTimeout = 15000

val reader = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8"))
val content = reader.readText()
reader.close()
connection.disconnect()

val version = extractVersion(content)

val file = File(context.filesDir, "public_suffix_list.dat")
file.writeText(content)

context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
if (version != null) putString(KEY_VERSION, version)
apply()
}
} catch (e: Exception) {
e.printStackTrace()
} finally {
onComplete?.invoke()
}
}.start()
}

private fun extractVersion(content: String): String? {
content.lineSequence().forEach { line ->
if (line.startsWith("// VERSION:")) {
return line.substringAfter("// VERSION:").trim()
}
}
return null
}
}