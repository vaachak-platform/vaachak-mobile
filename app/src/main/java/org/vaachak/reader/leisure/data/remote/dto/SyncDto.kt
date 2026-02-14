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

package org.vaachak.reader.leisure.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SyncRequest(
    @SerialName("last_sync_timestamp") val lastSyncTimestamp: Long,
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("states") val states: List<ReadingStateDto>,
    @SerialName("annotations") val annotations: List<AnnotationDto>,
    @SerialName("auth") val auth: SyncAuthDto,
    @SerialName("local_hashes") val localHashes: List<String>? = null
)

@Serializable
data class SyncAuthDto(
    @SerialName("username") val username: String,
    @SerialName("password") val password: String
)

@Serializable
data class SyncResponse(
    @SerialName("new_sync_timestamp") val newSyncTimestamp: Long,
    @SerialName("states") val states: List<ReadingStateDto>,
    @SerialName("annotations") val annotations: List<AnnotationDto>
)

@Serializable
data class ReadingStateDto(
    @SerialName("user_id") val userId: String, // Removed default to ensure we always map the real username
    @SerialName("book_hash") val bookHash: String,
    @SerialName("progress_cfi") val progressCfi: String,
    @SerialName("progress_percent") val progressPercent: Double = 0.0,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("device_name") val deviceName: String? = null
)

@Serializable
data class AnnotationDto(
    @SerialName("id") val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("book_hash") val bookHash: String,
    @SerialName("locator_json") val locatorJson: String,
    @SerialName("note") val note: String?,
    @SerialName("color") val color: String?,
    @SerialName("deleted") val deleted: Boolean,
    @SerialName("updated_at") val updatedAt: Long
)
@Serializable
data class RegisterRequest(
    @SerialName("username") val username: String,
    @SerialName("password") val password: String
)
@Serializable
data class InboxItemDto(
    val id: String,
    @SerialName("book_title") val bookTitle: String,
    @SerialName("download_url") val downloadUrl: String,
    @SerialName("created_at") val createdAt: Long
)