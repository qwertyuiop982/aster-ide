package com.liulanqi.app

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.*

class NoteEditActivity : AppCompatActivity() {

private lateinit var etTitle: EditText
private lateinit var etContent: EditText
private var noteId: String? = null

override fun onCreate(savedInstanceState: Bundle?) {
super.onCreate(savedInstanceState)
supportActionBar?.hide()

val layout = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setPadding(16, 16, 16, 16)
setBackgroundColor(obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground)).getColor(0, 0))
}

val titleRow = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
gravity = Gravity.CENTER_VERTICAL
setPadding(0, 0, 0, 16)
}

val btnBack = ImageButton(this).apply {
setImageResource(R.drawable.ic_back_adaptive)
background = null
setOnClickListener { finish() }
}
titleRow.addView(btnBack)

val spacer = LinearLayout(this).apply {
layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
}
titleRow.addView(spacer)

val btnSave = Button(this).apply {
text = "保存"
setOnClickListener { saveNote() }
}
titleRow.addView(btnSave)
layout.addView(titleRow)

etTitle = EditText(this).apply {
hint = "标题"
setPadding(8, 8, 8, 8)
textSize = 16f
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
setHintTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorHint)).getColor(0, 0))
}
layout.addView(etTitle)

etContent = EditText(this).apply {
hint = "内容"
setPadding(8, 8, 8, 8)
textSize = 14f
minLines = 10
gravity = android.view.Gravity.TOP
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
setHintTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorHint)).getColor(0, 0))
}
layout.addView(etContent)

noteId = intent?.getStringExtra("note_id")
if (noteId != null) {
val note = NoteManager.getNoteById(this, noteId!!)
if (note != null) {
etTitle.setText(note.title)
etContent.setText(note.content)
}
}

setContentView(layout)
}

private fun saveNote() {
val title = etTitle.text.toString().trim()
val content = etContent.text.toString().trim()
if (title.isEmpty() && content.isEmpty()) {
finish()
return
}

val now = System.currentTimeMillis()
val note = NoteManager.Note(
id = noteId ?: UUID.randomUUID().toString(),
title = title,
content = content,
createTime = if (noteId != null) NoteManager.getNoteById(this, noteId!!)?.createTime ?: now else now,
updateTime = now
)
NoteManager.saveNote(this, note)
Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
finish()
}
}