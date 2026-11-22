package dev.aster.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Base64
import androidx.annotation.RequiresApi
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.max

internal object AgentAttachments {
    const val MAX_IMAGES = 3
    const val MAX_DOCUMENTS = 8
    private const val MAX_IMAGE_BYTES = 384 * 1024
    private const val MAX_SOURCE_BYTES = 24 * 1024 * 1024
    private const val IMAGE_EDGE = 1440

    data class Item(val name: String, val text: String? = null, val image: String? = null, val id: String = UUID.randomUUID().toString()) {
        fun label() = JSONObject().put("id", id).put("name", name).put("image", image != null)
        fun imagePart() = image?.let { JSONObject().put("data", it).put("mimeType", "image/jpeg") }
    }

    fun load(context: Context, uri: Uri, photo: Boolean): Item {
        val resolver = context.contentResolver
        require(uri.scheme == "content") { "请选择系统文件选择器提供的文件" }
        val image = photo || resolver.getType(uri)?.startsWith("image/") == true
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull().orEmpty().replace(Regex("[\\p{Cntrl}/\\\\]"), "_").trim().take(120)
            .ifBlank { if (image) "图片" else "文件" }
        if (!image) {
            val text = resolver.openInputStream(uri)?.use(AgentExplicitContext::readUtf8) ?: error("无法读取文件")
            return Item(name, text = text)
        }
        val input = File.createTempFile("agent-input-", ".img", context.cacheDir)
        try {
            resolver.openInputStream(uri)?.use { source ->
                input.outputStream().use { out ->
                    val buffer = ByteArray(16 * 1024)
                    var size = 0
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        size += count
                        require(size <= MAX_SOURCE_BYTES) { "原始图片超过 24 MiB" }
                        out.write(buffer, 0, count)
                    }
                }
            } ?: error("无法读取图片")
            val bitmap = if (Build.VERSION.SDK_INT >= 28) decode(input) else decodeLegacy(input)
            try {
                return Item(name, image = encode(bitmap))
            } finally {
                bitmap.recycle()
            }
        } finally {
            input.delete()
        }
    }

    @RequiresApi(28)
    private fun decode(file: File): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
        val width = info.size.width
        val height = info.size.height
        checkDimensions(width, height)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val scale = minOf(1.0, IMAGE_EDGE.toDouble() / max(width, height))
        decoder.setTargetSize((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
    }

    private fun decodeLegacy(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        checkDimensions(bounds.outWidth, bounds.outHeight)
        val decode = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (max(bounds.outWidth, bounds.outHeight) / inSampleSize > IMAGE_EDGE) inSampleSize *= 2
        }
        val bitmap = BitmapFactory.decodeFile(file.path, decode) ?: error("无法解码图片")
        val orientation = runCatching {
            ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val transform = Matrix().apply {
            when (orientation) {
                2 -> setScale(-1f, 1f)
                3 -> setRotate(180f)
                4 -> setScale(1f, -1f)
                5 -> { setRotate(90f); postScale(-1f, 1f) }
                6 -> setRotate(90f)
                7 -> { setRotate(-90f); postScale(-1f, 1f) }
                8 -> setRotate(-90f)
            }
        }
        if (transform.isIdentity) return bitmap
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true)
        } finally {
            bitmap.recycle()
        }
    }

    private fun checkDimensions(width: Int, height: Int) {
        require(width > 0 && height > 0 && width.toLong() * height <= 120_000_000) { "图片尺寸过大或格式不支持" }
    }

    private fun encode(bitmap: Bitmap): String {
        var current = bitmap
        if (bitmap.hasAlpha()) {
            current = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Canvas(current).apply { drawColor(Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
        }
        try {
            val output = ByteArrayOutputStream()
            repeat(3) {
                for (quality in listOf(88, 74, 60)) {
                    output.reset()
                    check(current.compress(Bitmap.CompressFormat.JPEG, quality, output)) { "无法编码图片" }
                    if (output.size() <= MAX_IMAGE_BYTES) return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
                }
                val scaled = Bitmap.createScaledBitmap(current, (current.width * 0.7).toInt().coerceAtLeast(1),
                    (current.height * 0.7).toInt().coerceAtLeast(1), true)
                if (current !== bitmap && current !== scaled) current.recycle()
                current = scaled
            }
            error("无法将图片压缩到发送限制以内")
        } finally {
            if (current !== bitmap) current.recycle()
        }
    }
}
