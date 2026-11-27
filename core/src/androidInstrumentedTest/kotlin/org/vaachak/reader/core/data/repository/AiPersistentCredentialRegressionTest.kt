package org.vaachak.reader.core.data.repository

import android.app.KeyguardManager
import android.os.SystemClock
import android.security.keystore.KeyInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Permanent regression suite using uniquely named synthetic VAI3 slots only. Never modifies
 * an existing vault, v2 key, real API credential, app data or device PIN. The caller
 * must keep the device normally unlocked. No prompt UI or timed persistence tested.
 */
@RunWith(AndroidJUnit4::class)
class AiPersistentCredentialRegressionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val vault = "d90-persistent-probe-${UUID.randomUUID()}"
    private var foreground = true
    private val storage = AiPersistentCredentialStorage(context) { foreground }
    private val ledger = AiManagementGrantLedger(SystemClock::elapsedRealtime)
    private val slot = AiManagedSlot.GEMINI
    private val fixture = "synthetic-d90-not-a-real-key-${UUID.randomUUID()}"

    private fun requireSecureDevice() {
        val lock = context.getSystemService(KeyguardManager::class.java)
        assertTrue("A secure screen lock is required for this test", lock != null && lock.isDeviceSecure)
        assertFalse("Unlock the device normally before running this test", requireNotNull(lock).isDeviceLocked)
    }

    private fun write(target: AiManagedSlot = slot, value: String = fixture) {
        val binding = AiManagementBinding(vault, target, AiManagementAction.REPLACE, 1, 1)
        val grant = requireNotNull(ledger.complete(ledger.begin(binding), binding))
        storage.writeVerified(binding, value, ledger, grant)
    }

    private fun cleanup() {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        for (target in AiManagedSlot.entries) {
            // All aliases are derived from this test's unique generated fixture vault.
            storage.file(vault, target).delete()
            store.deleteEntry(storage.alias(vault, target))
        }
    }

    @Test fun newKeyPolicyAndEncryptedRoundTrip() {
        requireSecureDevice()
        try {
            assertEquals(AiPersistentCredentialStorage.Presence.MISSING, storage.presence(vault, slot))
            write()
            val file = storage.file(vault, slot)
            assertTrue(file.isFile)
            assertEquals(context.noBackupFilesDir.canonicalFile, file.parentFile!!.canonicalFile)
            assertEquals("VAI3", file.readBytes().copyOfRange(0, 4).toString(Charsets.US_ASCII))
            assertFalse(file.readText(Charsets.ISO_8859_1).contains(fixture))
            val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .getKey(storage.alias(vault, slot), null) as SecretKey
            val info = SecretKeyFactory.getInstance("AES", "AndroidKeyStore")
                .getKeySpec(key, KeyInfo::class.java) as KeyInfo
            assertEquals(256, info.keySize)
            assertFalse("ADR requires no recurring recent-auth timer for this separate AI key", info.isUserAuthenticationRequired)
            val oldCiphertext = file.readBytes()
            write()
            assertFalse("Randomized IV must change ciphertext", oldCiphertext.contentEquals(file.readBytes()))
            val fresh = AiPersistentCredentialStorage(context) { true }
            assertEquals(fixture, fresh.read(vault, slot))
        } finally { cleanup() }
    }

    @Test fun foregroundAndSlotIsolationAndCorruptionFailClosed() {
        requireSecureDevice()
        try {
            write()
            assertEquals(AiPersistentCredentialStorage.Presence.SAVED, storage.presence(vault, slot))
            foreground = false
            assertEquals(AiPersistentCredentialStorage.Presence.UNAVAILABLE, storage.presence(vault, slot))
            assertTrue(runCatching { storage.read(vault, slot) }.exceptionOrNull() is GatewayCredentialUnavailableException)
            foreground = true
            assertNull(storage.read(vault, AiManagedSlot.CLOUDFLARE))
            write(AiManagedSlot.CLOUDFLARE, "synthetic-cloudflare")
            storage.file(vault, AiManagedSlot.CLOUDFLARE).writeBytes(storage.file(vault, slot).readBytes())
            assertTrue(runCatching { storage.read(vault, AiManagedSlot.CLOUDFLARE) }.isFailure)
            val path = storage.file(vault, slot)
            val tampered = path.readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            path.writeBytes(tampered)
            assertTrue(runCatching { storage.read(vault, slot) }.isFailure)
            assertTrue(path.exists())
        } finally { cleanup() }
    }

    @Test fun missingKeyDoesNotRegenerateOrOverwriteCiphertext() {
        requireSecureDevice()
        try {
            write()
            val path = storage.file(vault, slot)
            val ciphertext = path.readBytes()
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            keys.deleteEntry(storage.alias(vault, slot))
            assertEquals(AiPersistentCredentialStorage.Presence.RECOVERY_REQUIRED, storage.presence(vault, slot))
            assertTrue(runCatching { storage.read(vault, slot) }.isFailure)
            assertTrue(runCatching { write() }.isFailure)
            assertArrayEquals(ciphertext, path.readBytes())
            assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(storage.alias(vault, slot)))
        } finally { cleanup() }
    }

    @Test fun authenticatedRemoveRetiresCiphertextAndAliasAndIsIdempotent() {
        requireSecureDevice()
        try {
            write()
            val binding = AiManagementBinding(vault, slot, AiManagementAction.REMOVE, 2, 1)
            repeat(2) {
                val grant = requireNotNull(ledger.complete(ledger.begin(binding), binding))
                storage.removeVerified(binding, ledger, grant) { foreground }
                assertFalse(storage.file(vault, slot).exists())
                assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    .containsAlias(storage.alias(vault, slot)))
                assertEquals(AiPersistentCredentialStorage.Presence.MISSING, storage.presence(vault, slot))
                assertNull(storage.read(vault, slot))
                assertTrue(runCatching { storage.removeVerified(binding, ledger, grant) { foreground } }.isFailure)
            }
        } finally { cleanup() }
    }

    @Test fun invalidOrRevokedWritesDoNotOverwriteSavedCiphertext() {
        requireSecureDevice()
        try {
            write()
            val path = storage.file(vault, slot)
            val original = path.readBytes()
            val binding = AiManagementBinding(vault, slot, AiManagementAction.REPLACE, 2, 1)
            for (bad in listOf("", " ", "x".repeat(4097))) {
                val grant = requireNotNull(ledger.complete(ledger.begin(binding), binding))
                assertTrue(runCatching { storage.writeVerified(binding, bad, ledger, grant) }.isFailure)
                assertArrayEquals(original, path.readBytes())
            }
            val grant = requireNotNull(ledger.complete(ledger.begin(binding), binding))
            assertTrue(runCatching { storage.writeVerified(binding, "replacement", ledger, grant) { false } }.isFailure)
            assertArrayEquals(original, path.readBytes())
            foreground = false
            val denied = requireNotNull(ledger.complete(ledger.begin(binding), binding))
            assertTrue(runCatching { storage.writeVerified(binding, "replacement", ledger, denied) }.isFailure)
            assertArrayEquals(original, path.readBytes())
            foreground = true
            assertEquals(fixture, storage.read(vault, slot))
        } finally { cleanup() }
    }

    @Test fun malformedEnvelopeAndVaultBindingFailClosed() {
        requireSecureDevice()
        val other = "$vault-other"
        try {
            write()
            val path = storage.file(vault, slot)
            val original = path.readBytes()
            val otherBinding = AiManagementBinding(other, slot, AiManagementAction.REPLACE, 1, 1)
            val otherGrant = requireNotNull(ledger.complete(ledger.begin(otherBinding), otherBinding))
            storage.writeVerified(otherBinding, "different synthetic credential", ledger, otherGrant)
            storage.file(other, slot).writeBytes(original)
            assertTrue(runCatching { storage.read(other, slot) }.isFailure)
            for (malformed in listOf(byteArrayOf(1), ByteArray(4129))) {
                path.writeBytes(malformed)
                assertEquals(AiPersistentCredentialStorage.Presence.RECOVERY_REQUIRED, storage.presence(vault, slot))
                assertTrue(runCatching { storage.read(vault, slot) }.isFailure)
            }
            path.writeBytes(original.copyOf().apply { this[0] = 0 })
            assertTrue(runCatching { storage.read(vault, slot) }.isFailure)
            path.writeBytes(original)
            assertEquals(fixture, storage.read(vault, slot))
        } finally {
            storage.file(other, slot).delete()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(storage.alias(other, slot))
            cleanup()
        }
    }
}
