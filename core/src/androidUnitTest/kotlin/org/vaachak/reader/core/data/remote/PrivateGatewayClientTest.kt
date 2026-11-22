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
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrivateGatewayClientTest {
    private val gateway = "https://reader.tailnet.ts.net:8443/v1"

    private fun clientFor(code: Int = 200, responseBody: String = """{"choices":[{"message":{"content":"Reading summary"}}]}"""):
        PrivateGatewayClient = PrivateGatewayClient(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("Simulated response")
                .body(responseBody.toResponseBody("application/json".toMediaType()))
                .build()
        }.build())

    private fun expectInvalid(message: String, block: suspend () -> Unit) {
        try {
            runBlocking { block() }
            fail("Expected validation failure: $message")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains(message))
        }
    }

    private fun expectGatewayFailure(message: String, block: suspend () -> Unit) {
        try {
            runBlocking { block() }
            fail("Expected gateway failure: $message")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains(message))
        }
    }

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
        val text = client.chat(gateway, "local-chat", "pilot-secret", "Explain this passage")
        assertEquals("Reading summary", text)
        assertEquals("/v1/chat/completions", path)
        assertEquals("Bearer pilot-secret", auth)
        assertTrue(body.contains("\"stream\":false"))
        assertTrue(body.contains("\"model\":\"local-chat\""))
        assertFalse(body.contains("pilot-secret"))
    }

    @Test fun unauthorizedResponseIsRejectedWithoutLeakingToken() {
        expectGatewayFailure("Private gateway HTTP 401") {
            clientFor(401, "unauthorized").chat(gateway, "local-chat", "topsecret", "summary")
        }
    }

    @Test fun redirectsAreNotFollowed() {
        expectGatewayFailure("Private gateway HTTP 302") {
            clientFor(302, "redirect").chat(gateway, "local-chat", "topsecret", "summary")
        }
    }

    @Test fun blankResponseIsRejected() {
        expectGatewayFailure("Empty gateway response") {
            clientFor(responseBody = "   ").chat(gateway, "local-chat", "topsecret", "summary")
        }
    }

    @Test fun missingTextIsRejected() {
        expectGatewayFailure("Gateway returned no text") {
            clientFor(responseBody = """{"choices":[{"message":{"content":"  "}}]}""")
                .chat(gateway, "local-chat", "topsecret", "summary")
        }
    }

    @Test fun blankBearerTokenIsRejected() {
        expectInvalid("Set the Vaachak gateway key") {
            clientFor().chat(gateway, "local-chat", "", "summary")
        }
    }

    @Test fun blankOrOversizedPromptIsRejected() {
        for (prompt in listOf("", "x".repeat(24_001))) {
            expectInvalid("Text exceeds gateway limit") {
                clientFor().chat(gateway, "local-chat", "pilot-secret", prompt)
            }
        }
    }

    @Test fun untrustedEndpointNeverReceivesToken() {
        expectInvalid("Use a private Tailscale HTTPS hostname") {
            clientFor().chat("https://outside.example:8443/v1", "local-chat", "pilot-secret", "summary")
        }
    }
}
