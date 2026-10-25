package dev.aster.client.browser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import com.liulanqi.app.WebViewEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID

/** Embedded Qingzhou engine; external pages receive no native JS interface. */
class BrowserActivity : Activity() {
    lateinit var engine: WebViewEngine
        private set
    private lateinit var address: EditText
    var documentId: String = UUID.randomUUID().toString()
        internal set
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = WebViewEngine(this)
        engine.webView.settings.apply {
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = android.webkit.WebSettings.getDefaultUserAgent(this@BrowserActivity)
        }
        engine.setOnSslError { _, _, _, cancel -> cancel() }
        engine.setOnPermissionRequest { _, _, _, deny -> deny() }
        engine.setOnFileChooser { callback, _ -> callback(null) }
        engine.setOnPageStarted { documentId = UUID.randomUUID().toString(); address.setText(it) }
        engine.setOnPageFinished { address.setText(it) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = LinearLayout(this)
        fun control(label: String, image: Int, action: () -> Unit) = ImageButton(this).apply {
            contentDescription = label; setImageResource(image); setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(48.dp(), 48.dp())
        }
        toolbar.addView(control("Back", android.R.drawable.ic_media_previous) { if (engine.canGoBack()) engine.goBack() })
        address = EditText(this).apply {
            setSingleLine(true); hint = "https://"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setOnEditorActionListener { _, _, _ -> runCatching { BrowserRuntime.validateUrl(text.toString()); engine.loadUrl(text.toString()) }.onFailure { error = it.message }; true }
        }
        toolbar.addView(address, LinearLayout.LayoutParams(0, 48.dp(), 1f))
        toolbar.addView(control("Reload", android.R.drawable.ic_popup_sync) { engine.reload() })
        toolbar.addView(control("Close", android.R.drawable.ic_menu_close_clear_cancel) { finish() })
        root.addView(toolbar)
        root.addView(engine.getView(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        BrowserRuntime.activity = this
        val url = intent.getStringExtra("url") ?: "https://example.com"
        runCatching { BrowserRuntime.validateUrl(url); engine.loadUrl(url) }
    }
    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
    override fun onDestroy() { if (BrowserRuntime.activity === this) BrowserRuntime.activity = null; engine.destroy(); super.onDestroy() }
}

object BrowserRuntime {
    @Volatile internal var activity: BrowserActivity? = null
    fun validateUrl(url: String) {
        val uri = Uri.parse(url)
        require(url.length <= 4096 && uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null) { "Only HTTP(S) URLs without embedded credentials are supported" }
    }
    fun open(context: Context) { context.startActivity(Intent(context, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    suspend fun execute(context: Context, operation: String, args: JSONObject): JSONObject = withContext(Dispatchers.Main) {
        if (operation == "open") {
            val url = args.getString("url"); validateUrl(url)
            val current = activity
            if (current == null) context.startActivity(Intent(context, BrowserActivity::class.java).putExtra("url", url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            else current.engine.loadUrl(url)
            return@withContext JSONObject().put("ok", true).put("loading", true).put("backend", "qingzhou")
        }
        val current = activity ?: error("Open the embedded Qingzhou browser first")
        when (operation) {
            "back" -> { if (current.engine.canGoBack()) current.engine.goBack(); JSONObject().put("ok", true) }
            "snapshot" -> {
                current.documentId = UUID.randomUUID().toString()
                val script = """(() => { const elements=Array.from(document.querySelectorAll('a,button,input,textarea,select,[role=button]')).filter(e=>e.getClientRects().length).slice(0,200); window.__asterElements=elements; return {url:location.href,title:document.title,text:document.body.innerText.slice(0,18000),elements:elements.map((e,i)=>({id:i,tag:e.tagName,text:(e.innerText||e.getAttribute('aria-label')||e.getAttribute('placeholder')||'').slice(0,200),type:e.type||'',bounds:{x:e.getBoundingClientRect().x,y:e.getBoundingClientRect().y,width:e.getBoundingClientRect().width,height:e.getBoundingClientRect().height}}))}; })()"""
                evaluate(current, script).put("snapshotId", current.documentId).put("ok", true).put("backend", "qingzhou")
            }
            "click", "fill" -> {
                require(args.getString("snapshotId") == current.documentId) { "Page changed; take a fresh browser snapshot" }
                val id = args.getInt("elementId"); require(id in 0..199)
                val operationJs = if (operation == "click") "e.click();" else {
                    val text = args.getString("text"); require(text.length <= 12000)
                    "if(!['INPUT','TEXTAREA'].includes(e.tagName)||e.type==='file')throw Error('Not a text field'); const proto=e.tagName==='INPUT'?HTMLInputElement.prototype:HTMLTextAreaElement.prototype;Object.getOwnPropertyDescriptor(proto,'value').set.call(e,${JSONObject.quote(text)});e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));"
                }
                evaluate(current, "(() => {try { const e=(window.__asterElements||[])[$id]; if(!e||!e.isConnected||!e.getClientRects().length||e.disabled)throw Error('Element unavailable; refresh snapshot'); $operationJs return {ok:true}; } catch(e){return {ok:false,error:e.message};}})()")
            }
            else -> error("Unsupported browser operation")
        }
    }
    private suspend fun evaluate(activity: BrowserActivity, script: String): JSONObject {
        val result = CompletableDeferred<String>()
        activity.engine.evaluateJavascript(script) { result.complete(it) }
        val value = JSONTokener(withTimeout(8000) { result.await() }).nextValue()
        return value as? JSONObject ?: error("Browser did not return a structured result")
    }
}
