package dev.aster.client.browser

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import kotlin.math.roundToInt

class BrowserActivity : Activity() {
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val languageTag = intent.getStringExtra(BrowserRuntime.LANGUAGE_TAG)
        val strings = if (Build.VERSION.SDK_INT < 33 && !languageTag.isNullOrBlank()) {
            createConfigurationContext(Configuration().apply {
                setLocales(LocaleList.forLanguageTags(languageTag))
            })
        } else this
        webView = createBrowserWebView(this)
        val address = EditText(this).apply {
            setSingleLine(true)
            hint = strings.getString(R.string.browser_address_hint)
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setOnEditorActionListener { _, _, _ ->
                val url = text.toString().trim()
                if (BrowserRuntime.isWebUrl(url)) webView.loadUrl(url)
                else error = strings.getString(R.string.browser_invalid_address)
                true
            }
        }
        webView.webViewClient = object : HttpWebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                address.setText(url)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                address.setText(url)
            }
        }
        val toolbar = LinearLayout(this).apply {
            addView(control(strings, R.string.browser_back, android.R.drawable.ic_media_previous) {
                if (webView.canGoBack()) webView.goBack()
            })
            addView(address, LinearLayout.LayoutParams(0, controlSize(), 1f))
            addView(control(strings, R.string.browser_reload, android.R.drawable.ic_popup_sync) { webView.reload() })
            addView(control(strings, R.string.browser_close, android.R.drawable.ic_menu_close_clear_cancel) { finish() })
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar)
            addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl("https://example.com")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    private fun control(strings: Context, label: Int, image: Int, action: () -> Unit) = ImageButton(this).apply {
        contentDescription = strings.getString(label)
        setImageResource(image)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(controlSize(), controlSize())
    }

    private fun controlSize() = (48 * resources.displayMetrics.density).roundToInt()
}
