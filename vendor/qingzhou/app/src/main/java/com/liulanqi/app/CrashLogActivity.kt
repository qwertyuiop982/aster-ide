package com.liulanqi.app

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class CrashLogActivity : AppCompatActivity() {

private lateinit var layout: LinearLayout

override fun onCreate(savedInstanceState: Bundle?) {
super.onCreate(savedInstanceState)
supportActionBar?.hide()

val scrollView = ScrollView(this)
layout = LinearLayout(this).apply {
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
text = "崩溃日志"
textSize = 24f
layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
})
val clearBtn = Button(this).apply {
text = "清空"
textSize = 12f
setOnClickListener {
AlertDialog.Builder(this@CrashLogActivity)
.setTitle("确认清空")
.setMessage("确定要清空所有崩溃日志吗？")
.setPositiveButton("清空") { _, _ ->
CrashHandler.clearAllLogs(this@CrashLogActivity)
loadLogList()
Toast.makeText(this@CrashLogActivity, "已清空", Toast.LENGTH_SHORT).show()
}
.setNegativeButton("取消", null)
.show()
}
}
titleRow.addView(clearBtn)
layout.addView(titleRow)

loadLogList()
}

private fun loadLogList() {
while (layout.childCount > 1) {
layout.removeViewAt(layout.childCount - 1)
}

val logs = CrashHandler.getCrashLogs(this)
if (logs.isEmpty()) {
layout.addView(TextView(this).apply {
text = "暂无崩溃日志"
textSize = 14f
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
})
} else {
for (file in logs) {
val content = CrashHandler.readCrashLog(file)
val logView = TextView(this).apply {
text = "${file.name}\n$content"
textSize = 11f
setPadding(0, 8, 0, 8)
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
}
layout.addView(logView)
layout.addView(View(this).apply {
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
1
).apply {
topMargin = 4
bottomMargin = 4
}
setBackgroundColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorTertiary)).getColor(0, 0))
})
}
}
}
}