package com.liulanqi.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class AboutActivity : AppCompatActivity() {

override fun onCreate(savedInstanceState: Bundle?) {
super.onCreate(savedInstanceState)
supportActionBar?.hide()

val scrollView = ScrollView(this)
val layout = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setPadding(24, 40, 24, 40)
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
text = "关于"
textSize = 24f
layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
})
layout.addView(titleRow)

layout.addView(createBody("轻舟浏览器"))
layout.addView(createSpace(8))
layout.addView(createBody("版本 v1.0.0-beta.1"))
layout.addView(createSpace(32))
layout.addView(createBody("你好！这是一款基于 Android WebView 的轻量浏览器，由我利用课余时间独立开发。"))
layout.addView(createSpace(8))
layout.addView(createBody("它很简洁，没有广告，专注于最基础的网页浏览功能。如果你喜欢折腾，欢迎提出建议！"))

layout.addView(createSpace(32))
layout.addView(createSection("📄 法律文档"))
layout.addView(createSpace(8))

val itemLicense = createLinkItem("用户协议")
itemLicense.setOnClickListener {
startActivity(Intent(this, LicenseActivity::class.java).apply {
putExtra("show_privacy", false)
putExtra("from_about", true)
})
}
layout.addView(itemLicense)

val itemPrivacy = createLinkItem("隐私政策")
itemPrivacy.setOnClickListener {
startActivity(Intent(this, LicenseActivity::class.java).apply {
putExtra("show_privacy", true)
putExtra("from_about", true)
})
}
layout.addView(itemPrivacy)

val itemOpenSource = createLinkItem("开放源代码许可")
itemOpenSource.setOnClickListener {
startActivity(Intent(this, LicenseViewerActivity::class.java))
}
layout.addView(itemOpenSource)

layout.addView(createSpace(32))
layout.addView(createSection("📦 获取源码"))
layout.addView(createSpace(8))
layout.addView(createBody("本软件以 AGPL-3.0 开源发布，源码托管于 GitHub，欢迎提交 Issue 和 Pull Request。"))
layout.addView(createSpace(8))

val copyBtn = Button(this).apply {
text = "复制源码地址"
textSize = 14f
setOnClickListener {
val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
val clip = ClipData.newPlainText("源码地址", "https://github.com/myos-Lu/qingzhou")
clipboard.setPrimaryClip(clip)
Toast.makeText(this@AboutActivity, "源码地址已复制到剪贴板", Toast.LENGTH_SHORT).show()
}
}
layout.addView(copyBtn)

scrollView.addView(layout)
setContentView(scrollView)
}

private fun createSection(text: String): TextView {
return TextView(this).apply {
this.text = text
textSize = 16f
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
}
}

private fun createBody(text: String): TextView {
return TextView(this).apply {
this.text = text
textSize = 14f
setLineSpacing(4f, 1f)
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
}
}

private fun createLinkItem(text: String): TextView {
return TextView(this).apply {
this.text = text
textSize = 14f
setTextColor(0xFF1E88E5.toInt())
paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
setPadding(0, 12, 0, 12)
isClickable = true
isFocusable = true
}
}

private fun createSpace(dp: Int): android.view.View {
return android.view.View(this).apply {
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
dpToPx(dp)
)
}
}

private fun dpToPx(dp: Int): Int {
return (dp * resources.displayMetrics.density).toInt()
}
}