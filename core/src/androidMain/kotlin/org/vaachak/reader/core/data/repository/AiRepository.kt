package org.vaachak.reader.core.data.repository

import android.util.Base64
import com.google.ai.client.generativeai.GenerativeModel
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import javax.inject.Inject
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.vaachak.reader.core.data.remote.CloudflareAiApi
import org.vaachak.reader.core.data.remote.PrivateGatewayClient
import org.vaachak.reader.core.domain.model.AiImageRequest
import org.vaachak.reader.core.domain.model.AiTextProvider

/** Preserves independent Gemini text, Gateway text, and direct Cloudflare image paths. */
class AiRepository @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val cloudflareApi: CloudflareAiApi,
    private val privateGatewayClient: PrivateGatewayClient
) {
    private suspend fun getGeminiModel(): GenerativeModel {
        val key = settingsRepo.geminiKey.first()
        if (key.isBlank()) throw IllegalStateException("Gemini API Key is missing.")
        return GenerativeModel(modelName = "gemini-2.0-flash", apiKey = key)
    }

    private suspend fun generateText(prompt: String): String {
        if (settingsRepo.isOfflineModeEnabled.first()) return "Error: Offline Mode is enabled."
        return when (settingsRepo.aiTextProvider.first()) {
            AiTextProvider.GEMINI -> getGeminiModel().generateContent(prompt).text ?: "No answer."
            AiTextProvider.PRIVATE_GATEWAY -> privateGatewayClient.chat(
                settingsRepo.privateGatewayUrl.first(),
                settingsRepo.privateGatewayModel.first(),
                settingsRepo.loadPrivateGatewayToken(),
                prompt
            )
        }
    }

    /** Never reflect arbitrary provider exception strings: they can contain secrets or excerpts. */
    private fun safeTextError(error: Exception): String = when {
        error.message == "Gemini API Key is missing." -> "Error: Gemini API Key is missing."
        error is GatewayCredentialUnavailableException ->
            "Error: Private gateway key unavailable. Unlock this device and retry."
        error is SocketTimeoutException -> "Error: AI text request timed out."
        error is UnknownHostException -> "Error: AI text provider hostname not found."
        error is SSLException -> "Error: AI text provider TLS validation failed."
        error is IOException -> "Error: AI text provider network error."
        else -> "Error: AI text request failed. Check the selected provider and its connection."
    }

    private suspend fun textAction(prompt: String): String = try {
        generateText(prompt)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        safeTextError(error)
    }

    /** Only the user-configured Cloudflare image endpoint is used for Visualize. */
    suspend fun visualizeText(prompt: String): String {
        if (settingsRepo.isOfflineModeEnabled.first()) return "Error: Offline Mode is enabled."
        return try {
            val url = settingsRepo.cfUrl.first().trim()
            val token = settingsRepo.cfToken.first().trim()
            if (url.isEmpty() || token.isEmpty()) {
                return "Error: Cloudflare not configured. Configure URL/Token in AI Settings."
            }
            if (!isValidHttpsEndpoint(url)) {
                return "Error: Cloudflare URL must be a valid HTTPS endpoint."
            }

            val response = cloudflareApi.generateImage(
                fullUrl = url,
                request = AiImageRequest(prompt = prompt),
                token = "Bearer $token"
            )

            if (response.isSuccessful) {
                val bytes = response.body()?.bytes()
                if (bytes != null && bytes.isNotEmpty()) {
                    Base64.encodeToString(bytes, Base64.DEFAULT)
                } else {
                    "Error: Empty image received from server."
                }
            } else {
                // Provider error bodies may echo Authorization, URLs or reading excerpts.
                // Close the body, but never display or log its contents.
                response.errorBody()?.close()
                cloudflareStatusError(response.code())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SocketTimeoutException) {
            "Error: Timeout. The image generation took too long."
        } catch (_: UnknownHostException) {
            "Error: Cloudflare hostname not found. Check the URL or network."
        } catch (_: SSLException) {
            "Error: Cloudflare TLS validation failed."
        } catch (_: IOException) {
            "Error: Cloudflare network error. Check your connection."
        } catch (_: Exception) {
            "Error: Cloudflare request failed. Check your configuration and connection."
        }
    }

    private fun isValidHttpsEndpoint(raw: String): Boolean = try {
        val uri = URI(raw)
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null &&
            uri.rawFragment == null
    } catch (_: Exception) {
        false
    }

    private fun cloudflareStatusError(code: Int): String = when (code) {
        400, 404 -> "Cloudflare Error $code: Check the configured image endpoint."
        401, 403 -> "Cloudflare Error $code: Check your Cloudflare credentials."
        429 -> "Cloudflare Error 429: Rate limit or quota reached. Retry later."
        in 500..599 -> "Cloudflare Error $code: Provider unavailable. Retry later."
        else -> "Cloudflare Error $code: Image request failed."
    }

    suspend fun explainContext(selectedText: String): String = textAction(
        "Provide a simple, 2-sentence explanation for: $selectedText"
    )

    suspend fun whoIsThis(selectedText: String, bookTitle: String, bookAuthor: String): String = textAction(
        "Reading '$bookTitle' by $bookAuthor. Identify character '$selectedText' without spoilers."
    )

    suspend fun generateRecap(
        bookTitle: String,
        highlightsContext: String,
        currentPageText: String
    ): String = textAction(
        """
            I am returning to read '$bookTitle'.
            Based on my previous highlights: "$highlightsContext"
            And current page: "$currentPageText"
            Provide a quick 3-sentence recap and 'Who's Who' for this scene. Avoid future spoilers.
        """.trimIndent()
    )

    suspend fun getRecallSummary(bookTitle: String, highlightsContext: String): String = textAction(
        """
            I am returning to read '$bookTitle'.
            Based on highlights: "$highlightsContext"
            Provide a quick 3-sentence recap. Avoid future spoilers.
        """.trimIndent()
    )
}
