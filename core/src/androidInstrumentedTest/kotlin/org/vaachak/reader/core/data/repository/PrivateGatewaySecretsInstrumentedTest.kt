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

/** Keystore integration: strict lock-screen policy on CI/BOOX; round trips on unlocked devices. */
@RunWith(AndroidJUnit4::class)
class PrivateGatewaySecretsInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val secrets get() = PrivateGatewaySecrets(context)
    private val deviceSecure get() =
        (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure

    private fun writeWithAvailableAuthentication(vault: String, token: String): Boolean {
        if (!deviceSecure) {
            val failure = runCatching { secrets.write(vault, token) }.exceptionOrNull()
            assertTrue(failure is GatewayCredentialUnavailableException)
            assertFalse(credentialFile(vault).exists())
            return false
        }
        try {
            secrets.write(vault, token)
            return true
        } catch (exception: GatewayCredentialUnavailableException) {
            // On a locked Samsung, unlock with the device PIN and rerun. CI has no PIN,
            // so its tested contract is the fail-closed path rather than bypassing auth.
            assumeNoException("Unlock device credentials immediately before this test", exception)
            return false
        }
    }

    @Test fun encryptedTokenRoundTripDoesNotPersistPlaintext() {
        val vault = "test-encryption-${System.nanoTime()}"
        val token = "dummy-local-key-for-coverage"
        try {
            assertEquals("", secrets.read(vault))
            if (!writeWithAvailableAuthentication(vault, token)) return
            assertEquals(token, secrets.read(vault))
            val stored = credentialFile(vault)
            assertTrue(stored.isFile)
            assertTrue(stored.readBytes().copyOfRange(0, 4).contentEquals("VAG2".toByteArray()))
            assertFalse(stored.readText(Charsets.ISO_8859_1).contains(token))
            secrets.clear(vault)
            assertEquals("", secrets.read(vault))
        } finally {
            secrets.clear(vault)
        }
    }

    @Test fun vaultCredentialsAreIsolatedAndClearedIndependently() {
        val first = "test-vault-a-${System.nanoTime()}"
        val second = "test-vault-b-${System.nanoTime()}"
        try {
            if (!writeWithAvailableAuthentication(first, "first-token")) return
            if (!writeWithAvailableAuthentication(second, "second-token")) return
            assertEquals("first-token", secrets.read(first))
            assertEquals("second-token", secrets.read(second))
            secrets.clear(first)
            assertEquals("", secrets.read(first))
            assertEquals("second-token", secrets.read(second))
        } finally {
            secrets.clear(first)
            secrets.clear(second)
        }
    }

    @Test fun malformedTokensAreRejected() {
        val vault = "test-invalid-${System.nanoTime()}"
        try {
            assertTrue(runCatching { secrets.write(vault, "has spaces") }.isFailure)
            assertTrue(runCatching { secrets.write(vault, "") }.isFailure)
            assertFalse(credentialFile(vault).exists())
        } finally {
            secrets.clear(vault)
        }
    }

    @Test fun corruptedCiphertextIsNotReturnedAsASecret() {
        val vault = "test-corrupt-${System.nanoTime()}"
        try {
            credentialFile(vault).writeBytes(byteArrayOf(1, 2, 3))
            assertTrue(runCatching { secrets.read(vault) }.isFailure)
            credentialFile(vault).writeBytes(ByteArray(40))
            assertTrue(runCatching { secrets.read(vault) }.isFailure)
        } finally {
            secrets.clear(vault)
        }
    }

    private fun credentialFile(vaultId: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(vaultId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(context.noBackupFilesDir, "private_ai_$digest.enc")
    }
}
