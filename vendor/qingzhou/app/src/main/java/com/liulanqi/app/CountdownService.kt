package com.liulanqi.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class CountdownService : Service() {

private var remainingMillis = 0L
private var isRunning = false
private var isPaused = false
private val handler = Handler(Looper.getMainLooper())
private var notificationManager: NotificationManager? = null
private var pauseReceiver: BroadcastReceiver? = null
private var stopReceiver: BroadcastReceiver? = null
private var stopRingtoneReceiver: BroadcastReceiver? = null
private var mediaPlayer: MediaPlayer? = null

companion object {
const val CHANNEL_ID = "countdown_channel"
const val CHANNEL_ID_ALERT = "countdown_alert_channel"
const val NOTIFICATION_ID = 2001
const val NOTIFICATION_ID_ALERT = 2002
const val ACTION_PAUSE = "com.liulanqi.app.COUNTDOWN_PAUSE"
const val ACTION_STOP = "com.liulanqi.app.COUNTDOWN_STOP"
const val ACTION_TICK = "com.liulanqi.app.COUNTDOWN_TICK"
const val ACTION_FINISHED = "com.liulanqi.app.COUNTDOWN_FINISHED"
const val ACTION_STOP_RINGTONE = "com.liulanqi.app.COUNTDOWN_STOP_RINGTONE"
const val EXTRA_MILLIS = "extra_millis"
const val EXTRA_REMAINING = "extra_remaining"
const val EXTRA_IS_PAUSED = "extra_is_paused"

var isServiceRunning = false
var currentRemaining: Long = 0L
var currentIsPaused = false
}

override fun onCreate() {
super.onCreate()
notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
createNotificationChannels()

pauseReceiver = object : BroadcastReceiver() {
override fun onReceive(context: Context?, intent: Intent?) {
if (isPaused) resume() else pause()
}
}
registerReceiver(pauseReceiver, IntentFilter(ACTION_PAUSE))

stopReceiver = object : BroadcastReceiver() {
override fun onReceive(context: Context?, intent: Intent?) {
stopCountdown()
}
}
registerReceiver(stopReceiver, IntentFilter(ACTION_STOP))

stopRingtoneReceiver = object : BroadcastReceiver() {
override fun onReceive(context: Context?, intent: Intent?) {
stopRingtone()
notificationManager?.cancel(NOTIFICATION_ID_ALERT)
}
}
registerReceiver(stopRingtoneReceiver, IntentFilter(ACTION_STOP_RINGTONE))
}

override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
intent?.let {
remainingMillis = it.getLongExtra(EXTRA_MILLIS, 0L)
if (remainingMillis > 0) {
startCountdown()
}
}
return START_STICKY
}

private fun startCountdown() {
isRunning = true
isPaused = false
isServiceRunning = true
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
startForeground(NOTIFICATION_ID, buildProgressNotification("倒计时进行中"))
}
handler.post(countdownRunnable)
}

private val countdownRunnable = object : Runnable {
override fun run() {
if (!isRunning) return
if (!isPaused) {
remainingMillis -= 100
currentRemaining = remainingMillis
currentIsPaused = false
if (remainingMillis <= 0) {
remainingMillis = 0
currentRemaining = 0
isServiceRunning = false
isRunning = false
stopForeground(STOP_FOREGROUND_REMOVE)
showFinishedNotification()
playRingtone()
sendFinishedBroadcast()
handler.removeCallbacks(this)
stopSelf()
return
}
sendTickBroadcast()
updateNotification("倒计时进行中")
}
handler.postDelayed(this, 100)
}
}

private fun playRingtone() {
try {
var alertUri: Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
if (alertUri == null) {
alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
}

if (alertUri != null) {
mediaPlayer = MediaPlayer()
mediaPlayer?.setAudioAttributes(
AudioAttributes.Builder()
.setUsage(AudioAttributes.USAGE_ALARM)
.setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
.build()
)
mediaPlayer?.isLooping = true
mediaPlayer?.setDataSource(this, alertUri)
mediaPlayer?.prepare()
mediaPlayer?.start()
}
} catch (e: Exception) {
e.printStackTrace()
}
}

private fun stopRingtone() {
mediaPlayer?.apply {
if (isPlaying) stop()
release()
}
mediaPlayer = null
}

private fun sendTickBroadcast() {
val intent = Intent(ACTION_TICK).apply {
putExtra(EXTRA_REMAINING, remainingMillis)
putExtra(EXTRA_IS_PAUSED, false)
}
LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
}

private fun sendFinishedBroadcast() {
val intent = Intent(ACTION_FINISHED)
LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
}

private fun showFinishedNotification() {
val contentIntent = Intent(this, TimerActivity::class.java)
val contentPendingIntent = PendingIntent.getActivity(
this, 0, contentIntent,
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
)

val stopRingtoneIntent = PendingIntent.getBroadcast(
this, 3, Intent(ACTION_STOP_RINGTONE),
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
)

val finishNotification = NotificationCompat.Builder(this, CHANNEL_ID_ALERT)
.setSmallIcon(R.drawable.ic_timer_notification)
.setContentTitle("倒计时结束")
.setContentText("设定的时间已经到了")
.setContentIntent(contentPendingIntent)
.addAction(android.R.drawable.ic_media_pause, "关闭铃声", stopRingtoneIntent)
.setAutoCancel(true)
.setPriority(NotificationCompat.PRIORITY_HIGH)
.build()

notificationManager?.notify(NOTIFICATION_ID_ALERT, finishNotification)
}

private fun pause() {
isPaused = true
currentIsPaused = true
updateNotification("倒计时已暂停")
val intent = Intent(ACTION_TICK).apply {
putExtra(EXTRA_REMAINING, remainingMillis)
putExtra(EXTRA_IS_PAUSED, true)
}
LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
}

private fun resume() {
isPaused = false
currentIsPaused = false
updateNotification("倒计时进行中")
val intent = Intent(ACTION_TICK).apply {
putExtra(EXTRA_REMAINING, remainingMillis)
putExtra(EXTRA_IS_PAUSED, false)
}
LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
}

private fun stopCountdown() {
isRunning = false
isServiceRunning = false
currentRemaining = 0
handler.removeCallbacks(countdownRunnable)
stopRingtone()
stopForeground(STOP_FOREGROUND_REMOVE)
notificationManager?.cancel(NOTIFICATION_ID_ALERT)
stopSelf()
}

private fun updateNotification(status: String) {
notificationManager?.notify(NOTIFICATION_ID, buildProgressNotification(status))
}

private fun buildProgressNotification(status: String): Notification {
val totalSeconds = remainingMillis / 1000
val hours = totalSeconds / 3600
val minutes = (totalSeconds % 3600) / 60
val seconds = totalSeconds % 60
val timeText = "%02d:%02d:%02d".format(hours, minutes, seconds)

val contentIntent = Intent(this, TimerActivity::class.java)
val contentPendingIntent = PendingIntent.getActivity(
this, 0, contentIntent,
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
)

val builder = NotificationCompat.Builder(this, CHANNEL_ID)
.setSmallIcon(R.drawable.ic_timer_notification)
.setContentTitle(status)
.setContentText("剩余时间: $timeText")
.setContentIntent(contentPendingIntent)
.setOngoing(true)
.setPriority(NotificationCompat.PRIORITY_LOW)

val pauseLabel = if (isPaused) "继续" else "暂停"
val pauseIntent = PendingIntent.getBroadcast(
this, 1, Intent(ACTION_PAUSE),
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
)
builder.addAction(android.R.drawable.ic_media_pause, pauseLabel, pauseIntent)

val stopIntent = PendingIntent.getBroadcast(
this, 2, Intent(ACTION_STOP),
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
)
builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopIntent)

return builder.build()
}

private fun createNotificationChannels() {
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
val progressChannel = NotificationChannel(
CHANNEL_ID,
"倒计时",
NotificationManager.IMPORTANCE_LOW
)
notificationManager?.createNotificationChannel(progressChannel)

val alertChannel = NotificationChannel(
CHANNEL_ID_ALERT,
"倒计时提醒",
NotificationManager.IMPORTANCE_HIGH
).apply {
enableVibration(true)
setSound(null, null)
}
notificationManager?.createNotificationChannel(alertChannel)
}
}

override fun onBind(intent: Intent?): IBinder? = null

override fun onDestroy() {
isRunning = false
isServiceRunning = false
handler.removeCallbacks(countdownRunnable)
stopRingtone()
try {
pauseReceiver?.let { unregisterReceiver(it) }
} catch (_: Exception) {}
try {
stopReceiver?.let { unregisterReceiver(it) }
} catch (_: Exception) {}
try {
stopRingtoneReceiver?.let { unregisterReceiver(it) }
} catch (_: Exception) {}
super.onDestroy()
}
}