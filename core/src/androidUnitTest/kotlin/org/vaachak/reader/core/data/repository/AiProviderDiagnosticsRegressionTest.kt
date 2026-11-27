package org.vaachak.reader.core.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.vaachak.reader.core.data.remote.*
import org.vaachak.reader.core.domain.model.AiTextProvider
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AiProviderDiagnosticsRegressionTest {
    private val settings = mockk<SettingsRepository>()
    private val gateway = mockk<PrivateGatewayClient>()
    private val gemini = mockk<GeminiRestClient>()
    private val cloudflare = mockk<CloudflareAiApi>()
    private val repo = AiRepository(settings, cloudflare, gateway, gemini)
    private fun configure(provider: AiTextProvider = AiTextProvider.GEMINI, model: String = "gemini-flash-latest", key: String = "synthetic-key") {
        coEvery { settings.loadAiRequest(any(), any()) } answers {
            AiRequestConfig(provider, "https://fixture.invalid/image", model, key, MutableStateFlow(false))
        }
    }

    @Test fun explicitModelDrivesAllFourTextActionsAndSyntheticDiagnostic() = runBlocking {
        configure()
        coEvery { gemini.generate(any(), any(), any()) } returns "synthetic answer"
        assertEquals("synthetic answer", repo.explainContext("excerpt"))
        assertEquals("synthetic answer", repo.whoIsThis("character", "book", "author"))
        assertEquals("synthetic answer", repo.generateRecap("book", "notes", "page"))
        assertEquals("synthetic answer", repo.getRecallSummary("book", "notes"))
        assertEquals("Gemini returned a complete text answer.", repo.testSavedGemini())
        coVerify(exactly = 5) { gemini.generate("gemini-flash-latest", "synthetic-key", any()) }
        coVerify(exactly = 1) { gemini.generate(any(), any(), "Explain the phrase The silence was deafening in two sentences.") }
        coVerify(exactly = 0) { gateway.chat(any(), any(), any(), any()) }
        coVerify(exactly = 0) { cloudflare.generateImage(any(), any(), any()) }
    }

    @Test fun catalogAndDiagnosticRejectMissingOrWrongProviderAndModel() = runBlocking {
        configure(provider = AiTextProvider.PRIVATE_GATEWAY)
        assertTrue(repo.discoverSavedGeminiModels().models.isEmpty())
        assertEquals("Select and save Gemini first.", repo.testSavedGemini())
        configure(key = "")
        assertEquals("Save a Gemini key first.", repo.discoverSavedGeminiModels().message)
        assertTrue(repo.testSavedGemini().contains("missing", ignoreCase = true))
        configure(model = "")
        assertTrue(repo.explainContext("excerpt").contains("Select and save a Gemini text model"))
        assertTrue(repo.testSavedGemini().contains("Select and save a Gemini text model"))
        coVerify(exactly = 0) { gemini.generate(any(), any(), any()) }
        coVerify(exactly = 0) { gemini.listTextCandidates(any()) }
        configure()
        coEvery { gemini.listTextCandidates("synthetic-key") } returns listOf("gemini-flash-latest")
        assertEquals(listOf("gemini-flash-latest"), repo.discoverSavedGeminiModels().models)
        coEvery { gemini.listTextCandidates(any()) } throws IllegalStateException("secret provider detail")
        val failed = repo.discoverSavedGeminiModels()
        assertTrue(failed.models.isEmpty()); assertFalse(failed.message.contains("secret provider detail"))
    }

    @Test fun cloudflareDiagnosticUsesFixedPromptAndValidatesRealImage() = runBlocking {
        configure()
        val png = android.util.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFklEQVR4nGMU0bBhYGBgYmBgYGBgAAAFogB8q5SvRgAAAABJRU5ErkJggg==", android.util.Base64.NO_WRAP)
        coEvery { cloudflare.generateImage(any(), any(), any()) } answers { Response.success(png.toResponseBody("image/png".toMediaType())) }
        assertEquals("Cloudflare returned a decodable image.", repo.testSavedCloudflare())
        coVerify(exactly = 1) { cloudflare.generateImage("https://fixture.invalid/image", match { it.prompt == "A simple line drawing of a lighthouse, no text, plain background." }, "Bearer synthetic-key") }
        for (code in listOf(402, 413, 418)) {
            coEvery { cloudflare.generateImage(any(), any(), any()) } answers { Response.error(code, "secret provider detail".toResponseBody()) }
            val result = repo.testSavedCloudflare()
            assertTrue(result.startsWith("Cloudflare Error")); assertFalse(result.contains("secret provider detail"))
        }
    }
}
