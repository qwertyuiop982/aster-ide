package com.liulanqi.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.view.View
import android.webkit.*
import android.content.Intent

class WebViewEngine(context: Context) : BrowserEngine {

    val webView: WebView = WebView(context).apply {
        id = View.generateViewId()
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.setSupportZoom(true)
        settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; en-US; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/117.0.5938.60 Mobile Safari/537.36"
    }

    private var onPageStartedCallback: ((String) -> Unit)? = null
    private var onPageFinishedCallback: ((String) -> Unit)? = null
    private var onTitleReceivedCallback: ((String) -> Unit)? = null
    private var onProgressChangedCallback: ((Int) -> Unit)? = null
    private var onSslErrorCallback: ((String, String, () -> Unit, () -> Unit) -> Unit)? = null
    private var onPermissionRequestCallback: ((String, List<String>, () -> Unit, () -> Unit) -> Unit)? = null
    private var onFileChooserCallback: (((Array<Uri>?) -> Unit, Intent?) -> Unit)? = null
    private var downloadListener: ((String) -> Unit)? = null

    init {
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                url?.let { onPageStartedCallback?.invoke(it) }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                url?.let { onPageFinishedCallback?.invoke(it) }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                val msg = when (error?.primaryError) {
                    SslError.SSL_EXPIRED -> "证书已过期"
                    SslError.SSL_IDMISMATCH -> "证书不匹配"
                    SslError.SSL_UNTRUSTED -> "证书不受信任"
                    SslError.SSL_DATE_INVALID -> "证书日期无效"
                    SslError.SSL_INVALID -> "证书无效"
                    else -> "证书错误"
                }
                val origin = try { Uri.parse(view?.url).host ?: "该网站" } catch (_: Exception) { "该网站" }
                onSslErrorCallback?.invoke(origin, msg, { handler?.proceed() }, { handler?.cancel() })
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    view?.loadUrl(url)
                }
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                title?.let { onTitleReceivedCallback?.invoke(it) }
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                onProgressChangedCallback?.invoke(newProgress)
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.let {
                    val resources = it.resources.map { res ->
                        when (res) {
                            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> "摄像头"
                            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> "麦克风"
                            else -> res
                        }
                    }
                    val origin = it.origin.host ?: "该网站"
                    onPermissionRequestCallback?.invoke(
                        origin, resources,
                        { it.grant(it.resources) },
                        { it.deny() }
                    )
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                val intent = fileChooserParams?.createIntent()
                onFileChooserCallback?.invoke(
                    { uris -> filePathCallback?.onReceiveValue(uris) },
                    intent
                )
                return true
            }
        }
    }

    override fun loadUrl(url: String) = webView.loadUrl(url)
    override fun goBack() = webView.goBack()
    override fun goForward() = webView.goForward()
    override fun reload() = webView.reload()
    override fun stopLoading() = webView.stopLoading()
    override fun canGoBack() = webView.canGoBack()
    override fun canGoForward() = webView.canGoForward()
    override fun getCurrentUrl() = webView.url
    override fun getCurrentTitle() = webView.title
    override fun getProgress() = webView.progress
    override fun evaluateJavascript(script: String, callback: (String) -> Unit) {
        webView.evaluateJavascript(script) { result -> callback(result) }
    }
    override fun addJavascriptInterface(obj: Any, name: String) {
        webView.addJavascriptInterface(obj, name)
    }
    override fun setDownloadListener(listener: (url: String) -> Unit) {
        downloadListener = listener
        webView.setDownloadListener { url, _, _, _, _ -> listener(url) }
    }
    override fun setOnPageStarted(callback: (url: String) -> Unit) { onPageStartedCallback = callback }
    override fun setOnPageFinished(callback: (url: String) -> Unit) { onPageFinishedCallback = callback }
    override fun setOnTitleReceived(callback: (title: String) -> Unit) { onTitleReceivedCallback = callback }
    override fun setOnProgressChanged(callback: (progress: Int) -> Unit) { onProgressChangedCallback = callback }
    override fun setOnSslError(callback: (url: String, error: String, proceed: () -> Unit, cancel: () -> Unit) -> Unit) { onSslErrorCallback = callback }
    override fun setOnPermissionRequest(callback: (origin: String, resources: List<String>, grant: () -> Unit, deny: () -> Unit) -> Unit) { onPermissionRequestCallback = callback }
    override fun setOnFileChooser(callback: (callback: (Array<Uri>?) -> Unit, intent: android.content.Intent?) -> Unit) { onFileChooserCallback = callback }
    override fun getView() = webView
    override fun destroy() {
        webView.stopLoading()
        webView.destroy()
    }
}