<div align="center">
  <h1>🌊 轻舟浏览器 · Qingzhou Browser</h1>
  <p><strong>一款轻量、纯净、安全的 Android 浏览器</strong></p>
  <p>
    <img src="https://img.shields.io/badge/License-AGPL--3.0-blue.svg" alt="License">
    <img src="https://img.shields.io/badge/Platform-Android-green.svg" alt="Platform">
    <img src="https://img.shields.io/badge/Language-Kotlin-orange.svg" alt="Language">
    <img src="https://img.shields.io/badge/Dev%20Tool-AIDE-purple.svg" alt="Dev Tool">
  </p>
  <p>"轻舟已过万重山。" —— 这不仅是一个浏览器，更是一个关于创造力的证明。</p>
</div>

---

## 📖 缘起：一个在手机上诞生的浏览器

在大多数人的印象中，开发一款浏览器需要强大的电脑和专业的 IDE。但**轻舟浏览器**却是一个例外。

它**完全诞生于一部 Android 手机**，使用的是 AIDE (Android IDE) 开发工具。从 UI 布局、Kotlin 代码编写，到调试、打包，全在方寸屏幕之间完成。这证明了在移动设备上也能创造出复杂而强大的生产力工具。

**轻舟浏览器不仅是一个网页浏览工具，它本身就是一个宣言：只要有热情和毅力，创造力不受设备限制。**

---

## ✨ 核心亮点

### 🛡️ 把你的隐私，还给你的手
- **超级 Cookie 防火墙**：内置 Public Suffix List (PSL) 规则引擎，自动识别并拦截 `domain=.com` 这类恶意的"超级 Cookie"，把追踪者挡在门外。
- **权限请求透明化**：摄像头、麦克风、地理位置、本地文件...每次网站想要越界，都必须经过你指尖的同意。
- **零数据收集承诺**：所有历史、书签、Cookie 都只呆在你的手机里，开发者不收集、不上传、不窥探。

### 🚫 告别烦人的广告
- **自研规则引擎**：支持 EasyList 等主流广告过滤规则，过滤效果立竿见影。
- **自动更新无忧**：规则库每 4 天自动检查更新，让你永远拥有最新的"防弹衣"。

### 📜 解锁无限可能：用户脚本引擎
- **兼容油猴脚本**：支持从 Greasy Fork 等平台导入 `.user.js` 脚本，自由定制网页功能。
- **安全与强大兼顾**：沙箱化执行环境 + 安装前安全审查，让脚本"戴着镣铐跳舞"，只做你允许的事。

---

## 🧩 所有功能

| 类别 | 功能列表 |
|------|---------|
| **界面体验** | 深色模式设置、多标签页管理、无痕浏览 |
| **隐私安全** | Cookie 管理（查看/删除/禁用）、PSL 规则库、权限拦截弹窗、外部应用跳转拦截 |
| **广告拦截** | EasyList 规则引擎、自动更新、拦截统计页面 |
| **脚本引擎** | 油猴脚本兼容、沙箱隔离、GM_setValue/getValue/addStyle、安装前安全审查、用户脚本帮助页面 |
| **生产力** | 书签管理、历史记录、下载管理器、笔记功能、内置计时器 |
| **其他** | 崩溃日志捕获与显示、搜索引擎切换（9种） |

---

## 🧱 技术栈

| 类别 | 详情 |
|------|------|
| **开发语言** | Kotlin |
| **开发环境** | AIDE (Android IDE) |
| **渲染内核** | Android System WebView (Chromium) |
| **数据存储** | SharedPreferences、JSON、本地文件 |
| **广告拦截** | 自研规则匹配引擎，兼容 EasyList 语法 |
| **脚本引擎** | 自研沙箱引擎，兼容部分 Greasemonkey API (GM_*) |
| **许可证** | GNU Affero General Public License v3.0 (AGPL-3.0) |

---

## 🚀 快速开始

### 方式一：直接安装 APK

前往 [Releases](https://github.com/myos-Lu/qingzhou/releases) 页面下载最新 APK，直接安装到 Android 手机。

### 方式二：从源码构建

1.  **克隆仓库**：在终端中执行 `git clone https://github.com/myos-Lu/qingzhou.git`
2.  **导入 AIDE**：将整个项目文件夹复制到手机存储的 `Android/data/com.chou.android.ide/files/Projects/` 目录下。
3.  **运行**：在 AIDE 中打开项目，点击"运行"编译，然后安装。

---

## 🤝 贡献指南

我们欢迎任何形式的贡献！无论是报告 Bug、提出新功能、改进文档，还是直接提交代码。

### 📋 准备工作

1.  阅读并遵守 [贡献者公约](CODE_OF_CONDUCT.md)。
2.  确认你的贡献与 **AGPL-3.0** 许可证兼容。
3.  Fork 本仓库并克隆到本地。

### 🐛 报告 Bug

如果你发现了 Bug，请在 [Issues](https://github.com/myos-Lu/qingzhou/issues) 中提交，并包含：

- **描述**：详细说明问题现象，附上截图
- **复现步骤**：逐步说明如何触发该问题
- **设备信息**：手机型号、Android 版本、应用版本
- **崩溃日志**：如果应用闪退，请在"设置 → 崩溃日志"中或下次打应用开时的主屏幕复制日志内容

### 💡 提出新功能

如果你有好的想法，欢迎在 [Discussions](https://github.com/myos-Lu/qingzhou/discussions) 中发起讨论，或在 Issues 中提交功能请求。

### 💻 贡献代码

1.  **Fork 本仓库**
2.  **创建你的功能分支**：`git checkout -b feature/your-feature-name`
3.  **编写代码**
    - 请使用 Kotlin 编写
    - 保持与现有代码风格一致
    - 为新功能添加必要的注释
4.  **提交你的更改**：`git add .` 然后 `git commit -m "添加：你的功能描述"`
5.  **推送到你的 Fork**：`git push origin feature/your-feature-name`
6.  **创建 Pull Request**：在 GitHub 上创建 Pull Request，描述你的更改内容。

### 📝 开发注意事项

- 本项目使用 **AIDE** 开发，确保你的更改在 AIDE 中能正常编译。
- 如果你使用 Android Studio 进行开发，请注意 AIDE 项目结构与标准 Gradle 项目略有不同。
- 不要添加 `android:icon` 以外的 mipmap 资源，AIDE 对某些资源格式支持有限。
- 所有第三方资源必须具有合法的使用许可。

---

## 📜 许可

本项目采用 **GNU Affero General Public License v3.0 (AGPL-3.0)** 许可证。详情见 [LICENSE](LICENSE) 文件。

---

## 👨‍💻 作者

**陆梓阳 (Ziyang Lu)**

- GitHub: [@myos-Lu](https://github.com/myos-Lu)
- 邮箱: jssntsbczmfc2622@foxmail.com

---

## 🙏 致谢

- **EasyList**：广告拦截规则来源
- **Public Suffix List**：域名安全规则来源
- **Greasy Fork**：用户脚本社区
- **AIDE**：让手机开发成为可能

---

<div align="center">
  <sub>始于 2026 · 为纯粹浏览而生</sub>
</div>
