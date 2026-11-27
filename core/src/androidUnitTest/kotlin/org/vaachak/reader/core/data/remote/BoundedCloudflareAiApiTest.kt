package org.vaachak.reader.core.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.vaachak.reader.core.domain.model.AiImageRequest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Exercise the real request construction and response adapter without external traffic. */
class BoundedCloudflareAiApiTest {
    private val url = "https://fixture.invalid/image"
    private val prompt = AiImageRequest(prompt = "synthetic landscape")
    private val token = "Bearer synthetic-token"
    private val png = "image/png".toMediaType()
    private fun adapter(code: Int, body: ResponseBody, inspect: (okhttp3.Request) -> Unit = {}): BoundedCloudflareAiApi =
        BoundedCloudflareAiApi(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            inspect(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
                .message("fixture").body(body).build()
        }.build())

    @Test fun oneShotPostPreservesWireContractAndReturnsBoundedBytes() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val calls = AtomicInteger()
        val captured = AtomicReference<okhttp3.Request>()
        val result = adapter(200, bytes.toResponseBody(png)) { request ->
            calls.incrementAndGet()
            captured.set(request)
        }.generateImage(url, prompt, token)
        val request = requireNotNull(captured.get())
        assertEquals(url, request.url.toString())
        assertEquals(token, request.header("Authorization"))
        assertEquals("POST", request.method)
        assertTrue(request.header("Accept").orEmpty().contains("image/png"))
        val body = requireNotNull(request.body)
        assertTrue(body.isOneShot())
        assertEquals("application/json", body.contentType()?.let { "${it.type}/${it.subtype}" })
        val buffer = Buffer()
        body.writeTo(buffer)
        assertEquals(body.contentLength(), buffer.size)
        val json = com.google.gson.JsonParser.parseString(buffer.readUtf8()).asJsonObject
        assertEquals(prompt.prompt, json.get("prompt").asString)
        assertEquals(prompt.width, json.get("width").asInt)
        assertEquals(1, calls.get())
        assertEquals(200, result.code())
        assertArrayEquals(bytes, requireNotNull(result.body()).use { it.bytes() })
    }

    @Test fun providerErrorsAndRedirectsDiscardBodyAndNeverReplay() = runBlocking {
        for (status in listOf(302, 401, 402, 413, 429, 503)) {
            val calls = AtomicInteger()
            val result = adapter(status, "private provider response".toResponseBody()) { calls.incrementAndGet() }
                .generateImage(url, prompt, token)
            assertEquals(status, result.code())
            assertFalse(result.isSuccessful)
            assertEquals("", requireNotNull(result.errorBody()).use { it.string() })
            assertEquals(1, calls.get())
        }
    }

    @Test fun emptyUnsupportedAndOversizedWireBodiesAreDistinguished() = runBlocking {
        val empty = adapter(200, ByteArray(0).toResponseBody(png)).generateImage(url, prompt, token)
        assertEquals(0L, requireNotNull(empty.body()).use { it.contentLength() })
        for (body in listOf("html".toResponseBody("text/html".toMediaType()),
            "untyped".toResponseBody(), ByteArray(AiImagePayload.MAX_BYTES + 1).toResponseBody(png))) {
            assertTrue(runCatching { adapter(200, body).generateImage(url, prompt, token) }.exceptionOrNull() is AiImageResponseException)
        }
        val unknownLength = object : ResponseBody() {
            private val data = Buffer().write(ByteArray(AiImagePayload.MAX_BYTES + 1))
            override fun contentType() = png
            override fun contentLength() = -1L
            override fun source() = data
        }
        assertTrue(runCatching { adapter(200, unknownLength).generateImage(url, prompt, token) }.exceptionOrNull() is AiImageResponseException)
    }

    @Test fun invalidOriginsAndPromptsNeverReachInterceptor() = runBlocking {
        val calls = AtomicInteger()
        val api = adapter(200, "fixture".toResponseBody(png)) { calls.incrementAndGet() }
        for (bad in listOf("http://fixture.invalid/image", "https://user@fixture.invalid/image",
            "https://:password@fixture.invalid/image", "$url#fragment", "not a url")) {
            assertTrue(runCatching { api.generateImage(bad, prompt, token) }.isFailure)
        }
        for (bad in listOf("", " ", "x".repeat(48_001))) {
            assertTrue(runCatching { api.generateImage(url, AiImageRequest(prompt = bad), token) }.isFailure)
        }
        assertEquals(0, calls.get())
    }
}
