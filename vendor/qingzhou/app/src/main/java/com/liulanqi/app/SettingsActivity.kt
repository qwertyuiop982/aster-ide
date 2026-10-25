package com.liulanqi.app

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import java.io.File

class SettingsActivity : AppCompatActivity() {

private lateinit var cacheSizeText: TextView
private lateinit var clipboardRightText: TextView
private lateinit var themeRightText: TextView
private lateinit var engineRightText: TextView

override fun onCreate(savedInstanceState: Bundle?) {
super.onCreate(savedInstanceState)
supportActionBar?.hide()

val scrollView = android.widget.ScrollView(this)
val layout = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setPadding(16, 16, 16, 16)
setBackgroundColor(obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground)).getColor(0, 0))
}
scrollView.addView(layout)
setContentView(scrollView)

val titleRow = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
gravity = Gravity.CENTER_VERTICAL
setPadding(0, 0, 0, 24)
}

val btnBack = ImageButton(this).apply {
setImageResource(R.drawable.ic_back_adaptive)
background = null
setOnClickListener { finish() }
}
titleRow.addView(btnBack)

titleRow.addView(TextView(this).apply {
text = "设置"
textSize = 24f
layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
})
layout.addView(titleRow)

val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)

val group1 = createCardGroup()
group1.addView(createSimpleItem("Cookie 管理", ">") {
startActivity(Intent(this, CookieListActivity::class.java))
})
group1.addView(createCardDivider())
group1.addView(createCacheItem())
group1.addView(createCardDivider())
group1.addView(createClipboardItem(prefs))
layout.addView(group1)

layout.addView(createCardSpacer())

val group2 = createCardGroup()
group2.addView(createSimpleItem("广告拦截", ">") {
startActivity(Intent(this, AdblockStatsActivity::class.java))
})
group2.addView(createCardDivider())
group2.addView(createThemeItem(prefs))
group2.addView(createCardDivider())
group2.addView(createEngineItem(prefs))
layout.addView(group2)

layout.addView(createCardSpacer())

val group3 = createCardGroup()
group3.addView(createSimpleItem("脚本管理", ">") {
startActivity(Intent(this, ScriptManagerActivity::class.java))
})
layout.addView(group3)

val size = computeCacheSize()
cacheSizeText.text = formatFileSize(size)
}

private fun createCardSpacer(): View {
return View(this).apply {
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
12
)
}
}

private fun createCardGroup(): LinearLayout {
val isDark = androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode() == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
val bg = GradientDrawable().apply {
shape = GradientDrawable.RECTANGLE
cornerRadius = 16f
setColor(if (isDark) 0xFF2A2A2A.toInt() else 0xFFFFFFFF.toInt())
}
return LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
background = bg
elevation = 2f
}
}

private fun createCardDivider(): View {
val isDark = androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode() == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
return View(this).apply {
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
1
).apply {
leftMargin = 16
}
setBackgroundColor(if (isDark) 0x1AFFFFFF else 0x1A000000)
}
}

private fun createSimpleItem(text: String, rightText: String, onClick: () -> Unit): LinearLayout {
return LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
setPadding(16, 20, 16, 20)
isClickable = true
isFocusable = true
gravity = Gravity.CENTER_VERTICAL
addView(TextView(this@SettingsActivity).apply {
this.text = text
textSize = 18f
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
})
val spacer = LinearLayout(this@SettingsActivity).apply {
layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
}
addView(spacer)
addView(TextView(this@SettingsActivity).apply {
this.text = rightText
textSize = 16f
gravity = Gravity.END
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary)).getColor(0, 0))
})
setOnClickListener { onClick() }
}
}

private fun createCacheItem(): LinearLayout {
val item = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
setPadding(16, 20, 16, 20)
isClickable = true
isFocusable = true
gravity = Gravity.CENTER_VERTICAL
}
item.addView(TextView(this).apply {
text = "清除缓存"
textSize = 18f
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
})
val spacer = LinearLayout(this).apply {
layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
}
item.addView(spacer)
cacheSizeText = TextView(this).apply {
text = ""
textSize = 16f
gravity = Gravity.END
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary)).getColor(0, 0))
}
item.addView(cacheSizeText)
item.addView(TextView(this).apply {
text = "  >"
textSize = 16f
gravity = Gravity.END
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary)).getColor(0, 0))
})
item.setOnClickListener { clearCache() }
return item
}

private fun createClipboardItem(prefs: android.content.SharedPreferences): LinearLayout {
val item = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
setPadding(16, 20, 16, 20)
isClickable = true
isFocusable = true
gravity = Gravity.CENTER_VERTICAL
}
item.addView(TextView(this).apply {
text = "剪贴板提示"
textSize = 18f
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
})
val spacer = LinearLayout(this).apply {
layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
}
item.addView(spacer)
val enabled = prefs.getBoolean("clipboard_alert", true)
clipboardRightText = TextView(this).apply {
text = if (enabled) "已开启  >" else "已关闭  >"
textSize = 16f
gravity = Gravity.END
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary)).getColor(0, 0))
}
item.addView(clipboardRightText)
item.setOnClickListener {
val current = prefs.getBoolean("clipboard_alert", true)
val options = arrayOf("开启", "关闭")
AlertDialog.Builder(this@SettingsActivity)
.setTitle("剪贴板提示")
.setSingleChoiceItems(options, if (current) 0 else 1) { dialog, which ->
val newValue = which == 0
prefs.edit().putBoolean("clipboard_alert", newValue).apply()
clipboardRightText.text = if (newValue) "已开启  >" else "已关闭  >"
dialog.dismiss()
}
.setNegativeButton("取消", null)
.show()
}
return item
}

private fun createThemeItem(prefs: android.content.SharedPreferences): LinearLayout {
val item = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
setPadding(16, 20, 16, 20)
isClickable = true
isFocusable = true
gravity = Gravity.CENTER_VERTICAL
}
item.addView(TextView(this).apply {
text = "深色模式"
textSize = 18f
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
})
val spacer = LinearLayout(this).apply {
layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
}
item.addView(spacer)
val defaultMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
if (!prefs.contains("night_mode")) {
prefs.edit().putInt("night_mode", defaultMode).apply()
}
val currentMode = prefs.getInt("night_mode", defaultMode)
themeRightText = TextView(this).apply {
text = "${getModeText(currentMode)}  >"
textSize = 16f
gravity = Gravity.END
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary)).getColor(0, 0))
}
item.addView(themeRightText)
item.setOnClickListener {
val current = prefs.getInt("night_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
val options = arrayOf("跟随系统", "浅色模式", "深色模式")
val checkedItem = when (current) {
AppCompatDelegate.MODE_NIGHT_NO -> 1
AppCompatDelegate.MODE_NIGHT_YES -> 2
else -> 0
}
AlertDialog.Builder(this@SettingsActivity)
.setTitle("选择主题模式")
.setSingleChoiceItems(options, checkedItem) { dialog, which ->
val newMode = when (which) {
0 -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
1 -> AppCompatDelegate.MODE_NIGHT_NO
2 -> AppCompatDelegate.MODE_NIGHT_YES
else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
}
prefs.edit().putInt("night_mode", newMode).apply()
AppCompatDelegate.setDefaultNightMode(newMode)
themeRightText.text = "${getModeText(newMode)}  >"
dialog.dismiss()
}
.setNegativeButton("取消", null)
.show()
}
return item
}

private fun createEngineItem(prefs: android.content.SharedPreferences): LinearLayout {
val item = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
setPadding(16, 20, 16, 20)
isClickable = true
isFocusable = true
gravity = Gravity.CENTER_VERTICAL
}
item.addView(TextView(this).apply {
text = "搜索引擎"
textSize = 18f
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
})
val spacer = LinearLayout(this).apply {
layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
}
item.addView(spacer)
val engines = arrayOf("百度", "Google", "Bing", "DuckDuckGo", "搜狗", "360搜索", "神马", "Yandex", "Ecosia")
val engineValues = arrayOf("baidu", "google", "bing", "duckduckgo", "sogou", "360", "sm", "yandex", "ecosia")
val currentEngine = prefs.getString("search_engine", "baidu") ?: "baidu"
val currentEngineIndex = engineValues.indexOf(currentEngine)
engineRightText = TextView(this).apply {
text = "${engines.getOrElse(currentEngineIndex) { "百度" }}  >"
textSize = 16f
gravity = Gravity.END
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary)).getColor(0, 0))
}
item.addView(engineRightText)
item.setOnClickListener {
val engines = arrayOf("百度", "Google", "Bing", "DuckDuckGo", "搜狗", "360搜索", "神马", "Yandex", "Ecosia")
val engineValues = arrayOf("baidu", "google", "bing", "duckduckgo", "sogou", "360", "sm", "yandex", "ecosia")
val currentEngine = prefs.getString("search_engine", "baidu") ?: "baidu"
val currentEngineIndex = engineValues.indexOf(currentEngine)
AlertDialog.Builder(this@SettingsActivity)
.setTitle("选择搜索引擎")
.setSingleChoiceItems(engines, if (currentEngineIndex >= 0) currentEngineIndex else 0) { dialog, which ->
prefs.edit().putString("search_engine", engineValues[which]).apply()
engineRightText.text = "${engines[which]}  >"
dialog.dismiss()
}
.setNegativeButton("取消", null)
.show()
}
return item
}

private fun computeCacheSize(): Long {
var total = 0L
val webViewDir = File(cacheDir, "WebView")
if (webViewDir.exists()) total += getDirSize(webViewDir)
val defaultDir = File(cacheDir, "Default")
if (defaultDir.exists()) total += getDirSize(defaultDir)
return total
}

private fun getDirSize(dir: File): Long {
var size = 0L
if (dir.isDirectory) {
dir.listFiles()?.forEach { size += if (it.isDirectory) getDirSize(it) else it.length() }
}
return size
}

private fun clearCache() {
Thread {
try {
val webViewDir = File(cacheDir, "WebView")
if (webViewDir.exists()) deleteDir(webViewDir)
val defaultDir = File(cacheDir, "Default")
if (defaultDir.exists()) deleteDir(defaultDir)
deleteDatabase("webview.db")
deleteDatabase("webviewCache.db")
} catch (e: Exception) {
runOnUiThread {
Toast.makeText(this@SettingsActivity, "清除失败: ${e.message}", Toast.LENGTH_SHORT).show()
}
return@Thread
}
val sizeAfter = computeCacheSize()
runOnUiThread {
cacheSizeText.text = formatFileSize(sizeAfter)
Toast.makeText(this@SettingsActivity, "缓存已清除", Toast.LENGTH_SHORT).show()
}
}.start()
}

private fun deleteDir(dir: File) {
if (dir.isDirectory) {
dir.listFiles()?.forEach {
if (it.isDirectory) deleteDir(it)
it.delete()
}
}
}

private fun formatFileSize(size: Long): String {
return when {
size < 1024 -> "$size B"
size < 1024 * 1024 -> "${size / 1024} KB"
size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
else -> "${size / (1024 * 1024 * 1024)} GB"
}
}

private fun getModeText(mode: Int): String {
return when (mode) {
AppCompatDelegate.MODE_NIGHT_YES -> "深色模式"
AppCompatDelegate.MODE_NIGHT_NO -> "浅色模式"
else -> "跟随系统"
}
}
}