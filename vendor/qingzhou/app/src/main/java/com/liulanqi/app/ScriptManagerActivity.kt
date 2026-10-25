package com.liulanqi.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class ScriptManagerActivity : AppCompatActivity() {

private lateinit var rootLayout: LinearLayout
private val handler = Handler(Looper.getMainLooper())

override fun onCreate(savedInstanceState: Bundle?) {
super.onCreate(savedInstanceState)
supportActionBar?.hide()

val scrollView = ScrollView(this)
val layout = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setPadding(16, 16, 16, 16)
setBackgroundColor(obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground)).getColor(0, 0))
}
rootLayout = layout
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
text = "脚本管理"
textSize = 24f
layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
})

val btnHelp = Button(this).apply {
text = "使用帮助"
textSize = 14f
setOnClickListener {
startActivity(Intent(this@ScriptManagerActivity, ScriptHelpActivity::class.java))
}
}
titleRow.addView(btnHelp)
rootLayout.addView(titleRow)

val switchRow = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
gravity = Gravity.CENTER_VERTICAL
setPadding(0, 8, 0, 16)
}
val switchLabel = TextView(this).apply {
text = "启用脚本引擎"
textSize = 16f
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
}
switchRow.addView(switchLabel)

val spacer = View(this).apply {
layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
}
switchRow.addView(spacer)

val engineSwitch = Switch(this).apply {
val prefs = getSharedPreferences("scripts", MODE_PRIVATE)
isChecked = prefs.getBoolean("script_engine_enabled", true)
setOnCheckedChangeListener { _, isChecked ->
prefs.edit().putBoolean("script_engine_enabled", isChecked).apply()
}
}
switchRow.addView(engineSwitch)
rootLayout.addView(switchRow)

val autoRecognizeRow = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
gravity = Gravity.CENTER_VERTICAL
setPadding(0, 8, 0, 16)
}
val autoRecognizeLabel = TextView(this).apply {
text = "自动识别脚本"
textSize = 16f
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
}
autoRecognizeRow.addView(autoRecognizeLabel)

val spacer2 = View(this).apply {
layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
}
autoRecognizeRow.addView(spacer2)

val autoRecognizeSwitch = Switch(this).apply {
val prefs = getSharedPreferences("scripts", MODE_PRIVATE)
isChecked = prefs.getBoolean("auto_recognize_script", true)
setOnCheckedChangeListener { _, isChecked ->
prefs.edit().putBoolean("auto_recognize_script", isChecked).apply()
}
}
autoRecognizeRow.addView(autoRecognizeSwitch)
rootLayout.addView(autoRecognizeRow)

rootLayout.addView(View(this).apply {
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
1
).apply {
topMargin = 8
bottomMargin = 16
}
setBackgroundColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorTertiary)).getColor(0, 0))
})

refreshScriptList()
}

private fun addBottomButtons() {
val btnRow = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
gravity = Gravity.CENTER
setPadding(0, 16, 0, 0)
}

val btnImport = Button(this).apply {
text = "导入脚本"
textSize = 16f
setPadding(32, 16, 32, 16)
setOnClickListener {
showImportFromUrlDialog()
}
}
btnRow.addView(btnImport)

val btnPaste = Button(this).apply {
text = "粘贴代码"
textSize = 16f
setPadding(32, 16, 32, 16)
setOnClickListener {
showPasteCodeDialog()
}
}
btnRow.addView(btnPaste)

rootLayout.addView(btnRow)
}

private fun showImportFromUrlDialog() {
val ctx = this
val inputLayout = LinearLayout(ctx).apply {
orientation = LinearLayout.VERTICAL
setPadding(32, 24, 32, 8)
}
val editText = EditText(ctx).apply {
hint = "输入脚本链接（.user.js）"
setSingleLine()
setPadding(12, 12, 12, 12)
setTextColor(ctx.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
setHintTextColor(ctx.obtainStyledAttributes(intArrayOf(android.R.attr.textColorHint)).getColor(0, 0))
}
inputLayout.addView(editText)

AlertDialog.Builder(ctx)
.setTitle("从链接导入脚本")
.setView(inputLayout)
.setPositiveButton("下载") { d, _ ->
val url = editText.text.toString().trim()
if (url.isEmpty()) {
Toast.makeText(ctx, "请输入链接", Toast.LENGTH_SHORT).show()
return@setPositiveButton
}
d.dismiss()
performImportFromUrl(url)
}
.setNegativeButton("取消", null)
.show()
}

private fun showPasteCodeDialog() {
val ctx = this
val scrollView = ScrollView(ctx)
val layout = LinearLayout(ctx).apply {
orientation = LinearLayout.VERTICAL
setPadding(16, 16, 16, 16)
}
val editText = EditText(ctx).apply {
hint = "在此粘贴完整的脚本代码"
minLines = 10
gravity = Gravity.TOP
setPadding(12, 12, 12, 12)
setTextColor(ctx.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
setHintTextColor(ctx.obtainStyledAttributes(intArrayOf(android.R.attr.textColorHint)).getColor(0, 0))
}
layout.addView(editText)
scrollView.addView(layout)

AlertDialog.Builder(ctx)
.setTitle("粘贴代码导入")
.setView(scrollView)
.setPositiveButton("解析") { d, _ ->
val code = editText.text.toString().trim()
if (code.isEmpty()) {
Toast.makeText(ctx, "请输入脚本代码", Toast.LENGTH_SHORT).show()
return@setPositiveButton
}
d.dismiss()
val meta = ScriptEngine.parseMetadata(code)
val warnings = ScriptEngine.scanCode(code)
var sriError: String? = null
try {
ScriptEngine.validateRequires(meta.requires)
} catch (e: SRIException) {
sriError = e.message
}
showInstallConfirmDialog(code, meta, warnings, sriError)
}
.setNegativeButton("取消", null)
.show()
}

private fun performImportFromUrl(url: String) {
val ctx = this
val h = handler
val progressDialog = AlertDialog.Builder(ctx)
.setTitle("请稍候")
.setMessage("正在下载脚本...")
.setCancelable(false)
.create()
progressDialog.show()

Thread {
var content: String? = null
var errorMsg: String? = null
try {
val connection = URL(url).openConnection() as HttpURLConnection
connection.requestMethod = "GET"
connection.connectTimeout = 15000
connection.readTimeout = 15000
val reader = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8"))
content = reader.readText()
reader.close()
connection.disconnect()
} catch (e: Exception) {
errorMsg = e.message ?: "下载失败"
}

h.post {
progressDialog.dismiss()
if (content != null && content.isNotEmpty()) {
val meta = ScriptEngine.parseMetadata(content)
val warnings = ScriptEngine.scanCode(content)
var sriError: String? = null
try {
ScriptEngine.validateRequires(meta.requires)
} catch (e: SRIException) {
sriError = e.message
}
showInstallConfirmDialog(content, meta, warnings, sriError)
} else {
Toast.makeText(ctx, errorMsg ?: "下载失败，请检查链接", Toast.LENGTH_LONG).show()
}
}
}.start()
}

private fun showInstallConfirmDialog(code: String, meta: ScriptMetadata, warnings: List<CodeWarning>, sriError: String?) {
val ctx = this
val scrollView = ScrollView(ctx)
val layout = LinearLayout(ctx).apply {
orientation = LinearLayout.VERTICAL
setPadding(16, 16, 16, 16)
}

layout.addView(TextView(ctx).apply {
text = "脚本信息"
textSize = 18f
setPadding(0, 0, 0, 12)
setTextColor(ctx.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
})

layout.addView(createInfoRow("名称", meta.name))
layout.addView(createInfoRow("版本", meta.version))
layout.addView(createInfoRow("匹配站点", meta.matches.joinToString("\n").ifEmpty { "全局" }))
layout.addView(createInfoRow("权限请求", meta.grants.joinToString(", ").ifEmpty { "无" }))

layout.addView(View(ctx).apply {
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
1
).apply {
topMargin = 16
bottomMargin = 12
}
setBackgroundColor(ctx.obtainStyledAttributes(intArrayOf(android.R.attr.textColorTertiary)).getColor(0, 0))
})

layout.addView(TextView(ctx).apply {
text = "安全审查"
textSize = 16f
setPadding(0, 0, 0, 8)
setTextColor(ctx.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
})

if (warnings.isNotEmpty()) {
layout.addView(TextView(ctx).apply {
text = "⚠️ 检测到可疑代码："
textSize = 14f
setPadding(0, 0, 0, 4)
setTextColor(0xFFE53935.toInt())
})
for (w in warnings) {
layout.addView(TextView(ctx).apply {
text = "  - 第${w.line}行: ${w.type}"
textSize = 13f
setPadding(16, 2, 0, 2)
setTextColor(0xFFE53935.toInt())
})
}
}

if (sriError != null) {
layout.addView(TextView(ctx).apply {
text = "⚠️ 外部资源校验失败："
textSize = 14f
setPadding(0, 8, 0, 4)
setTextColor(0xFFE53935.toInt())
})
layout.addView(TextView(ctx).apply {
text = "  $sriError"
textSize = 13f
setPadding(16, 2, 0, 2)
setTextColor(0xFFE53935.toInt())
})
}

if (warnings.isEmpty() && sriError == null) {
layout.addView(TextView(ctx).apply {
text = "✓ 未检测到可疑代码"
textSize = 14f
setPadding(0, 0, 0, 4)
setTextColor(0xFF4CAF50.toInt())
})
}

layout.addView(View(ctx).apply {
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
8
)
})

layout.addView(TextView(ctx).apply {
text = "以上检测由自动化工具完成，不保证100%准确，请谨慎安装"
textSize = 11f
setTextColor(ctx.obtainStyledAttributes(intArrayOf(android.R.attr.textColorTertiary)).getColor(0, 0))
})

scrollView.addView(layout)

val builder = AlertDialog.Builder(ctx)
.setTitle("安装确认")
.setView(scrollView)
.setNegativeButton("取消", null)

if (sriError != null) {
builder.setPositiveButton("仍要安装") { _, _ ->
tryInstallScript(code)
}
} else {
builder.setPositiveButton("安装") { _, _ ->
tryInstallScript(code)
}
}

builder.show()
}

private fun tryInstallScript(code: String) {
val ctx = this
try {
ScriptEngine.installScript(ctx, code)
refreshScriptList()
Toast.makeText(ctx, "脚本已安装", Toast.LENGTH_SHORT).show()
} catch (e: SRIException) {
AlertDialog.Builder(ctx)
.setTitle("安装失败")
.setMessage(e.message)
.setPositiveButton("确定", null)
.show()
} catch (e: Exception) {
Toast.makeText(ctx, "安装失败: ${e.message}", Toast.LENGTH_SHORT).show()
}
}

private fun createInfoRow(label: String, value: String): LinearLayout {
val ctx = this
return LinearLayout(ctx).apply {
orientation = LinearLayout.HORIZONTAL
setPadding(0, 4, 0, 4)
addView(TextView(ctx).apply {
text = "$label："
textSize = 14f
setTextColor(ctx.obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary)).getColor(0, 0))
})
addView(TextView(ctx).apply {
text = value
textSize = 14f
setTextColor(ctx.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
})
}
}

private fun loadScriptList() {
val ctx = this
val scripts = ScriptEngine.getScriptList(ctx)
if (scripts.isEmpty()) {
rootLayout.addView(TextView(ctx).apply {
text = "暂无脚本\n\n点击下方按钮导入或粘贴代码"
textSize = 14f
gravity = Gravity.CENTER
setPadding(0, 32, 0, 32)
setTextColor(ctx.obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary)).getColor(0, 0))
})
return
}

rootLayout.addView(TextView(ctx).apply {
text = "已安装脚本 (${scripts.size})"
textSize = 14f
setPadding(0, 0, 0, 12)
setTextColor(ctx.obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary)).getColor(0, 0))
})

for (script in scripts) {
val item = createScriptItem(script)
rootLayout.addView(item)
}
}

private fun createScriptItem(script: ScriptInfo): LinearLayout {
val ctx = this
val item = LinearLayout(ctx).apply {
orientation = LinearLayout.HORIZONTAL
setPadding(12, 12, 12, 12)
gravity = Gravity.CENTER_VERTICAL
setBackgroundColor(
if (androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode() == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
0x33FFFFFF else 0xFFF5F5F5.toInt()
)
}

val info = LinearLayout(ctx).apply {
orientation = LinearLayout.VERTICAL
layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
}

info.addView(TextView(ctx).apply {
text = script.name
textSize = 16f
setTextColor(ctx.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
})
info.addView(TextView(ctx).apply {
text = "v${script.version}  |  ${script.matches.firstOrNull() ?: "全局"}"
textSize = 12f
setTextColor(ctx.obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary)).getColor(0, 0))
})

item.addView(info)

val editBtn = Button(ctx).apply {
text = "编辑"
textSize = 12f
setOnClickListener {
val intent = Intent(ctx, ScriptEditActivity::class.java).apply {
putExtra("script_id", script.id)
}
startActivity(intent)
}
}
item.addView(editBtn)

val scriptSwitch = Switch(ctx).apply {
isChecked = script.enabled
setOnCheckedChangeListener { _, isChecked ->
ScriptEngine.setScriptEnabled(ctx, script.id, isChecked)
script.enabled = isChecked
}
}
item.addView(scriptSwitch)

val deleteBtn = Button(ctx).apply {
text = "删除"
textSize = 12f
setOnClickListener {
ScriptEngine.deleteScript(ctx, script.id)
rootLayout.removeView(item)
refreshScriptList()
Toast.makeText(ctx, "已删除", Toast.LENGTH_SHORT).show()
}
}
item.addView(deleteBtn)

return item
}

private fun refreshScriptList() {
while (rootLayout.childCount > 1) {
rootLayout.removeViewAt(rootLayout.childCount - 1)
}
loadScriptList()
addBottomButtons()
}
}