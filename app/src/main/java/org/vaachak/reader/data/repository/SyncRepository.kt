package org.vaachak.reader.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.vaachak.reader.data.local.BookDao
import org.vaachak.reader.data.remote.SyncApi
import org.vaachak.reader.data.remote.dto.*
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository responsible for synchronizing reading progress and book metadata with a remote server.
 *
 * This class orchestrates the entire sync process, including:
 * - Authenticating with the sync server.
 * - Sending local changes (reading progress, new books) to the server.
 * - Receiving and merging remote changes from other devices.
 * - Handling user registration and login.
 * - Providing robust network operations with automatic retries.
 *
 * @param syncApi The Retrofit API interface for communicating with the sync server.
 * @param bookDao The Data Access Object for interacting with the local book database.
 * @param settingsRepo The repository for accessing app settings, such as sync credentials and server URLs.
 */
@Singleton
class SyncRepository @Inject constructor(
    private val syncApi: SyncApi,
    private val bookDao: BookDao,
    private val settingsRepo: SettingsRepository
) {

    /**
     * Orchestrates the main synchronization process with automatic retry logic.
     * This is the primary entry point for triggering a sync. It wraps the core
     * sync operation in a retry mechanism to handle transient network errors.
     *
     * @return A [Result] indicating success ([Result.success]) or failure ([Result.failure]) of the sync process.
     */
    suspend fun sync(): Result<Unit> = withContext(Dispatchers.IO) {
        Log.d("SyncRepository", "--- STARTING SYNC ---")

        // Removed explicit <Unit> as the compiler can now infer it correctly
        return@withContext runWithRetry {
            executeSyncOperation()
        }
    }

    /**
     * Executes a single, complete synchronization operation.
     *
     * This function performs the following steps:
     * 1. Gathers local data: authentication credentials, device info, and recently changed book states.
     * 2. Sends this data to the remote sync server.
     * 3. Receives updated states from the server.
     * 4. Merges the remote states with the local database, applying updates only if the remote data is newer.
     * 5. Updates the last sync timestamp upon successful completion.
     *
     * @return A [Result] indicating the success or failure of this specific sync attempt.
     */
    private suspend fun executeSyncOperation(): Result<Unit> {
        Log.d("SyncDebug", "1. Entering executeSyncOperation")
        try {
            val username = settingsRepo.syncUsername.first()
            val password = settingsRepo.syncPassword.first()
            val deviceId = settingsRepo.ensureDeviceId()
            val deviceName = settingsRepo.deviceName.first()
            val useLocal = settingsRepo.useLocalServer.first()
            var targetUrl = if (useLocal) {
                settingsRepo.localServerUrl.first()
            } else {
                settingsRepo.syncCloudUrl.first()
            }

            Log.d("SyncDebug", "2. Target URL: $targetUrl and device name: $deviceName")
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

    /**
     * Tests the connection to a given server URL by making a simple API call.
     *
     * @param url The base URL of the sync server to test.
     * @return A [Result] indicating whether the connection was successful.
     */
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

    /**
     * A generic higher-order function that executes a suspend block with a retry policy.
     * It retries the operation on specific network-related failures with an exponential backoff strategy.
     *
     * @param maxRetries The maximum number of times to retry the operation.
     * @param block The suspend lambda to execute.
     * @return The [Result] from the executed block. Returns the last failure result if all retries are exhausted.
     */
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

    /**
     * Registers a new user account on the sync server.
     *
     * @param user The username for the new account.
     * @param pass The password for the new account.
     * @return A [Result] indicating the success or failure of the registration.
     */
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

    /**
     * Logs a user in by verifying credentials with the server and saving the profile locally.
     * Upon successful login, it resets the sync timestamp to ensure the device pulls all
     * existing data from the cloud on the next sync.
     *
     * @param user The username to log in with.
     * @param pass The password for the account.
     * @return A [Result] indicating the success or failure of the login attempt.
     */
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
     * Placeholder for a future feature to check a server-side inbox for books sent to this device.
     * This function is kept to prevent 'unused' warnings and to indicate planned functionality.
     */
    fun checkInboxSilently() {
        // This is a placeholder to keep the code active and avoid 'unused' warnings
        Log.d("SyncRepository", "Inbox feature is ready for implementation.")
    }
}