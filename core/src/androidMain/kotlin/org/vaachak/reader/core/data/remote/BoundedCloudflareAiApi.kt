package org.vaachak.reader.core.data.remote

import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.vaachak.reader.core.domain.model.AiImageRequest
import retrofit2.Response

/** Preserve the existing JSON wire contract while bounding bodies before they enter Retrofit. */
internal class BoundedCloudflareAiApi(baseClient: OkHttpClient) : CloudflareAiApi {
    private val client = baseClient.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()
    private val gson = Gson()

    override suspend fun generateImage(fullUrl: String, request: AiImageRequest, token: String): Response<ResponseBody> {
        val url = fullUrl.toHttpUrl()
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) { "Invalid HTTPS image endpoint" }
        require(request.prompt.isNotBlank() && request.prompt.length <= 48_000) { "Invalid image prompt" }
        val payload = gson.toJson(request)
        val call = client.newCall(Request.Builder().url(url)
            .header("Authorization", token).header("Accept", "image/jpeg, image/png, image/webp, application/json")
            .post(payload.toRequestBody("application/json".toMediaType()).oneShotAiBody()).build())
        return call.awaitParsed<Response<ResponseBody>> { response ->
            val raw = response.newBuilder().body(null).build()
            if (!response.isSuccessful) {
                // Never materialize a provider error body, even for a redirect or chunked response.
                Response.error<ResponseBody>("".toResponseBody(), raw)
            } else {
                val body = response.body ?: return@awaitParsed Response.success("".toResponseBody(), raw)
                if (body.contentLength() == 0L) return@awaitParsed Response.success("".toResponseBody(body.contentType()), raw)
                val type = body.contentType()?.let { "${it.type}/${it.subtype}" }
                val limit = AiImagePayload.wireLimit(type)
                if (body.contentLength() > limit) throw AiImageResponseException()
                val bytes = response.peekBody(limit.toLong() + 1).bytes()
                if (bytes.size > limit) throw AiImageResponseException()
                Response.success(bytes.toResponseBody(body.contentType()), raw)
            }
        }
    }
}
