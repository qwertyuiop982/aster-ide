package com.liulanqi.app

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.*

class NoteListActivity : AppCompatActivity() {

private lateinit var layout: LinearLayout

override fun onCreate(savedInstanceState: Bundle?) {
super.onCreate(savedInstanceState)
supportActionBar?.hide()

val scrollView = ScrollView(this)
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
text = "笔记"
textSize = 24f
layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
})
val addBtn = Button(this).apply {
text = "新建"
textSize = 12f
setOnClickListener {
startActivity(Intent(this@NoteListActivity, NoteEditActivity::class.java))
}
}
titleRow.addView(addBtn)
layout.addView(titleRow)

loadNotes()
}

override fun onResume() {
super.onResume()
refreshList()
}

private fun refreshList() {
while (layout.childCount > 1) {
layout.removeViewAt(layout.childCount - 1)
}
loadNotes()
}

private fun loadNotes() {
val notes = NoteManager.getAllNotes(this)
if (notes.isEmpty()) {
layout.addView(TextView(this).apply {
text = "暂无笔记"
textSize = 14f
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
})
return
}

val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
for (note in notes) {
val itemView = createNoteItemView(note, sdf)
layout.addView(itemView)
}
}

private fun deleteNote(noteId: String, itemView: LinearLayout) {
AlertDialog.Builder(this)
.setTitle("确认删除")
.setMessage("确定要删除这条笔记吗？")
.setPositiveButton("删除") { _, _ ->
NoteManager.deleteNote(this, noteId)
layout.removeView(itemView)
if (layout.childCount <= 1) {
layout.addView(TextView(this).apply {
text = "暂无笔记"
textSize = 14f
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
})
}
Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
}
.setNegativeButton("取消", null)
.show()
}

private fun createNoteItemView(note: NoteManager.Note, sdf: SimpleDateFormat): LinearLayout {
val itemView = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
setPadding(0, 12, 0, 12)
}

val info = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
}

val titleText = note.title.ifEmpty { "无标题" }
val preview = note.content.ifEmpty { "无内容" }.let {
if (it.length > 50) it.substring(0, 50) + "..." else it
}

info.addView(TextView(this).apply {
text = titleText
textSize = 16f
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
})
info.addView(TextView(this).apply {
text = preview
textSize = 13f
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary)).getColor(0, 0))
})
info.addView(TextView(this).apply {
text = sdf.format(Date(note.updateTime))
textSize = 11f
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorTertiary)).getColor(0, 0))
})

itemView.addView(info)

val deleteBtn = Button(this).apply {
text = "删除"
textSize = 12f
setOnClickListener {
deleteNote(note.id, itemView)
}
}
itemView.addView(deleteBtn)

itemView.isClickable = true
itemView.setOnClickListener {
val intent = Intent(this, NoteEditActivity::class.java).apply {
putExtra("note_id", note.id)
}
startActivity(intent)
}

return itemView
}
}