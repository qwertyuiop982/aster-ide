package com.liulanqi.app

import android.view.View

interface BrowserEngine {
    fun loadUrl(url: String)
    fun goBack()
    fun goForward()
    fun reload()
    fun stopLoading()
    fun canGoBack(): Boolean
    fun canGoForward(): Boolean
    fun getCurrentUrl(): String?
    fun getCurrentTitle(): String?
    fun getProgress(): Int
    fun evaluateJavascript(script: String, callback: (String) -> Unit)
    fun addJavascriptInterface(obj: Any, name: String)
    fun setDownloadListener(listener: (url: String) -> Unit)
    fun setOnPageStarted(callback: (url: String) -> Unit)
    fun setOnPageFinished(callback: (url: String) -> Unit)
    fun setOnTitleReceived(callback: (title: String) -> Unit)
    fun setOnProgressChanged(callback: (progress: Int) -> Unit)
    fun setOnSslError(callback: (url: String, error: String, proceed: () -> Unit, cancel: () -> Unit) -> Unit)
    fun setOnPermissionRequest(callback: (origin: String, resources: List<String>, grant: () -> Unit, deny: () -> Unit) -> Unit)
    fun setOnFileChooser(callback: (callback: (Array<android.net.Uri>?) -> Unit, intent: android.content.Intent?) -> Unit)
    fun getView(): View
    fun destroy()
}