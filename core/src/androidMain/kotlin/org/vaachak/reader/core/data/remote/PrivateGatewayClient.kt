package org.vaachak.reader.core.data.remote

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Existing R7.1 pilot: authenticated Tailscale HTTPS, nonstreaming text, one model alias. */
object PrivateGatewayEndpoint {
    fun normalizedBase(raw: String): String {
        val uri = try { URI(raw.trim().trimEnd('/')) }
        catch (_: Exception) { throw IllegalArgumentException("Invalid gateway URL") }
        val host = uri.host?.lowercase() ?: throw IllegalArgumentException("Missing gateway hostname")
        require(uri.scheme?.lowercase() == "https" && host.endsWith(".ts.net") && host.length > 7) {
            "Use a private Tailscale HTTPS hostname"
        }
        require(uri.port == 8443 && uri.rawPath == "/v1" && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.rawAuthority?.contains('@') != true) {
            "Use https://<tailnet-host>.ts.net:8443/v1"
        }
        return "https://$host:8443/v1"
    }
    fun validatedModel(raw: String): String = raw.trim().also {
        require(Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,100}$").matches(it)) { "Invalid model alias" }
    }
}

/** A successful catalog probe is not evidence of inference, backend or model health. */
enum class GatewayProbeResult {
    MODEL_LISTED, MODEL_NOT_LISTED, CATALOG_UNSUPPORTED, CATALOG_UNRECOGNIZED
}

@Singleton
class PrivateGatewayClient @Inject constructor(baseClient: OkHttpClient) {
    private val client = baseClient.newBuilder()
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(135, TimeUnit.SECONDS)
        .callTimeout(145, TimeUnit.SECONDS)
        .build()
    private val diagnosticClient = client.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private fun base(raw: String): String = try {
        PrivateGatewayEndpoint.normalizedBase(raw)
    } catch (error: IllegalArgumentException) {
        throw GatewayInputException(AiFailure.URL, error.message ?: "Invalid gateway URL")
    }

    private fun model(raw: String): String = try {
        PrivateGatewayEndpoint.validatedModel(raw)
    } catch (_: IllegalArgumentException) {
        throw GatewayInputException(AiFailure.MODEL, "Invalid model alias")
    }

    private fun authorized(url: String, bearer: String): Request.Builder {
        if (bearer.isBlank()) {
            throw GatewayInputException(AiFailure.KEY, "Set the Vaachak gateway key in AI Settings")
        }
        return Request.Builder().url(url)
            .header("Authorization", "Bearer $bearer")
            .header("Accept", "application/json")
    }

    suspend fun chat(base: String, modelId: String, bearer: String, prompt: String): String {
        val url = base(base)
        val model = model(modelId)
        require(prompt.isNotBlank() && prompt.length <= 24_000) { "Text exceeds gateway limit" }
        val payload = JSONObject()
            .put("model", model)
            .put("stream", false)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        val request = authorized("$url/chat/completions", bearer)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return client.newCall(request).awaitParsed(::parseChat)
    }

    /** Optional compatibility probe only. A missing /models route never triggers inference. */
    suspend fun probe(base: String, modelId: String, bearer: String): GatewayProbeResult {
        val url = base(base)
        val model = model(modelId)
        val request = authorized("$url/models", bearer).get().build()
        return diagnosticClient.newCall(request).awaitParsed { parseCatalog(it, model) }
    }

    private fun parseChat(response: Response): String {
        if (response.code != 200) throw GatewayHttpException(response.code)
        val body = response.peekBody(131_072L).string()
        if (body.isBlank()) throw GatewayResponseException("Empty gateway response")
        val result = try {
            JSONObject(body).optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content")?.trim().orEmpty()
        } catch (_: JSONException) {
            throw GatewayResponseException("Invalid gateway response")
        }
        if (result.isEmpty()) throw GatewayResponseException("Gateway returned no text")
        return result
    }

    private fun parseCatalog(response: Response, model: String): GatewayProbeResult {
        if (response.code == 404 || response.code == 405 || response.code == 501) {
            return GatewayProbeResult.CATALOG_UNSUPPORTED
        }
        if (response.code != 200) throw GatewayHttpException(response.code)
        // Bounded body; reject truncated/oversized or unrecognized catalogs rather than claim success.
        val bytes = response.peekBody(65_537L).bytes()
        if (bytes.size > 65_536) return GatewayProbeResult.CATALOG_UNRECOGNIZED
        val entries = try {
            JSONObject(bytes.toString(Charsets.UTF_8)).optJSONArray("data")
        } catch (_: JSONException) {
            null
        } ?: return GatewayProbeResult.CATALOG_UNRECOGNIZED
        for (index in 0 until entries.length()) {
            val id = entries.optJSONObject(index)?.optString("id").orEmpty()
            if (id.isBlank()) return GatewayProbeResult.CATALOG_UNRECOGNIZED
        }
        return if ((0 until entries.length()).any { entries.optJSONObject(it)?.optString("id") == model }) {
            GatewayProbeResult.MODEL_LISTED
        } else {
            GatewayProbeResult.MODEL_NOT_LISTED
        }
    }
}

/** Cancellation remains connected to the call through response body parsing and closure. */
internal suspend fun <T> Call.awaitParsed(parse: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                val result = response.use { if (continuation.isActive) parse(it) else return }
                continuation.resume(result)
            } catch (error: Exception) {
                continuation.resumeWithException(error)
            }
        }
    })
}
