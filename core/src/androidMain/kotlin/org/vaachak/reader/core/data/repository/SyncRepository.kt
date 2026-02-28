package org.vaachak.reader.core.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.vaachak.reader.core.data.local.BookDao
import org.vaachak.reader.core.data.local.HighlightDao
import org.vaachak.reader.core.data.local.SyncVaultDao
import org.vaachak.reader.core.data.remote.SyncApi
import org.vaachak.reader.core.data.remote.dto.RegisterRequest
import org.vaachak.reader.core.data.remote.dto.SyncDto
import org.vaachak.reader.core.domain.model.SyncVaultEntity
import org.vaachak.reader.core.security.CryptoManager
import org.vaachak.reader.core.security.EncryptedPayload
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncRepository @Inject constructor(
    private val bookDao: BookDao,
    private val highlightDao: HighlightDao,
    private val syncVaultDao: SyncVaultDao,
    private val cryptoManager: CryptoManager,
    private val syncApi: SyncApi,
    private val settingsRepo: SettingsRepository,
    private val vaultRepository: VaultRepository // <-- 1. INJECT THE VAULT
) {

    // ==========================================
    // 1. END-TO-END ENCRYPTED VAULT SYNC LOGIC
    // ==========================================

    suspend fun sync(): Result<Unit> = runCatching {
        // 1. Ensure the API is pointing to the correct server before syncing
        val useLocal = settingsRepo.useLocalServer.first()
        val targetUrl = if (useLocal) settingsRepo.localServerUrl.first() else settingsRepo.syncCloudUrl.first()
        syncApi.setBaseUrl(targetUrl)

        // 2. Push local changes to the Vault and Cloudflare
        pushLocalChanges()

        // 3. Fetch the last sync timestamp, pull new data, and update the timestamp
        val lastSync = settingsRepo.lastSyncTimestamp.first()
        pullRemoteChanges(lastSync)
        settingsRepo.setLastSyncTimestamp(System.currentTimeMillis())
    }

    private suspend fun pushLocalChanges() {
        val profileId = vaultRepository.activeVaultId.first() // <-- GET ACTIVE USER

        // Pass profileId to DAO
        val dirtyBooks = bookDao.getBooksModifiedSince(profileId, 0L).filter { it.isDirty }

        for (book in dirtyBooks) {
            // Pass profileId to DAO
            val highlights = highlightDao.getHighlightsForBook(book.bookHash, profileId).first()

            val payloadObj = SyncDto.CleartextPayload(
                bookHash = book.bookHash,
                progress = book.progress,
                lastCfiLocation = book.lastCfiLocation,
                updatedAt = book.updatedAt,
                highlights = highlights
            )

            val jsonString = Json.encodeToString(payloadObj)
            val encrypted = cryptoManager.encrypt(jsonString)

            val vaultEntity = SyncVaultEntity(
                bookHash = book.bookHash,
                encryptedBlob = encrypted.ciphertext,
                iv = encrypted.iv,
                remoteUpdatedAt = book.updatedAt,
                needsPush = true
            )
            syncVaultDao.upsertSyncPayload(vaultEntity)

            // Pass profileId to DAO
            bookDao.updateBookMetadataFromSync(book.bookHash, profileId, book.lastCfiLocation ?: "", book.progress, book.updatedAt)
        }

        val pendingUploads = syncVaultDao.getPendingUploads().first()
        if (pendingUploads.isNotEmpty()) {
            val networkPayload = pendingUploads.map {
                SyncDto.NetworkPayload(it.bookHash, it.encryptedBlob, it.iv, it.remoteUpdatedAt)
            }
            // Execute network call
            syncApi.pushEncryptedVault(networkPayload)

            // Clear the push flags once successful
            pendingUploads.forEach {
                syncVaultDao.upsertSyncPayload(it.copy(needsPush = false))
            }
        }
    }

    private suspend fun pullRemoteChanges(lastSyncTime: Long) {
        val profileId = vaultRepository.activeVaultId.first() // <-- GET ACTIVE USER
        val remoteBlobs = syncApi.pullEncryptedVault(lastSyncTime)

        for (blob in remoteBlobs) {
            val payload = EncryptedPayload(blob.ciphertext, blob.iv)
            try {
                val decryptedJson = cryptoManager.decrypt(payload)
                val cleartext = Json.decodeFromString<SyncDto.CleartextPayload>(decryptedJson)

                // Pass profileId to DAO
                bookDao.updateProgressFromCloud(
                    bookHash = cleartext.bookHash,
                    profileId = profileId,
                    progress = cleartext.progress,
                    cfiLocation = cleartext.lastCfiLocation ?: "",
                    timestamp = cleartext.updatedAt
                )

                // Stamp incoming highlights with the local profileId so they attach correctly
                cleartext.highlights.forEach {
                    highlightDao.insertHighlight(it.copy(profileId = profileId))
                }
            } catch (e: Exception) {
                // Skip if decryption fails (e.g., wrong AES key)
                continue
            }
        }
    }

    // ==========================================
    // 2. LEGACY AUTHENTICATION & ROUTING LOGIC
    // ==========================================

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

    suspend fun register(user: String, pass: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // 1. Determine which URL to use based on settings
            val useLocal = settingsRepo.useLocalServer.first()
            val targetUrl = if (useLocal) settingsRepo.localServerUrl.first() else settingsRepo.syncCloudUrl.first()

            if (targetUrl.isBlank()) return@withContext Result.failure(Exception("URL is blank"))

            // 2. Point the API to the correct server
            syncApi.setBaseUrl(targetUrl)

            // 3. Execute
            val registerRequest = RegisterRequest(username = user, passwordHash = pass)
            syncApi.register(registerRequest)
        } catch (e: Exception) {
            Result.failure(e)
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
}