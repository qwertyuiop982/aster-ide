package com.liulanqi.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class LicenseActivity : AppCompatActivity() {

override fun onCreate(savedInstanceState: Bundle?) {
super.onCreate(savedInstanceState)
supportActionBar?.hide()

val showPrivacy = intent?.getBooleanExtra("show_privacy", false) ?: false
val fromAbout = intent?.getBooleanExtra("from_about", false) ?: false

val scrollView = ScrollView(this)
val layout = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setPadding(24, 40, 24, 40)
}

if (fromAbout) {
val topRow = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
setPadding(0, 0, 0, 16)
}

val btnBack = ImageButton(this).apply {
setImageResource(R.drawable.ic_back_adaptive)
background = null
setOnClickListener {
finish()
}
}
topRow.addView(btnBack)

topRow.addView(View(this).apply {
layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
})

layout.addView(topRow)
}

if (showPrivacy) {
layout.addView(TextView(this).apply {
text = "隐私政策"
textSize = 24f
})
layout.addView(TextView(this).apply {
text = "下滑以查看完整内容"
textSize = 13f
setPadding(0, 8, 0, 0)
})
layout.addView(createSpace(24))
layout.addView(TextView(this).apply {
text = LicenseText.privacyText
textSize = 13f
setLineSpacing(4f, 1f)
})
} else if (fromAbout) {
layout.addView(TextView(this).apply {
text = "用户协议"
textSize = 24f
})
layout.addView(TextView(this).apply {
text = "下滑以查看完整内容"
textSize = 13f
setPadding(0, 8, 0, 0)
})
layout.addView(createSpace(24))
layout.addView(TextView(this).apply {
text = LicenseText.fullText
textSize = 13f
setLineSpacing(4f, 1f)
})
} else {
layout.addView(TextView(this).apply {
text = "欢迎使用轻舟浏览器"
textSize = 24f
})
layout.addView(TextView(this).apply {
text = "下滑以找到“同意并继续”或“不同意”按钮"
textSize = 13f
setPadding(0, 8, 0, 0)
})
layout.addView(createSpace(24))
layout.addView(TextView(this).apply {
text = LicenseText.fullText
textSize = 13f
setLineSpacing(4f, 1f)
})
layout.addView(createSpace(32))

val btnAgree = Button(this).apply {
text = "同意并继续"
textSize = 16f
setOnClickListener {
getSharedPreferences("settings", Context.MODE_PRIVATE)
.edit().putBoolean("agreed_license", true).apply()
startActivity(Intent(this@LicenseActivity, MainActivity::class.java))
finish()
}
}
layout.addView(btnAgree)

layout.addView(createSpace(12))

val btnDisagree = Button(this).apply {
text = "不同意"
textSize = 16f
setOnClickListener {
finishAffinity()
}
}
layout.addView(btnDisagree)
}

scrollView.addView(layout)
setContentView(scrollView)
}

private fun createSpace(dp: Int): View {
return View(this).apply {
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
(dp * resources.displayMetrics.density).toInt()
)
}
}

override fun onBackPressed() {
val fromAbout = intent?.getBooleanExtra("from_about", false) ?: false
if (fromAbout) {
finish()
}
}
}