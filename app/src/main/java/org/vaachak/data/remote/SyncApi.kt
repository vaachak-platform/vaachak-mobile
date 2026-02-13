package org.vaachak.data.remote

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.vaachak.data.remote.dto.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncApi @Inject constructor() {

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                prettyPrint = true
                isLenient = true
                encodeDefaults = true
            })
        }
        install(io.ktor.client.plugins.HttpTimeout) {
            requestTimeoutMillis = 15000 // 15 seconds
            connectTimeoutMillis = 10000 // 10 seconds
            socketTimeoutMillis = 15000
        }
        install(Logging) {
            level = LogLevel.ALL
        }
    }

    // Default fallback - will be overwritten by setBaseUrl from Repository
    private var baseUrl = "https://vaachak-sync.ai-mindseye.workers.dev"

    fun setBaseUrl(url: String) {
        if (url.isNotBlank()) {
            // Remove spaces and trailing slashes
            var cleanedUrl = url.trim().trimEnd('/')

            // Ensure protocol exists
            if (!cleanedUrl.startsWith("http")) {
                cleanedUrl = "https://$cleanedUrl"
            }

            this.baseUrl = cleanedUrl
            Log.d("SyncApi", "Base URL standardizing to: $baseUrl")
        }
    }

    /**
     * SYNC: Sends SyncRequest (which includes SyncAuthDto credentials)
     */
    suspend fun syncData(request: SyncRequest): Result<SyncResponse> = withContext(Dispatchers.IO) {
        return@withContext try {
            Log.d("SyncApi", "Network call started to: $baseUrl/api/v1/sync")
            val response = client.post("$baseUrl/api/v1/sync") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }

            Log.d("SyncApi", "Response received: ${response.status}")

            when (response.status.value) {
                in 200..299 -> Result.success(response.body<SyncResponse>())
                401 -> Result.failure(Exception("Invalid username or password"))
                else -> Result.failure(Exception("Server Error: ${response.status.value}"))
            }
        } catch (e: Exception) {
            Log.e("SyncApi", "CRITICAL NETWORK ERROR: ${e.localizedMessage}", e)
            Result.failure(e)
        }
    }

    /**
     * REGISTER: Sends RegisterRequest with credentials
     */
    suspend fun register(request: RegisterRequest): Result<Unit> {
        return try {
            val response = client.post("$baseUrl/api/v1/register") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }

            when (response.status.value) {
                in 200..299 -> Result.success(Unit)
                409 -> Result.failure(Exception("Username already taken"))
                else -> Result.failure(Exception("Registration failed: ${response.status.value}"))
            }
        } catch (e: Exception) {
            Log.e("SyncApi", "Registration Error: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * TEST: Lightweight health check
     */
    suspend fun testConnection(): Result<Unit> {
        return try {
            val response = client.get("$baseUrl/api/v1/health")
            if (response.status.value in 200..299) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Server returned ${response.status.value}"))
            }
        } catch (e: Exception) {
            Log.e("SyncApi", "Health check failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun getInbox(deviceId: String): Result<List<InboxItemDto>> {
        return try {
            val response = client.get("$baseUrl/api/v1/inbox") {
                url { parameters.append("device_id", deviceId) }
            }
            if (response.status.value in 200..299) {
                Result.success(response.body<List<InboxItemDto>>())
            } else {
                Result.failure(Exception("Inbox Error: ${response.status.value}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun login(request: RegisterRequest): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            // Log the exact URL for debugging
            val fullUrl = "${baseUrl.trimEnd('/')}/api/v1/login"
            Log.d("SyncDebug", "Login call to: $fullUrl")

            val response = client.post(fullUrl) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }

            Log.d("SyncDebug", "Login Response: ${response.status.value}")

            if (response.status.value in 200..299) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Login failed: ${response.status.value}"))
            }
        } catch (e: Exception) {
            Log.e("SyncDebug", "Login Exception", e)
            Result.failure(e)
        }
    }
}