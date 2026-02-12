package org.vaachak.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.vaachak.data.local.BookDao
import org.vaachak.data.remote.SyncApi
import org.vaachak.data.remote.dto.AnnotationDto
import org.vaachak.data.remote.dto.ReadingStateDto
import org.vaachak.data.remote.dto.SyncRequest
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncRepository @Inject constructor(
    private val syncApi: SyncApi,
    private val bookDao: BookDao,
    private val settingsRepo: SettingsRepository
) {

    suspend fun sync(): Result<Unit> = withContext(Dispatchers.IO) {
        Log.d("Sync", "--- STARTING SYNC PROCESS ---")
        try {
            val deviceId = settingsRepo.ensureDeviceId()
            val useLocal = settingsRepo.useLocalServer.first()
            val localUrl = settingsRepo.localServerUrl.first()
            val cloudUrl = settingsRepo.syncCloudUrl.first()
            val currentBaseUrl = if (useLocal) localUrl else cloudUrl

            if (currentBaseUrl.isBlank()) return@withContext Result.failure(Exception("No URL configured"))

            syncApi.setBaseUrl(currentBaseUrl)
            val lastSyncTime = settingsRepo.getLastSyncTimestamp()
            val dirtyBooks = bookDao.getBooksModifiedSince(lastSyncTime)

            // Track what we are pushing so we can ignore echoes in the response
            val pushedHashes = dirtyBooks.map { it.bookHash }.toSet()

            val states = dirtyBooks.map { book ->
                val progressData = book.lastCfiLocation ?: book.lastLocationJson ?: ""
                ReadingStateDto(
                    userId = "default_user",
                    bookHash = book.bookHash,
                    progressCfi = progressData,
                    updatedAt = book.lastRead
                )
            }

            val request = SyncRequest(
                lastSyncTimestamp = lastSyncTime,
                deviceId = deviceId,
                states = states,
                annotations = emptyList()
            )

            val responseResult = syncApi.syncData(request)

            responseResult.fold(
                onSuccess = { response ->
                    val currentDeviceId = settingsRepo.deviceId.first()

                    response.states.forEach { remoteState ->
                        // SHIELD: If the update came from THIS device, ignore it completely.
                        if (remoteState.deviceId == currentDeviceId) {
                            Log.d("Sync", "Ignoring echo update for ${remoteState.bookHash}")
                            return@forEach
                        }


                        val localBook = bookDao.getBookByHash(remoteState.bookHash)

                        // 2. Standard "Last Write Wins" logic
                        if (localBook != null && remoteState.updatedAt > localBook.lastRead) {
                            // Extract progress to keep the UI section correct
                            val progressDouble = try {
                                val jsonObj = org.json.JSONObject(remoteState.progressCfi)
                                jsonObj.optJSONObject("locations")?.optDouble("totalProgression") ?: localBook.progress
                            } catch (e: Exception) {
                                localBook.progress
                            }

                            bookDao.updateProgressFromCloud(localBook.id, progressDouble, remoteState.progressCfi, remoteState.updatedAt)
                        }
                    }

                    settingsRepo.setLastSyncTimestamp(response.newSyncTimestamp)
                    Result.success(Unit)
                },
                onFailure = { Result.failure(it) }
            )
        } catch (e: Exception) {
            Log.e("Sync", "Fatal error", e)
            Result.failure(e)
        }
    }

    suspend fun checkInbox() {
        try {
            val deviceId = settingsRepo.ensureDeviceId()
            syncApi.getInbox(deviceId).onSuccess { items ->
                if (items.isNotEmpty()) Log.d("Sync", "Inbox has ${items.size} items")
            }
        } catch (e: Exception) {
            Log.e("Sync", "Inbox check failed", e)
        }
    }
}