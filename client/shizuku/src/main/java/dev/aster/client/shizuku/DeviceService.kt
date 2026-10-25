package dev.aster.client.shizuku

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Process
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Only fixed device operations cross the privileged boundary, never a shell script. */
class DeviceService : IDeviceService.Stub() {
    override fun destroy() { Process.killProcess(Process.myPid()) }

    private fun run(args: List<String>, limit: Int = 1024 * 1024): ByteArray {
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val reader = Executors.newSingleThreadExecutor()
        try {
            val output = reader.submit<ByteArray> {
                process.inputStream.use { input ->
                    val bytes = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        check(bytes.size() + count <= limit) { "Device output exceeds limit" }
                        bytes.write(buffer, 0, count)
                    }
                    bytes.toByteArray()
                }
            }
            check(process.waitFor(12, TimeUnit.SECONDS)) { "Device operation timed out" }
            val bytes = output.get(2, TimeUnit.SECONDS)
            check(process.exitValue() == 0) { "Device operation failed: " + bytes.toString(Charsets.UTF_8).take(400) }
            return bytes
        } finally {
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
            reader.shutdownNow()
        }
    }

    private fun coordinate(args: JSONObject, key: String): String {
        val value = args.getInt(key)
        require(value in 0..16000) { "Invalid coordinate" }
        return value.toString()
    }

    @Synchronized override fun execute(operation: String, arguments: String): String {
        return try {
            require(arguments.length <= 16000)
            val args = JSONObject(arguments)
            val result = when (operation) {
                "hierarchy" -> {
                    val file = File("/data/local/tmp/aster-ui-${UUID.randomUUID()}.xml")
                    try {
                        run(listOf("/system/bin/uiautomator", "dump", "--compressed", file.path))
                        check(file.isFile && file.length() <= 1024 * 1024) { "UI hierarchy unavailable" }
                        JSONObject().put("xml", file.readText())
                    } finally { file.delete() }
                }
                "tap" -> {
                    run(listOf("/system/bin/input", "tap", coordinate(args, "x"), coordinate(args, "y")))
                    JSONObject()
                }
                "swipe" -> {
                    val duration = args.optInt("duration", 300)
                    require(duration in 100..2000)
                    run(listOf("/system/bin/input", "swipe", coordinate(args, "x"), coordinate(args, "y"), coordinate(args, "endX"), coordinate(args, "endY"), duration.toString()))
                    JSONObject()
                }
                "key" -> {
                    val key = args.getString("key")
                    require(key in setOf("BACK", "HOME", "ENTER", "DEL", "TAB", "APP_SWITCH"))
                    run(listOf("/system/bin/input", "keyevent", "KEYCODE_$key"))
                    JSONObject()
                }
                "text" -> {
                    val text = args.getString("text")
                    require(text.length in 1..2000 && text.all { it.code in 32..126 } && '%' !in text) { "Device text input currently accepts printable ASCII without percent; use browser fill for Unicode" }
                    run(listOf("/system/bin/input", "text", text.replace(" ", "%s")))
                    JSONObject()
                }
                else -> error("Unsupported device operation")
            }
            result.put("ok", true).toString()
        } catch (e: Exception) { JSONObject().put("ok", false).put("error", e.message).toString() }
    }

    @Synchronized override fun screenshot(): Bundle {
        val raw = run(listOf("/system/bin/screencap", "-p"), 20 * 1024 * 1024)
        val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: error("Screenshot unavailable or protected")
        try {
            val scale = minOf(1.0, 1280.0 / maxOf(bitmap.width, bitmap.height))
            val resized = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
            try {
                val output = ByteArrayOutputStream()
                resized.compress(Bitmap.CompressFormat.JPEG, 75, output)
                check(output.size() <= 600000) { "Screenshot exceeds Binder limit" }
                return Bundle().apply {
                    putByteArray("jpeg", output.toByteArray())
                    putInt("width", resized.width); putInt("height", resized.height)
                    putInt("screenWidth", bitmap.width); putInt("screenHeight", bitmap.height)
                }
            } finally { if (resized !== bitmap) resized.recycle() }
        } finally { bitmap.recycle() }
    }
}
