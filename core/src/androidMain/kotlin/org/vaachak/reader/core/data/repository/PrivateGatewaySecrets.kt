package org.vaachak.reader.core.data.repository

import android.app.KeyguardManager
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.system.Os
import android.system.OsConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Safe, non-secret diagnostic messages for unavailable device-authenticated credentials. */
class GatewayCredentialUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * Vault gateway tokens are encrypted with a device-credential-authenticated Android Keystore key.
 * Requires the device PIN/pattern/password to have been used within the preceding 60 seconds.
 *
 * No-lock BOOX devices can still use offline reading and ordinary Settings, but may not persist
 * a private-gateway token without first configuring a secure screen lock. No plaintext fallback.
 *
 * VAG1 ciphertext from earlier releases is migrated in place after device authorization.
 * The historical non-authenticated v1 key is read only for this one-way migration; no new
 * credentials are ever written with it. Do not delete the v1 alias globally while other vaults
 * may still hold unmigrated VAG1 files.
 */
@Singleton
class PrivateGatewaySecrets @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        const val CREDENTIAL_PREFIX = "vaachak_credential_v1:"
        val keyCreationLock = Any()
    }

    private val durableFile = DurableCredentialFile { directory ->
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try {
            check(OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) { "Credential storage path is not a directory" }
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }

    private val legacyAlias = "vaachak.private.ai.gateway.key.v1"
    private val authenticatedAlias = "vaachak.private.ai.gateway.key.v2"
    // Existing installs may contain SYNC_PASSWORD files written under the older
    // non-authenticated v1 alias. Keep that alias read-only for a one-way migration;
    // NEVER create a new key under it or delete it while another vault may need it.
    private val legacySyncAlias = "vaachak.background.sync.key.v1"
    private val currentMagic = "VAG2".toByteArray(Charsets.US_ASCII)
    private val authenticatedSyncMagic = "VAS2".toByteArray(Charsets.US_ASCII)
    private val legacyMagic = "VAG1".toByteArray(Charsets.US_ASCII)

    private fun isSyncCredential(vaultId: String): Boolean =
        vaultId.startsWith("vaachak_credential_v1:SYNC_PASSWORD:")

    private fun vaultFile(id: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(id.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(context.noBackupFilesDir, "private_ai_$digest.enc")
    }

    private fun checkDeviceCredential() {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!keyguard.isDeviceSecure) {
            throw GatewayCredentialUnavailableException(
                "Private gateway keys require a device PIN, pattern or password. " +
                    "Configure a secure screen lock before saving a key."
            )
        }
    }

    private fun authenticatedKey(): SecretKey = synchronized(keyCreationLock) {
        checkDeviceCredential()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(authenticatedAlias, null) as? SecretKey)?.let { return@synchronized it }
        val builder = KeyGenParameterSpec.Builder(
            authenticatedAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
        builder.setUserAuthenticationParameters(60, KeyProperties.AUTH_DEVICE_CREDENTIAL)
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply { init(builder.build()) }
            .generateKey()
    }

    /**
     * All NEW credentials (including sync) use the already authenticated v2 key:
     * setUserAuthenticationRequired(true), and 60-second AUTH_DEVICE_CREDENTIAL
     * on Android 11+. This implements SonarCloud kotlin:S6288 without weakening
     * Gemini, Cloudflare or Gateway policy.
     *
     * Consequence: unattended sync cannot retrieve its password after expiry of
     * device-credential authorization. It must defer until the user authenticates.
     */
    private fun decryptAead(vaultId: String, bytes: ByteArray, key: SecretKey): String {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE, key,
            GCMParameterSpec(128, bytes.copyOfRange(4, 16))
        )
        cipher.updateAAD(vaultId.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(bytes.copyOfRange(16, bytes.size)), Charsets.UTF_8)
    }

    private fun <T> withDeviceAuthentication(block: () -> T): T {
        return try {
            block()
        } catch (exception: UserNotAuthenticatedException) {
            throw GatewayCredentialUnavailableException(
                "Unlock this device with its PIN, pattern or password and retry within 60 seconds.",
                exception
            )
        } catch (exception: java.security.GeneralSecurityException) {
            throw GatewayCredentialUnavailableException(
                "Secure credentials are unavailable. Unlock or reconfigure this device and retry.",
                exception
            )
        }
    }

    /** Prove recent device authorization before touching historical non-authenticated keys. */
    private fun requireFreshAuthentication() {
        withDeviceAuthentication {
            Cipher.getInstance(AES_GCM_TRANSFORMATION).init(Cipher.ENCRYPT_MODE, authenticatedKey())
        }
    }

    @Synchronized
    fun read(vaultId: String): String {
        val file = vaultFile(vaultId)
        if (!file.exists()) return ""
        val bytes = file.readBytes()
        require(bytes.size in 32..8192) { "Stored gateway key is invalid; replace it in Settings" }
        val magic = bytes.copyOfRange(0, 4)
        val token = when {
            // New sync ciphertext is unambiguously distinguished from the old
            // VAG2/non-authenticated format; we never guess which key encrypted it.
            magic.contentEquals(authenticatedSyncMagic) && isSyncCredential(vaultId) ->
                withDeviceAuthentication { decryptAead(vaultId, bytes, authenticatedKey()) }
            magic.contentEquals(currentMagic) && isSyncCredential(vaultId) -> {
                // One-way, fail-closed migration from the old device-bound v1 sync key.
                // Require RECENT authorization BEFORE decrypting the legacy key.
                requireFreshAuthentication()
                val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                val oldKey = store.getKey(legacySyncAlias, null) as? SecretKey
                    ?: throw GatewayCredentialUnavailableException(
                        "An older sync key is unavailable. Re-enter the sync password in Settings."
                    )
                val plaintext = withDeviceAuthentication {
                    decryptAead(vaultId, bytes, oldKey)
                }
                // write() atomically replaces VAG2 with authenticated VAS2 only
                // AFTER encryption succeeds. A post-rename sync failure is reported;
                // the new ciphertext may already exist and must not be treated as lost.
                write(vaultId, plaintext)
                plaintext
            }
            magic.contentEquals(currentMagic) -> withDeviceAuthentication {
                decryptAead(vaultId, bytes, authenticatedKey())
            }
            magic.contentEquals(legacyMagic) &&
                !vaultId.startsWith(CREDENTIAL_PREFIX) -> {
                requireFreshAuthentication()
                val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                val oldKey = store.getKey(legacyAlias, null) as? SecretKey
                    ?: throw GatewayCredentialUnavailableException(
                        "An older private gateway key is no longer available. Re-enter the key in Settings."
                    )
                val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, oldKey, GCMParameterSpec(128, bytes.copyOfRange(4, 16)))
                val plaintext = String(cipher.doFinal(bytes.copyOfRange(16, bytes.size)), Charsets.UTF_8)
                // Atomic overwrite happens only after authenticated encryption succeeds.
                write(vaultId, plaintext)
                plaintext
            }
            else -> throw IllegalArgumentException("Stored gateway key is invalid; replace it in Settings")
        }
        return token
    }

    @Synchronized
    fun write(vaultId: String, token: String) {
        // Separate internal namespace for Gemini/Cloudflare and sync secrets. These may
        // legitimately contain whitespace; the Gateway token policy stays strict.
        val scoped = vaultId.startsWith(CREDENTIAL_PREFIX)
        if (scoped) {
            require(token.isNotEmpty() && token.toByteArray(Charsets.UTF_8).size <= 4096) {
                "Invalid credential length"
            }
        } else {
            require(token.length in 1..512 && token.none(Char::isWhitespace)) {
                "Invalid gateway key"
            }
        }
        val encrypted = withDeviceAuthentication {
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, authenticatedKey())
            cipher.updateAAD(vaultId.toByteArray(Charsets.UTF_8))
            val magic = if (isSyncCredential(vaultId)) authenticatedSyncMagic else currentMagic
            magic + cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        }
        durableFile.replace(vaultFile(vaultId), encrypted)
    }

    /** Re-establish durability before publishing a previously written OPDS generation. */
    @Synchronized
    internal fun ensureDurable(vaultId: String) {
        durableFile.ensureDurable(vaultFile(vaultId))
    }

    /** AI migration metadata lookup; never decrypt just to report presence. */
    @Synchronized
    internal fun hasStoredCredential(id: String): Boolean = vaultFile(id).exists()

    /** Retire only the exact AI slot. Shared sync/OPDS aliases are never deleted. */
    @Synchronized
    internal fun retireAiCredential(id: String) {
        require(!id.startsWith(CREDENTIAL_PREFIX) ||
            id.startsWith("vaachak_credential_v1:GEMINI:") ||
            id.startsWith("vaachak_credential_v1:CLOUDFLARE:"))
        durableFile.remove(vaultFile(id))
    }

    @Synchronized
    fun clear(vaultId: String) {
        val file = vaultFile(vaultId)
        check(!file.exists() || file.delete()) { "Cannot remove gateway key" }
    }
}
