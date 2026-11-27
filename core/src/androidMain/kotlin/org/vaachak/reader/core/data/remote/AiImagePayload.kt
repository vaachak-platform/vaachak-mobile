package org.vaachak.reader.core.data.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import okhttp3.ResponseBody
import android.util.JsonReader
import android.util.JsonToken
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class AiImageResponseException : IllegalStateException("Invalid or oversized image response")

/** Bound wire data, encoded data and decoded pixel allocation independently. */
object AiImagePayload {
    const val MAX_BYTES = 8 * 1024 * 1024
    const val MAX_BASE64 = ((MAX_BYTES + 2) / 3) * 4
    internal const val MAX_JSON_BYTES = MAX_BASE64 + 4096
    private const val MAX_DIMENSION = 2048
    private const val MAX_PIXELS = 4_194_304L
    private val bitmapDispatcher = Dispatchers.Default.limitedParallelism(1)

    internal fun wireLimit(contentType: String?): Int = when (contentType) {
        "application/json" -> MAX_JSON_BYTES
        "image/jpeg", "image/png", "image/webp" -> MAX_BYTES
        else -> throw AiImageResponseException()
    }

    private fun mime(bytes: ByteArray): String? = when {
        bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg"
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)) -> "image/png"
        bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray(Charsets.US_ASCII)) &&
            bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray(Charsets.US_ASCII)) -> "image/webp"
        else -> null
    }

    private fun dimensionsAllowed(width: Int, height: Int): Boolean =
        width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION && width.toLong() * height <= MAX_PIXELS

    private fun validate(bytes: ByteArray, expectedMime: String? = null) {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) throw AiImageResponseException()
        val actual = mime(bytes) ?: throw AiImageResponseException()
        if (expectedMime != null && actual != expectedMime) throw AiImageResponseException()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (!dimensionsAllowed(bounds.outWidth, bounds.outHeight) || bounds.outMimeType != actual) throw AiImageResponseException()
    }

    private fun decodeBase64(encoded: String): ByteArray {
        // Accept only bounded standard base64, never URLs, data URIs or arbitrary nested JSON.
        if (encoded.isEmpty() || encoded.length > MAX_BASE64 || encoded.length % 4 != 0 ||
            encoded.any { it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' && it != '+' && it != '/' && it != '=' }) {
            throw AiImageResponseException()
        }
        val bytes = try { Base64.decode(encoded, Base64.NO_WRAP) } catch (_: IllegalArgumentException) { throw AiImageResponseException() }
        if (bytes.size > MAX_BYTES || Base64.encodeToString(bytes, Base64.NO_WRAP) != encoded) throw AiImageResponseException()
        return bytes
    }

    private fun jsonImage(raw: ByteArray): ByteArray {
        return try {
            JsonReader(InputStreamReader(ByteArrayInputStream(raw), Charsets.UTF_8)).use { reader ->
                reader.isLenient = false
                reader.beginObject()
                var image: String? = null
                var nested: String? = null
                var success: Boolean? = null
                val seen = mutableSetOf<String>()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    if (!seen.add(name)) throw AiImageResponseException()
                    when (name) {
                        "image" -> { if (reader.peek() != JsonToken.STRING) throw AiImageResponseException(); image = reader.nextString() }
                        "success" -> { if (reader.peek() != JsonToken.BOOLEAN) throw AiImageResponseException(); success = reader.nextBoolean() }
                        "result" -> {
                            reader.beginObject()
                            if (!reader.hasNext() || reader.nextName() != "image" || reader.peek() != JsonToken.STRING) throw AiImageResponseException()
                            nested = reader.nextString()
                            if (reader.hasNext()) throw AiImageResponseException()
                            reader.endObject()
                        }
                        "errors", "messages" -> { reader.beginArray(); if (reader.hasNext()) throw AiImageResponseException(); reader.endArray() }
                        else -> throw AiImageResponseException()
                    }
                }
                reader.endObject()
                if (reader.peek() != JsonToken.END_DOCUMENT) throw AiImageResponseException()
                val encoded = when {
                    image != null && nested == null && seen == setOf("image") -> image
                    image == null && nested != null && success == true -> nested
                    else -> throw AiImageResponseException()
                }
                decodeBase64(requireNotNull(encoded))
            }
        } catch (_: Exception) { throw AiImageResponseException() }
    }

    /** The direct transport caps wire bytes before Retrofit could buffer them. Recheck here for all callers. */
    internal suspend fun read(body: ResponseBody): String = withContext(Dispatchers.IO) {
        body.use {
            val contentType = it.contentType()?.let { type -> "${type.type}/${type.subtype}" }
            if (it.contentLength() == 0L) return@withContext "Error: Empty image received from server."
            val cap = wireLimit(contentType)
            if (it.contentLength() > cap) throw AiImageResponseException()
            val source = it.source()
            val sink = okio.Buffer()
            while (sink.size <= cap) {
                val read = source.read(sink, minOf(8192L, cap.toLong() + 1 - sink.size))
                if (read == -1L) break
                if (sink.size > cap) throw AiImageResponseException()
            }
            val raw = sink.readByteArray()
            if (raw.isEmpty()) return@withContext "Error: Empty image received from server."
            val bytes = if (contentType == "application/json") jsonImage(raw) else raw
            validate(bytes, if (contentType == "application/json") null else contentType)
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
    }

    /** Caller is free to display only a successfully decoded bounded bitmap. Never log decoder exceptions. */
    suspend fun decodeForDisplay(encoded: String): Bitmap? = withContext(bitmapDispatcher) {
        try {
            currentCoroutineContext().ensureActive()
            val bytes = decodeBase64(encoded)
            validate(bytes)
            currentCoroutineContext().ensureActive()
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.let { bitmap ->
                if (dimensionsAllowed(bitmap.width, bitmap.height)) bitmap else { bitmap.recycle(); null }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
}
