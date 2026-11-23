package org.vaachak.reader.core.data.repository

import android.util.Base64
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.vaachak.reader.core.data.remote.CloudflareAiApi
import org.vaachak.reader.core.data.remote.PrivateGatewayClient
import org.vaachak.reader.core.domain.model.AiTextProvider
import java.net.SocketTimeoutException
import retrofit2.Response

/** Real AI repository branching, isolated from any live network or API credentials. */
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
        cfUrl: String = "https://gateway.example/image",
        cfToken: String = "image-secret"
    ) {
        every { settings.isOfflineModeEnabled } returns flowOf(offline)
        every { settings.aiTextProvider } returns flowOf(provider)
        every { settings.geminiKey } returns flowOf(geminiKey)
        every { settings.privateGatewayUrl } returns flowOf("https://reader.tailnet.ts.net:8443/v1")
        every { settings.privateGatewayModel } returns flowOf("local-chat")
        coEvery { settings.loadPrivateGatewayToken() } returns "gateway-secret"
        every { settings.cfUrl } returns flowOf(cfUrl)
        every { settings.cfToken } returns flowOf(cfToken)
    }

    @Test fun offlineModeBlocksEveryTextOperationAndImageRequests() = runBlocking {
        configure(offline = true)
        val blocked = "Error: Offline Mode is enabled."
        assertEquals(blocked, repository.explainContext("passage"))
        assertEquals(blocked, repository.whoIsThis("Ada", "Book", "Author"))
        assertEquals(blocked, repository.generateRecap("Book", "highlights", "page"))
        assertEquals(blocked, repository.getRecallSummary("Book", "highlights"))
        assertEquals(blocked, repository.visualizeText("scene"))
    }

    @Test fun privateGatewayReceivesAllFourReaderPromptsWithoutRemoteCalls() = runBlocking {
        configure()
        coEvery { gateway.chat(any(), any(), any(), any()) } returns "Local answer"
        assertEquals("Local answer", repository.explainContext("old pier"))
        assertEquals("Local answer", repository.whoIsThis("Ada", "Novel", "Writer"))
        assertEquals("Local answer", repository.generateRecap("Novel", "chapter notes", "first page"))
        assertEquals("Local answer", repository.getRecallSummary("Novel", "chapter notes"))
        coVerify(exactly = 4) {
            gateway.chat("https://reader.tailnet.ts.net:8443/v1", "local-chat", "gateway-secret", any())
        }
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
    }

    @Test fun gatewayExceptionsBecomeUserFacingErrorsWithoutLeakingConfiguration() = runBlocking {
        configure()
        coEvery { gateway.chat(any(), any(), any(), any()) } throws IllegalStateException("Gateway unavailable")
        assertEquals("Error: Gateway unavailable", repository.explainContext("passage"))
        assertEquals("Error: Gateway unavailable", repository.whoIsThis("Ada", "Novel", "Writer"))
    }

    @Test fun imageRequestRequiresBothUrlAndToken() = runBlocking {
        configure(cfUrl = " ")
        assertEquals(
            "Error: AI Settings missing. Please configure URL/Token in Settings.",
            repository.visualizeText("mountain")
        )
        configure(cfToken = " ")
        assertEquals(
            "Error: AI Settings missing. Please configure URL/Token in Settings.",
            repository.visualizeText("mountain")
        )
    }

    @Test fun successfulImageResponseEncodesBytesAndSendsBearerToken() = runBlocking {
        configure(cfUrl = " https://gateway.example/image ", cfToken = " image-secret ")
        coEvery { cloudflare.generateImage(any(), any(), any()) } returns
            Response.success("raw-image".toResponseBody())
        assertEquals(
            Base64.encodeToString("raw-image".toByteArray(), Base64.DEFAULT),
            repository.visualizeText("mountain")
        )
        coVerify(exactly = 1) {
            cloudflare.generateImage(
                "https://gateway.example/image",
                match { it.prompt == "mountain" },
                "Bearer image-secret"
            )
        }
    }

    @Test fun successfulHttpResponseWithEmptyBodyIsReported() = runBlocking {
        configure()
        coEvery { cloudflare.generateImage(any(), any(), any()) } returns
            Response.success("".toResponseBody())
        assertEquals("Error: Empty image received from server.", repository.visualizeText("mountain"))
    }

    @Test fun unsuccessfulImageResponseIncludesStatusAndResponseBody() = runBlocking {
        configure()
        coEvery { cloudflare.generateImage(any(), any(), any()) } returns
            Response.error<ResponseBody>(429, "rate limited".toResponseBody())
        assertEquals("Cloudflare Error 429: rate limited", repository.visualizeText("mountain"))
    }

    @Test fun timeoutAndOtherFailuresAreConvertedToVisibleErrors() = runBlocking {
        configure()
        coEvery { cloudflare.generateImage(any(), any(), any()) } throws SocketTimeoutException("slow server")
        assertEquals(
            "Error: Timeout. The image generation took too long.",
            repository.visualizeText("mountain")
        )
        coEvery { cloudflare.generateImage(any(), any(), any()) } throws IllegalStateException("Network unavailable")
        assertEquals("Error: Network unavailable", repository.visualizeText("mountain"))
    }
}
