package org.vaachak.reader.core.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a device because Robolectric cannot emulate the hardware-backed AndroidKeyStore. */
@RunWith(AndroidJUnit4::class)
class PrivateGatewaySecretsInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val secrets get() = PrivateGatewaySecrets(context)

    @Test fun encryptedTokenRoundTripDoesNotPersistPlaintext() {
        val vault = "test-encryption-${System.nanoTime()}"
        val token = "dummy-local-key-for-coverage"
        try {
            assertEquals("", secrets.read(vault))
            secrets.write(vault, token)
            assertEquals(token, secrets.read(vault))
            val stored = credentialFile(vault)
            assertTrue(stored.isFile)
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
            secrets.write(first, "first-token")
            secrets.write(second, "second-token")
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
            val whitespace = runCatching { secrets.write(vault, "has spaces") }
            val empty = runCatching { secrets.write(vault, "") }
            assertTrue(whitespace.isFailure)
            assertTrue(empty.isFailure)
            assertFalse(credentialFile(vault).exists())
        } finally {
            secrets.clear(vault)
        }
    }

    @Test fun corruptedCiphertextIsNotReturnedAsASecret() {
        val vault = "test-corrupt-${System.nanoTime()}"
        try {
            secrets.write(vault, "valid-test-token")
            credentialFile(vault).writeBytes(byteArrayOf(1, 2, 3))
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
