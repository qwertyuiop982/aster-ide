package com.liulanqi.app

import android.os.Bundle
import android.view.Gravity
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ScriptHelpActivity : AppCompatActivity() {

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
text = "使用帮助"
textSize = 24f
layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
})
layout.addView(titleRow)

layout.addView(createSection("什么是用户脚本？"))
layout.addView(createBody("用户脚本是一段可以自定义网页行为的 JavaScript 代码。它由第三方开发者编写，可以修改网页的外观、添加新功能或移除不需要的内容。"))

layout.addView(createSection("有什么用？"))
layout.addView(createBody("· 网页翻译：在网页上直接显示双语对照翻译，无需跳转第三方翻译网站\n· 移除广告：屏蔽特定网站的横幅广告、弹窗广告和视频贴片广告\n· 视频增强：为视频网站添加倍速播放、画面旋转、画中画、下载按钮\n· 美化界面：修改网页字体、颜色、行间距、背景色，打造舒适的阅读体验\n· 自动化操作：自动翻页加载更多内容、自动展开被折叠的全文、自动签到领积分\n· 数据导出：将网页表格数据导出为 Excel 或 CSV 文件\n· 解除限制：解除部分网站的复制限制、右键限制、区域限制"))

layout.addView(createSection("如何获取用户脚本？"))
layout.addView(createBody("1. 从脚本市场下载（推荐）：访问 Greasy Fork（greasyfork.org）或其他脚本分享网站\n2. 搜索需要的脚本：在搜索框输入关键词，如\"翻译\"\"去广告\"\"视频下载\"\n3. 下载 .user.js 文件：在脚本详情页点击\"安装\"或\"下载\"，保存到手机\n4. 导入浏览器：在脚本管理页面点击\"导入脚本\"，选择刚才下载的文件即可安装"))

layout.addView(createSection("⚠️ 安全提示"))
layout.addView(createBody("· 请仅安装来源可信的脚本，避免安装未知来源的脚本\n· 安装前浏览器会自动进行安全审查，包括权限审计和代码扫描\n· 审查结果仅供参考，不保证100%准确\n· 如发现脚本异常或影响浏览体验，可随时在脚本管理页面禁用或删除"))

scrollView.addView(layout)
setContentView(scrollView)
}

private fun createSection(text: String): TextView {
return TextView(this).apply {
this.text = text
textSize = 18f
setPadding(0, 20, 0, 8)
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
}
}

private fun createBody(text: String): TextView {
return TextView(this).apply {
this.text = text
textSize = 15f
setLineSpacing(6f, 1f)
setTextColor(obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary)).getColor(0, 0))
}
}
}