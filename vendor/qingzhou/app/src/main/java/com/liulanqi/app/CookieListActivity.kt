package com.liulanqi.app

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class CookieListActivity : AppCompatActivity() {

private lateinit var rootLayout: LinearLayout
private val handler = Handler(Looper.getMainLooper())

override fun onCreate(savedInstanceState: Bundle?) {
super.onCreate(savedInstanceState)
supportActionBar?.hide()

val scrollView = ScrollView(this)
rootLayout = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setPadding(16, 16, 16, 16)
setBackgroundColor(obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground)).getColor(0, 0))
}
scrollView.addView(rootLayout)
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
text = "Cookie 管理"
textSize = 24f
layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
})
rootLayout.addView(titleRow)

val switchRow = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
gravity = Gravity.CENTER_VERTICAL
setPadding(0, 8, 0, 8)
}
val switchLabel = TextView(this).apply {
text = "启用 Cookie"
textSize = 16f
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
}
switchRow.addView(switchLabel)

val spacer = View(this).apply {
layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
}
switchRow.addView(spacer)

val cookieSwitch = Switch(this).apply {
val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
isChecked = prefs.getBoolean("cookie_enabled", true)
setOnCheckedChangeListener { _, isChecked ->
prefs.edit().putBoolean("cookie_enabled", isChecked).apply()
Toast.makeText(
this@CookieListActivity,
if (isChecked) "Cookie 已开启" else "Cookie 已关闭",
Toast.LENGTH_SHORT
).show()
}
}
switchRow.addView(cookieSwitch)
rootLayout.addView(switchRow)

val pslStatusRow = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
setPadding(0, 12, 0, 12)
gravity = Gravity.CENTER_VERTICAL
}
val downloaded = PslUpdater.isDownloaded(this@CookieListActivity)
val pslLabel = TextView(this).apply {
text = if (downloaded) {
"规则版本：${PslUpdater.getVersion(this@CookieListActivity)}"
} else {
"规则状态：未下载"
}
textSize = 13f
layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary)).getColor(0, 0))
}
pslStatusRow.addView(pslLabel)

val pslButton = Button(this).apply {
text = if (downloaded) "更新规则" else "下载规则"
textSize = 12f
setOnClickListener { button ->
val btn = button as Button
btn.isEnabled = false
btn.text = "下载中..."
PslUpdater.update(this@CookieListActivity) {
handler.post {
val newDownloaded = PslUpdater.isDownloaded(this@CookieListActivity)
btn.isEnabled = true
btn.text = if (newDownloaded) "更新规则" else "下载规则"
pslLabel.text = if (newDownloaded) {
"规则版本：${PslUpdater.getVersion(this@CookieListActivity)}"
} else {
"规则状态：未下载"
}
Toast.makeText(this@CookieListActivity, "规则库已更新", Toast.LENGTH_SHORT).show()
}
}
}
}
pslStatusRow.addView(pslButton)
rootLayout.addView(pslStatusRow)

rootLayout.addView(View(this).apply {
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
1
).apply {
topMargin = 12
bottomMargin = 12
}
setBackgroundColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorTertiary)).getColor(0, 0))
})

loadCookieList()
}

private fun loadCookieList() {
val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
val domains = prefs.getStringSet("cookie_domains", emptySet()) ?: emptySet()
if (domains.isEmpty()) {
rootLayout.addView(TextView(this).apply {
text = "暂无 Cookie"
textSize = 14f
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
})
return
}

val cookieManager = CookieManager.getInstance()
for (domain in domains) {
val url = "https://$domain"
val cookieString = cookieManager.getCookie(url)
if (cookieString.isNullOrEmpty()) continue

val section = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setPadding(0, 12, 0, 12)
}

val headerRow = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
}

val domainText = TextView(this).apply {
text = domain
textSize = 16f
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
}
headerRow.addView(domainText)

val deleteBtn = Button(this).apply {
text = "删除全部"
textSize = 12f
setOnClickListener {
AlertDialog.Builder(this@CookieListActivity)
.setTitle("确认删除")
.setMessage("确定要删除 $domain 下的所有 Cookie 吗？")
.setPositiveButton("删除") { _, _ ->
deleteDomainCookies(domain, cookieString)
rootLayout.removeView(section)
if (rootLayout.childCount <= 1) {
rootLayout.addView(TextView(this@CookieListActivity).apply {
text = "暂无 Cookie"
textSize = 14f
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
})
}
Toast.makeText(this@CookieListActivity, "已删除 $domain 的 Cookie", Toast.LENGTH_SHORT).show()
}
.setNegativeButton("取消", null)
.show()
}
}
headerRow.addView(deleteBtn)
section.addView(headerRow)

val cookies = parseCookies(cookieString)
for ((key, value) in cookies) {
val cookieRow = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
setPadding(24, 4, 0, 4)
}

val cookieText = TextView(this).apply {
text = "$key = $value"
textSize = 13f
layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary)).getColor(0, 0))
}
cookieRow.addView(cookieText)

val deleteSingleBtn = Button(this).apply {
text = "×"
textSize = 12f
setOnClickListener {
deleteSingleCookie(domain, key)
cookieRow.visibility = View.GONE
Toast.makeText(this@CookieListActivity, "已删除 $key", Toast.LENGTH_SHORT).show()

var hasVisibleCookie = false
for (i in 1 until section.childCount) {
if (section.getChildAt(i).visibility == View.VISIBLE) {
hasVisibleCookie = true
break
}
}
if (!hasVisibleCookie) {
rootLayout.removeView(section)
if (rootLayout.childCount <= 1) {
rootLayout.addView(TextView(this@CookieListActivity).apply {
text = "暂无 Cookie"
textSize = 14f
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
})
}
}
}
}
cookieRow.addView(deleteSingleBtn)

section.addView(cookieRow)
}

rootLayout.addView(section)
}
}

private fun parseCookies(cookieString: String): List<Pair<String, String>> {
val list = mutableListOf<Pair<String, String>>()
val parts = cookieString.split(";")
for (part in parts) {
val trimmed = part.trim()
val equalIndex = trimmed.indexOf('=')
if (equalIndex > 0) {
val key = trimmed.substring(0, equalIndex)
val value = trimmed.substring(equalIndex + 1)
list.add(Pair(key, value))
}
}
return list
}

private fun deleteDomainCookies(domain: String, cookieString: String) {
val cookies = parseCookies(cookieString)
val cookieManager = CookieManager.getInstance()
val url = "https://$domain"
for ((key, _) in cookies) {
cookieManager.setCookie(url, "$key=; Max-Age=0")
}
cookieManager.flush()

val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
val domains = prefs.getStringSet("cookie_domains", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
domains.remove(domain)
prefs.edit().putStringSet("cookie_domains", domains).apply()
}

private fun deleteSingleCookie(domain: String, key: String) {
val cookieManager = CookieManager.getInstance()
val url = "https://$domain"
cookieManager.setCookie(url, "$key=; Max-Age=0")
cookieManager.flush()
}
}