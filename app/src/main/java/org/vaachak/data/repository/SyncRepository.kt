package org.vaachak.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.vaachak.data.local.BookDao
import org.vaachak.data.remote.SyncApi
import org.vaachak.data.remote.dto.*
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncRepository @Inject constructor(
    private val syncApi: SyncApi,
    private val bookDao: BookDao,
    private val settingsRepo: SettingsRepository
) {

    /**
     * Orchestrates the synchronization process with automatic retry logic.
     */
    suspend fun sync(): Result<Unit> = withContext(Dispatchers.IO) {
        Log.d("SyncRepository", "--- STARTING SYNC ---")

        // Removed explicit <Unit> as the compiler can now infer it correctly
        return@withContext runWithRetry {
            executeSyncOperation()
        }
    }

    private suspend fun executeSyncOperation(): Result<Unit> {
        Log.d("SyncDebug", "1. Entering executeSyncOperation")
        try {
            val username = settingsRepo.syncUsername.first()
            val password = settingsRepo.syncPassword.first()
            val deviceId = settingsRepo.ensureDeviceId()
            val deviceName = settingsRepo.deviceName.first()
            val useLocal = settingsRepo.useLocalServer.first()
            val targetUrl = if (useLocal) settingsRepo.localServerUrl.first() else settingsRepo.syncCloudUrl.first()

            Log.d("SyncDebug", "2. Target URL: $targetUrl")
            if (targetUrl.isBlank()) return Result.failure(Exception("URL is blank"))

            syncApi.setBaseUrl(targetUrl)

            val allLocalHashes = bookDao.getAllBookHashes()
            val lastSyncTime = settingsRepo.getLastSyncTimestamp()

            // 1. Prepare Outgoing States
            val dirtyBooks = bookDao.getBooksModifiedSince(lastSyncTime)
            val states = dirtyBooks.map { book ->
                ReadingStateDto(
                    userId = username,
                    bookHash = book.bookHash,
                    progressCfi = book.lastCfiLocation ?: book.lastLocationJson ?: "",
                    progressPercent = book.progress, // ADDED: Send local progress to cloud
                    updatedAt = book.lastRead,
                    deviceId = deviceId,
                    deviceName = deviceName
                )
            }

            val request = SyncRequest(
                lastSyncTimestamp = lastSyncTime,
                deviceId = deviceId,
                deviceName = deviceName,
                auth = SyncAuthDto(username, password),
                states = states,
                annotations = emptyList(),
                localHashes = allLocalHashes
            )

            Log.d("SyncDebug", "3. Calling syncApi.syncData...")

            return syncApi.syncData(request).map { response ->
                Log.d("SyncDebug", "4. Sync Success, processing ${response.states.size} remote states")

                // 2. Handle the incoming states (Merging logic)
                response.states.forEach { remoteState ->
                    // Skip if the update came from this specific device
                    if (remoteState.deviceId == deviceId) return@forEach

                    // Find if we actually have this book locally
                    val localBook = bookDao.getBookByHash(remoteState.bookHash)

                    // Only update if the remote data is strictly newer than local
                    if (localBook != null && remoteState.updatedAt > localBook.lastRead) {

                        Log.d("SyncDebug", "Cloud update for: ${localBook.title}. Remote Progress: ${remoteState.progressPercent}")

                        // Update the main books table
                        bookDao.updateBookMetadataFromSync(
                            bookHash = remoteState.bookHash,
                            cfi = remoteState.progressCfi,
                            // UPDATED: Use the actual progress from the cloud
                            progress = remoteState.progressPercent,
                            timestamp = remoteState.updatedAt
                        )
                    }
                }

                settingsRepo.setLastSyncTimestamp(response.newSyncTimestamp)
                Unit
            }
        } catch (e: Exception) {
            Log.e("SyncDebug", "FATAL ERROR in executeSyncOperation", e)
            return Result.failure(e)
        }
    }
    suspend fun testConnection(url: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d("SyncDebug", "1. Repository: setting URL: $url")
                syncApi.setBaseUrl(url)

                // CRITICAL: You must return the result of the API call
                val result = syncApi.testConnection()
                Log.d("SyncDebug", "2. Repository: API result is ${result.isSuccess}")
                result
            } catch (e: Exception) {
                Log.e("SyncDebug", "FATAL: Repository testConnection failed", e)
                Result.failure(e)
            }
        }
    }
    private suspend fun <T> runWithRetry(
        maxRetries: Int = 3,
        block: suspend () -> Result<T>
    ): Result<T> {
        var currentDelay = 1000L
        for (attempt in 0 until maxRetries) {
            val result = block()
            if (result.isSuccess) return result

            val error = result.exceptionOrNull()
            val isNetworkError = error is IOException || error?.message?.contains("500") == true

            if (attempt < maxRetries - 1 && isNetworkError) {
                Log.w("SyncRepository", "Attempt ${attempt + 1} failed, retrying in $currentDelay ms...")
                delay(currentDelay)
                currentDelay *= 2
            } else {
                return result
            }
        }
        return Result.failure(Exception("Maximum retry attempts reached"))
    }

    suspend fun register(user: String, pass: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // 1. Determine which URL to use based on settings
            val useLocal = settingsRepo.useLocalServer.first()
            val targetUrl = if (useLocal) settingsRepo.localServerUrl.first() else settingsRepo.syncCloudUrl.first()

            if (targetUrl.isBlank()) return@withContext Result.failure(Exception("URL is blank"))

            // 2. Point the API to the correct server
            syncApi.setBaseUrl(targetUrl)

            // 3. Execute
            val registerRequest = RegisterRequest(username = user, password = pass)
            syncApi.register(registerRequest)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun login(user: String, pass: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // 1. Setup URL
            val useLocal = settingsRepo.useLocalServer.first()
            val targetUrl = if (useLocal) settingsRepo.localServerUrl.first() else settingsRepo.syncCloudUrl.first()
            syncApi.setBaseUrl(targetUrl)

            // 2. Call the server to verify credentials
            val result = syncApi.login(RegisterRequest(user, pass))

            if (result.isSuccess) {
                // 3. Get existing device name to satisfy the updateSyncProfile signature
                val currentDeviceName = settingsRepo.deviceName.first()

                // 4. Use your existing function to save the profile
                settingsRepo.updateSyncProfile(
                    user = user,
                    pass = pass,
                    name = currentDeviceName
                )

                // 5. Reset sync anchor so the new device pulls existing cloud progress
                settingsRepo.setLastSyncTimestamp(0L)

                Log.d("SyncDebug", "Login successful. Profile updated for $user.")
            }
            result
        } catch (e: Exception) {
            Log.e("SyncDebug", "Login error", e)
            Result.failure(e)
        }
    }

    /**
     * Future feature: Check server-side inbox for books sent to this device.
     */
    fun checkInboxSilently() {
        // This is a placeholder to keep the code active and avoid 'unused' warnings
        Log.d("SyncRepository", "Inbox feature is ready for implementation.")
    }
}