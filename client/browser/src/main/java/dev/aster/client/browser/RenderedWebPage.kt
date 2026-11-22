package dev.aster.client.browser

import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.view.View
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume
import kotlin.math.roundToInt

internal fun createBrowserWebView(context: Context, background: Boolean = false) = WebView(context).apply {
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        mediaPlaybackRequiresUserGesture = true
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        setSupportMultipleWindows(false)
        javaScriptCanOpenWindowsAutomatically = false
        useWideViewPort = true
        loadWithOverviewMode = true
        setGeolocationEnabled(false)
    }
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
    if (background) {
        webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()

            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                result.confirm()
                return true
            }

            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                result.cancel()
                return true
            }

            override fun onJsPrompt(
                view: WebView, url: String, message: String, defaultValue: String, result: JsPromptResult,
            ): Boolean {
                result.cancel()
                return true
            }

            override fun onJsBeforeUnload(view: WebView, url: String, message: String, result: JsResult): Boolean {
                result.confirm()
                return true
            }
        }
    }
}

internal open class HttpWebViewClient(private val allowNavigation: (String) -> Boolean = BrowserRuntime::isWebUrl) :
    WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
        request.isForMainFrame && !allowNavigation(request.url.toString())

    @Suppress("DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView, url: String) = !allowNavigation(url)

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) = handler.cancel()
}

internal class RenderedWebPage(
    context: Context,
    private val allowNavigation: (String) -> Boolean = BrowserRuntime::isWebUrl,
    desktopViewport: Boolean = false,
) {
    private val webView = createBrowserWebView(context.applicationContext, background = true)
    private var pending = false
    private var startedUrl: String? = null
    private var loadError: String? = null
    private var currentUrl: String? = null
    private var rendererGone = false
    private var destroyed = false
    var navigationVersion: Int = 0
        private set

    init {
        val density = context.resources.displayMetrics.density
        val width = ((if (desktopViewport) 1280 else 430) * density).roundToInt()
        val height = ((if (desktopViewport) 900 else 820) * density).roundToInt()
        if (desktopViewport) {
            val engineVersion = Regex("Chrome/([\\d.]+)").find(webView.settings.userAgentString)?.groupValues?.get(1)
            if (engineVersion != null) {
                webView.settings.userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/$engineVersion Safari/537.36"
            }
        }
        // A detached WebView needs an explicit viewport for media queries and element bounds.
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        webView.layout(0, 0, width, height)
        webView.webViewClient = object : HttpWebViewClient(allowNavigation) {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                beginNavigation()
                startedUrl = url
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                val previous = currentUrl
                currentUrl = url
                if (previous != url) navigationVersion++
                if (startedUrl == null && previous != null &&
                    previous != url && previous.substringBefore('#') == url.substringBefore('#')) {
                    pending = false
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (url == startedUrl || url == view.url) {
                    pending = false
                    currentUrl = url
                    startedUrl = null
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val blocked = super.shouldOverrideUrlLoading(view, request)
                if (blocked && pending) failNavigation("The page redirected outside the allowed URLs")
                return blocked
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) failNavigation(error.description.toString())
            }

            override fun onReceivedHttpError(
                view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse,
            ) {
                if (request.isForMainFrame) failNavigation("HTTP ${errorResponse.statusCode}")
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                super.onReceivedSslError(view, handler, error)
                if (error.url == startedUrl || error.url == view.url) failNavigation("TLS certificate error")
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                rendererGone = true
                failNavigation("The WebView renderer stopped")
                close()
                return true
            }
        }
    }

    val isClosed: Boolean get() = destroyed

    fun load(url: String) {
        require(allowNavigation(url)) { "Only HTTP(S) URLs without embedded credentials are supported" }
        beginNavigation()
        webView.loadUrl(url)
    }

    fun goBack(): Boolean {
        if (!webView.canGoBack()) return false
        beginNavigation()
        webView.goBack()
        return true
    }

    suspend fun awaitReady() {
        val ready = withTimeoutOrNull(15_000) {
            while (pending) delay(100)
            true
        }
        check(ready == true) { "Timed out waiting for the WebView page to load" }
        check(loadError == null) { loadError.orEmpty() }
        check(!destroyed) { "The WebView is closed" }
    }

    suspend fun evaluate(script: String): JSONObject? {
        check(!destroyed) { "The WebView is closed" }
        return withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine { continuation ->
                webView.evaluateJavascript(script) { result ->
                    if (continuation.isActive) {
                        continuation.resume(runCatching { JSONTokener(result).nextValue() as? JSONObject }.getOrNull())
                    }
                }
            }
        }
    }

    fun close() {
        if (destroyed) return
        destroyed = true
        if (!rendererGone) webView.stopLoading()
        webView.destroy()
    }

    private fun beginNavigation() {
        pending = true
        startedUrl = null
        loadError = null
        navigationVersion++
    }

    private fun failNavigation(error: String) {
        pending = false
        loadError = error
        navigationVersion++
    }
}
