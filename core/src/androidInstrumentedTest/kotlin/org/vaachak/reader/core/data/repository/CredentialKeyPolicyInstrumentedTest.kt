package org.vaachak.reader.core.data.repository

import android.app.KeyguardManager
import android.content.Context
import android.security.keystore.KeyInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Synthetic credentials only. Verifies an actual Android Keystore key's policy,
 * not just the encryption algorithm or the presence of an encrypted file.
 * Never touches a user vault, production token, or historical alias.
 */
@RunWith(AndroidJUnit4::class)
class CredentialKeyPolicyInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val secrets get() = PrivateGatewaySecrets(context)

    private fun encryptedFile(id: String): File {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(id.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(context.noBackupFilesDir, "private_ai_$hash.enc")
    }

    @Test fun syncAndAiUseAuthenticationRequiredKeystoreAndNoBackupStorage() {
        val secure = (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure
        val suffix = System.nanoTime()
        val sync = "vaachak_credential_v1:SYNC_PASSWORD:\u0000policy_sync_$suffix"
        val gemini = "vaachak_credential_v1:GEMINI:\u0000policy_gemini_$suffix"
        val syncSecret = "synthetic sync password $suffix"
        val geminiSecret = "synthetic-gemini-$suffix"
        try {
            if (!secure) {
                assertTrue(runCatching { secrets.write(sync, syncSecret) }
                    .exceptionOrNull() is GatewayCredentialUnavailableException)
                assertFalse(encryptedFile(sync).exists())
                return
            }
            try {
                secrets.write(sync, syncSecret)
                secrets.write(gemini, geminiSecret)
            } catch (error: GatewayCredentialUnavailableException) {
                // Interactive devices may require a fresh device credential unlock.
                // Do NOT bypass authentication or weaken the production policy.
                assumeNoException("Unlock the device with PIN/pattern/password and retry", error)
                return
            }

            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = keyStore.getKey("vaachak.private.ai.gateway.key.v2", null) as? SecretKey
            assertNotNull("Expected authenticated v2 alias", key)
            val info = SecretKeyFactory.getInstance("AES", "AndroidKeyStore")
                .getKeySpec(requireNotNull(key), KeyInfo::class.java) as KeyInfo
            assertTrue("Key must require device authentication", info.isUserAuthenticationRequired)

            assertTrue(encryptedFile(sync).isFile)
            assertTrue(encryptedFile(gemini).isFile)
            assertTrue(encryptedFile(sync).parentFile!!.canonicalPath == context.noBackupFilesDir.canonicalPath)
            assertTrue(encryptedFile(sync).readBytes().copyOfRange(0, 4)
                .contentEquals("VAS2".toByteArray(Charsets.US_ASCII)))
            assertTrue(encryptedFile(gemini).readBytes().copyOfRange(0, 4)
                .contentEquals("VAG2".toByteArray(Charsets.US_ASCII)))
            assertFalse(encryptedFile(sync).readText(Charsets.ISO_8859_1).contains(syncSecret))
            assertFalse(encryptedFile(gemini).readText(Charsets.ISO_8859_1).contains(geminiSecret))

            // Simulate a fresh repository instance without migrating/removing files.
            val freshInstance = PrivateGatewaySecrets(context)
            assertEquals(syncSecret, freshInstance.read(sync))
            assertEquals(geminiSecret, freshInstance.read(gemini))
        } finally {
            secrets.clear(sync)
            secrets.clear(gemini)
        }
    }
}
