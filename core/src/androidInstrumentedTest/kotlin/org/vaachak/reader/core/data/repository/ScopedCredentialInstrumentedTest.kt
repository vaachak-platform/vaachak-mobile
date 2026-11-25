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

/**
 * Android Keystore tests for the authenticated SYNC_PASSWORD, Gemini and vault
 * namespaces. Uses synthetic credentials only. CI requires successful PIN auth.
 */
@RunWith(AndroidJUnit4::class)
class ScopedCredentialInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val secrets get() = PrivateGatewaySecrets(context)
    private val secureLock get() =
        (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure
    private val strictCi get() =
        InstrumentationRegistry.getArguments().getString("vaachakAuthCi") == "true"

    private fun path(id: String): File {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(id.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(context.noBackupFilesDir, "private_ai_$hash.enc")
    }

    private fun writeWithAvailableAuthentication(id: String, token: String): Boolean {
        if (!secureLock) {
            if (strictCi) throw AssertionError("CI device must have a secure PIN configured")
            val error = runCatching { secrets.write(id, token) }.exceptionOrNull()
            assertTrue("Credentials must fail closed on no-lock devices", error is GatewayCredentialUnavailableException)
            assertFalse(path(id).exists())
            return false
        }
        try {
            secrets.write(id, token)
            return true
        } catch (error: GatewayCredentialUnavailableException) {
            if (strictCi) throw AssertionError("CI must authenticate with its PIN", error)
            assumeNoException("Authenticate with the device PIN before this test", error)
            return false
        }
    }

    @Test fun syncPasswordRequiresDeviceAuthenticationAndRoundTrips() {
        val id = "vaachak_credential_v1:SYNC_PASSWORD:\u0000sync_test_${System.nanoTime()}"
        val value = "fixture password with spaces"
        try {
            if (!writeWithAvailableAuthentication(id, value)) return
            assertEquals(value, secrets.read(id))
            assertTrue(path(id).isFile)
            val contents = path(id).readBytes()
            assertTrue(contents.copyOfRange(0, 4).contentEquals("VAS2".toByteArray(Charsets.US_ASCII)))
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
            if (!writeWithAvailableAuthentication(id, "fixture-gemini")) return
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
            if (!writeWithAvailableAuthentication(origin, "fixture-vault-secret")) return
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
