package dev.aster.ide.runtime

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.aster.core.R

class BuildKeepAliveService : Service() {
    companion object {
        fun start(context: Context) { ContextCompat.startForegroundService(context, Intent(context, BuildKeepAliveService::class.java)) }
        fun stop(context: Context) { context.stopService(Intent(context, BuildKeepAliveService::class.java)) }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("build", "编译与安装任务", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, packageManager.getLaunchIntentForPackage(packageName) ?: Intent(), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, "build").setSmallIcon(R.drawable.ic_aster).setContentTitle("Aster")
            .setContentText("Termux 任务正在运行").setOngoing(true).setSilent(true).setContentIntent(open).build()
        startForeground(910, notification)
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }
}