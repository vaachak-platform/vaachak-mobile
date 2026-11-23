package org.vaachak.reader.core.data.repository

import android.app.KeyguardManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import org.junit.runner.RunWith

/** Android Keystore and BOOX/no-lock behavior, using only fake credentials. */
@RunWith(AndroidJUnit4::class)
class ScopedCredentialInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val secrets get() = PrivateGatewaySecrets(context)
    private val secureLock get() =
        (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure

    private fun path(id: String): File {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(id.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(context.noBackupFilesDir, "private_ai_$hash.enc")
    }

    @Test fun backgroundSyncPasswordRoundTripsWithoutScreenAuthentication() {
        val id = "vaachak_credential_v1:SYNC_PASSWORD:\u0000sync_test_${System.nanoTime()}"
        val value = "fixture password with spaces"
        try {
            secrets.write(id, value)
            assertEquals(value, secrets.read(id))
            assertTrue(path(id).isFile)
            assertFalse(path(id).readText(Charsets.ISO_8859_1).contains(value))
            secrets.clear(id)
            assertEquals("", secrets.read(id))
        } finally {
            secrets.clear(id)
        }
    }

    @Test fun directAiKeyStillRequiresSecureScreenLock() {
        val id = "vaachak_credential_v1:GEMINI:\u0000ai_test_${System.nanoTime()}"
        try {
            if (!secureLock) {
                val error = runCatching { secrets.write(id, "fixture-gemini") }.exceptionOrNull()
                assertTrue(error is GatewayCredentialUnavailableException)
                assertFalse(path(id).exists())
                return
            }
            try {
                secrets.write(id, "fixture-gemini")
            } catch (error: GatewayCredentialUnavailableException) {
                assumeNoException("Unlock using device PIN before running this test", error)
                return
            }
            assertEquals("fixture-gemini", secrets.read(id))
            assertFalse(path(id).readText(Charsets.ISO_8859_1).contains("fixture-gemini"))
        } finally {
            secrets.clear(id)
        }
    }

    @Test fun namedCredentialsAreBoundToVaultAndKind() {
        val origin = "vaachak_credential_v1:SYNC_PASSWORD:\u0000vault_${System.nanoTime()}"
        val wrong = "vaachak_credential_v1:SYNC_PASSWORD:\u0000other_${System.nanoTime()}"
        val copy = "vaachak_credential_v1:CLOUDFLARE:\u0000vault_${System.nanoTime()}"
        try {
            secrets.write(origin, "fixture-vault-secret")
            path(wrong).writeBytes(path(origin).readBytes())
            path(copy).writeBytes(path(origin).readBytes())
            assertTrue(runCatching { secrets.read(wrong) }.isFailure)
            assertTrue(runCatching { secrets.read(copy) }.isFailure)
        } finally {
            secrets.clear(origin)
            secrets.clear(wrong)
            secrets.clear(copy)
        }
    }
}
