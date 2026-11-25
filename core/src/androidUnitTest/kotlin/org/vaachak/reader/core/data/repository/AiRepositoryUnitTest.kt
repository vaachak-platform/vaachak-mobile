package org.vaachak.reader.core.data.repository

import android.util.Base64
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.vaachak.reader.core.data.remote.CloudflareAiApi
import org.vaachak.reader.core.data.remote.PrivateGatewayClient
import org.vaachak.reader.core.domain.model.AiTextProvider
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import retrofit2.Response

/** P0 provider coexistence and privacy contracts. Never requires real network credentials. */
@RunWith(RobolectricTestRunner::class)
class AiRepositoryUnitTest {
    private val settings = mockk<SettingsRepository>()
    private val cloudflare = mockk<CloudflareAiApi>()
    private val gateway = mockk<PrivateGatewayClient>()
    private val repository = AiRepository(settings, cloudflare, gateway)

    private fun configure(
        offline: Boolean = false,
        provider: AiTextProvider = AiTextProvider.PRIVATE_GATEWAY,
        geminiKey: String = "",
        cfUrl: String = "https://cloudflare.example/image",
        cfToken: String = "image-secret"
    ) {
        every { settings.isOfflineModeEnabled } returns flowOf(offline)
        every { settings.aiTextProvider } returns flowOf(provider)
        every { settings.geminiKey } returns flowOf(geminiKey)
        every { settings.privateGatewayUrl } returns flowOf("https://reader.example.ts.net:8443/v1")
        every { settings.privateGatewayModel } returns flowOf("local-chat")
        coEvery { settings.loadPrivateGatewayToken() } returns "gateway-secret"
        every { settings.cfUrl } returns flowOf(cfUrl)
        every { settings.cfToken } returns flowOf(cfToken)
        coEvery { settings.loadAiRequest(any(), any()) } coAnswers {
            if (offline) throw AiOfflineException()
            val image = firstArg<AiRequestKind>() == AiRequestKind.IMAGE
            AiRequestConfig(
                provider,
                if (image) cfUrl else "https://reader.example.ts.net:8443/v1",
                "local-chat",
                if (image) cfToken else if (provider == AiTextProvider.GEMINI) geminiKey else "gateway-secret",
                MutableStateFlow(false)
            )
        }
    }

    @Test fun offlineModeBlocksEveryTextOperationAndImageRequests() = runBlocking {
        for (provider in AiTextProvider.entries) {
            configure(offline = true, provider = provider)
            val blocked = "Error: Offline Mode is enabled."
            assertEquals(blocked, repository.explainContext("passage"))
            assertEquals(blocked, repository.whoIsThis("Ada", "Book", "Author"))
            assertEquals(blocked, repository.generateRecap("Book", "highlights", "page"))
            assertEquals(blocked, repository.getRecallSummary("Book", "highlights"))
            assertEquals(blocked, repository.visualizeText("scene"))
        }
        coVerify(exactly = 0) { gateway.chat(any(), any(), any(), any()) }
        coVerify(exactly = 0) { cloudflare.generateImage(any(), any(), any()) }
        coVerify(exactly = 0) { settings.loadPrivateGatewayToken() }
    }

    @Test fun privateGatewayReceivesAllFourReaderPromptsWithoutRemoteCalls() = runBlocking {
        configure()
        coEvery { gateway.chat(any(), any(), any(), any()) } returns "Local answer"
        assertEquals("Local answer", repository.explainContext("old pier"))
        assertEquals("Local answer", repository.whoIsThis("Ada", "Novel", "Writer"))
        assertEquals("Local answer", repository.generateRecap("Novel", "chapter notes", "first page"))
        assertEquals("Local answer", repository.getRecallSummary("Novel", "chapter notes"))
        coVerify(exactly = 4) {
            gateway.chat("https://reader.example.ts.net:8443/v1", "local-chat", "gateway-secret", any())
        }
        coVerify(exactly = 0) { cloudflare.generateImage(any(), any(), any()) }
        coVerify {
            gateway.chat(any(), any(), any(), match { it.contains("old pier") && it.contains("2-sentence") })
            gateway.chat(any(), any(), any(), match { it.contains("Ada") && it.contains("without spoilers") })
            gateway.chat(any(), any(), any(), match { it.contains("chapter notes") && it.contains("first page") })
            gateway.chat(any(), any(), any(), match { it.contains("quick 3-sentence recap") })
        }
    }

    @Test fun missingGeminiKeyFailsBeforeAnyGoogleNetworkRequest() = runBlocking {
        configure(provider = AiTextProvider.GEMINI)
        assertEquals("Error: Gemini API Key is missing.", repository.explainContext("passage"))
        assertEquals("Error: Gemini API Key is missing.", repository.getRecallSummary("Novel", "notes"))
        coVerify(exactly = 0) { gateway.chat(any(), any(), any(), any()) }
        coVerify(exactly = 0) { cloudflare.generateImage(any(), any(), any()) }
    }

    @Test fun gatewayExceptionsDoNotLeakArbitraryMessagesOrFallBack() = runBlocking {
        configure()
        val secret = "Bearer gateway-secret passage='private excerpt'"
        coEvery { gateway.chat(any(), any(), any(), any()) } throws IllegalStateException(secret)
        val result = repository.explainContext("private excerpt")
        assertEquals("Error: AI text request failed. Check the selected provider and its connection.", result)
        assertFalse(result.contains("gateway-secret"))
        assertFalse(result.contains("private excerpt"))
        coVerify(exactly = 0) { cloudflare.generateImage(any(), any(), any()) }
    }

    @Test fun imageRequestRequiresBothUrlAndToken() = runBlocking {
        configure(cfUrl = " ")
        assertEquals(
            "Error: Cloudflare not configured. Configure URL/Token in AI Settings.",
            repository.visualizeText("mountain")
        )
        configure(cfToken = " ")
        assertEquals(
            "Error: Cloudflare not configured. Configure URL/Token in AI Settings.",
            repository.visualizeText("mountain")
        )
        coVerify(exactly = 0) { cloudflare.generateImage(any(), any(), any()) }
    }

    @Test fun cloudflareImageStaysIndependentOfBothTextProviderChoices() = runBlocking {
        for (provider in AiTextProvider.entries) {
            configure(provider = provider, cfUrl = " https://cloudflare.example/image ", cfToken = " image-secret ")
            coEvery { cloudflare.generateImage(any(), any(), any()) } returns
                Response.success("raw-image".toResponseBody())
            assertEquals(
                Base64.encodeToString("raw-image".toByteArray(), Base64.DEFAULT),
                repository.visualizeText("mountain")
            )
        }
        coVerify(exactly = 2) {
            cloudflare.generateImage(
                "https://cloudflare.example/image",
                match { it.prompt == "mountain" },
                "Bearer image-secret"
            )
        }
        coVerify(exactly = 0) { gateway.chat(any(), any(), any(), any()) }
    }

    @Test fun successfulHttpResponseWithEmptyBodyIsReported() = runBlocking {
        configure()
        coEvery { cloudflare.generateImage(any(), any(), any()) } returns
            Response.success("".toResponseBody())
        assertEquals("Error: Empty image received from server.", repository.visualizeText("mountain"))
    }

    @Test fun cloudflareErrorsNeverEchoRawProviderResponseBodies() = runBlocking {
        configure()
        for ((code, expected) in listOf(
            400 to "Cloudflare Error 400: Check the configured image endpoint.",
            401 to "Cloudflare Error 401: Check your Cloudflare credentials.",
            403 to "Cloudflare Error 403: Check your Cloudflare credentials.",
            404 to "Cloudflare Error 404: Check the configured image endpoint.",
            429 to "Cloudflare Error 429: Rate limit or quota reached. Retry later.",
            503 to "Cloudflare Error 503: Provider unavailable. Retry later."
        )) {
            coEvery { cloudflare.generateImage(any(), any(), any()) } returns
                Response.error<ResponseBody>(code,
                    "Bearer image-secret, prompt='private passage', url=https://secret.example".toResponseBody())
            val result = repository.visualizeText("private passage")
            assertEquals(expected, result)
            assertFalse(result.contains("image-secret"))
            assertFalse(result.contains("private passage"))
            assertFalse(result.contains("secret.example"))
        }
    }

    @Test fun imageRequestsRejectCleartextAndMalformedEndpointsWithoutNetwork() = runBlocking {
        for (url in listOf(
            "http://cloudflare.example/image",
            "https://user:pass@cloudflare.example/image",
            "garbled URL",
            "https://cloudflare.example/image#fragment"
        )) {
            configure(cfUrl = url)
            assertEquals("Error: Cloudflare URL must be a valid HTTPS endpoint.",
                repository.visualizeText("mountain"))
        }
        coVerify(exactly = 0) { cloudflare.generateImage(any(), any(), any()) }
    }

    @Test fun timeoutDnsTlsAndOtherFailuresAreRedacted() = runBlocking {
        configure()
        coEvery { cloudflare.generateImage(any(), any(), any()) } throws SocketTimeoutException("contains image-secret")
        assertEquals("Error: Timeout. The image generation took too long.", repository.visualizeText("mountain"))
        coEvery { cloudflare.generateImage(any(), any(), any()) } throws UnknownHostException("private passage")
        assertEquals("Error: Cloudflare hostname not found. Check the URL or network.", repository.visualizeText("mountain"))
        coEvery { cloudflare.generateImage(any(), any(), any()) } throws SSLException("secret certificate")
        assertEquals("Error: Cloudflare TLS validation failed.", repository.visualizeText("mountain"))
        coEvery { cloudflare.generateImage(any(), any(), any()) } throws IOException("Bearer image-secret")
        assertEquals("Error: Cloudflare network error. Check your connection.", repository.visualizeText("mountain"))
        coEvery { cloudflare.generateImage(any(), any(), any()) } throws
            IllegalStateException("Bearer image-secret private passage")
        val result = repository.visualizeText("private passage")
        assertEquals("Error: Cloudflare request failed. Check your configuration and connection.", result)
        assertFalse(result.contains("image-secret"))
        assertFalse(result.contains("private passage"))
    }

    @Test fun coroutineCancellationIsPropagatedForBothNetworkOperations() = runBlocking {
        configure()
        coEvery { gateway.chat(any(), any(), any(), any()) } throws CancellationException("cancelled text")
        try {
            repository.explainContext("private passage")
            fail("Text cancellation must be propagated")
        } catch (_: CancellationException) {
            // Expected. Do not turn cancelled work into a successful error response.
        }

        coEvery { cloudflare.generateImage(any(), any(), any()) } throws CancellationException("cancelled image")
        try {
            repository.visualizeText("private passage")
            fail("Image cancellation must be propagated")
        } catch (_: CancellationException) {
            // Expected.
        }
    }
}
