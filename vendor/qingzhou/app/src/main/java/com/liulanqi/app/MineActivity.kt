package com.liulanqi.app

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MineActivity : AppCompatActivity() {

override fun onCreate(savedInstanceState: Bundle?) {
super.onCreate(savedInstanceState)
supportActionBar?.hide()

val scrollView = android.widget.ScrollView(this)
val layout = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setPadding(16, 16, 16, 16)
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
text = "我的"
textSize = 24f
layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
})
layout.addView(titleRow)

val group1 = createCardGroup()
group1.addView(createCardItem(R.drawable.ic_menu_bookmarks, "书签") {
startActivity(Intent(this, BookmarkActivity::class.java))
})
group1.addView(createCardDivider())
group1.addView(createCardItem(R.drawable.ic_menu_history, "历史记录") {
startActivity(Intent(this, HistoryActivity::class.java))
})
group1.addView(createCardDivider())
group1.addView(createCardItem(R.drawable.ic_menu_download, "下载管理") {
startActivity(Intent(this, DownloadActivity::class.java))
})
group1.addView(createCardDivider())
group1.addView(createCardItem(R.drawable.ic_menu_note, "笔记") {
startActivity(Intent(this, NoteListActivity::class.java))
})
group1.addView(createCardDivider())
group1.addView(createCardItem(R.drawable.ic_menu_crash, "崩溃日志") {
startActivity(Intent(this, CrashLogActivity::class.java))
})
layout.addView(group1)

layout.addView(createCardSpacer())

val group2 = createCardGroup()
group2.addView(createCardItem(R.drawable.ic_menu_bookmarks, "我要贡献") {
val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/myos-Lu/qingzhou"))
startActivity(intent)
})
layout.addView(group2)

layout.addView(createCardSpacer())

val group3 = createCardGroup()
group3.addView(createCardItem(R.drawable.ic_menu_settings, "设置") {
startActivity(Intent(this, SettingsActivity::class.java))
})
group3.addView(createCardDivider())
group3.addView(createCardItem(R.drawable.ic_menu_about, "关于") {
startActivity(Intent(this, AboutActivity::class.java))
})
layout.addView(group3)
}

private fun createCardSpacer(): View {
return View(this).apply {
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
12
)
}
}

private fun createCardGroup(): LinearLayout {
val isDark = androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode() == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
val bg = GradientDrawable().apply {
shape = GradientDrawable.RECTANGLE
cornerRadius = 16f
setColor(if (isDark) 0xFF2A2A2A.toInt() else 0xFFFFFFFF.toInt())
}
return LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
background = bg
elevation = 2f
}
}

private fun createCardDivider(): View {
val isDark = androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode() == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
return View(this).apply {
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
1
).apply {
leftMargin = 72
}
setBackgroundColor(if (isDark) 0x1AFFFFFF else 0x1A000000)
}
}

private fun createCardItem(iconRes: Int, text: String, onClick: () -> Unit): LinearLayout {
val isDark = androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode() == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
return LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
setPadding(16, 20, 16, 20)
isClickable = true
isFocusable = true
gravity = Gravity.CENTER_VERTICAL
setBackgroundColor(if (isDark) 0xFF2A2A2A.toInt() else 0xFFFFFFFF.toInt())
addView(ImageView(this@MineActivity).apply {
setImageResource(iconRes)
layoutParams = LinearLayout.LayoutParams(48, 48).apply {
marginEnd = 24
}
})
addView(TextView(this@MineActivity).apply {
this.text = text
textSize = 18f
gravity = Gravity.CENTER_VERTICAL
})
setOnClickListener { onClick() }
}
}
}