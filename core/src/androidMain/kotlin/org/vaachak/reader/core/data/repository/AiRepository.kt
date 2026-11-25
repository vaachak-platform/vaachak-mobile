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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import org.vaachak.reader.core.data.remote.AiFailure
import org.vaachak.reader.core.data.remote.CloudflareAiApi
import org.vaachak.reader.core.data.remote.GatewayProbeResult
import org.vaachak.reader.core.data.remote.PrivateGatewayClient
import org.vaachak.reader.core.domain.model.AiImageRequest
import org.vaachak.reader.core.domain.model.AiTextProvider

/** A point-in-time compatibility report, never a claim of successful text inference. */
enum class GatewayDiagnostic(val label: String) {
    MODEL_LISTED("Model listed by gateway. Text inference and authentication enforcement are not verified."),
    MODEL_NOT_LISTED("Model not listed. Check the saved alias; text inference was not tested."),
    UNSUPPORTED("Model catalog unsupported. Text inference and authentication are not verified."),
    UNRECOGNIZED("Unrecognized model catalog. Compatibility is not verified."),
    NOT_SELECTED("Select and save Private Text Gateway before testing."),
    OFFLINE("Offline Mode is enabled. No request sent."),
    FAILED("Gateway compatibility check failed.");
}

class GatewayDiagnosticReport(val status: GatewayDiagnostic, val message: String = status.label)

/** Independent Gemini text, Gateway text and direct Cloudflare image paths. */
class AiRepository @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val cloudflareApi: CloudflareAiApi,
    private val privateGatewayClient: PrivateGatewayClient
) {
    private suspend fun configuration(kind: AiRequestKind): AiRequestConfig =
        settingsRepo.loadAiRequest(kind, currentCoroutineContext()[AiVaultScope]?.vaultId)

    private suspend fun generateText(prompt: String): String {
        val config = configuration(AiRequestKind.TEXT)
        return config.runGuarded {
            withTimeoutOrNull(150_000L) {
                when (config.provider) {
                    AiTextProvider.GEMINI -> {
                        if (config.credential.isBlank()) throw MissingGeminiKey()
                        GenerativeModel(modelName = "gemini-2.0-flash", apiKey = config.credential)
                            .generateContent(prompt).text ?: "No answer."
                    }
                    AiTextProvider.PRIVATE_GATEWAY -> privateGatewayClient.chat(
                        config.url, config.model, config.credential, prompt
                    )
                }
            } ?: "Error: AI text request timed out."
        }
    }

    private class MissingGeminiKey : IllegalStateException()

    private fun safeTextError(error: Exception): String = when (error) {
        is AiOfflineException -> "Error: ${AiFailure.OFFLINE.guidance}"
        is MissingGeminiKey -> "Error: Gemini API Key is missing."
        is GatewayCredentialUnavailableException -> "Error: ${AiFailure.LOCKED.guidance}"
        else -> "Error: ${AiFailure.from(error).guidance}"
    }

    private suspend fun textAction(prompt: String): String = try {
        generateText(prompt)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        safeTextError(error)
    }

    suspend fun testSavedGateway(): GatewayDiagnosticReport = try {
        val config = configuration(AiRequestKind.TEXT)
        if (config.provider != AiTextProvider.PRIVATE_GATEWAY) {
            GatewayDiagnosticReport(GatewayDiagnostic.NOT_SELECTED)
        } else {
            config.runGuarded {
                val probe = privateGatewayClient.probe(config.url, config.model, config.credential)
                GatewayDiagnosticReport(when (probe) {
                    GatewayProbeResult.MODEL_LISTED -> GatewayDiagnostic.MODEL_LISTED
                    GatewayProbeResult.MODEL_NOT_LISTED -> GatewayDiagnostic.MODEL_NOT_LISTED
                    GatewayProbeResult.CATALOG_UNSUPPORTED -> GatewayDiagnostic.UNSUPPORTED
                    GatewayProbeResult.CATALOG_UNRECOGNIZED -> GatewayDiagnostic.UNRECOGNIZED
                })
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: AiOfflineException) {
        GatewayDiagnosticReport(GatewayDiagnostic.OFFLINE)
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        GatewayDiagnosticReport(GatewayDiagnostic.FAILED, safeTextError(error))
    }

    /** Only the user-configured Cloudflare image endpoint is used for Visualize. */
    suspend fun visualizeText(prompt: String): String = try {
        val config = configuration(AiRequestKind.IMAGE)
        config.runGuarded {
            withTimeoutOrNull(150_000L) { requestImage(config, prompt) }
                ?: "Error: Timeout. The image generation took too long."
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: AiOfflineException) {
        "Error: Offline Mode is enabled."
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

    private suspend fun requestImage(config: AiRequestConfig, prompt: String): String {
        val url = config.url.trim()
        val token = config.credential.trim()
        if (url.isEmpty() || token.isEmpty()) {
            return "Error: Cloudflare not configured. Configure URL/Token in AI Settings."
        }
        if (!isValidHttpsEndpoint(url)) return "Error: Cloudflare URL must be a valid HTTPS endpoint."
        val response = cloudflareApi.generateImage(
            fullUrl = url, request = AiImageRequest(prompt = prompt), token = "Bearer $token"
        )
        if (!response.isSuccessful) {
            response.errorBody()?.close()
            return cloudflareStatusError(response.code())
        }
        val bytes = response.body()?.use { it.bytes() }
        return if (bytes != null && bytes.isNotEmpty()) {
            Base64.encodeToString(bytes, Base64.DEFAULT)
        } else {
            "Error: Empty image received from server."
        }
    }

    private fun isValidHttpsEndpoint(raw: String): Boolean = try {
        val uri = URI(raw)
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawFragment == null
    } catch (_: Exception) {
        false
    }

    private fun cloudflareStatusError(code: Int): String = when (code) {
        400, 404 -> "Cloudflare Error $code: Check the configured image endpoint."
        401, 403 -> "Cloudflare Error $code: Check your Cloudflare credentials."
        429 -> "Cloudflare Error 429: Rate limit or quota reached. Retry later."
        in 300..399 -> "Cloudflare Error $code: Redirect rejected. Configure the final HTTPS endpoint."
        in 500..599 -> "Cloudflare Error $code: Provider unavailable. Retry later."
        else -> "Cloudflare Error $code: Image request failed."
    }

    suspend fun explainContext(selectedText: String): String = textAction(
        "Provide a simple, 2-sentence explanation for: $selectedText"
    )

    suspend fun whoIsThis(selectedText: String, bookTitle: String, bookAuthor: String): String = textAction(
        "Reading '$bookTitle' by $bookAuthor. Identify character '$selectedText' without spoilers."
    )

    suspend fun generateRecap(bookTitle: String, highlightsContext: String, currentPageText: String): String = textAction(
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
