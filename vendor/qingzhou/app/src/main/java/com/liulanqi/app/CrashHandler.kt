package com.liulanqi.app

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashHandler {

private var originalHandler: Thread.UncaughtExceptionHandler? = null
private var appContext: Context? = null

fun init(context: Context) {
appContext = context.applicationContext
originalHandler = Thread.getDefaultUncaughtExceptionHandler()
Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
saveCrashLog(throwable)
originalHandler?.uncaughtException(thread, throwable)
}
}

private fun saveCrashLog(throwable: Throwable) {
try {
val logDir = appContext?.getExternalFilesDir("crash_logs") ?: return
if (!logDir.exists()) logDir.mkdirs()
val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
val fileName = "crash_${sdf.format(Date())}.txt"
val logFile = File(logDir, fileName)
FileWriter(logFile).use { writer ->
val pw = PrintWriter(writer)
pw.println("崩溃时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
pw.println()
throwable.printStackTrace(pw)
pw.flush()
}
} catch (_: Exception) {}
}

fun getCrashLogs(context: Context): List<File> {
val logDir = context.getExternalFilesDir("crash_logs") ?: return emptyList()
if (!logDir.exists()) return emptyList()
return logDir.listFiles()?.filter { it.name.startsWith("crash_") && it.name.endsWith(".txt") }
?.sortedByDescending { it.lastModified() } ?: emptyList()
}

fun readCrashLog(file: File): String {
return if (file.exists()) file.readText() else "日志文件不存在"
}

fun clearAllLogs(context: Context) {
val logDir = context.getExternalFilesDir("crash_logs") ?: return
if (logDir.exists()) {
logDir.listFiles()?.forEach { it.delete() }
}
}
}