package com.liulanqi.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class CrashDisplayActivity : AppCompatActivity() {

override fun onCreate(savedInstanceState: Bundle?) {
super.onCreate(savedInstanceState)
supportActionBar?.hide()

val logFiles = CrashHandler.getCrashLogs(this)
if (logFiles.isEmpty()) {
finish()
return
}

val scrollView = ScrollView(this)
val layout = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setPadding(24, 40, 24, 40)
}

layout.addView(TextView(this).apply {
text = "应用上次运行时崩溃了"
textSize = 20f
setPadding(0, 0, 0, 16)
})

layout.addView(TextView(this).apply {
text = "以下是崩溃日志，长按可选择复制"
textSize = 14f
setPadding(0, 0, 0, 16)
})

val latestLog = logFiles.last()
val logContent = CrashHandler.readCrashLog(latestLog)

val logTextView = TextView(this).apply {
text = logContent
textSize = 12f
setTextIsSelectable(true)
setPadding(8, 8, 8, 8)
setBackgroundColor(Color.argb(30, 0, 0, 0))
}
layout.addView(logTextView)

val copyBtn = Button(this).apply {
text = "复制全部日志"
setOnClickListener {
val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
val clip = ClipData.newPlainText("崩溃日志", logContent)
clipboard.setPrimaryClip(clip)
Toast.makeText(this@CrashDisplayActivity, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
}
}
layout.addView(copyBtn)

layout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 16) })

val clearBtn = Button(this).apply {
text = "清除日志并进入浏览器"
setOnClickListener {
CrashHandler.clearAllLogs(this@CrashDisplayActivity)
startActivity(Intent(this@CrashDisplayActivity, MainActivity::class.java).apply {
flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
})
finish()
}
}
layout.addView(clearBtn)

scrollView.addView(layout)
setContentView(scrollView)
}
}