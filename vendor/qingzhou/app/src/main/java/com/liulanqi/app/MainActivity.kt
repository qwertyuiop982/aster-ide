package com.liulanqi.app

import android.app.Dialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    private val tabs = mutableListOf<TabInfo>()
    private var currentTabIndex = 0
    private var isIncognitoMode = false

    private lateinit var container: FrameLayout
    private lateinit var etUrl: EditText
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnRefresh: ImageButton
    private lateinit var btnBookmark: ImageButton
    private lateinit var btnGo: ImageButton
    private lateinit var btnMine: ImageButton
    private lateinit var btnTabs: ImageButton
    private lateinit var btnIncognito: ImageButton
    private lateinit var btnTimer: ImageButton
    private lateinit var progressBar: ProgressBar
    private var pendingDownloadUrl: String? = null
    private var onDownloadComplete: BroadcastReceiver? = null

    private var tabBadge: TextView? = null
    private var tabsContainer: FrameLayout? = null

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    private val scriptCrashCount = mutableMapOf<String, Int>()
    private val scriptCrashTimestamp = mutableMapOf<String, Long>()

    private val userAgent = "Mozilla/5.0 (Linux; Android 13; en-US; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/117.0.5938.60 Mobile Safari/537.36"

    private var pendingPermissionRequest: PermissionRequest? = null
    private var pendingGeolocationOrigin: String? = null
    private var pendingGeolocationCallback: GeolocationPermissions.Callback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        CrashHandler.init(applicationContext)

        val crashLogs = CrashHandler.getCrashLogs(this)
        if (crashLogs.isNotEmpty()) {
            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            val lastCleanLaunch = prefs.getLong("last_clean_launch_time", 0L)
            val latestLogTime = crashLogs.maxOf { it.lastModified() }

            if (latestLogTime > lastCleanLaunch) {
                startActivity(Intent(this, CrashDisplayActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
                return
            } else {
                CrashHandler.clearAllLogs(this)
            }
        }

        if (!getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("agreed_license", false)) {
            setContentView(View(this))
            showLicenseDialog()
            return
        }

        initMainContent()
    }

    override fun onResume() {
        super.onResume()
        if (::container.isInitialized) {
            container.postDelayed({
                AdBlocker.autoCheckUpdate(this)
            }, 2000)
        }
    }

    private fun initMainContent() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val nightMode = prefs.getInt("night_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(nightMode)

        prefs.edit().putLong("last_clean_launch_time", System.currentTimeMillis()).apply()

        AdBlocker.init(this)

        val adblockNeeds = AdBlocker.needsRulesDownload(this)
        val pslNeeds = !PslUpdater.isDownloaded(this)

        if (adblockNeeds || pslNeeds) {
            val items = mutableListOf<String>()
            if (adblockNeeds) items.add("广告拦截规则（约 1-2 MB）")
            if (pslNeeds) items.add("域名安全规则（约 300 KB）")
            val message = "浏览器需要下载以下规则库以确保功能完整：\n${items.joinToString("\n")}\n\n下载需要联网，可能会产生流量费用，是否继续？"

            AlertDialog.Builder(this)
                .setTitle("下载规则库")
                .setMessage(message)
                .setPositiveButton("下载") { _, _ ->
                    var completed = 0
                    val total = items.size

                    val onItemComplete: () -> Unit = {
                        completed++
                        if (completed >= total) {
                            runOnUiThread {
                                Toast.makeText(this, "规则库下载完成", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }

                    if (adblockNeeds) {
                        AdBlocker.updateRulesWithCallback(this, onItemComplete)
                    }
                    if (pslNeeds) {
                        PslUpdater.update(this, onItemComplete)
                    }
                    Toast.makeText(this, "正在下载规则...", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("暂不下载", null)
                .show()
        }

        setContentView(R.layout.activity_main)

        etUrl = findViewById(R.id.et_url)
        btnBack = findViewById(R.id.btn_back)
        btnForward = findViewById(R.id.btn_forward)
        btnRefresh = findViewById(R.id.btn_refresh)
        btnBookmark = findViewById(R.id.btn_bookmark)
        btnGo = findViewById(R.id.btn_go)
        btnMine = findViewById(R.id.btn_mine)
        btnTabs = findViewById(R.id.btn_tabs)
        btnIncognito = findViewById(R.id.btn_incognito)
        btnTimer = findViewById(R.id.btn_timer)
        progressBar = findViewById(R.id.progress_bar)
        container = findViewById(R.id.fragment_container)

        tabsContainer = btnTabs.parent as? FrameLayout

        etUrl.inputType = InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

        container.post {
            newTab("about:blank", false)
            switchTab(0)
            registerDownloadReceiver()
            handleIntent(intent)
        }

        btnBack.setOnClickListener {
            val tab = tabs.getOrNull(currentTabIndex)
            if (tab != null && tab.engine.canGoBack()) tab.engine.goBack()
        }
        btnForward.setOnClickListener {
            val tab = tabs.getOrNull(currentTabIndex)
            if (tab != null && tab.engine.canGoForward()) tab.engine.goForward()
        }
        btnRefresh.setOnClickListener {
            val tab = tabs.getOrNull(currentTabIndex) ?: return@setOnClickListener
            if (tab.engine.getProgress() < 100) tab.engine.stopLoading()
            tab.engine.reload()
        }
        btnGo.setOnClickListener {
            loadUrl(etUrl.text.toString())
        }
        btnBookmark.setOnClickListener {
            val tab = tabs.getOrNull(currentTabIndex) ?: return@setOnClickListener
            val url = tab.currentUrl ?: return@setOnClickListener
            if (url == "about:blank") return@setOnClickListener
            if (BookmarkManager.isBookmarked(this, url)) {
                BookmarkManager.removeBookmark(this, url)
                Toast.makeText(this, "已取消收藏", Toast.LENGTH_SHORT).show()
            } else {
                val title = tab.currentTitle ?: url
                BookmarkManager.addBookmark(this, title, url)
                Toast.makeText(this, "已添加收藏", Toast.LENGTH_SHORT).show()
            }
            updateBookmarkIcon()
        }
        btnMine.setOnClickListener {
            startActivity(Intent(this, MineActivity::class.java))
        }
        btnTabs.setOnClickListener {
            showTabsPanel()
        }
        btnIncognito.setOnClickListener {
            toggleIncognitoMode()
        }
        val btnScripts: ImageButton = findViewById(R.id.btn_scripts)
        btnScripts.setOnClickListener {
            startActivity(Intent(this, ScriptManagerActivity::class.java))
        }
        btnTimer.setOnClickListener {
            startActivity(Intent(this, TimerActivity::class.java))
        }

        etUrl.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus && etUrl.text.toString() == "about:blank") {
                etUrl.setText("")
            }
            val tab = tabs.getOrNull(currentTabIndex)
            if (hasFocus && tab != null && tab.isTitleSet && tab.currentTitle != null) {
                val currentText = etUrl.text.toString()
                if (currentText == tab.currentTitle && tab.currentUrl != null && tab.currentUrl != "about:blank") {
                    etUrl.setText(tab.currentUrl)
                    etUrl.setSelection(tab.currentUrl!!.length)
                }
            }
            if (hasFocus) {
                btnGo.visibility = View.VISIBLE
            } else {
                updateGoButtonVisibility()
            }
        }
        etUrl.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                loadUrl(etUrl.text.toString())
                true
            } else {
                false
            }
        }
    }

    private fun updateGoButtonVisibility() {
        val tab = tabs.getOrNull(currentTabIndex)
        val text = etUrl.text.toString()
        if (text.isEmpty() || text == "about:blank") {
            btnGo.visibility = View.VISIBLE
            return
        }
        if (tab != null && tab.isTitleSet && tab.currentTitle == text) {
            btnGo.visibility = View.GONE
        } else {
            btnGo.visibility = View.VISIBLE
        }
    }

    private fun showTabsPanel() {
        val dialog = Dialog(this)
        val panelLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 16)
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = "标签管理 (${tabs.size})"
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        val btnNewTab = Button(this).apply {
            text = "新建"
            textSize = 14f
            setOnClickListener {
                newTab("about:blank", isIncognitoMode)
                switchTab(tabs.size - 1)
                dialog.dismiss()
            }
        }
        titleRow.addView(btnNewTab)

        val btnClose = Button(this).apply {
            text = "关闭"
            textSize = 14f
            setOnClickListener { dialog.dismiss() }
        }
        titleRow.addView(btnClose)
        panelLayout.addView(titleRow)

        val currentList = ArrayList(tabs)
        for (i in currentList.indices) {
            val tab = currentList[i]
            val itemRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(8, 12, 8, 12)
                gravity = Gravity.CENTER_VERTICAL
                if (i == currentTabIndex) {
                    setBackgroundColor(0x1A1E88E5.toInt())
                }
            }

            val tabInfo = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val titleText = tab.currentTitle ?: tab.currentUrl ?: "新标签"
            val suffix = if (tab.isIncognito) " (无痕)" else ""
            tabInfo.addView(TextView(this).apply {
                text = "$titleText$suffix"
                textSize = 14f
            })
            if (tab.currentUrl != null && tab.isTitleSet) {
                tabInfo.addView(TextView(this).apply {
                    text = tab.currentUrl
                    textSize = 11f
                })
            }

            itemRow.addView(tabInfo)

            val btnRemove = Button(this).apply {
                text = "×"
                textSize = 16f
                setPadding(12, 4, 12, 4)
                setOnClickListener {
                    closeTab(i)
                    dialog.dismiss()
                }
            }
            itemRow.addView(btnRemove)

            itemRow.isClickable = true
            itemRow.setOnClickListener {
                if (i != currentTabIndex) {
                    switchTab(i)
                }
                dialog.dismiss()
            }

            panelLayout.addView(itemRow)
        }

        dialog.setContentView(panelLayout)
        val window = dialog.window
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setGravity(Gravity.BOTTOM)
            window.setWindowAnimations(android.R.style.Animation_Dialog)
        }
        dialog.show()
    }

    private fun toggleIncognitoMode() {
        isIncognitoMode = !isIncognitoMode
        val message = if (isIncognitoMode) "已进入无痕模式，浏览记录不会保存。" else "已退出无痕模式。"
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun newTab(url: String, incognito: Boolean = isIncognitoMode): TabInfo {
        val engine = WebViewEngine(this)
        val webView = engine.webView

        // Cookie 配置
        val cookieEnabled = getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getBoolean("cookie_enabled", true)
        if (cookieEnabled) {
            CookieManager.getInstance().setAcceptCookie(true)
        } else {
            CookieManager.getInstance().setAcceptCookie(false)
            CookieManager.getInstance().removeAllCookies(null)
        }

        // 下载监听
        webView.setDownloadListener { downloadUrl, _, _, _, _ ->
            handleDownloadWithPermission(downloadUrl)
        }

        // 注入原生接口
        webView.addJavascriptInterface(ClipboardBridge(this@MainActivity), "_clipboardBridge")
        webView.addJavascriptInterface(GMBridge(this@MainActivity), "GM_bridge")

        // 设置回调
        engine.setOnPageStarted { url ->
            val tab = tabs.find { it.engine == engine } ?: return@setOnPageStarted
            tab.isTitleSet = false
            tab.hasHistoryRecorded = false
        }
        engine.setOnPageFinished { url ->
            val tab = tabs.find { it.engine == engine } ?: return@setOnPageFinished
            tab.currentUrl = url
            if (engine.getView() == tabs[currentTabIndex].engine.getView()) {
                if (url == "about:blank") {
                    etUrl.setText("")
                    tab.currentTitle = null
                    tab.isTitleSet = false
                } else if (!tab.isTitleSet && url != null) {
                    etUrl.setText(url)
                } else if (tab.isTitleSet && tab.currentTitle != null) {
                    etUrl.setText(tab.currentTitle)
                }
                updateBookmarkIcon()
                updateNavButtons()
                updateGoButtonVisibility()
            }
            if (url != null && url != "about:blank") {
                try {
                    val uri = Uri.parse(url)
                    val host = uri.host
                    if (host != null) {
                        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                        val domains = prefs.getStringSet("cookie_domains", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                        if (domains.add(host)) {
                            prefs.edit().putStringSet("cookie_domains", domains).apply()
                        }
                    }
                } catch (_: Exception) {}
            }
            if (url != null && url != "about:blank") {
                injectScripts(webView, url)
            }
        }
        engine.setOnTitleReceived { title ->
            val tab = tabs.find { it.engine == engine } ?: return@setOnTitleReceived
            tab.currentTitle = title
            tab.isTitleSet = true
            if (engine.getView() == tabs[currentTabIndex].engine.getView()) {
                etUrl.setText(title)
                updateBookmarkIcon()
                updateGoButtonVisibility()
            }
            if (!tab.isIncognito && !tab.hasHistoryRecorded) {
                HistoryManager.addHistory(this@MainActivity, title, engine.getCurrentUrl() ?: "")
                tab.hasHistoryRecorded = true
            }
        }
        engine.setOnProgressChanged { progress ->
            if (engine.getView() == tabs.getOrNull(currentTabIndex)?.engine?.getView()) {
                if (progress < 100) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = progress
                } else {
                    progressBar.visibility = View.GONE
                }
            }
        }
        engine.setOnSslError { url, error, proceed, cancel ->
            AlertDialog.Builder(this@MainActivity)
                .setTitle("危险网站")
                .setMessage("${url} 的证书存在问题：${error}\n\n不建议继续访问。如果仍要访问，请勿输入任何敏感信息（如密码、银行卡号等）。")
                .setPositiveButton("仍要访问") { _, _ -> proceed() }
                .setNegativeButton("终止访问") { _, _ -> cancel() }
                .setCancelable(false)
                .show()
        }
        engine.setOnPermissionRequest { origin, resources, grant, deny ->
            AlertDialog.Builder(this@MainActivity)
                .setTitle("权限请求")
                .setMessage("${origin} 想要使用以下权限：\n${resources.joinToString(", ")}")
                .setPositiveButton("允许") { _, _ -> grant() }
                .setNegativeButton("禁止") { _, _ -> deny() }
                .show()
        }
        engine.setOnFileChooser { callback, intent ->
            if (intent != null) {
                fileChooserCallback = object : ValueCallback<Array<Uri>> {
                    override fun onReceiveValue(value: Array<Uri>?) {
                        callback(value)
                        fileChooserCallback = null
                    }
                }
                startActivityForResult(intent, 200)
            } else {
                callback(null)
            }
        }

        // 广告拦截与自定义 URL 加载
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    if (url.endsWith(".user.js") || url.contains(".user.js?")) {
                        promptInstallUserScript(url)
                        return true
                    }
                    if (isDownloadUrl(url)) {
                        handleDownloadWithPermission(url)
                        return true
                    }
                    view?.loadUrl(url)
                } else {
                    showOpenExternalDialog(url)
                }
                return true
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (AdBlocker.shouldBlock(url)) {
                    return WebResourceResponse("text/plain", "UTF-8", null)
                }
                return null
            }

            @Suppress("DEPRECATION")
            override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
                if (url == null) return null
                if (AdBlocker.shouldBlock(url)) {
                    return WebResourceResponse("text/plain", "UTF-8", null)
                }
                return null
            }
        }

        val tab = TabInfo(engine, url, isIncognito = incognito)
        engine.loadUrl(url)
        tabs.add(tab)
        updateTabsButton()
        return tab
    }

    private fun injectScripts(webView: WebView, url: String) {
        val ctx = this
        val prefs = ctx.getSharedPreferences("scripts", MODE_PRIVATE)
        if (!prefs.getBoolean("script_engine_enabled", true)) return
        val scripts = ScriptEngine.getScriptList(ctx)
        for (script in scripts) {
            if (!script.enabled) continue
            if (!urlMatches(script.matches, url)) continue
            val injectedJs = buildInjectedJs(script)
            webView.evaluateJavascript(injectedJs) { result ->
                if (result != null && result.startsWith("\"crash:")) {
                    handleScriptCrash(script.id, script.name)
                }
            }
        }
    }

    private fun buildInjectedJs(script: ScriptInfo): String {
        val scriptId = script.id
        return "(function(){\n" +
                "try {\n" +
                "window._GM = {\n" +
                "    setValue: function(key, value) { return GM_bridge.setValue('$scriptId', key, JSON.stringify(value)); },\n" +
                "    getValue: function(key, defaultValue) { var raw = GM_bridge.getValue('$scriptId', key, JSON.stringify(defaultValue)); try { return JSON.parse(raw); } catch(e) { return defaultValue; } },\n" +
                "    addStyle: function(css) { var s = document.createElement('style'); s.textContent = css; document.head.appendChild(s); }\n" +
                "};\n" +
                script.code + "\n" +
                "} catch(e) { return 'crash:' + e.message; }\n" +
                "})();"
    }

    private fun handleScriptCrash(scriptId: String, scriptName: String) {
        val now = System.currentTimeMillis()
        val lastTime = scriptCrashTimestamp[scriptId] ?: 0L
        if (now - lastTime < 30000) {
            val count = (scriptCrashCount[scriptId] ?: 0) + 1
            scriptCrashCount[scriptId] = count
            if (count >= 3) {
                ScriptEngine.setScriptEnabled(this, scriptId, false)
                scriptCrashCount.remove(scriptId)
                scriptCrashTimestamp.remove(scriptId)
                AlertDialog.Builder(this)
                    .setTitle("脚本已自动禁用")
                    .setMessage("脚本\"$scriptName\"在 30 秒内导致页面连续崩溃 3 次，已被自动禁用。\n\n您可以在脚本管理页面重新启用它。")
                    .setPositiveButton("前往脚本管理") { _, _ ->
                        startActivity(Intent(this, ScriptManagerActivity::class.java))
                    }
                    .setNegativeButton("关闭", null)
                    .show()
            }
        } else {
            scriptCrashCount[scriptId] = 1
        }
        scriptCrashTimestamp[scriptId] = now
    }

    private fun urlMatches(matches: List<String>, url: String): Boolean {
        if (matches.isEmpty()) return false
        for (pattern in matches) {
            if (wildcardMatch(pattern, url)) return true
        }
        return false
    }

    private fun wildcardMatch(pattern: String, input: String): Boolean {
        val regex = pattern
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".")
        return Regex(regex).matches(input)
    }

    private fun promptInstallUserScript(url: String) {
        val ctx = this
        AlertDialog.Builder(ctx)
            .setTitle("安装用户脚本")
            .setMessage("检测到用户脚本链接，是否下载并安装？\n$url")
            .setPositiveButton("安装") { _, _ ->
                downloadAndInstallScript(url)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun downloadAndInstallScript(url: String) {
        val ctx = this
        Thread {
            var content: String? = null
            var errorMsg: String? = null
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                val reader = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8"))
                content = reader.readText()
                reader.close()
                connection.disconnect()
            } catch (e: Exception) {
                errorMsg = e.message ?: "下载失败"
            }
            runOnUiThread {
                if (content != null && content.isNotEmpty()) {
                    ScriptEngine.installScript(ctx, content)
                    Toast.makeText(ctx, "脚本安装成功", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(ctx, errorMsg ?: "下载失败", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showOpenExternalDialog(url: String) {
        try {
            val uri = Uri.parse(url)
            val intent = Intent(Intent.ACTION_VIEW, uri)

            val pm = packageManager
            val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            } else {
                @Suppress("DEPRECATION")
                pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }

            val appName = if (resolveInfo != null) {
                resolveInfo.loadLabel(pm).toString()
            } else {
                uri.host ?: uri.scheme ?: "未知应用"
            }

            AlertDialog.Builder(this)
                .setTitle("外部应用跳转")
                .setMessage("${appName} 想要打开外部应用")
                .setPositiveButton("允许") { _, _ ->
                    try {
                        startActivity(intent)
                    } catch (_: Exception) {
                        Toast.makeText(this, "无法打开此链接", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("禁止", null)
                .show()
        } catch (_: Exception) {
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 200) {
            fileChooserCallback?.onReceiveValue(
                if (resultCode == RESULT_OK && data != null) arrayOf(data.data!!) else null
            )
            fileChooserCallback = null
        }
    }

    private fun switchTab(index: Int) {
        if (index < 0 || index >= tabs.size) return
        for (i in tabs.indices) {
            tabs[i].engine.getView().visibility = View.GONE
        }
        currentTabIndex = index
        val tab = tabs[index]
        tab.engine.getView().visibility = View.VISIBLE
        if (tab.engine.getView().parent == null) {
            container.addView(tab.engine.getView())
        } else {
            tab.engine.getView().bringToFront()
        }
        val url = tab.currentUrl ?: tab.engine.getCurrentUrl()
        if (url == "about:blank") {
            etUrl.setText("")
        } else if (tab.isTitleSet && tab.currentTitle != null) {
            etUrl.setText(tab.currentTitle)
        } else if (url != null) {
            etUrl.setText(url)
        }
        updateBookmarkIcon()
        updateNavButtons()
        updateTabsButton()
        updateGoButtonVisibility()
    }

    private fun closeTab(index: Int) {
        val tab = tabs.removeAt(index)
        container.removeView(tab.engine.getView())
        tab.engine.stopLoading()
        tab.engine.destroy()

        if (tabs.isEmpty()) {
            newTab("about:blank", isIncognitoMode)
            currentTabIndex = 0
            switchTab(0)
            updateTabsButton()
            return
        }

        if (index <= currentTabIndex) {
            if (currentTabIndex > 0) {
                currentTabIndex--
            }
        }
        if (currentTabIndex >= tabs.size) {
            currentTabIndex = tabs.size - 1
        }
        switchTab(currentTabIndex)
        updateTabsButton()
    }

    private fun updateTabsButton() {
        val tabCount = tabs.size
        btnTabs.contentDescription = "标签($tabCount)"

        tabBadge?.let { tabsContainer?.removeView(it) }
        tabBadge = null

        if (tabCount > 1) {
            val badge = TextView(this).apply {
                text = if (tabCount > 99) "99+" else tabCount.toString()
                textSize = 11f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setBackgroundColor(Color.RED)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    topMargin = 0
                    rightMargin = 0
                }
                setPadding(6, 2, 6, 2)
                includeFontPadding = false
            }
            tabsContainer?.addView(badge)
            tabBadge = badge
        }
    }

    private fun updateBookmarkIcon() {
        val tab = tabs.getOrNull(currentTabIndex) ?: return
        val url = tab.currentUrl
        if (url != null && url != "about:blank" && BookmarkManager.isBookmarked(this, url)) {
            btnBookmark.setImageResource(R.drawable.ic_bookmark_adaptive)
        } else {
            btnBookmark.setImageResource(R.drawable.ic_bookmark_border_adaptive)
        }
    }

    private fun updateNavButtons() {
        val tab = tabs.getOrNull(currentTabIndex)
        btnBack.isEnabled = tab?.engine?.canGoBack() ?: false
        btnForward.isEnabled = tab?.engine?.canGoForward() ?: false
    }

    private fun loadUrl(input: String) {
        val engine = getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("search_engine", "baidu") ?: "baidu"
        val url = WebUtils.normalizeUrl(input, engine)
        if (url.isNotEmpty()) {
            val tab = tabs.getOrNull(currentTabIndex)
            if (tab != null) {
                tab.engine.loadUrl(url)
                etUrl.clearFocus()
            }
        }
    }

    private fun showLicenseDialog() {
        val scrollView = ScrollView(this)
        val textView = TextView(this).apply {
            text = LicenseText.fullText + "\n\n" + LicenseText.privacyText
            textSize = 13f
            setLineSpacing(4f, 1f)
            setPadding(32, 16, 32, 16)
        }
        scrollView.addView(textView)

        AlertDialog.Builder(this)
            .setTitle("用户协议与隐私政策")
            .setView(scrollView)
            .setPositiveButton("同意并继续") { _, _ ->
                getSharedPreferences("settings", Context.MODE_PRIVATE)
                    .edit().putBoolean("agreed_license", true).apply()
                initMainContent()
            }
            .setNegativeButton("不同意") { _, _ ->
                finishAffinity()
            }
            .setCancelable(false)
            .show()
    }

    private fun handleIntent(intent: Intent?) {
        val url = intent?.getStringExtra("load_url")
        if (url != null && url.isNotEmpty()) {
            val tab = tabs.getOrNull(currentTabIndex)
            if (tab != null) {
                etUrl.setText(url)
                tab.engine.loadUrl(url)
                intent.removeExtra("load_url")
            } else {
                newTab(url, isIncognitoMode)
                switchTab(tabs.size - 1)
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIntent(it) }
    }

    override fun onBackPressed() {
        val tab = tabs.getOrNull(currentTabIndex)
        if (tab != null && tab.engine.canGoBack()) {
            tab.engine.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        for (tab in tabs) {
            tab.engine.stopLoading()
            tab.engine.destroy()
        }
        tabs.clear()
        onDownloadComplete?.let { unregisterReceiver(it) }
        super.onDestroy()
    }

    private fun isDownloadUrl(url: String): Boolean {
        val lower = url.lowercase()
        val extensions = arrayOf(".apk", ".zip", ".rar", ".pdf", ".doc", ".docx", ".xls", ".xlsx",
            ".ppt", ".pptx", ".mp3", ".mp4", ".avi", ".mkv", ".torrent", ".7z")
        for (ext in extensions) {
            if (lower.contains(ext)) return true
        }
        return false
    }

    private fun handleDownloadWithPermission(url: String) {
        if (pendingDownloadUrl == url) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            downloadFile(url)
            return
        }
        if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            downloadFile(url)
        } else {
            pendingDownloadUrl = url
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                AlertDialog.Builder(this)
                    .setTitle("需要存储权限")
                    .setMessage("浏览器需要存储权限来下载文件到您的设备。")
                    .setPositiveButton("去授权") { _, _ ->
                        requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 100)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            } else {
                requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 100)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                pendingDownloadUrl?.let { downloadFile(it) }
                pendingDownloadUrl = null
            } else {
                AlertDialog.Builder(this)
                    .setTitle("缺少存储权限")
                    .setMessage("无法下载文件，请在设置中授予存储权限。")
                    .setPositiveButton("去设置") { _, _ ->
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        intent.data = Uri.parse("package:$packageName")
                        startActivity(intent)
                    }
                    .setNegativeButton("取消", null)
                    .show()
                pendingDownloadUrl = null
            }
        } else if (requestCode == 300) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                pendingPermissionRequest?.grant(pendingPermissionRequest?.resources ?: emptyArray())
            } else {
                pendingPermissionRequest?.deny()
            }
            pendingPermissionRequest = null
        } else if (requestCode == 400) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pendingGeolocationCallback?.invoke(pendingGeolocationOrigin, true, false)
            } else {
                pendingGeolocationCallback?.invoke(pendingGeolocationOrigin, false, false)
            }
            pendingGeolocationOrigin = null
            pendingGeolocationCallback = null
        }
    }

    private fun downloadFile(url: String) {
        try {
            val fileName = URLUtil.guessFileName(url, null, null) ?: "download"
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(fileName)
                setDescription("正在下载")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                val cookie = CookieManager.getInstance().getCookie(url)
                if (cookie != null) {
                    addRequestHeader("Cookie", cookie)
                }
            }
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, "开始下载", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "下载失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun registerDownloadReceiver() {
        onDownloadComplete = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Toast.makeText(this@MainActivity, "下载完成", Toast.LENGTH_SHORT).show()
            }
        }
        registerReceiver(onDownloadComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
    }
}

class ClipboardBridge(private val context: Context) {
    @JavascriptInterface
    fun onCopy(host: String) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("clipboard_alert", true)) return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, "${host} 已写入剪切板", Toast.LENGTH_SHORT).show()
        }
    }
}

class GMBridge(context: Context) {
    private val appContext = context.applicationContext
    private val maxStorageSize = 1024 * 1024L

    @JavascriptInterface
    fun setValue(scriptId: String, key: String, value: String): Boolean {
        val prefs = appContext.getSharedPreferences("scripts_storage_$scriptId", Context.MODE_PRIVATE)
        val currentSize = estimateStorageSize(prefs)
        val newEntrySize = key.toByteArray().size + value.toByteArray().size
        if (currentSize + newEntrySize > maxStorageSize) {
            return false
        }
        prefs.edit().putString(key, value).apply()
        return true
    }

    @JavascriptInterface
    fun getValue(scriptId: String, key: String, defaultValue: String): String {
        val prefs = appContext.getSharedPreferences("scripts_storage_$scriptId", Context.MODE_PRIVATE)
        return prefs.getString(key, defaultValue) ?: defaultValue
    }

    private fun estimateStorageSize(prefs: android.content.SharedPreferences): Long {
        var size = 0L
        for (entry in prefs.all) {
            size += entry.key.toByteArray().size
            size += (entry.value as? String)?.toByteArray()?.size ?: 0
        }
        return size
    }
}

data class TabInfo(
    val engine: WebViewEngine,
    var currentUrl: String? = null,
    var currentTitle: String? = null,
    var isTitleSet: Boolean = false,
    val isIncognito: Boolean = false,
    var hasHistoryRecorded: Boolean = false
)

object WebUtils {
    fun normalizeUrl(input: String, engine: String = "baidu"): String {
        if (input.isBlank()) return ""
        val trimmed = input.trim()
        return when {
            trimmed == "about:blank" -> "about:blank"
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.contains(".") && !trimmed.contains(" ") -> "https://$trimmed"
            else -> {
                val query = URLEncoder.encode(trimmed, "UTF-8")
                when (engine) {
                    "google" -> "https://www.google.com/search?q=$query"
                    "bing" -> "https://www.bing.com/search?q=$query"
                    "duckduckgo" -> "https://duckduckgo.com/?q=$query"
                    "sogou" -> "https://www.sogou.com/web?query=$query"
                    "360" -> "https://www.so.com/s?q=$query"
                    "sm" -> "https://m.sm.cn/s?q=$query"
                    "yandex" -> "https://yandex.com/search/?text=$query"
                    "ecosia" -> "https://www.ecosia.org/search?q=$query"
                    else -> "https://www.baidu.com/s?wd=$query"
                }
            }
        }
    }
}