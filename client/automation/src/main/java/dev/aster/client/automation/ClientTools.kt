package dev.aster.client.automation

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.util.Xml
import dev.aster.client.browser.BrowserRuntime
import dev.aster.client.shizuku.ShizukuClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringReader
import java.security.MessageDigest
import java.util.UUID

class ClientTools(private val context: Context) {
    private val lock = Mutex()
    private var snapshotId = ""
    private var snapshotHash = ""
    private var snapshotTime = 0L
    private var nodes = JSONArray()
    private var capture: Bundle? = null
    private var pixels: IntArray? = null

    suspend fun execute(tool: String, args: JSONObject): JSONObject = lock.withLock {
        if (tool == "browser") return@withLock BrowserRuntime.execute(context, args.getString("action"), args)
        require(tool == "desktop") { "Unknown client tool" }
        val action = args.getString("action")
        if (action == "status") return@withLock ShizukuClient.status().put("ok", true)
        if (action in setOf("snapshot", "inspect")) {
            if (action == "inspect") require(args.getString("intent").length in 1..4000) { "Describe what to inspect" }
            snapshotId = ""; capture = null; pixels = null
            val result = ShizukuClient.useService(context) { service ->
                val hierarchy = JSONObject(service.execute("hierarchy", "{}"))
                check(hierarchy.optBoolean("ok")) { hierarchy.optString("error") }
                val xml = hierarchy.getString("xml")
                if (args.optBoolean("screenshot", false) || action == "inspect") {
                    capture = service.screenshot()
                    pixels = imagePixels(capture!!)
                }
                snapshotHash = digest(xml)
                nodes = parseNodes(xml)
                JSONObject().put("elements", nodes)
            }
            snapshotId = UUID.randomUUID().toString(); snapshotTime = android.os.SystemClock.elapsedRealtime()
            result.put("ok", true).put("snapshotId", snapshotId).put("coordinateSpace", "screen pixels")
            capture?.let { image ->
                val bytes = image.getByteArray("jpeg") ?: error("Screenshot missing")
                val metadata = JSONObject().put("width", image.getInt("width")).put("height", image.getInt("height"))
                    .put("screenWidth", image.getInt("screenWidth")).put("screenHeight", image.getInt("screenHeight"))
                    .put("mimeType", "image/jpeg")
                if (action == "inspect") {
                    result.put("imageData", metadata.put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP)))
                } else {
                    val directory = File(context.cacheDir, "automation").apply { mkdirs() }
                    directory.listFiles()?.forEach { it.delete() }
                    val file = File(directory, "$snapshotId.jpg"); file.writeBytes(bytes)
                    result.put("image", metadata.put("path", file.path).put("visualAnalysis", false))
                }
            }
            return@withLock result
        }
        require(action in setOf("click", "tap", "swipe", "text", "key")) { "Unsupported desktop action" }
        require(snapshotId.isNotEmpty() && args.getString("snapshotId") == snapshotId && android.os.SystemClock.elapsedRealtime() - snapshotTime < 60000) { "Take a fresh desktop snapshot before acting" }
        ShizukuClient.useService(context) { service ->
            val current = JSONObject(service.execute("hierarchy", "{}"))
            check(current.optBoolean("ok")) { current.optString("error") }
            check(digest(current.getString("xml")) == snapshotHash) { "Screen changed; take a fresh desktop snapshot" }
            capture?.let { previous ->
                val fresh = service.screenshot()
                check(previous.getInt("screenWidth") == fresh.getInt("screenWidth") && previous.getInt("screenHeight") == fresh.getInt("screenHeight")) { "Display rotated; take a fresh snapshot" }
                val before = pixels ?: error("Screenshot verification unavailable")
                val after = imagePixels(fresh)
                val difference = before.indices.sumOf { kotlin.math.abs(before[it] - after[it]) }.toDouble() / before.size
                check(difference < 12.0) { "Visible screen changed; inspect again before acting" }
            }
            val parameters = JSONObject(args.toString())
            val operation = if (action == "click") {
                val id = args.getInt("elementId")
                require(id in 0 until nodes.length())
                val node = nodes.getJSONObject(id)
                check(node.optBoolean("enabled") && node.optBoolean("clickable")) { "Element is not actionable" }
                val bounds = node.getJSONArray("bounds")
                parameters.put("x", (bounds.getInt(0) + bounds.getInt(2)) / 2).put("y", (bounds.getInt(1) + bounds.getInt(3)) / 2)
                "tap"
            } else {
                if (action in setOf("tap", "swipe")) {
                    val space = args.optString("coordinateSpace", "screen")
                    require(space in setOf("screen", "image")) { "Unknown coordinate space" }
                    if (space == "image") {
                        val image = capture ?: error("Take a screenshot before using image coordinates")
                        fun scale(key: String, extent: String, screenExtent: String) {
                            val coordinate = args.getInt(key)
                            val limit = image.getInt(extent)
                            require(coordinate in 0 until limit) { "Coordinate outside screenshot" }
                            parameters.put(key, (coordinate.toLong() * image.getInt(screenExtent) / limit).toInt())
                        }
                        scale("x", "width", "screenWidth"); scale("y", "height", "screenHeight")
                        if (action == "swipe") { scale("endX", "width", "screenWidth"); scale("endY", "height", "screenHeight") }
                    }
                }
                action
            }
            // Invalidate before dispatch: even a partially failed action cannot reuse a stale image.
            snapshotId = ""; capture = null; pixels = null
            val result = JSONObject(service.execute(operation, parameters.toString()))
            if (result.optBoolean("ok")) {
                val after = JSONObject(service.execute("hierarchy", "{}"))
                result.put("verification", JSONObject().put("observed", after.optBoolean("ok"))
                    .put("screenChanged", if (after.optBoolean("ok")) digest(after.getString("xml")) != snapshotHash else JSONObject.NULL))
            }
            result.put("verificationRequired", true)
        }
    }

    private fun imagePixels(image: Bundle): IntArray {
        val bytes = image.getByteArray("jpeg") ?: error("Screenshot missing")
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Invalid screenshot")
        try {
            return IntArray(256) { index ->
                val x = ((index % 16 + 0.5) * bitmap.width / 16).toInt().coerceIn(0, bitmap.width - 1)
                val y = ((0.1 + (index / 16 + 0.5) * 0.8 / 16) * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
                val color = bitmap.getPixel(x, y)
                (((color shr 16) and 255) * 3 + ((color shr 8) and 255) * 6 + (color and 255)) / 10
            }
        } finally { bitmap.recycle() }
    }
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun parseNodes(xml: String): JSONArray {
        val parser = Xml.newPullParser().apply { setInput(StringReader(xml)) }
        val result = JSONArray()
        while (parser.eventType != XmlPullParser.END_DOCUMENT && result.length() < 300) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "node") {
                fun attribute(name: String) = parser.getAttributeValue(null, name).orEmpty()
                val bounds = Regex("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]").matchEntire(attribute("bounds"))
                if (bounds != null) result.put(JSONObject().put("id", result.length()).put("text", if (attribute("password") == "true") "[redacted]" else attribute("text").take(500))
                    .put("description", if (attribute("password") == "true") "[redacted]" else attribute("content-desc").take(500)).put("resourceId", attribute("resource-id"))
                    .put("package", attribute("package")).put("clickable", attribute("clickable") == "true")
                    .put("enabled", attribute("enabled") == "true").put("bounds", JSONArray(bounds.groupValues.drop(1).map(String::toInt))))
            }
            parser.next()
        }
        return result
    }
}
