package dev.aster.client.shizuku

import android.app.Activity
import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.LocaleList
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import rikka.shizuku.Shizuku

object ShizukuClient {
    fun status(): JSONObject {
        val alive = Shizuku.pingBinder()
        val authorized = alive && runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        return JSONObject().put("available", alive).put("authorized", authorized)
    }

    suspend fun <T> useService(context: Context, action: (IDeviceService) -> T): T {
        check(status().optBoolean("authorized")) { "Start Shizuku and authorize Aster in Device permissions" }
        val ready = CompletableDeferred<IDeviceService>()
        val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, DeviceService::class.java.name))
            .daemon(false).processNameSuffix("aster_device").version(2)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service != null) ready.complete(IDeviceService.Stub.asInterface(service))
                else ready.completeExceptionally(IllegalStateException("Shizuku returned no service"))
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                ready.completeExceptionally(IllegalStateException("Shizuku disconnected"))
            }
        }
        try {
            withContext(Dispatchers.Main) { Shizuku.bindUserService(args, connection) }
            val service = withTimeout(10000) { ready.await() }
            return withContext(Dispatchers.IO) { action(service) }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                runCatching { Shizuku.unbindUserService(args, connection, true) }
            }
        }
    }
}

class PermissionActivity : Activity() {
    private lateinit var strings: Context
    private var prompt: Dialog? = null
    private val listener = Shizuku.OnRequestPermissionResultListener { _, result ->
        runOnUiThread {
            val message = if (result == PackageManager.PERMISSION_GRANTED) {
                R.string.shizuku_authorized
            } else R.string.shizuku_permission_denied
            show(strings.getString(message))
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val languageTag = intent.getStringExtra(LANGUAGE_TAG).orEmpty()
        strings = if (Build.VERSION.SDK_INT < 33 && languageTag.isNotEmpty()) {
            createConfigurationContext(Configuration().apply { setLocales(LocaleList.forLanguageTags(languageTag)) })
        } else this
        Shizuku.addRequestPermissionResultListener(listener)
        val state = ShizukuClient.status()
        when {
            !state.optBoolean("available") -> show(strings.getString(R.string.shizuku_unavailable))
            state.optBoolean("authorized") -> show(strings.getString(R.string.shizuku_authorized))
            else -> show(strings.getString(R.string.shizuku_permission_prompt), strings.getString(R.string.shizuku_authorize), cancel = true) {
                runCatching { Shizuku.requestPermission(4101) }.onFailure {
                    show(it.message ?: strings.getString(R.string.shizuku_request_failed))
                }
            }
        }
    }

    private fun show(message: String, actionLabel: String = strings.getString(R.string.shizuku_ok), cancel: Boolean = false, action: () -> Unit = { finish() }) {
        if (isFinishing || isDestroyed) return
        prompt?.dismiss()
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density + .5f).toInt()
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val background = Color.parseColor(if (dark) "#202523" else "#F5F8F7")
        val ink = Color.parseColor(if (dark) "#F1F6F4" else "#1B2622")
        val accent = Color.parseColor(if (dark) "#83D7BF" else "#08785F")
        val dialog = Dialog(this)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
            this.background = GradientDrawable().apply { setColor(background); cornerRadius = dp(8).toFloat() }
        }
        card.addView(TextView(this).apply {
            text = "Shizuku"; textSize = 18f; setTextColor(ink); setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(12))
        })
        card.addView(ScrollView(this).apply {
            val maxHeight = (resources.displayMetrics.heightPixels * .35f).toInt()
            addView(TextView(context).apply { text = message; textSize = 15f; setTextColor(ink); this.maxHeight = maxHeight })
        })
        card.addView(LinearLayout(this).apply {
            gravity = Gravity.END
            setPadding(0, dp(10), 0, 0)
            fun command(title: String, primary: Boolean, run: () -> Unit) = Button(context).apply {
                text = title; isAllCaps = false; setTextColor(if (primary) background else accent)
                this.background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(if (primary) accent else Color.TRANSPARENT)
                }
                setOnClickListener { dialog.dismiss(); run() }
            }
            if (cancel) addView(command(strings.getString(R.string.shizuku_cancel), false) { finish() })
            addView(command(actionLabel, true, action))
        })
        dialog.setContentView(card)
        dialog.setOnCancelListener { finish() }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(.45f)
            setLayout(dp(420).coerceAtMost((resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(1)), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        prompt = dialog
    }

    override fun onDestroy() {
        prompt?.dismiss()
        Shizuku.removeRequestPermissionResultListener(listener)
        super.onDestroy()
    }

    companion object {
        private const val LANGUAGE_TAG = "dev.aster.client.shizuku.LANGUAGE_TAG"

        fun open(context: Context, languageTag: String) {
            context.startActivity(Intent(context, PermissionActivity::class.java)
                .putExtra(LANGUAGE_TAG, languageTag)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
