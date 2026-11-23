package org.vaachak.reader.core.data.repository

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject

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
class PrivateGatewaySecrets @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val legacyAlias = "vaachak.private.ai.gateway.key.v1"
    private val authenticatedAlias = "vaachak.private.ai.gateway.key.v2"
    private val currentMagic = "VAG2".toByteArray(Charsets.US_ASCII)
    private val legacyMagic = "VAG1".toByteArray(Charsets.US_ASCII)

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

    private fun authenticatedKey(): SecretKey {
        checkDeviceCredential()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(authenticatedAlias, null) as? SecretKey)?.let { return it }
        val builder = KeyGenParameterSpec.Builder(
            authenticatedAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(60, KeyProperties.AUTH_DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(60)
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply { init(builder.build()) }
            .generateKey()
    }

    private fun <T> withDeviceAuthentication(block: () -> T): T {
        return try {
            block()
        } catch (exception: UserNotAuthenticatedException) {
            throw GatewayCredentialUnavailableException(
                "Unlock this device with its PIN, pattern or password and retry within 60 seconds.",
                exception
            )
        }
    }

    /** Prove recent device authorization before touching historical non-authenticated keys. */
    private fun requireFreshAuthentication() {
        withDeviceAuthentication {
            Cipher.getInstance("AES/GCM/NoPadding").init(Cipher.ENCRYPT_MODE, authenticatedKey())
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
            magic.contentEquals(currentMagic) -> withDeviceAuthentication {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, authenticatedKey(), GCMParameterSpec(128, bytes.copyOfRange(4, 16)))
                cipher.updateAAD(vaultId.toByteArray(Charsets.UTF_8))
                String(cipher.doFinal(bytes.copyOfRange(16, bytes.size)), Charsets.UTF_8)
            }
            magic.contentEquals(legacyMagic) -> {
                requireFreshAuthentication()
                val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                val oldKey = store.getKey(legacyAlias, null) as? SecretKey
                    ?: throw GatewayCredentialUnavailableException(
                        "An older private gateway key is no longer available. Re-enter the key in Settings."
                    )
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
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
        require(token.length in 1..512 && token.none(Char::isWhitespace)) { "Invalid gateway key" }
        val encrypted = withDeviceAuthentication {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, authenticatedKey())
            cipher.updateAAD(vaultId.toByteArray(Charsets.UTF_8))
            currentMagic + cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        }
        val dest = vaultFile(vaultId)
        val temporary = File.createTempFile(".private_ai_", ".tmp", context.noBackupFilesDir)
        try {
            temporary.writeBytes(encrypted)
            Files.move(
                temporary.toPath(), dest.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
            )
        } finally {
            check(!temporary.exists() || temporary.delete()) { "Cannot remove temporary gateway key" }
        }
    }

    @Synchronized
    fun clear(vaultId: String) {
        val file = vaultFile(vaultId)
        check(!file.exists() || file.delete()) { "Cannot remove gateway key" }
    }
}
