package org.vaachak.reader.core.data.repository

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
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
    private val requireAuthenticatedCi get() =
        InstrumentationRegistry.getArguments().getString("vaachakAuthCi") == "true"

    private fun writeWithAvailableAuthentication(vault: String, token: String): Boolean {
        if (!deviceSecure) {
            if (requireAuthenticatedCi) {
                throw AssertionError("CI emulator must have a secure PIN before core coverage starts")
            }
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
            if (requireAuthenticatedCi) {
                throw AssertionError("CI emulator must be unlocked using its PIN before Keystore tests", exception)
            }
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


    @Test fun credentialLengthAndWhitespaceBoundariesFailClosed() {
        val vault = "test-token-bounds-${System.nanoTime()}"
        try {
            listOf("", " ", "key with spaces", "key\twith\ttabs", "key\nwith-newline", "x".repeat(513)).forEach { invalid ->
                assertTrue("Expected invalid token to fail", runCatching { secrets.write(vault, invalid) }.isFailure)
                assertFalse(credentialFile(vault).exists())
            }
        } finally {
            secrets.clear(vault)
        }
    }

    @Test fun invalidEnvelopeLengthsAndVersionAreRejected() {
        val vault = "test-envelope-${System.nanoTime()}"
        try {
            val file = credentialFile(vault)
            listOf(
                byteArrayOf(),
                ByteArray(31),
                ByteArray(8193),
                ByteArray(40) { 0x51.toByte() }
            ).forEach { invalid ->
                file.writeBytes(invalid)
                assertTrue(runCatching { secrets.read(vault) }.isFailure)
            }
        } finally {
            secrets.clear(vault)
        }
    }

    @Test fun clearingOneVaultDoesNotDeleteAnotherVaultFile() {
        val first = "test-clear-first-${System.nanoTime()}"
        val second = "test-clear-second-${System.nanoTime()}"
        try {
            credentialFile(first).writeBytes(ByteArray(40) { 1 })
            credentialFile(second).writeBytes(ByteArray(40) { 2 })
            secrets.clear(first)
            secrets.clear(first) // idempotent
            assertFalse(credentialFile(first).exists())
            assertTrue(credentialFile(second).exists())
        } finally {
            secrets.clear(first)
            secrets.clear(second)
        }
    }

    @Test fun authenticatedCiphertextRejectsModificationAndWrongVault() {
        val original = "test-aad-original-${System.nanoTime()}"
        val other = "test-aad-other-${System.nanoTime()}"
        try {
            if (!writeWithAvailableAuthentication(original, "safe-fixture-token")) return
            val encrypted = credentialFile(original).readBytes()
            credentialFile(other).writeBytes(encrypted)
            assertTrue("Ciphertext must bind to its vault", runCatching { secrets.read(other) }.isFailure)
            val corrupted = encrypted.copyOf()
            corrupted[corrupted.lastIndex] = (corrupted.last().toInt() xor 1).toByte()
            credentialFile(original).writeBytes(corrupted)
            assertTrue("Tampered GCM ciphertext must fail", runCatching { secrets.read(original) }.isFailure)
        } finally {
            secrets.clear(original)
            secrets.clear(other)
        }
    }

    @Test fun legacyVag1FixtureIsMigratedToAuthenticatedVag2() {
        val vault = "test-legacy-migration-${System.nanoTime()}"
        if (!deviceSecure) {
            // Without a secure device lock, even reading legacy VAG1 must fail closed.
            val fake = "VAG1".toByteArray() + ByteArray(40)
            try {
                credentialFile(vault).writeBytes(fake)
                assertTrue(runCatching { secrets.read(vault) }.exceptionOrNull() is GatewayCredentialUnavailableException)
            } finally {
                secrets.clear(vault)
            }
            return
        }
        // Probe authorization before touching any historical key alias.
        val probe = "test-auth-probe-${System.nanoTime()}"
        try {
            if (!writeWithAvailableAuthentication(probe, "temporary-probe")) return
        } finally {
            secrets.clear(probe)
        }

        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "vaachak.private.ai.gateway.key.v1"
        val aliasAlreadyExisted = store.containsAlias(alias)
        try {
            val legacyKey = if (aliasAlreadyExisted) {
                store.getKey(alias, null) as SecretKey
            } else {
                // A new test fixture uses an AUTHENTICATED key even under the old
                // envelope alias; never recreate weak legacy key policy in tests.
                val params = KeyGenParameterSpec.Builder(
                    alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    params.setUserAuthenticationParameters(60, KeyProperties.AUTH_DEVICE_CREDENTIAL)
                } else {
                    @Suppress("DEPRECATION")
                    params.setUserAuthenticationValidityDurationSeconds(60)
                }
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                    .apply { init(params.build()) }.generateKey()
            }
            val original = "dummy-legacy-migration-fixture"
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, legacyKey)
            credentialFile(vault).writeBytes("VAG1".toByteArray() + cipher.iv + cipher.doFinal(original.toByteArray()))

            assertEquals(original, secrets.read(vault))
            assertTrue(credentialFile(vault).readBytes().copyOfRange(0, 4).contentEquals("VAG2".toByteArray()))
            assertEquals(original, secrets.read(vault))
        } catch (exception: GatewayCredentialUnavailableException) {
            if (requireAuthenticatedCi) {
                throw AssertionError("CI must exercise authenticated VAG1 migration, not skip it", exception)
            }
            assumeNoException("Unlock using device credentials immediately before this test", exception)
        } finally {
            secrets.clear(vault)
            // Never remove a pre-existing alias: real users may have unmigrated
            // encrypted data under it. Only clean up a test-generated alias.
            if (!aliasAlreadyExisted) store.deleteEntry(alias)
        }
    }

    private fun credentialFile(vaultId: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(vaultId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(context.noBackupFilesDir, "private_ai_$digest.enc")
    }
}
