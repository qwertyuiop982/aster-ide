package com.liulanqi.app

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class ScriptEditActivity : AppCompatActivity() {

private lateinit var etCode: EditText
private var scriptId: String? = null

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
setOnClickListener { saveScript() }
}
titleRow.addView(btnSave)
layout.addView(titleRow)

val scrollView = ScrollView(this)
etCode = EditText(this).apply {
setPadding(8, 8, 8, 8)
textSize = 12f
minLines = 20
gravity = Gravity.TOP
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
setHintTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorHint)).getColor(0, 0))
}
scrollView.addView(etCode)
layout.addView(scrollView)

scriptId = intent?.getStringExtra("script_id")
if (scriptId != null) {
val script = ScriptEngine.getScriptList(this).find { it.id == scriptId }
if (script != null) {
etCode.setText(script.code)
}
}

setContentView(layout)
}

private fun saveScript() {
val code = etCode.text.toString().trim()
if (code.isEmpty()) {
finish()
return
}

if (scriptId != null) {
val existing = ScriptEngine.getScriptList(this).find { it.id == scriptId }
if (existing != null) {
ScriptEngine.deleteScript(this, scriptId!!)
}
}

ScriptEngine.installScript(this, code)
Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
finish()
}
}