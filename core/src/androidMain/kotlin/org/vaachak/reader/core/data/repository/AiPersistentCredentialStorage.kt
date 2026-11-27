package org.vaachak.reader.core.data.repository

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.os.UserManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * D90-ADR-01 AI-only credential backend.
 * No read or write of the existing VAG1/VAG2/VAS2/OPDS files or aliases.
 * The eventual repository owns persisted-vault checks, revision, native prompt,
 * legacy retirement/tombstones and in-flight cancellation. This layer must not
 * be used to bypass those checks. No long-lived plaintext credential cache.
 */
internal class AiPersistentCredentialStorage(
    private val context: Context,
    private val foreground: () -> Boolean
) {
    internal enum class Presence { MISSING, SAVED, UNAVAILABLE, RECOVERY_REQUIRED }

    private companion object {
        const val PREFIX = "vaachak.ai.persistent.v3."
        const val MAX_SECRET = 4096
        const val MAX_ENVELOPE = MAX_SECRET + 32
        val magic = "VAI3".toByteArray(Charsets.US_ASCII)
        val fileLock = Any()
    }

    private val durable = DurableCredentialFile { directory ->
        val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }

    /** No decryption or key creation just to report presence. */
    fun presence(vault: String, slot: AiManagedSlot): Presence = synchronized(fileLock) {
        val path = file(vault, slot)
        when {
            !path.exists() -> Presence.MISSING
            !eligible() -> Presence.UNAVAILABLE
            !path.isFile || path.length() !in 33L..MAX_ENVELOPE.toLong() -> Presence.RECOVERY_REQUIRED
            else -> try {
                if (keyStore().containsAlias(alias(vault, slot))) Presence.SAVED
                else Presence.RECOVERY_REQUIRED
            } catch (_: Exception) { Presence.UNAVAILABLE }
        }
    }

    /** Missing is null. Inaccessible or malformed ciphertext is never interpreted as missing. */
    fun read(vault: String, slot: AiManagedSlot): String? = synchronized(fileLock) {
        requireEligible()
        val path = file(vault, slot)
        if (!path.exists()) return@synchronized null
        protectedOperation {
            val key = existingKey(vault, slot) ?: throw IOException("AI key unavailable")
            val plaintext = decrypt(vault, slot, readEnvelope(path), key)
            try {
                requireEligible()
                plaintext.toString(Charsets.UTF_8)
            } finally { plaintext.fill(0) }
        }
    }

    /**
     * Publish verified ciphertext in the NEW namespace only. Existing legacy files
     * remain untouched. No live routing/cutover is performed by this primitive.
     * The caller must consume a grant scoped to this exact slot and operation.
     */
    fun writeVerified(
        binding: AiManagementBinding,
        value: String,
        ledger: AiManagementGrantLedger,
        grant: AiManagementGrantLedger.Grant,
        stillAuthorized: () -> Boolean = { true }
    ) = synchronized(fileLock) {
        check(binding.action == AiManagementAction.REPLACE || binding.action == AiManagementAction.MIGRATE)
        check(ledger.consume(grant, binding) && stillAuthorized()) { "Management authorization required" }
        requireEligible()
        val bytes = value.toByteArray(Charsets.UTF_8)
        try {
            require(bytes.size in 1..MAX_SECRET && value.isNotBlank()) { "Invalid AI credential" }
            protectedOperation {
                val vault = binding.vault
                val slot = binding.slot
                val path = file(vault, slot)
                val key = existingKey(vault, slot) ?: run {
                    // Do not silently replace a lost key if ciphertext already exists.
                    check(!path.exists()) { "AI credential recovery required" }
                    createKey(vault, slot)
                }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key)
                cipher.updateAAD(aad(vault, slot))
                check(cipher.iv.size == 12)
                val envelope = magic + cipher.iv + cipher.doFinal(bytes)
                val verified = decrypt(vault, slot, envelope, key)
                try { check(MessageDigest.isEqual(bytes, verified)) }
                finally { verified.fill(0) }
                requireEligible()
                check(stillAuthorized()) { "Management context changed" }
                durable.replace(path, envelope)
                val persisted = decrypt(vault, slot, readEnvelope(path), key)
                try { check(MessageDigest.isEqual(bytes, persisted)) }
                finally { persisted.fill(0) }
                requireEligible()
                check(stillAuthorized()) { "Management context changed" }
            }
        } finally { bytes.fill(0) }
    }

    /** Removal requires its own single-use grant, including recovery of lost keys. */
    fun removeVerified(binding: AiManagementBinding, ledger: AiManagementGrantLedger,
                       grant: AiManagementGrantLedger.Grant, stillAuthorized: () -> Boolean) = synchronized(fileLock) {
        check(binding.action == AiManagementAction.REMOVE && ledger.consume(grant, binding) && stillAuthorized()) {
            "Management authorization required"
        }
        requireEligible()
        protectedOperation {
            check(stillAuthorized()) { "Management context changed" }
            durable.remove(file(binding.vault, binding.slot))
            val store = keyStore()
            val name = alias(binding.vault, binding.slot)
            if (store.containsAlias(name)) store.deleteEntry(name)
            requireEligible()
            check(stillAuthorized()) { "Management context changed" }
        }
    }

    private fun eligible(): Boolean {
        val lock = context.getSystemService(KeyguardManager::class.java) ?: return false
        val user = context.getSystemService(UserManager::class.java) ?: return false
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return foreground() && user.isUserUnlocked && lock.isDeviceSecure &&
            !lock.isDeviceLocked && !lock.isKeyguardLocked && power.isInteractive
    }

    private fun requireEligible() {
        if (!eligible()) throw GatewayCredentialUnavailableException("AI credentials require an unlocked secure foreground device.")
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun existingKey(vault: String, slot: AiManagedSlot): SecretKey? =
        keyStore().getKey(alias(vault, slot), null) as? SecretKey

    private fun createKey(vault: String, slot: AiManagedSlot): SecretKey {
        // ADR-01: no recurring recent-auth window for this AI-only key. This is
        // an intentional policy change requiring hotspot review and device proof.
        // No fallback to a key without unlocked-device enforcement on old Android.
        val spec = KeyGenParameterSpec.Builder(alias(vault, slot),
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .setUnlockedDeviceRequired(true)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply { init(spec) }.generateKey()
    }

    private fun digest(vault: String, slot: AiManagedSlot): String {
        require(vault.isNotBlank())
        return MessageDigest.getInstance("SHA-256").digest(aad(vault, slot))
            .joinToString("") { "%02x".format(it) }
    }

    private fun aad(vault: String, slot: AiManagedSlot): ByteArray =
        "VAI3:${slot.name}:$vault".toByteArray(Charsets.UTF_8)

    internal fun alias(vault: String, slot: AiManagedSlot): String = PREFIX + digest(vault, slot)
    internal fun file(vault: String, slot: AiManagedSlot): File =
        File(context.noBackupFilesDir, "ai_persistent_v3_${digest(vault, slot)}.enc")

    private fun readEnvelope(path: File): ByteArray {
        val length = path.length()
        check(length in 33L..MAX_ENVELOPE.toLong()) { "Invalid AI envelope" }
        val bytes = ByteArray(length.toInt())
        java.io.DataInputStream(path.inputStream()).use {
            it.readFully(bytes)
            check(it.read() == -1) { "Invalid AI envelope" }
        }
        return bytes
    }

    private fun decrypt(vault: String, slot: AiManagedSlot, envelope: ByteArray, key: SecretKey): ByteArray {
        check(envelope.size in 33..MAX_ENVELOPE && envelope.copyOfRange(0, 4).contentEquals(magic)) {
            "Invalid AI envelope"
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, envelope.copyOfRange(4, 16)))
        cipher.updateAAD(aad(vault, slot))
        return cipher.doFinal(envelope, 16, envelope.size - 16)
    }

    private fun <T> protectedOperation(block: () -> T): T = try {
        block()
    } catch (error: GatewayCredentialUnavailableException) {
        throw error
    } catch (_: Exception) {
        // Never include provider values, filenames or underlying exception messages.
        throw GatewayCredentialUnavailableException("Saved AI credential unavailable. Check secure lock or recovery options.")
    }
}
