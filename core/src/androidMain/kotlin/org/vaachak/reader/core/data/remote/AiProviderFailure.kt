package org.vaachak.reader.core.data.remote

import com.google.ai.client.generativeai.type.InvalidAPIKeyException
import com.google.ai.client.generativeai.type.QuotaExceededException
import com.google.ai.client.generativeai.type.RequestTimeoutException
import com.google.ai.client.generativeai.type.SerializationException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import retrofit2.HttpException

/** Only fixed messages and numeric status codes may cross the provider boundary. */
internal enum class AiFailure(val guidance: String) {
    OFFLINE("Offline Mode is enabled."),
    URL("Invalid private gateway URL. Check the saved HTTPS API base."),
    MODEL("Invalid or unavailable model. Check the saved model alias."),
    KEY("Private gateway key missing. Configure it in AI Settings."),
    LOCKED("Private gateway key unavailable. Unlock this device and retry."),
    DNS("AI text provider hostname not found."),
    TLS("AI text provider TLS validation failed."),
    ROUTE("Cannot reach the provider. Check network access and Tailscale for a private gateway."),
    TIMEOUT("AI text request timed out."),
    UNAUTHORIZED("Provider rejected authentication (401). Check the saved credential."),
    FORBIDDEN("Provider denied access (403). Check permissions."),
    RATE_LIMIT("Provider rate limit or quota reached (429). Retry later."),
    GEMINI_KEY("Gemini rejected the API key. Check the saved credential."),
    GEMINI_QUOTA("Gemini quota exhausted. Retry later."),
    NOT_FOUND("Provider endpoint or model not found (404). Check configuration."),
    SERVER("AI provider unavailable. Retry later."),
    REDIRECT("Provider redirect rejected. Check the saved endpoint."),
    INVALID_RESPONSE("Provider returned an invalid or incomplete response."),
    NETWORK("AI text provider network error."),
    UNKNOWN("AI text request failed. Check the selected provider and its connection.");

    companion object {
        fun http(code: Int): AiFailure = when (code) {
            401 -> UNAUTHORIZED
            403 -> FORBIDDEN
            404 -> NOT_FOUND
            429 -> RATE_LIMIT
            in 300..399 -> REDIRECT
            in 500..599 -> SERVER
            else -> UNKNOWN
        }

        fun from(error: Throwable): AiFailure {
            // Bounded traversal handles wrapped transport failures without examining messages.
            var cause: Throwable? = error
            repeat(8) {
                val current = cause ?: return UNKNOWN
                when (current) {
                    is GatewayHttpException -> return http(current.status)
                    is GatewayInputException -> return current.failure
                    is GatewayResponseException, is SerializationException -> return INVALID_RESPONSE
                    is InvalidAPIKeyException -> return GEMINI_KEY
                    is QuotaExceededException -> return GEMINI_QUOTA
                    is RequestTimeoutException -> return TIMEOUT
                    is HttpException -> return http(current.code())
                    is UnknownHostException -> return DNS
                    is SSLException -> return TLS
                    is ConnectException, is NoRouteToHostException -> return ROUTE
                    is InterruptedIOException -> return TIMEOUT
                }
                val next = current.cause
                if (next == null || next === current) {
                    return if (current is IOException) NETWORK else UNKNOWN
                }
                cause = next
            }
            return UNKNOWN
        }
    }
}

internal class GatewayHttpException(val status: Int) : IllegalStateException("Private gateway HTTP $status")
internal class GatewayInputException(val failure: AiFailure, message: String) : IllegalArgumentException(message)
internal class GatewayResponseException(message: String) : IllegalStateException(message)
