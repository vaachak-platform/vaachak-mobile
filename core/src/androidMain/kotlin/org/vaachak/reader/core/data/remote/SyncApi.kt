/*
 * Copyright (c) 2026 Piyush Daiya
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.vaachak.reader.core.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.vaachak.reader.core.data.remote.dto.LoginRequest
import org.vaachak.reader.core.data.remote.dto.RegisterRequest
import org.vaachak.reader.core.data.remote.dto.SyncDto
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Credential-bearing requests must never have body-level HTTP logging enabled.
 * Ktor's Logging(LogLevel.ALL) previously logged JSON request bodies, including
 * sync authentication. HTTPS is mandatory for cloud and local servers alike.
 */
@Singleton
class SyncApi @Inject constructor() {
    private val client = HttpClient(CIO) {
        // Do not forward credential-bearing requests to redirect destinations.
        followRedirects = false
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                prettyPrint = true
                isLenient = true
                encodeDefaults = true
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 15_000
        }
        // Intentionally no Ktor Logging plugin. Even HEADERS can disclose Basic
        // credentials; BODY/ALL can expose the sync password JSON payload.
    }

    private var baseUrl = "https://vaachak-sync.ai-mindseye.workers.dev"

    fun setBaseUrl(url: String) {
        baseUrl = SyncEndpointPolicy.normalizedBase(url)
    }

    private fun <T> networkFailure(): Result<T> = Result.failure(
        IllegalStateException("Secure sync transport failed; check HTTPS URL, certificate, and network")
    )

    suspend fun syncVault(request: SyncDto.SyncRequest): Result<SyncDto.SyncResponse> =
        withContext(Dispatchers.IO) {
            try {
                val response = client.post("${baseUrl.trimEnd('/')}/api/v1/sync") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
                when (response.status.value) {
                    in 200..299 -> Result.success(response.body<SyncDto.SyncResponse>())
                    401 -> Result.failure(IllegalStateException("Unauthorized: invalid username or password"))
                    else -> Result.failure(IllegalStateException("Sync server returned HTTP ${response.status.value}"))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Do not log/return the original throwable: it may embed a URL
                // or sensitive request material from an HTTP client/plugin.
                networkFailure()
            }
        }

    suspend fun register(request: RegisterRequest): Result<Unit> = try {
        val response = client.post("${baseUrl.trimEnd('/')}/api/v1/register") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        when (response.status.value) {
            in 200..299 -> Result.success(Unit)
            409 -> Result.failure(IllegalStateException("Username already taken"))
            else -> Result.failure(IllegalStateException("Registration failed: HTTP ${response.status.value}"))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        networkFailure()
    }

    suspend fun login(request: LoginRequest): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = client.post("${baseUrl.trimEnd('/')}/api/v1/login") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            if (response.status.value in 200..299) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("Login failed: HTTP ${response.status.value}"))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            networkFailure()
        }
    }

    suspend fun testConnection(): Result<Unit> = try {
        val response = client.get("${baseUrl.trimEnd('/')}/api/v1/health")
        if (response.status.value in 200..299) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("Server returned HTTP ${response.status.value}"))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        networkFailure()
    }
}
