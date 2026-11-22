package org.vaachak.reader.core.data.repository

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM with Android Keystore; ciphertext is per vault and excluded from Android backup. */
internal class PrivateGatewaySecrets(private val context: Context) {
    private val alias = "vaachak.private.ai.gateway.key.v1"

    private fun vaultFile(id: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(id.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(context.noBackupFilesDir, "private_ai_$digest.enc")
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return generator.generateKey()
    }

    @Synchronized
    fun read(vaultId: String): String {
        val file = vaultFile(vaultId)
        if (!file.exists()) return ""
        val bytes = file.readBytes()
        require(bytes.size in 32..8192 && bytes.take(4) == "VAG1".toByteArray().toList()) {
            "Stored gateway key is invalid; replace it in Settings"
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(4, 16)))
        return String(cipher.doFinal(bytes.copyOfRange(16, bytes.size)), Charsets.UTF_8)
    }

    @Synchronized
    fun write(vaultId: String, token: String) {
        require(token.length in 1..512 && token.none(Char::isWhitespace)) { "Invalid gateway key" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = "VAG1".toByteArray() + cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val dest = vaultFile(vaultId)
        val temporary = File.createTempFile(".private_ai_", ".tmp", context.noBackupFilesDir)
        try {
            temporary.writeBytes(encrypted)
            Files.move(temporary.toPath(), dest.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temporary.delete()
        }
    }

    @Synchronized
    fun clear(vaultId: String) {
        val file = vaultFile(vaultId)
        if (file.exists() && !file.delete()) throw IllegalStateException("Cannot remove gateway key")
    }
}
