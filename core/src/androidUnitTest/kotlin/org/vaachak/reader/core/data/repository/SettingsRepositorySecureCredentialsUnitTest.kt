package org.vaachak.reader.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.vaachak.reader.core.domain.model.AiTextProvider
import java.util.UUID

/** P0-08: migration, no plaintext fallback, independent provider and vault slots. */
@RunWith(RobolectricTestRunner::class)
class SettingsRepositorySecureCredentialsUnitTest {
    private val firstVault = "secure_${UUID.randomUUID()}"
    private val secondVault = "secure_${UUID.randomUUID()}"
    private val selectedVault = MutableStateFlow(firstVault)
    private val vault = mockk<VaultRepository>()
    private val secrets = mockk<PrivateGatewaySecrets>()
    private val files = mutableMapOf<String, String>()
    private val repository: SettingsRepository

    init {
        every { vault.activeVaultId } returns selectedVault
        every { secrets.write(any(), any()) } answers {
            files[firstArg()] = secondArg()
            Unit
        }
        every { secrets.read(any()) } answers { files[firstArg()].orEmpty() }
        every { secrets.clear(any()) } answers { files.remove(firstArg()); Unit }
        repository = SettingsRepository(ApplicationProvider.getApplicationContext<Context>(), vault, secrets)
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun store(): DataStore<Preferences> {
        repository.cfUrl.first()
        val field = SettingsRepository::class.java.getDeclaredField("activeVaults")
        field.isAccessible = true
        val active = field.get(repository) as MutableMap<String, DataStore<Preferences>>
        return requireNotNull(active[selectedVault.value])
    }

    @Test fun threeLegacySecretsMigrateAndRemovePlaintextKeys() = runBlocking {
        val dataStore = store()
        dataStore.edit {
            it[SettingsRepository.GEMINI_KEY] = "old-gemini"
            it[SettingsRepository.CF_TOKEN] = "old-cloudflare"
            it[SettingsRepository.SYNC_PASSWORD] = "sync password with spaces"
        }
        assertEquals("old-gemini", repository.geminiKey.first())
        assertEquals("old-cloudflare", repository.cfToken.first())
        assertEquals("sync password with spaces", repository.syncPassword.first())
        val after = dataStore.data.first()
        assertNull(after[SettingsRepository.GEMINI_KEY])
        assertNull(after[SettingsRepository.CF_TOKEN])
        assertNull(after[SettingsRepository.SYNC_PASSWORD])
        assertEquals(3, files.size)
        assertTrue(files.keys.all { it.startsWith("vaachak_credential_v1:") })
    }

    @Test fun freshSavesNeverPersistRawCredentialPreferences() = runBlocking {
        val dataStore = store()
        repository.saveSettings(" gemini ", "https://cloudflare.example/image", " cf ", false)
        repository.updateSyncProfile("reader", " pass with spaces ", "Reader")
        assertEquals("gemini", repository.geminiKey.first())
        assertEquals("cf", repository.cfToken.first())
        assertEquals(" pass with spaces ", repository.syncPassword.first())
        assertNull(dataStore.data.first()[SettingsRepository.GEMINI_KEY])
        assertNull(dataStore.data.first()[SettingsRepository.CF_TOKEN])
        assertNull(dataStore.data.first()[SettingsRepository.SYNC_PASSWORD])
        assertEquals("https://cloudflare.example/image", repository.cfUrl.first())
    }

    @Test fun failedMigrationKeepsLegacyDataAndCanRetry() = runBlocking {
        val dataStore = store()
        dataStore.edit { it[SettingsRepository.GEMINI_KEY] = "recoverable-key" }
        every { secrets.write(any(), any()) } throws GatewayCredentialUnavailableException("Unlock device")
        assertEquals("", repository.geminiKey.first())
        assertEquals("recoverable-key", dataStore.data.first()[SettingsRepository.GEMINI_KEY])
        every { secrets.write(any(), any()) } answers { files[firstArg()] = secondArg(); Unit }
        assertEquals("recoverable-key", repository.geminiKey.first())
        assertNull(dataStore.data.first()[SettingsRepository.GEMINI_KEY])
    }

    @Test fun blankUiFieldCannotDeleteAnUnmigratedLegacyKey() = runBlocking {
        val dataStore = store()
        dataStore.edit { it[SettingsRepository.CF_TOKEN] = "legacy-protected" }
        val error = runCatching { repository.saveSettings("", "https://example", "", false) }
            .exceptionOrNull()
        assertTrue(error is GatewayCredentialUnavailableException)
        assertEquals("legacy-protected", dataStore.data.first()[SettingsRepository.CF_TOKEN])
    }

    @Test fun lockedCiphertextCannotBeClearedByAnEmptyAiForm() = runBlocking {
        repository.saveSettings("gemini-saved", "https://example", "cf-saved", false)
        every { secrets.read(match { it.contains(":GEMINI:") }) } throws
            GatewayCredentialUnavailableException("Unlock device")
        val error = runCatching { repository.saveSettings("", "https://example", "", false) }
            .exceptionOrNull()
        assertTrue(error is GatewayCredentialUnavailableException)
        assertTrue(files.values.contains("gemini-saved"))
        assertTrue(files.values.contains("cf-saved"))
    }

    @Test fun providerAndVaultSwitchesPreserveIndependentCredentials() = runBlocking {
        repository.saveSettings("gemini-one", "https://cf.example", "cf-one", false)
        repository.setAiTextProvider(AiTextProvider.PRIVATE_GATEWAY)
        assertEquals("gemini-one", repository.geminiKey.first())
        assertEquals("cf-one", repository.cfToken.first())
        selectedVault.value = secondVault
        assertEquals("", repository.geminiKey.first())
        assertEquals("", repository.cfToken.first())
        repository.saveSettings("gemini-two", "https://second.example", "cf-two", false)
        selectedVault.value = firstVault
        assertEquals("gemini-one", repository.geminiKey.first())
        assertEquals("cf-one", repository.cfToken.first())
        assertEquals(AiTextProvider.PRIVATE_GATEWAY, repository.aiTextProvider.first())
        selectedVault.value = secondVault
        assertEquals("gemini-two", repository.geminiKey.first())
        assertEquals("cf-two", repository.cfToken.first())
    }

    @Test fun explicitSyncLogoutClearsOnlySyncPassword() = runBlocking {
        repository.saveSettings("gemini", "https://cf.example", "cf", false)
        repository.updateSyncProfile("reader", "background-password", "Tablet")
        assertEquals("background-password", repository.syncPassword.first())
        repository.updateSyncProfile("", "", "")
        assertEquals("", repository.syncPassword.first())
        assertEquals("gemini", repository.geminiKey.first())
        assertEquals("cf", repository.cfToken.first())
        assertFalse(files.values.contains("background-password"))
    }
}
