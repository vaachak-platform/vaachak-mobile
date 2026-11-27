package org.vaachak.reader.core.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
class GeminiRestClientTest {
    private fun client(code: Int = 200, body: String, inspect: (okhttp3.Request) -> Unit = {}): GeminiRestClient =
        GeminiRestClient(OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            inspect(req)
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(body.toResponseBody()).build()
        }.build())

    @Test fun textRequestUsesFixedOriginAndHeader() = runBlocking {
        val c = client(body = """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"thought":true,"text":"hidden"},{"text":"answer","thoughtSignature":"opaque"}]}}]}""") {
            assertEquals("generativelanguage.googleapis.com", it.url.host)
            assertEquals("/v1beta/models/gemini-3.6-flash:generateContent", it.url.encodedPath)
            assertNull(it.url.query)
            assertEquals("synthetic-key", it.header("x-goog-api-key"))
            assertEquals("POST", it.method)
        }
        assertEquals("answer", c.generate("models/gemini-3.6-flash", "synthetic-key", "Synthetic passage"))
    }

    @Test fun catalogFiltersNonTextAndDoesNotInfer() = runBlocking {
        val c = client(body = """{"models":[{"name":"models/gemini-3.6-flash","supportedGenerationMethods":["generateContent"]},{"name":"models/gemini-3.8-flash-tts","supportedGenerationMethods":["generateContent"]},{"name":"models/gemini-3.1-flash-image","supportedGenerationMethods":["generateContent"]},{"name":"models/gemini-4-neon","supportedGenerationMethods":["countTokens"]}]}""") {
            assertEquals("GET", it.method)
            assertEquals("/v1beta/models", it.url.encodedPath)
        }
        assertEquals(listOf("gemini-3.6-flash"), c.listTextCandidates("synthetic-key"))
    }

    @Test fun paymentAndOtherStatusesAreNumericAndRedacted() = runBlocking {
        for (code in listOf(302, 400, 401, 402, 403, 404, 429, 503)) {
            val error = runCatching { client(code, "private credential").generate("gemini-3.6-flash", "key", "passage") }.exceptionOrNull()
            assertTrue(error is GeminiHttpException)
            assertEquals(code, (error as GeminiHttpException).status)
            assertFalse(error.message.orEmpty().contains("private credential"))
        }
    }

    @Test fun incompleteOrThoughtOnlyAnswersAreRejected() {
        val c = client(body = "")
        for (body in listOf("{}", """{"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[{"text":"partial"}]}}]}""", """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"thought":true,"text":"hidden"}]}}]}""")) {
            assertTrue(runCatching { c.parseAnswer(JSONObject(body)) }.exceptionOrNull() is GeminiResponseException)
        }
    }

    @Test fun modelIdsCannotChangeOriginOrPath() {
        for (bad in listOf("", "https://evil.example", "gemini-3/../../secret", "gemini-3?key=x", "gemini-3.8-flash-tts")) {
            assertFalse(GeminiTextModels.isCandidate(bad))
        }
        assertTrue(GeminiTextModels.isCandidate("models/gemini-flash-latest"))
    }
}
