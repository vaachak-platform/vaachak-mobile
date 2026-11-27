package org.vaachak.reader.core.data.remote

import android.util.Base64
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
class AiImagePayloadTest {
    private val encoded = "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFklEQVR4nGMU0bBhYGBgYmBgYGBgAAAFogB8q5SvRgAAAABJRU5ErkJggg=="
    private val bytes get() = Base64.decode(encoded, Base64.NO_WRAP)
    @Test fun rawPngAndBothSupportedJsonEnvelopesDecode() = runBlocking {
        assertEquals(encoded, AiImagePayload.read(bytes.toResponseBody("image/png".toMediaType())))
        assertEquals(encoded, AiImagePayload.read("{\"image\":\"$encoded\"}".toResponseBody("application/json".toMediaType())))
        assertEquals(encoded, AiImagePayload.read("{\"success\":true,\"result\":{\"image\":\"$encoded\"},\"errors\":[],\"messages\":[]}".toResponseBody("application/json".toMediaType())))
        val bitmap = requireNotNull(AiImagePayload.decodeForDisplay(encoded))
        assertEquals(2, bitmap.width); assertEquals(2, bitmap.height); bitmap.recycle()
    }
    @Test fun mismatchedMimeAndMalformedImageRejected() = runBlocking {
        assertTrue(runCatching { AiImagePayload.read(bytes.toResponseBody("image/jpeg".toMediaType())) }.isFailure)
        assertTrue(runCatching { AiImagePayload.read("not-image".toResponseBody("image/png".toMediaType())) }.isFailure)
        assertNull(AiImagePayload.decodeForDisplay("not base64"))
    }
    @Test fun unsupportedOrAmbiguousJsonIsRejected() = runBlocking {
        for (payload in listOf("{\"image\":\"$encoded\",\"image\":\"$encoded\"}",
            "{\"success\":false,\"result\":{\"image\":\"$encoded\"}}", "{\"image\":{\"data\":\"$encoded\"}}",
            "{\"image\":\"https://example.invalid/image\"}")) {
            assertTrue(runCatching { AiImagePayload.read(payload.toResponseBody("application/json".toMediaType())) }.isFailure)
        }
    }
    @Test fun oversizedWireAndEncodedPayloadRejected() = runBlocking {
        assertTrue(runCatching { AiImagePayload.read(ByteArray(AiImagePayload.MAX_BYTES + 1).toResponseBody("image/png".toMediaType())) }.isFailure)
        assertNull(AiImagePayload.decodeForDisplay("A".repeat(AiImagePayload.MAX_BASE64 + 4)))
        assertTrue(runCatching { AiImagePayload.wireLimit("text/html") }.isFailure)
    }
}
