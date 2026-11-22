package dev.aster.client.automation

import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.security.MessageDigest

internal class DesktopSnapshot private constructor(
    private val id: String,
    private val issuedAt: Long,
    private val hash: ByteArray,
    val elements: JSONArray,
) {
    data class Target(val id: Int, val x: Int, val y: Int)

    fun requireCurrent(snapshotId: String, now: Long) {
        require(id == snapshotId && now - issuedAt in 0L until 60_000L) {
            "Take a fresh desktop snapshot before acting"
        }
    }

    fun matches(xml: String) = hash.contentEquals(digest(xml))

    fun target(elementId: Int, longPress: Boolean): Target {
        require(elementId in 0 until elements.length()) { "Unknown desktop element" }
        val selected = elements.getJSONObject(elementId)
        check(selected.getBoolean("enabled")) { "Element is not actionable" }
        var node = selected
        while (longPress && !(node.getBoolean("enabled") && node.getBoolean("longClickable"))) {
            val parent = node.optInt("parentId", -1)
            check(parent in 0 until node.getInt("id")) { "Element is not actionable" }
            node = elements.getJSONObject(parent)
        }
        check(node.getBoolean("enabled") && (longPress || node.getBoolean("clickable"))) { "Element is not actionable" }
        val bounds = node.getJSONArray("bounds")
        val left = bounds.getInt(0)
        val top = bounds.getInt(1)
        val right = bounds.getInt(2)
        val bottom = bounds.getInt(3)
        check(right > left && bottom > top) { "Element is not visible" }
        return Target(node.getInt("id"), left + (right - left) / 2, top + (bottom - top) / 2)
    }

    companion object {
        private val boundsPattern = Regex("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]")

        private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

        fun capture(id: String, now: Long, xml: String, parser: XmlPullParser): DesktopSnapshot {
            parser.setInput(StringReader(xml))
            val elements = JSONArray()
            val nodeStack = mutableListOf<Int?>()
            while (parser.eventType != XmlPullParser.END_DOCUMENT && elements.length() < 300) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "node") {
                    val nodeId = addNode(parser, elements, nodeStack.lastOrNull { it != null })
                    nodeStack += nodeId
                } else if (parser.eventType == XmlPullParser.END_TAG && parser.name == "node" && nodeStack.isNotEmpty()) {
                    nodeStack.removeAt(nodeStack.lastIndex)
                }
                parser.next()
            }
            return DesktopSnapshot(id, now, digest(xml), elements)
        }

        private fun addNode(parser: XmlPullParser, elements: JSONArray, parentId: Int?): Int? {
            fun attribute(name: String) = parser.getAttributeValue(null, name).orEmpty()
            fun flag(name: String) = attribute(name) == "true"
            val bounds = boundsPattern.matchEntire(attribute("bounds"))?.groupValues?.drop(1) ?: return null
            val coordinates = bounds.map { it.toIntOrNull() ?: return null }
            val id = elements.length()
            val password = flag("password")
            elements.put(JSONObject().put("id", id).put("parentId", parentId ?: JSONObject.NULL)
                .put("text", if (password) "[redacted]" else attribute("text").take(500))
                .put("description", if (password) "[redacted]" else attribute("content-desc").take(500))
                .put("resourceId", attribute("resource-id")).put("className", attribute("class")).put("package", attribute("package"))
                .put("clickable", flag("clickable")).put("focusable", flag("focusable")).put("longClickable", flag("long-clickable"))
                .put("focused", flag("focused")).put("scrollable", flag("scrollable")).put("checkable", flag("checkable"))
                .put("checked", flag("checked")).put("enabled", flag("enabled")).put("bounds", JSONArray(coordinates)))
            return id
        }
    }
}
