package com.liulanqi.app

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

override fun onCreate(savedInstanceState: Bundle?) {
super.onCreate(savedInstanceState)
supportActionBar?.hide()

val scrollView = android.widget.ScrollView(this)
val layout = LinearLayout(this).apply {
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
text = "历史记录"
textSize = 24f
layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
})
val clearBtn = Button(this).apply {
text = "清空"
textSize = 12f
setOnClickListener {
val historyList = HistoryManager.getHistoryList(this@HistoryActivity)
if (historyList.isEmpty()) {
Toast.makeText(this@HistoryActivity, "暂无历史记录", Toast.LENGTH_SHORT).show()
} else {
AlertDialog.Builder(this@HistoryActivity)
.setTitle("确认清空")
.setMessage("确定要清空所有历史记录吗？")
.setPositiveButton("清空") { _, _ ->
HistoryManager.clearAll(this@HistoryActivity)
while (layout.childCount > 1) {
layout.removeViewAt(layout.childCount - 1)
}
loadHistory(layout)
Toast.makeText(this@HistoryActivity, "已清空", Toast.LENGTH_SHORT).show()
}
.setNegativeButton("取消", null)
.show()
}
}
}
titleRow.addView(clearBtn)
layout.addView(titleRow)

loadHistory(layout)
}

private fun loadHistory(layout: LinearLayout) {
val historyList = HistoryManager.getHistoryList(this)
if (historyList.isEmpty()) {
layout.addView(TextView(this).apply {
text = "暂无历史记录"
textSize = 14f
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
})
} else {
val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
for (item in historyList) {
val itemView = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
setPadding(0, 12, 0, 12)
isClickable = true
}

val info = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
}
info.addView(TextView(this).apply {
text = item.title
textSize = 14f
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
})
info.addView(TextView(this).apply {
text = item.url
textSize = 12f
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary)).getColor(0, 0))
})
info.addView(TextView(this).apply {
text = sdf.format(Date(item.timestamp))
textSize = 10f
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorTertiary)).getColor(0, 0))
})
itemView.addView(info)

val moreBtn = Button(this).apply {
text = "\u22EE"
textSize = 20f
setPadding(16, 8, 16, 8)
setOnClickListener {
val popup = PopupMenu(this@HistoryActivity, it)
popup.menu.add("打开")
popup.menu.add("删除")
popup.setOnMenuItemClickListener { menuItem ->
when (menuItem.title) {
"打开" -> {
val intent = Intent(this@HistoryActivity, MainActivity::class.java).apply {
putExtra("load_url", item.url)
flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
}
startActivity(intent)
}
"删除" -> {
HistoryManager.removeHistory(this@HistoryActivity, item.url)
layout.removeView(itemView)
if (layout.childCount <= 1) {
layout.addView(TextView(this@HistoryActivity).apply {
text = "暂无历史记录"
textSize = 14f
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
})
}
Toast.makeText(this@HistoryActivity, "已删除", Toast.LENGTH_SHORT).show()
}
}
true
}
popup.show()
}
}
itemView.addView(moreBtn)

itemView.setOnClickListener {
val intent = Intent(this, MainActivity::class.java).apply {
putExtra("load_url", item.url)
flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
}
startActivity(intent)
}

layout.addView(itemView)
}
}
}
}