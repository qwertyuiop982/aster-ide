package com.liulanqi.app

import android.os.Bundle
import android.view.Gravity
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class CLAViewerActivity : AppCompatActivity() {

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
text = "贡献者许可协议"
textSize = 24f
layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
})
layout.addView(titleRow)

val claText = TextView(this).apply {
text = getCLAText()
textSize = 12f
setLineSpacing(4f, 1f)
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
}
layout.addView(claText)

scrollView.addView(layout)
setContentView(scrollView)
}

private fun getCLAText(): String {
return """
轻舟浏览器 贡献者许可协议（CLA）

版本：1.0
生效日期：[填写日期]

本协议由您（"贡献者"）与"轻舟浏览器"项目版权所有者（"版权所有者"）共同缔结。感谢您对本项目的关注和贡献。

一、定义

1. "贡献"：指您主动提交给本项目、意图使其成为项目一部分的任何原创作品，包括但不限于源代码、文档、设计、测试用例、图标、翻译等。
2. "本项目"：指版权所有者开发并维护的"轻舟浏览器"软件。

二、版权转让

1. 您在此不可撤销地、永久地、全球性地将您所提交"贡献"的全部版权、所有权及相关知识产权转让给版权所有者。
2. 您声明并保证：
- 您是所提交贡献的原始作者，拥有完整的版权。
- 您的贡献是自愿且无偿的。
- 您的贡献不侵犯任何第三方的知识产权。
3. 版权所有者有权以任何形式使用、修改、合并、发布、分发、再许可及/或销售您的贡献，无需另行通知或支付报酬。

三、反向许可

作为回报，版权所有者在此向您授予一个永久、全球、免费、非排他、不可撤销的许可，允许您以 Apache License, Version 2.0 的方式使用、复制、修改、分发本项目的全部源代码。该许可是独立于项目所采用的任何其他许可证的额外权利。

四、其他条款

1. 本协议不要求版权所有者必须将您的贡献纳入项目。
2. 本协议适用中华人民共和国法律。因本协议产生的争议，双方应友好协商解决；协商不成的，可向版权所有者所在地有管辖权的人民法院提起诉讼。

---

版权所有者：陆梓阳
项目：轻舟浏览器
日期：2026年7月20日
""".trimIndent()
}
}