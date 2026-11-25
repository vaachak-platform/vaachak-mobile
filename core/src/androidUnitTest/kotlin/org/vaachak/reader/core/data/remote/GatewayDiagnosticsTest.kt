package org.vaachak.reader.core.data.remote

import com.google.ai.client.generativeai.type.InvalidAPIKeyException
import com.google.ai.client.generativeai.type.QuotaExceededException
import com.google.ai.client.generativeai.type.RequestTimeoutException
import com.google.ai.client.generativeai.type.SerializationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

@RunWith(RobolectricTestRunner::class)
class GatewayDiagnosticsTest {
    private val base = "https://reader.example.ts.net:8443/v1"
    private val model = "local-chat"
    private val secret = "synthetic-token"

    private fun fake(code: Int, body: String, inspect: (Interceptor.Chain) -> Unit = {}): PrivateGatewayClient =
        PrivateGatewayClient(OkHttpClient.Builder().addInterceptor { chain ->
            inspect(chain)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("Synthetic response")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build())

    @Test fun catalogUsesOnlyAuthorizedGetAndNoReadingPayload() = runBlocking {
        var count = 0
        val client = fake(200, """{"data":[{"id":"local-chat"}]}""") { chain ->
            count++
            val request = chain.request()
            assertEquals("GET", request.method)
            assertEquals("/v1/models", request.url.encodedPath)
            assertEquals("Bearer $secret", request.header("Authorization"))
            assertEquals(null, request.body)
            assertEquals(null, request.url.query)
        }
        assertEquals(GatewayProbeResult.MODEL_LISTED, client.probe(base, model, secret))
        assertEquals(1, count)
    }

    @Test fun catalogMissingModelAndInvalidBodiesAreNotSuccessfulInference() = runBlocking {
        assertEquals(GatewayProbeResult.MODEL_NOT_LISTED,
            fake(200, """{"data":[{"id":"other"}]}""").probe(base, model, secret))
        for (body in listOf("", "not-json", "{}", """{"data":[{}]}""", "x".repeat(65_537))) {
            assertEquals(GatewayProbeResult.CATALOG_UNRECOGNIZED, fake(200, body).probe(base, model, secret))
        }
    }

    @Test fun unsupportedCatalogNeverFallsBackToPost() = runBlocking {
        for (status in listOf(404, 405, 501)) {
            var count = 0
            val client = fake(status, "private body") { chain ->
                count++
                assertEquals("GET", chain.request().method)
            }
            assertEquals(GatewayProbeResult.CATALOG_UNSUPPORTED, client.probe(base, model, secret))
            assertEquals(1, count)
        }
    }

    @Test fun httpFailuresExposeOnlyFixedStatusAndNeverBody() = runBlocking {
        for ((code, category) in listOf(302 to AiFailure.REDIRECT, 401 to AiFailure.UNAUTHORIZED,
            403 to AiFailure.FORBIDDEN, 429 to AiFailure.RATE_LIMIT, 503 to AiFailure.SERVER,
            400 to AiFailure.UNKNOWN)) {
            try {
                fake(code, secret).probe(base, model, secret)
                fail("Expected typed HTTP failure")
            } catch (error: GatewayHttpException) {
                assertEquals(category, AiFailure.from(error))
                assertFalse(error.message.orEmpty().contains(secret))
            }
        }
    }

    @Test fun invalidInputNeverInvokesTransport() = runBlocking {
        val client = fake(200, "{}") { fail("Invalid input sent network request") }
        for ((url, alias, key) in listOf(Triple("http://outside.invalid/v1", model, secret),
            Triple(base, "invalid model", secret), Triple(base, model, ""))) {
            try {
                client.probe(url, alias, key)
                fail("Expected validation error")
            } catch (_: GatewayInputException) {
                // Correctly rejected before creating a call.
            }
        }
    }

    @Test fun cancellationCancelsUnderlyingOkHttpCall() = runBlocking {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val client = PrivateGatewayClient(OkHttpClient.Builder().addInterceptor { chain ->
            entered.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!chain.call().isCanceled() && System.nanoTime() < deadline) Thread.sleep(10)
            if (chain.call().isCanceled()) cancelled.countDown()
            throw IOException("synthetic failure")
        }.build())
        val job = launch { client.probe(base, model, secret) }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            job.cancelAndJoin()
            assertTrue(withContext(Dispatchers.IO) { cancelled.await(5, TimeUnit.SECONDS) })
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test fun failureMappingDoesNotReadMessages() {
        val cases = listOf(
            UnknownHostException(secret) to AiFailure.DNS,
            SSLException(secret) to AiFailure.TLS,
            ConnectException(secret) to AiFailure.ROUTE,
            SocketTimeoutException(secret) to AiFailure.TIMEOUT,
            IOException(secret) to AiFailure.NETWORK,
            IllegalStateException(secret) to AiFailure.UNKNOWN,
            InvalidAPIKeyException(secret) to AiFailure.GEMINI_KEY,
            QuotaExceededException(secret) to AiFailure.GEMINI_QUOTA,
            RequestTimeoutException(secret) to AiFailure.TIMEOUT,
            SerializationException(secret) to AiFailure.INVALID_RESPONSE,
            GatewayInputException(AiFailure.MODEL, secret) to AiFailure.MODEL
        )
        for ((error, expected) in cases) {
            assertEquals(expected, AiFailure.from(error))
            assertEquals(expected, AiFailure.from(IllegalStateException(secret, error)))
            assertFalse(expected.guidance.contains(secret))
        }
        assertEquals(AiFailure.NOT_FOUND, AiFailure.http(404))
    }
}
