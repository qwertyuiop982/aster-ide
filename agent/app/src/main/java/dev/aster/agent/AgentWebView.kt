package dev.aster.agent

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.annotation.Keep
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

private const val PAGE = AgentWebAssets.PAGE
internal fun trustedAgentAsset(uri: Uri): String? = AgentWebAssets.resolve(uri.toString())

@Keep
class AgentJavascriptBridge(private val token: String, private val receive: (String) -> Unit,
                            private val read: (String, String) -> String = { _, _ -> "" }) {
    @JavascriptInterface fun bootstrapToken(): String = token
    @JavascriptInterface fun postMessage(value: String) { if (value.length <= 80000) receive(value) }
    @JavascriptInterface fun readUiState(providedToken: String, after: String): String = read(providedToken, after)
}

@SuppressLint("SetJavaScriptEnabled")
private class AgentPage(context: Context, private val onRequest: (JSONObject) -> Unit,
                        private val onState: (AgentWebState) -> Unit, private val diagnostic: (String) -> Unit) : WebView(context) {
    private val token = UUID.randomUUID().toString()
    private val mailbox = AgentUiMailbox(token)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var attached = true
    @Volatile private var trustedDocument = false
    private var begun = false
    private var scriptsReady = false
    private var painted = false
    private var failed = false
    private val resources = AtomicInteger()
    private val timeout = Runnable {
        if (attached && !painted) fail(if (!scriptsReady) "界面启动超时：网页脚本未就绪。已读取 ${resources.get()} 个本地资源；WebView ${width}×${height}。" else "界面启动超时：脚本就绪但未收到非空首屏布局。WebView ${width}×${height}。")
    }
    init {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.mediaPlaybackRequiresUserGesture = true
        isSaveEnabled = false
        diagnostic("WebView user agent: ${settings.userAgentString}")
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            WebView.getCurrentWebViewPackage()?.let { diagnostic("WebView provider: ${it.packageName} ${it.versionName}") }
        }
        addJavascriptInterface(AgentJavascriptBridge(token, { value ->
            val request = runCatching { JSONObject(value) }.getOrNull()
            if (request != null && request.optString("token") == token) main.post {
                if (attached && url == PAGE) {
                    if (request.optString("command") == "web_boot" && request.optInt("version") == 1) {
                        when (request.optString("phase")) {
                            "ready" -> if (!failed) { scriptsReady = true; diagnostic("UI scripts ready; awaiting authenticated snapshot pull") }
                            "rendered" -> if (scriptsReady && !failed && width > 0 && height > 0 &&
                                mailbox.wasRead(request.optLong("revision", 0)) &&
                                request.optJSONObject("layout")?.let { layout ->
                                    listOf("width", "height", "conversationHeight", "inputHeight").all { layout.optDouble(it, 0.0) > 0.0 }
                                } == true) {
                                painted = true; main.removeCallbacks(timeout)
                                diagnostic("UI first frame: ${request.optString("message")} / native ${width}×${height}")
                                onState(AgentWebState(AgentWebPhase.READY, "Agent 界面已显示"))
                            }
                            "error" -> fail(request.optString("message", "界面脚本失败").take(1200))
                        }
                    } else if (scriptsReady && painted && !failed) onRequest(request)
                }
            }
        }, { key, after -> if (attached && trustedDocument) mailbox.read(key, after) else "" }), "AsterNative")
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !request.isForMainFrame || request.url.toString() != PAGE
            @Deprecated("Legacy WebView callback")
            override fun shouldOverrideUrlLoading(view: WebView, url: String) = url != PAGE
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val asset = if (request.method == "GET") trustedAgentAsset(request.url) else null
                if (asset == null) return response(403, "Blocked")
                val mime = when { asset.endsWith(".js") -> "text/javascript"; asset.endsWith(".css") -> "text/css"; else -> "text/html" }
                return try {
                    val input = context.assets.open("agent/$asset")
                    resources.incrementAndGet()
                    main.post { if (attached) diagnostic("Local UI resource: $asset") }
                    WebResourceResponse(mime, "UTF-8", 200, "OK", AgentWebAssets.headers, input)
                } catch (error: Exception) {
                    main.post { fail("无法读取界面资源 $asset：${error.message}") }
                    response(500, "Missing asset")
                }
            }
            override fun onPageStarted(view: WebView, url: String, icon: android.graphics.Bitmap?) {
                trustedDocument = url == PAGE
                scriptsReady = false; painted = false; failed = false
                diagnostic("UI page started: $url")
                onState(AgentWebState()); main.removeCallbacks(timeout); main.postDelayed(timeout, 15000)
            }
            override fun onPageCommitVisible(view: WebView, url: String) { if (attached) diagnostic("WebView page commit visible; native ${width}×${height}") }
            override fun onPageFinished(view: WebView, url: String) {
                if (attached) diagnostic("UI document loaded; scriptsReady=$scriptsReady; native ${width}×${height}")
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame || trustedAgentAsset(request.url) != null) fail("界面资源加载失败 (${error.errorCode})：${error.description}")
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame || trustedAgentAsset(request.url) != null) fail("界面资源响应失败：HTTP ${errorResponse.statusCode}")
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel(); fail("界面地址加载失败：TLS ${error.primaryError}")
            }
            @androidx.annotation.RequiresApi(26)
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                fail("WebView 渲染进程已退出，请重新加载界面。")
                release(); return true
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    diagnostic("JavaScript: ${message.message().take(1000)} @ ${message.sourceId()}:${message.lineNumber()}")
                    if (message.message().startsWith("Uncaught")) fail(message.message().take(1000))
                }
                return true
            }
        }
        setDownloadListener { _, _, _, _, _ -> }
    }
    fun begin() { if (attached && !begun) { begun = true; diagnostic("Loading local Agent page after native view creation"); main.postDelayed(timeout, 15000); loadUrl(PAGE) } }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) diagnostic("WebView measured $w×$h")
    }
    private fun response(code: Int, reason: String) = WebResourceResponse("text/plain", "UTF-8", code, reason, emptyMap(), ByteArrayInputStream(ByteArray(0)))
    private fun fail(message: String) {
        if (!attached || failed) return
        failed = true; main.removeCallbacks(timeout)
        diagnostic(message); onState(AgentWebState(AgentWebPhase.FAILED, message))
    }
    fun closePanel() { if (scriptsReady && attached) evaluateJavascript("window.AsterClosePanel && window.AsterClosePanel();", null) }
    fun display(value: String, dark: Boolean) {
        if (!attached) return
        setBackgroundColor(if (dark) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        if (!mailbox.offer(value) && value != "{}" && !failed) fail("页面状态缺少有效工作区、会话或底层引擎，或超过界面容量；请复制启动诊断。")
    }
    fun release() {
        if (!attached) return
        attached = false; trustedDocument = false; scriptsReady = false; main.removeCallbacksAndMessages(null)
        removeJavascriptInterface("AsterNative"); stopLoading()
        (parent as? ViewGroup)?.removeView(this)
        destroy()
    }
}

@Composable fun AgentWebView(state: String, onRequest: (JSONObject) -> Unit, modifier: Modifier = Modifier,
                            panelOpen: Boolean = false, dark: Boolean = false,
                            onState: (AgentWebState) -> Unit = {}, onDiagnostic: (String) -> Unit = {}) {
    val request by rememberUpdatedState(onRequest)
    val status by rememberUpdatedState(onState)
    val diagnostic by rememberUpdatedState(onDiagnostic)
    var allowCreation by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf<AgentPage?>(null) }
    LaunchedEffect(Unit) { withFrameNanos { }; allowCreation = true }
    BackHandler(enabled = panelOpen) { page?.closePanel() }
    DisposableEffect(Unit) { onDispose { page?.release() } }
    if (allowCreation) AndroidView(factory = { context ->
        runCatching {
            AgentPage(context, { request(it) }, { status(it) }, { diagnostic(it) }).also { created ->
                page = created
                created.display(state, dark)
                created.post { created.begin() }
            }
        }.getOrElse { error ->
            val message = "无法启动系统 WebView：${error.message ?: "未知错误"}"
            Handler(Looper.getMainLooper()).post { diagnostic(message); status(AgentWebState(AgentWebPhase.FAILED, message)) }
            android.widget.TextView(context).apply { text = message }
        }
    }, update = { (it as? AgentPage)?.display(state, dark) }, modifier = modifier)
}
