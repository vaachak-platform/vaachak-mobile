package org.vaachak.reader.core.data.remote

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AiImageFormatBoundaryTest {
    private fun image(format: Bitmap.CompressFormat, width: Int = 2, height: Int = 2): ByteArray {
        val bitmap = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        try {
            val output = ByteArrayOutputStream()
            assertTrue(bitmap.compress(format, 90, output))
            return output.toByteArray()
        } finally { bitmap.recycle() }
    }
    @Test fun realJpegAndWebpAreAcceptedButWrongMimeAndOversizedDimensionsAreNot() = runBlocking {
        // Fixed real image fixtures avoid SDK-dependent Bitmap.CompressFormat constants.
        for ((fixture, mime) in listOf(
            "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAACAAIDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDlKKKK+hPBP//Z" to "image/jpeg",
            "UklGRh4AAABXRUJQVlA4TBEAAAAvAUAAAAdQsJbUsv+BiOh/AAA=" to "image/webp"
        )) {
            val bytes = Base64.decode(fixture, Base64.NO_WRAP)
            val encoded = AiImagePayload.read(bytes.toResponseBody(mime.toMediaType()))
            assertEquals(Base64.encodeToString(bytes, Base64.NO_WRAP),encoded)
            val bitmap = requireNotNull(AiImagePayload.decodeForDisplay(encoded))
            assertEquals(2,bitmap.width); bitmap.recycle()
            assertTrue(runCatching { AiImagePayload.read(bytes.toResponseBody("image/png".toMediaType())) }.isFailure)
        }
        for ((w,h) in listOf(2049 to 1,1 to 2049)) {
            val bytes = image(Bitmap.CompressFormat.PNG,w,h)
            assertTrue(runCatching { AiImagePayload.read(bytes.toResponseBody("image/png".toMediaType())) }.isFailure)
            assertNull(AiImagePayload.decodeForDisplay(Base64.encodeToString(bytes,Base64.NO_WRAP)))
        }
    }
    @Test fun malformedJsonFieldsAndNoncanonicalBase64CannotDecode() = runBlocking {
        val encoded = Base64.encodeToString(image(Bitmap.CompressFormat.PNG), Base64.NO_WRAP)
        for (payload in listOf(
            "{}", "{\"success\":1,\"result\":{\"image\":\"$encoded\"}}",
            "{\"success\":true,\"result\":{}}", "{\"success\":true,\"result\":{\"other\":\"$encoded\"}}",
            "{\"success\":true,\"result\":{\"image\":1}}",
            "{\"success\":true,\"result\":{\"image\":\"$encoded\",\"extra\":1}}",
            "{\"success\":true,\"result\":{\"image\":\"$encoded\"},\"errors\":[1]}",
            "{\"image\":\"$encoded\"}{}", "{\"image\":\"$encoded\",\"unknown\":0}",
            "{\"image\":\"$encoded\",\"success\":true}"
        )) {
            assertTrue("Invalid JSON envelope accepted",runCatching { AiImagePayload.read(payload.toResponseBody("application/json".toMediaType())) }.isFailure)
        }
        for (bad in listOf("", "A", "====", "AB==", "AA A", "AA-A")) assertNull(AiImagePayload.decodeForDisplay(bad))
    }
}
