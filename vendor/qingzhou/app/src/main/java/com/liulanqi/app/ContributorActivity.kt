package com.liulanqi.app

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.view.View

class ContributorActivity : AppCompatActivity() {

override fun onCreate(savedInstanceState: Bundle?) {
super.onCreate(savedInstanceState)
supportActionBar?.hide()

val scrollView = ScrollView(this)
val layout = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setPadding(16, 16, 16, 16)
setBackgroundColor(obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground)).getColor(0, 0))
}

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
text = "贡献入口"
textSize = 24f
layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
})
layout.addView(titleRow)

val introText = TextView(this).apply {
text = "轻舟浏览器是一个开源项目，我们欢迎任何形式的贡献。"
textSize = 16f
setPadding(0, 12, 0, 24)
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
}
layout.addView(introText)

val contributeBtn = Button(this).apply {
text = "我要贡献"
textSize = 18f
setPadding(48, 16, 48, 16)
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
LinearLayout.LayoutParams.WRAP_CONTENT
).apply {
bottomMargin = 32
}
setOnClickListener {
startActivity(Intent(this@ContributorActivity, CLASigningActivity::class.java))
}
}
layout.addView(contributeBtn)

val divider = View(this).apply {
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
1
).apply {
bottomMargin = 16
}
setBackgroundColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorTertiary)).getColor(0, 0))
}
layout.addView(divider)

val claLink = TextView(this).apply {
text = "📄 贡献者协议"
textSize = 16f
setPadding(0, 16, 0, 16)
setTextColor(0xFF1E88E5.toInt())
isClickable = true
setOnClickListener {
startActivity(Intent(this@ContributorActivity, CLAViewerActivity::class.java))
}
}
layout.addView(claLink)

scrollView.addView(layout)
setContentView(scrollView)
}
}