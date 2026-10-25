package com.liulanqi.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TimerActivity : AppCompatActivity() {

private lateinit var tvDate: TextView
private lateinit var tvTime: TextView
private val handler = Handler(Looper.getMainLooper())
private val dateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

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
text = "计时器"
textSize = 24f
layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
})
layout.addView(titleRow)

tvDate = TextView(this).apply {
textSize = 24f
setPadding(0, 0, 0, 8)
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
}
layout.addView(tvDate)

tvTime = TextView(this).apply {
textSize = 32f
setPadding(0, 0, 0, 48)
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
}
layout.addView(tvTime)

val btnStopwatch = Button(this).apply {
text = "秒表"
textSize = 18f
setPadding(48, 16, 48, 16)
setOnClickListener {
startActivity(Intent(this@TimerActivity, StopwatchActivity::class.java))
}
}
layout.addView(btnStopwatch)

val btnCountdown = Button(this).apply {
text = "倒计时"
textSize = 18f
setPadding(48, 16, 48, 16)
setOnClickListener {
startActivity(Intent(this@TimerActivity, CountdownActivity::class.java))
}
}
layout.addView(btnCountdown)

val tvNotice = TextView(this).apply {
text = "暂时仅支持消息通知"
textSize = 12f
setPadding(0, 32, 0, 0)
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary)).getColor(0, 0))
}
layout.addView(tvNotice)

setContentView(layout)
updateDateTime()
}

private fun updateDateTime() {
val now = Date()
tvDate.text = dateFormatter.format(now)
tvTime.text = timeFormatter.format(now)
handler.postDelayed({ updateDateTime() }, 1000)
}

override fun onDestroy() {
super.onDestroy()
handler.removeCallbacksAndMessages(null)
}
}