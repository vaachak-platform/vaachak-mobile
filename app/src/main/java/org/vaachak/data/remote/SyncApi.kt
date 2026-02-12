/*
 *  Copyright (c) 2026 Piyush Daiya
 *  *
 *  * Permission is hereby granted, free of charge, to any person obtaining a copy
 *  * of this software and associated documentation files (the "Software"), to deal
 *  * in the Software without restriction, including without limitation the rights
 *  * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  * copies of the Software, and to permit persons to whom the Software is
 *  * furnished to do so, subject to the following conditions:
 *  *
 *  * The above copyright notice and this permission notice shall be included in all
 *  * copies or substantial portions of the Software.
 *  *
 *  * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  * SOFTWARE.
 */

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
import kotlinx.serialization.json.Json
import org.vaachak.data.remote.dto.InboxItemDto
import org.vaachak.data.remote.dto.SyncRequest
import org.vaachak.data.remote.dto.SyncResponse
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncApi @Inject constructor() {

    // 1. The HTTP Client Configuration
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true // robust against server changes
                prettyPrint = true
                isLenient = true
                encodeDefaults = true
            })
        }
        install(Logging) {
            level = LogLevel.ALL
        }
    }

    // 2. Dynamic Base URL (We will pass this from Repository later)
    // Defaulting to Cloudflare for safety
    private var baseUrl = "https://vaachak-sync.yourname.workers.dev"

    fun setBaseUrl(url: String) {
        // Ensure no trailing slash to avoid double slashes //
        this.baseUrl = url.trimEnd('/')
    }

    // 3. The SYNC Endpoint
    suspend fun syncData(request: SyncRequest): Result<SyncResponse> {
        return try {
            // Use absolute path logic or check if baseUrl is properly formatted
            val response = client.post("$baseUrl/api/v1/sync") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }

            if (response.status.value in 200..299) {
                Result.success(response.body<SyncResponse>())
            } else {
                Result.failure(Exception("Server returned status ${response.status}"))
            }
        } catch (e: Exception) {
            Log.e("SyncApi", "Network Error: ${e.message}")
            Result.failure(e)
        }
    }

    // 4. The INBOX Endpoint (Fetch pending downloads)
    suspend fun getInbox(deviceId: String): Result<List<InboxItemDto>> {
        return try {
            val response: List<InboxItemDto> = client.get("$baseUrl/api/v1/inbox") {
                url { parameters.append("device_id", deviceId) }
            }.body()
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Note: File Upload (Send to Device) requires Multipart support
    // We can add that when we implement the "Send" feature UI.
}