package com.liulanqi.app

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class DownloadActivity : AppCompatActivity() {

private lateinit var layout: LinearLayout
private lateinit var emptyView: TextView
private var receiver: BroadcastReceiver? = null
private val handler = Handler(Looper.getMainLooper())
private var hasDownloading = false
private val refreshRunnable = object : Runnable {
override fun run() {
if (hasDownloading) {
updateProgressOnly()
}
handler.postDelayed(this, 2000)
}
}

override fun onCreate(savedInstanceState: Bundle?) {
super.onCreate(savedInstanceState)
supportActionBar?.hide()

val scrollView = android.widget.ScrollView(this)
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
text = "下载管理"
textSize = 24f
layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
})
layout.addView(titleRow)

emptyView = TextView(this).apply {
text = "暂无下载文件"
textSize = 14f
visibility = View.GONE
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
}
layout.addView(emptyView)

refreshList()

receiver = object : BroadcastReceiver() {
override fun onReceive(context: Context?, intent: Intent?) {
refreshList()
}
}
registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))

handler.postDelayed(refreshRunnable, 2000)
}

override fun onDestroy() {
super.onDestroy()
handler.removeCallbacks(refreshRunnable)
receiver?.let { unregisterReceiver(it) }
}

private fun refreshList() {
layout.removeViews(2, layout.childCount - 2)

val downloads = getAllDownloads()
hasDownloading = downloads.any { it.status == DownloadManager.STATUS_RUNNING || it.status == DownloadManager.STATUS_PENDING }

if (downloads.isEmpty()) {
emptyView.visibility = View.VISIBLE
} else {
emptyView.visibility = View.GONE
for (download in downloads) {
val item = createDownloadItemView(download)
item.tag = download
layout.addView(item)
}
}
}

private fun updateProgressOnly() {
val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
val query = DownloadManager.Query()
val cursor: Cursor = dm.query(query)
val dataMap = mutableMapOf<Long, Pair<Int, Long>>()
if (cursor.moveToFirst()) {
do {
val idIndex = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
val downloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
if (idIndex >= 0 && statusIndex >= 0) {
val id = cursor.getLong(idIndex)
val status = cursor.getInt(statusIndex)
val downloaded = if (downloadedIndex >= 0) cursor.getLong(downloadedIndex) else 0L
dataMap[id] = Pair(status, downloaded)
}
} while (cursor.moveToNext())
}
cursor.close()

var needFullRefresh = false
for (i in 2 until layout.childCount) {
val child = layout.getChildAt(i)
val info = child.tag as? DownloadInfo ?: continue
val newData = dataMap[info.id] ?: continue
if (newData.first != info.status) {
needFullRefresh = true
break
}
if (newData.first == DownloadManager.STATUS_RUNNING && info.totalSize > 0) {
val newProgress = (newData.second * 100 / info.totalSize).toInt()
val statusView = (child as? LinearLayout)?.getChildAt(0)?.let { row ->
(row as? LinearLayout)?.getChildAt(0)?.let { col ->
(col as? LinearLayout)?.getChildAt(1) as? TextView
}
}
val progressBar = (child as? LinearLayout)?.getChildAt(1) as? ProgressBar
if (statusView != null && newData.second > info.downloadedSize) {
statusView.text = "下载中 $newProgress%"
progressBar?.progress = newProgress
info.downloadedSize = newData.second
}
}
}

if (needFullRefresh) {
refreshList()
}
}

private fun getAllDownloads(): List<DownloadInfo> {
val list = mutableListOf<DownloadInfo>()
val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
val query = DownloadManager.Query()
val cursor: Cursor = dm.query(query)
if (cursor.moveToFirst()) {
do {
val idIndex = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
val titleIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
val sizeIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
val downloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
val uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)

if (idIndex >= 0 && titleIndex >= 0 && statusIndex >= 0) {
val id = cursor.getLong(idIndex)
val title = cursor.getString(titleIndex) ?: "未知文件"
val status = cursor.getInt(statusIndex)
val totalSize = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L
val downloadedSize = if (downloadedIndex >= 0) cursor.getLong(downloadedIndex) else 0L
val localUri = if (uriIndex >= 0) cursor.getString(uriIndex) else null
val progress = if (totalSize > 0) (downloadedSize * 100 / totalSize).toInt() else 0

list.add(DownloadInfo(id, title, status, progress, totalSize, downloadedSize, localUri))
}
} while (cursor.moveToNext())
}
cursor.close()
return list
}

private fun createDownloadItemView(download: DownloadInfo): View {
val item = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setPadding(0, 12, 0, 12)
}

val topRow = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
}

val info = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
}

val name = TextView(this).apply {
text = download.title
textSize = 14f
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
}
info.addView(name)

val statusText = when (download.status) {
DownloadManager.STATUS_PENDING -> "等待中"
DownloadManager.STATUS_RUNNING -> {
if (download.totalSize > 0) "下载中 ${download.progress}%"
else "下载中..."
}
DownloadManager.STATUS_SUCCESSFUL -> "已完成  ${formatFileSize(download.totalSize)}"
DownloadManager.STATUS_FAILED -> "失败"
DownloadManager.STATUS_PAUSED -> "已暂停"
else -> "状态未知(${download.status})"
}
val statusView = TextView(this).apply {
text = statusText
textSize = 12f
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary)).getColor(0, 0))
}
info.addView(statusView)

topRow.addView(info)

if (download.status == DownloadManager.STATUS_SUCCESSFUL) {
val moreBtn = Button(this).apply {
text = "\u22EE"
textSize = 20f
setPadding(16, 8, 16, 8)
setOnClickListener {
showCompletedMenu(it, download)
}
}
topRow.addView(moreBtn)
} else if (download.status == DownloadManager.STATUS_RUNNING || download.status == DownloadManager.STATUS_PENDING) {
val cancelBtn = Button(this).apply {
text = "取消"
textSize = 12f
setOnClickListener {
val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
dm.remove(download.id)
Toast.makeText(this@DownloadActivity, "已取消", Toast.LENGTH_SHORT).show()
refreshList()
}
}
topRow.addView(cancelBtn)
} else {
val removeBtn = Button(this).apply {
text = "移除"
textSize = 12f
setOnClickListener {
val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
dm.remove(download.id)
Toast.makeText(this@DownloadActivity, "已移除", Toast.LENGTH_SHORT).show()
refreshList()
}
}
topRow.addView(removeBtn)
}

item.addView(topRow)

if (download.status == DownloadManager.STATUS_RUNNING && download.totalSize > 0) {
val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
max = 100
progress = download.progress
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
8
).apply {
topMargin = 8
}
}
item.addView(progressBar)
}

return item
}

private fun showCompletedMenu(anchor: View, download: DownloadInfo) {
val popup = PopupMenu(this, anchor)
popup.menu.add("打开")
popup.menu.add("删除任务和文件")
popup.menu.add("仅删除任务")
popup.menu.add("详情")
popup.setOnMenuItemClickListener { item ->
when (item.title) {
"打开" -> openDownloadedFile(download.id)
"删除任务和文件" -> {
AlertDialog.Builder(this)
.setTitle("确认删除")
.setMessage("确定要删除 ${download.title} 吗？")
.setPositiveButton("删除") { _, _ ->
deleteFile(download.id, download.localUri)
}
.setNegativeButton("取消", null)
.show()
}
"仅删除任务" -> {
val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
dm.remove(download.id)
Toast.makeText(this, "任务已移除", Toast.LENGTH_SHORT).show()
refreshList()
}
"详情" -> showDetailDialog(download)
}
true
}
popup.show()
}

private fun showDetailDialog(download: DownloadInfo) {
val info = "文件名: ${download.title}\n" +
"大小: ${formatFileSize(download.totalSize)}\n" +
"下载链接: ${download.localUri ?: "未知"}"

val textView = TextView(this).apply {
text = info
textSize = 14f
setPadding(24, 16, 24, 0)
setTextIsSelectable(true)
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
}

AlertDialog.Builder(this)
.setTitle("下载详情")
.setView(textView)
.setPositiveButton("关闭", null)
.show()
}

private fun openDownloadedFile(downloadId: Long) {
try {
val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
dm.getUriForDownloadedFile(downloadId)
} else {
val cursor = dm.query(DownloadManager.Query().setFilterById(downloadId))
var fileUri: Uri? = null
if (cursor.moveToFirst()) {
val uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
if (uriIndex >= 0) {
val uriString = cursor.getString(uriIndex)
if (uriString != null) {
fileUri = Uri.parse(uriString)
}
}
}
cursor.close()
fileUri ?: throw Exception("无法获取文件路径")
}
val mimeType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
dm.getMimeTypeForDownloadedFile(downloadId)
} else {
val ext = android.webkit.MimeTypeMap.getFileExtensionFromUrl(uri.toString())
android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
}
val intent = Intent(Intent.ACTION_VIEW).apply {
setDataAndType(uri, mimeType)
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
}
startActivity(intent)
} catch (e: Exception) {
Toast.makeText(this, "无法打开文件", Toast.LENGTH_SHORT).show()
}
}

private fun deleteFile(downloadId: Long, localUri: String?) {
localUri?.let {
val file = File(Uri.parse(it).path ?: return@let)
if (file.exists()) file.delete()
}
val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
dm.remove(downloadId)
Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
refreshList()
}

private fun formatFileSize(size: Long): String {
return when {
size < 1024 -> "$size B"
size < 1024 * 1024 -> "${size / 1024} KB"
size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
else -> "${size / (1024 * 1024 * 1024)} GB"
}
}
}

data class DownloadInfo(
val id: Long,
val title: String,
val status: Int,
val progress: Int,
var totalSize: Long,
var downloadedSize: Long,
val localUri: String?
)