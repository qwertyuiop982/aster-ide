package com.liulanqi.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object HistoryManager {

private const val PREFS_NAME = "history"
private const val KEY_HISTORY = "history_list"
private const val MAX_SIZE = 200

private fun getPrefs(context: Context): SharedPreferences {
return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

fun addHistory(context: Context, title: String, url: String) {
val list = getHistoryListInternal(context).toMutableList()
list.removeAll { it.url == url }
list.add(0, HistoryItem(title, url, System.currentTimeMillis()))
if (list.size > MAX_SIZE) {
list.subList(MAX_SIZE, list.size).clear()
}
saveList(context, list)
}

fun removeHistory(context: Context, url: String) {
val list = getHistoryListInternal(context).toMutableList()
list.removeAll { it.url == url }
saveList(context, list)
}

fun clearAll(context: Context) {
getPrefs(context).edit().remove(KEY_HISTORY).apply()
}

fun getHistoryList(context: Context): List<HistoryItem> {
return getHistoryListInternal(context)
}

private fun getHistoryListInternal(context: Context): List<HistoryItem> {
val json = getPrefs(context).getString(KEY_HISTORY, null) ?: return emptyList()
val list = mutableListOf<HistoryItem>()
try {
val arr = JSONArray(json)
for (i in 0 until arr.length()) {
val obj = arr.getJSONObject(i)
list.add(
HistoryItem(
title = obj.getString("title"),
url = obj.getString("url"),
timestamp = obj.getLong("timestamp")
)
)
}
} catch (_: Exception) {}
return list
}

private fun saveList(context: Context, list: List<HistoryItem>) {
val arr = JSONArray()
for (item in list) {
val obj = JSONObject()
obj.put("title", item.title)
obj.put("url", item.url)
obj.put("timestamp", item.timestamp)
arr.put(obj)
}
getPrefs(context).edit().putString(KEY_HISTORY, arr.toString()).apply()
}
}

data class HistoryItem(
val title: String,
val url: String,
val timestamp: Long
)