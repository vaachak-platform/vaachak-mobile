package org.vaachak.reader.core.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Catalog eligibility is only a heuristic, not proof of text inference entitlement. */
object GeminiTextModels {
    private val idPattern = Regex("^gemini-[a-z0-9][a-z0-9.-]{0,100}$")
    private val excluded = Regex("(^|-)(image|tts|audio|live|robotics|computer|transcribe|omni|customtools|nano|banana)(-|$)")
    fun normalize(raw: String): String = raw.trim().removePrefix("models/").also {
        require(idPattern.matches(it) && !excluded.containsMatchIn(it)) { "Select a Gemini text model" }
    }
    fun isCandidate(raw: String): Boolean = runCatching { normalize(raw) }.isSuccess
}

internal class GeminiHttpException(val status: Int) : IllegalStateException("Gemini HTTP $status")
internal class GeminiResponseException : IllegalStateException("Gemini returned no complete text answer")

/** Fixed Google HTTPS origin, no redirects or automatic connection replay. No raw body or key in errors. */
@Singleton
class GeminiRestClient @Inject constructor(baseClient: OkHttpClient) {
    private val client = baseClient.newBuilder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(135, TimeUnit.SECONDS).build()
    private val base = "https://generativelanguage.googleapis.com/v1beta/".toHttpUrl()

    private fun request(key: String): Request.Builder {
        require(key.isNotBlank()) { "Gemini key missing" }
        return Request.Builder().header("x-goog-api-key", key).header("Accept", "application/json")
    }

    suspend fun generate(model: String, key: String, prompt: String): String {
        val id = GeminiTextModels.normalize(model)
        require(prompt.isNotBlank() && prompt.length <= 48_000) { "Invalid reader prompt length" }
        val payload = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts",
            JSONArray().put(JSONObject().put("text", prompt)))))
        val url = base.newBuilder().addPathSegment("models").addPathSegment("$id:generateContent").build()
        val req = request(key).url(url).post(payload.toString().toRequestBody("application/json".toMediaType()).oneShotAiBody()).build()
        return client.newCall(req).awaitParsed { response -> parseAnswer(readJson(response, 512 * 1024)) }
    }

    /** Bounded pagination; never run inference to discover models. */
    suspend fun listTextCandidates(key: String): List<String> {
        val models = linkedSetOf<String>()
        val seen = mutableSetOf<String>()
        var token = ""
        repeat(8) {
            val url = base.newBuilder().addPathSegment("models").addQueryParameter("pageSize", "100")
            if (token.isNotEmpty()) url.addQueryParameter("pageToken", token)
            val page = client.newCall(request(key).url(url.build()).get().build())
                .awaitParsed { readJson(it, 1024 * 1024) }
            val rows = page.optJSONArray("models") ?: throw GeminiResponseException()
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: throw GeminiResponseException()
                val id = row.optString("name")
                val methods = row.optJSONArray("supportedGenerationMethods") ?: continue
                if (GeminiTextModels.isCandidate(id) && (0 until methods.length()).any {
                        methods.optString(it) == "generateContent"
                    }) models.add(GeminiTextModels.normalize(id))
            }
            token = page.optString("nextPageToken")
            if (token.isEmpty()) return models.sorted()
            if (token.length > 4096 || !seen.add(token)) throw GeminiResponseException()
        }
        throw GeminiResponseException()
    }

    private fun readJson(response: Response, max: Int): JSONObject {
        if (response.code != 200) throw GeminiHttpException(response.code)
        val bytes = response.peekBody(max.toLong() + 1).bytes()
        if (bytes.isEmpty() || bytes.size > max) throw GeminiResponseException()
        return try { JSONObject(bytes.toString(Charsets.UTF_8)) }
        catch (_: JSONException) { throw GeminiResponseException() }
    }

    internal fun parseAnswer(result: JSONObject): String {
        val candidate = result.optJSONArray("candidates")?.optJSONObject(0) ?: throw GeminiResponseException()
        if (candidate.optString("finishReason") != "STOP") throw GeminiResponseException()
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: throw GeminiResponseException()
        val text = (0 until parts.length()).mapNotNull { index ->
            val part = parts.optJSONObject(index) ?: return@mapNotNull null
            if (part.optBoolean("thought")) null else part.opt("text") as? String
        }.joinToString("\n").trim()
        if (text.isBlank() || text.length > 32_000) throw GeminiResponseException()
        return text
    }
}
