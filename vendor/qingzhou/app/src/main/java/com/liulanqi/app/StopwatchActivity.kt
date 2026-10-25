package com.liulanqi.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class StopwatchActivity : AppCompatActivity() {

private var startTime = 0L
private var elapsedBeforePause = 0L
private var isRunning = false
private val handler = Handler(Looper.getMainLooper())
private lateinit var tvTimer: TextView
private lateinit var btnAction: Button
private lateinit var tvRecords: TextView
private val records = mutableListOf<String>()

override fun onCreate(savedInstanceState: Bundle?) {
super.onCreate(savedInstanceState)
supportActionBar?.hide()

val layout = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setPadding(24, 40, 24, 40)
gravity = Gravity.CENTER_HORIZONTAL
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
text = "秒表"
textSize = 24f
layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
})
layout.addView(titleRow)

tvTimer = TextView(this).apply {
text = "00:00.000"
textSize = 48f
setPadding(0, 24, 0, 24)
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
}
layout.addView(tvTimer)

val btnRow = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
gravity = Gravity.CENTER
}

btnAction = Button(this).apply {
text = "开始"
textSize = 16f
setPadding(32, 16, 32, 16)
setOnClickListener {
if (isRunning) pause() else start()
}
}
btnRow.addView(btnAction)

val btnReset = Button(this).apply {
text = "重置"
textSize = 16f
setPadding(32, 16, 32, 16)
setOnClickListener { reset() }
}
btnRow.addView(btnReset)

val btnRecord = Button(this).apply {
text = "记录"
textSize = 16f
setPadding(32, 16, 32, 16)
setOnClickListener { record() }
}
btnRow.addView(btnRecord)

layout.addView(btnRow)

tvRecords = TextView(this).apply {
text = ""
textSize = 14f
setPadding(0, 24, 0, 0)
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary)).getColor(0, 0))
}
layout.addView(tvRecords)

setContentView(layout)
}

private val updateRunnable = object : Runnable {
override fun run() {
if (isRunning) {
val total = SystemClock.elapsedRealtime() - startTime + elapsedBeforePause
val millis = total % 1000
val seconds = (total / 1000) % 60
val minutes = (total / 1000) / 60
tvTimer.text = "%02d:%02d.%03d".format(minutes, seconds, millis)
handler.postDelayed(this, 10)
}
}
}

private fun start() {
if (isRunning) return
isRunning = true
startTime = SystemClock.elapsedRealtime()
btnAction.text = "暂停"
handler.post(updateRunnable)
}

private fun pause() {
if (!isRunning) return
isRunning = false
elapsedBeforePause += SystemClock.elapsedRealtime() - startTime
btnAction.text = "继续"
handler.removeCallbacks(updateRunnable)
}

private fun reset() {
isRunning = false
elapsedBeforePause = 0L
startTime = 0L
tvTimer.text = "00:00.000"
btnAction.text = "开始"
handler.removeCallbacks(updateRunnable)
records.clear()
tvRecords.text = ""
}

private fun record() {
val total = if (isRunning) {
SystemClock.elapsedRealtime() - startTime + elapsedBeforePause
} else {
elapsedBeforePause
}
val millis = total % 1000
val seconds = (total / 1000) % 60
val minutes = (total / 1000) / 60
val recordText = "记录 #${records.size + 1}: %02d:%02d.%03d".format(minutes, seconds, millis)
records.add(recordText)
tvRecords.text = records.joinToString("\n")
}

override fun onDestroy() {
super.onDestroy()
handler.removeCallbacks(updateRunnable)
}
}