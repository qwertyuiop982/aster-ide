package dev.aster.client.shizuku

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
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
    private val listener = Shizuku.OnRequestPermissionResultListener { _, result ->
        runOnUiThread { show(if (result == PackageManager.PERMISSION_GRANTED) "Shizuku authorized" else "Shizuku permission denied") }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(listener)
        val state = ShizukuClient.status()
        when {
            !state.optBoolean("available") -> show("Start Shizuku using wireless debugging, ADB or root, then return here. Embedding the client does not grant system privileges.")
            state.optBoolean("authorized") -> show("Shizuku authorized")
            else -> AlertDialog.Builder(this).setTitle("Device automation").setMessage("Allow Aster to request Shizuku access for approved screen capture and device operations?")
                .setPositiveButton("Authorize") { _, _ -> runCatching { Shizuku.requestPermission(4101) }.onFailure { show(it.message ?: "Permission request failed") } }
                .setNegativeButton("Cancel") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
        }
    }
    private fun show(message: String) { AlertDialog.Builder(this).setTitle("Shizuku").setMessage(message).setPositiveButton("OK") { _, _ -> finish() }.setOnCancelListener { finish() }.show() }
    override fun onDestroy() { Shizuku.removeRequestPermissionResultListener(listener); super.onDestroy() }
}