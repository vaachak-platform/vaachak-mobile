package org.vaachak.reader.core.data.remote

import org.vaachak.reader.core.common.AppCoroutineConfig
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** First pilot supports authenticated Tailscale HTTPS, nonstreaming text, and one model alias. */
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

    suspend fun chat(base: String, modelId: String, bearer: String, prompt: String): String =
        withContext(AppCoroutineConfig.io) {
            val url = PrivateGatewayEndpoint.normalizedBase(base)
            val model = PrivateGatewayEndpoint.validatedModel(modelId)
            require(bearer.isNotBlank()) { "Set the Vaachak gateway key in AI Settings" }
            require(prompt.isNotBlank() && prompt.length <= 24_000) { "Text exceeds gateway limit" }
            val payload = JSONObject()
                .put("model", model)
                .put("stream", false)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            val request = Request.Builder().url("$url/chat/completions")
                .header("Authorization", "Bearer $bearer")
                .header("Accept", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                check(response.code == 200) { "Private gateway HTTP ${response.code}" }
                val body = response.peekBody(131_072L).string()
                check(body.isNotBlank()) { "Empty gateway response" }
                val result = JSONObject(body).optJSONArray("choices")
                    ?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                    ?.trim().orEmpty()
                check(result.isNotEmpty()) { "Gateway returned no text" }
                result
            }
        }
}
