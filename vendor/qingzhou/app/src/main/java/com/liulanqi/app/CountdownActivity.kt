package com.liulanqi.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class CountdownActivity : AppCompatActivity() {

private lateinit var btnAction: Button
private lateinit var btnPause: Button
private lateinit var btnStop: Button
private lateinit var etHour: EditText
private lateinit var etMinute: EditText
private lateinit var etSecond: EditText
private lateinit var setupLayout: LinearLayout
private lateinit var tvTimer: TextView

private var isValidating = false
private var tickReceiver: BroadcastReceiver? = null
private var finishReceiver: BroadcastReceiver? = null

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
text = "倒计时"
textSize = 24f
layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
})
layout.addView(titleRow)

tvTimer = TextView(this).apply {
text = "00:00:00"
textSize = 48f
setPadding(0, 24, 0, 24)
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
}
layout.addView(tvTimer)

setupLayout = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
gravity = Gravity.CENTER
setPadding(0, 0, 0, 24)
}

etHour = EditText(this).apply {
hint = "时"
width = 120
inputType = InputType.TYPE_CLASS_NUMBER
setPadding(12, 8, 12, 8)
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
setHintTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorHint)).getColor(0, 0))
}
etMinute = EditText(this).apply {
hint = "分"
width = 120
inputType = InputType.TYPE_CLASS_NUMBER
setPadding(12, 8, 12, 8)
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
setHintTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorHint)).getColor(0, 0))
}
etSecond = EditText(this).apply {
hint = "秒"
width = 120
inputType = InputType.TYPE_CLASS_NUMBER
setPadding(12, 8, 12, 8)
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
setHintTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorHint)).getColor(0, 0))
}

val textWatcher = object : TextWatcher {
override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
override fun afterTextChanged(s: Editable?) {
if (isValidating) return
isValidating = true
validateInputs()
updateButtonState()
isValidating = false
}
}
etHour.addTextChangedListener(textWatcher)
etMinute.addTextChangedListener(textWatcher)
etSecond.addTextChangedListener(textWatcher)

setupLayout.addView(etHour)
setupLayout.addView(etMinute)
setupLayout.addView(etSecond)

layout.addView(setupLayout)

val btnRow = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
gravity = Gravity.CENTER
}

btnAction = Button(this).apply {
text = "开始"
textSize = 16f
setPadding(32, 16, 32, 16)
isEnabled = false
isClickable = false
setTextColor(0xFF888888.toInt())
setOnClickListener {
startCountdown()
}
}
btnRow.addView(btnAction)

btnPause = Button(this).apply {
text = "暂停"
textSize = 16f
setPadding(32, 16, 32, 16)
visibility = android.view.View.GONE
setOnClickListener {
val intent = Intent(CountdownService.ACTION_PAUSE)
sendBroadcast(intent)
}
}
btnRow.addView(btnPause)

btnStop = Button(this).apply {
text = "停止"
textSize = 16f
setPadding(32, 16, 32, 16)
visibility = android.view.View.GONE
setOnClickListener {
val intent = Intent(CountdownService.ACTION_STOP)
sendBroadcast(intent)
resetUI()
}
}
btnRow.addView(btnStop)

layout.addView(btnRow)

setContentView(layout)

if (CountdownService.isServiceRunning) {
setupLayout.visibility = android.view.View.GONE
btnAction.visibility = android.view.View.GONE
btnPause.visibility = android.view.View.VISIBLE
btnStop.visibility = android.view.View.VISIBLE
btnPause.text = if (CountdownService.currentIsPaused) "继续" else "暂停"
updateTimerDisplay(CountdownService.currentRemaining)
}

tickReceiver = object : BroadcastReceiver() {
override fun onReceive(context: Context?, intent: Intent?) {
val remaining = intent?.getLongExtra(CountdownService.EXTRA_REMAINING, 0L) ?: 0L
val isPaused = intent?.getBooleanExtra(CountdownService.EXTRA_IS_PAUSED, false) ?: false
updateTimerDisplay(remaining)
btnPause.text = if (isPaused) "继续" else "暂停"
}
}
LocalBroadcastManager.getInstance(this)
.registerReceiver(tickReceiver!!, IntentFilter(CountdownService.ACTION_TICK))

finishReceiver = object : BroadcastReceiver() {
override fun onReceive(context: Context?, intent: Intent?) {
resetUI()
tvTimer.text = "00:00:00"
}
}
LocalBroadcastManager.getInstance(this)
.registerReceiver(finishReceiver!!, IntentFilter(CountdownService.ACTION_FINISHED))
}

private fun updateTimerDisplay(millis: Long) {
val totalSeconds = millis / 1000
val hours = totalSeconds / 3600
val minutes = (totalSeconds % 3600) / 60
val seconds = totalSeconds % 60
tvTimer.text = "%02d:%02d:%02d".format(hours, minutes, seconds)
}

private fun validateInputs() {
validateField(etHour, 99)
validateField(etMinute, 59)
validateField(etSecond, 59)
}

private fun validateField(field: EditText, max: Int) {
val text = field.text.toString()
if (text.isNotEmpty()) {
val value = text.toIntOrNull() ?: 0
if (value > max) {
field.setText(max.toString())
field.setSelection(field.text.length)
}
}
}

private fun updateButtonState() {
val hour = etHour.text.toString().toIntOrNull() ?: 0
val minute = etMinute.text.toString().toIntOrNull() ?: 0
val second = etSecond.text.toString().toIntOrNull() ?: 0
val hasTime = hour > 0 || minute > 0 || second > 0
val canStart = hasTime && !CountdownService.isServiceRunning
btnAction.isEnabled = canStart
btnAction.isClickable = canStart
if (canStart) {
btnAction.setTextColor(0xFF000000.toInt())
} else {
btnAction.setTextColor(0xFF888888.toInt())
}
}

private fun startCountdown() {
val hour = etHour.text.toString().toIntOrNull() ?: 0
val minute = etMinute.text.toString().toIntOrNull() ?: 0
val second = etSecond.text.toString().toIntOrNull() ?: 0
val totalMillis = (hour * 3600L + minute * 60L + second) * 1000L
if (totalMillis <= 0) return

val intent = Intent(this, CountdownService::class.java).apply {
putExtra(CountdownService.EXTRA_MILLIS, totalMillis)
}
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
startForegroundService(intent)
} else {
startService(intent)
}

setupLayout.visibility = android.view.View.GONE
btnAction.visibility = android.view.View.GONE
btnPause.visibility = android.view.View.VISIBLE
btnStop.visibility = android.view.View.VISIBLE
btnPause.text = "暂停"
}

private fun resetUI() {
btnAction.visibility = android.view.View.VISIBLE
btnAction.text = "开始"
btnAction.isEnabled = true
btnAction.isClickable = true
btnAction.setTextColor(0xFF000000.toInt())
btnPause.visibility = android.view.View.GONE
btnStop.visibility = android.view.View.GONE
setupLayout.visibility = android.view.View.VISIBLE
}

override fun onDestroy() {
super.onDestroy()
tickReceiver?.let { LocalBroadcastManager.getInstance(this).unregisterReceiver(it) }
finishReceiver?.let { LocalBroadcastManager.getInstance(this).unregisterReceiver(it) }
}
}