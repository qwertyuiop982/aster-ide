package com.liulanqi.app

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class BookmarkActivity : AppCompatActivity() {

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
text = "书签"
textSize = 24f
layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
})
layout.addView(titleRow)

val bookmarks = BookmarkManager.getBookmarks(this)
if (bookmarks.isEmpty()) {
val empty = TextView(this).apply {
text = "暂无书签"
textSize = 14f
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
}
layout.addView(empty)
} else {
for (bookmark in bookmarks) {
val item = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
setPadding(0, 12, 0, 12)
}

val info = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
}

val nameView = TextView(this).apply {
text = bookmark.title
textSize = 14f
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
}
info.addView(nameView)

val urlView = TextView(this).apply {
text = bookmark.url
textSize = 12f
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary)).getColor(0, 0))
}
info.addView(urlView)

item.addView(info)

val moreBtn = Button(this).apply {
text = "\u22EE"
textSize = 20f
setPadding(16, 8, 16, 8)
setOnClickListener {
showPopupMenu(it, bookmark, layout, item)
}
}
item.addView(moreBtn)

item.isClickable = true
item.setOnClickListener {
openInBrowser(bookmark.url)
}

layout.addView(item)
}
}
}

private fun showPopupMenu(anchor: View, bookmark: Bookmark, layout: LinearLayout, item: LinearLayout) {
val popup = PopupMenu(this, anchor)
popup.menu.add("打开")
popup.menu.add("删除")
popup.setOnMenuItemClickListener { menuItem ->
when (menuItem.title) {
"打开" -> openInBrowser(bookmark.url)
"删除" -> {
AlertDialog.Builder(this)
.setTitle("确认删除")
.setMessage("确定要删除书签\"${bookmark.title}\"吗？")
.setPositiveButton("删除") { _, _ ->
BookmarkManager.removeBookmark(this, bookmark.url)
layout.removeView(item)
if (layout.childCount <= 1) {
val empty = TextView(this).apply {
text = "暂无书签"
textSize = 14f
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
}
layout.addView(empty)
}
Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
}
.setNegativeButton("取消", null)
.show()
}
}
true
}
popup.show()
}

private fun openInBrowser(url: String) {
val intent = Intent(this, MainActivity::class.java).apply {
putExtra("load_url", url)
flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
}
startActivity(intent)
}
}