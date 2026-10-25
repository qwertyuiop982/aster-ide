package com.liulanqi.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class AdblockStatsActivity : AppCompatActivity() {

private lateinit var scrollView: ScrollView
private lateinit var rootLayout: LinearLayout
private val handler = Handler(Looper.getMainLooper())

override fun onCreate(savedInstanceState: Bundle?) {
super.onCreate(savedInstanceState)
supportActionBar?.hide()

scrollView = ScrollView(this)
rootLayout = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setPadding(16, 16, 16, 16)
setBackgroundColor(obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground)).getColor(0, 0))
}

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
text = "广告拦截"
textSize = 24f
layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
})
rootLayout.addView(titleRow)

buildContent()
scrollView.addView(rootLayout)
setContentView(scrollView)
}

private fun buildContent() {
while (rootLayout.childCount > 1) {
rootLayout.removeViewAt(rootLayout.childCount - 1)
}

val enabledSwitchRow = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
setPadding(0, 8, 0, 16)
gravity = Gravity.CENTER_VERTICAL
}

val enabledLabel = TextView(this).apply {
text = "启用广告拦截"
textSize = 16f
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
}
enabledSwitchRow.addView(enabledLabel)

val spacerSwitch = LinearLayout(this).apply {
layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
}
enabledSwitchRow.addView(spacerSwitch)

val enabledSwitch = Switch(this).apply {
isChecked = AdBlocker.isEnabled()
setOnCheckedChangeListener { _, isChecked ->
AdBlocker.setEnabled(isChecked, this@AdblockStatsActivity)
refreshView()
}
}
enabledSwitchRow.addView(enabledSwitch)
rootLayout.addView(enabledSwitchRow)

val ruleStatus = AdBlocker.getRuleStatus(this)
val ruleRow = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
setPadding(0, 12, 0, 12)
gravity = Gravity.CENTER_VERTICAL
}

val ruleLabel = TextView(this).apply {
text = "规则状态：${ruleStatus.first}"
textSize = 14f
layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary)).getColor(0, 0))
}
ruleRow.addView(ruleLabel)

if (AdBlocker.isEnabled()) {
if (ruleStatus.second) {
val updateBtn = Button(this).apply {
text = "更新规则"
textSize = 12f
setOnClickListener {
it.isEnabled = false
text = "下载中..."
AdBlocker.updateRulesWithCallback(this@AdblockStatsActivity) {
handler.post {
it.isEnabled = true
text = "更新规则"
refreshView()
Toast.makeText(this@AdblockStatsActivity, "规则下载成功", Toast.LENGTH_SHORT).show()
}
}
}
}
ruleRow.addView(updateBtn)
} else {
val downloadBtn = Button(this).apply {
text = "下载规则"
textSize = 12f
setOnClickListener {
it.isEnabled = false
text = "下载中..."
AdBlocker.updateRulesWithCallback(this@AdblockStatsActivity) {
handler.post {
it.isEnabled = true
text = "更新规则"
refreshView()
Toast.makeText(this@AdblockStatsActivity, "规则下载成功", Toast.LENGTH_SHORT).show()
}
}
}
}
ruleRow.addView(downloadBtn)
}
}
rootLayout.addView(ruleRow)

val autoUpdateItem = createAutoUpdateItem()
rootLayout.addView(autoUpdateItem)
}

private fun createAutoUpdateItem(): LinearLayout {
val item = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
setPadding(0, 12, 0, 12)
gravity = Gravity.CENTER_VERTICAL
}

val label = TextView(this).apply {
text = "自动更新"
textSize = 14f
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
}
item.addView(label)

val spacer = LinearLayout(this).apply {
layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
}
item.addView(spacer)

val currentMode = AdBlocker.getAutoUpdateMode(this)
val modeText = when (currentMode) {
0 -> "不启用"
1 -> "仅 WLAN"
2 -> "WLAN 和移动数据"
else -> "仅 WLAN"
}

val rightText = TextView(this).apply {
text = "$modeText  >"
textSize = 14f
gravity = Gravity.END
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary)).getColor(0, 0))
}
item.addView(rightText)

item.isClickable = true
item.setOnClickListener {
val latestMode = AdBlocker.getAutoUpdateMode(this@AdblockStatsActivity)
val options = arrayOf("不启用", "仅 WLAN", "WLAN 和移动数据")
AlertDialog.Builder(this)
.setTitle("自动更新")
.setSingleChoiceItems(options, latestMode) { dialog, which ->
AdBlocker.setAutoUpdateMode(this@AdblockStatsActivity, which)
val newModeText = when (which) {
0 -> "不启用"
1 -> "仅 WLAN"
2 -> "WLAN 和移动数据"
else -> "仅 WLAN"
}
rightText.text = "$newModeText  >"
dialog.dismiss()
}
.setNegativeButton("取消", null)
.show()
}

return item
}

private fun refreshView() {
while (rootLayout.childCount > 1) {
rootLayout.removeViewAt(rootLayout.childCount - 1)
}
buildContent()
}
}