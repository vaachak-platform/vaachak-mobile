package org.vaachak.reader.core.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrivateGatewayClientTest {
    @Test fun authorizedChatUsesExpectedPathAndNonstreamingBody() = runBlocking {
        var path = ""
        var auth = ""
        var body = ""
        val fake = Interceptor { chain ->
            val req = chain.request()
            path = req.url.encodedPath
            auth = req.header("Authorization").orEmpty()
            body = Buffer().also { req.body?.writeTo(it) }.readUtf8()
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("""{"choices":[{"message":{"content":"Reading summary"}}]}"""
                    .toResponseBody("application/json".toMediaType()))
                .build()
        }
        val client = PrivateGatewayClient(OkHttpClient.Builder().addInterceptor(fake).build())
        val text = client.chat("https://ai-g732.tail1f3687.ts.net:8443/v1",
            "local-chat", "pilot-secret", "Explain this passage")
        assertEquals("Reading summary", text)
        assertEquals("/v1/chat/completions", path)
        assertEquals("Bearer pilot-secret", auth)
        assertTrue(body.contains("\"stream\":false"))
        assertTrue(body.contains("\"model\":\"local-chat\""))
        assertFalse(body.contains("pilot-secret"))
    }
}
