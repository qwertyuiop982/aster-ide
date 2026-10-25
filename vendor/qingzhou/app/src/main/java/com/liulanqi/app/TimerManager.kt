package com.liulanqi.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

object TimerManager {

private var startTime: Long = 0L
private var accumulatedTime: Long = 0L
private var isRunning = false
private var currentUrl: String? = null
private val handler = Handler(Looper.getMainLooper())
private var tickRunnable: Runnable? = null

fun startTiming(url: String, onTick: (String) -> Unit) {
if (isRunning && currentUrl != url) {
stopTiming(url)
}
if (isRunning) return

currentUrl = url
startTime = SystemClock.elapsedRealtime()
accumulatedTime = 0L
isRunning = true

tickRunnable = object : Runnable {
override fun run() {
if (isRunning) {
val elapsed = SystemClock.elapsedRealtime() - startTime + accumulatedTime
val seconds = elapsed / 1000
val display = formatTime(seconds)
onTick(display)
handler.postDelayed(this, 1000)
}
}
}
handler.post(tickRunnable!!)
}

fun stopTiming(url: String): Long {
if (!isRunning) return 0L
val total = SystemClock.elapsedRealtime() - startTime + accumulatedTime
isRunning = false
tickRunnable?.let { handler.removeCallbacks(it) }
tickRunnable = null
currentUrl = null
return total
}

fun getCurrentUrl(): String? {
return currentUrl
}

fun isTiming(): Boolean {
return isRunning
}

fun formatTime(seconds: Long): String {
val sec = seconds % 60
val min = (seconds / 60) % 60
val hour = seconds / 3600
return if (hour > 0) {
"%02d:%02d:%02d".format(hour, min, sec)
} else {
"%02d:%02d".format(min, sec)
}
}
}