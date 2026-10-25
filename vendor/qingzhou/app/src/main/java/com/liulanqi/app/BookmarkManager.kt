package com.liulanqi.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object BookmarkManager {

private const val PREFS_NAME = "bookmarks"
private const val KEY_BOOKMARKS = "bookmark_list"

private fun getPrefs(context: Context): SharedPreferences {
return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

fun addBookmark(context: Context, title: String, url: String) {
val list = getBookmarks(context).toMutableList()
list.add(0, Bookmark(title, url))
saveBookmarks(context, list)
}

fun removeBookmark(context: Context, url: String) {
val list = getBookmarks(context).toMutableList()
list.removeAll { it.url == url }
saveBookmarks(context, list)
}

fun isBookmarked(context: Context, url: String): Boolean {
return getBookmarks(context).any { it.url == url }
}

fun getBookmarks(context: Context): List<Bookmark> {
val json = getPrefs(context).getString(KEY_BOOKMARKS, null) ?: return emptyList()
val list = mutableListOf<Bookmark>()
try {
val arr = JSONArray(json)
for (i in 0 until arr.length()) {
val obj = arr.getJSONObject(i)
list.add(Bookmark(obj.getString("title"), obj.getString("url")))
}
} catch (_: Exception) {}
return list
}

private fun saveBookmarks(context: Context, list: List<Bookmark>) {
val arr = JSONArray()
for (b in list) {
val obj = JSONObject()
obj.put("title", b.title)
obj.put("url", b.url)
arr.put(obj)
}
getPrefs(context).edit().putString(KEY_BOOKMARKS, arr.toString()).apply()
}
}

data class Bookmark(
val title: String,
val url: String
)