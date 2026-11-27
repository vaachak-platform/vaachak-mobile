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

    private val ai = AiCredentialRuntimeFixture()

    init {
        every { vault.activeVaultId } returns selectedVault
        every { vault.persistedVaultIdentity } returns selectedVault
        every { secrets.write(any(), any()) } answers {
            files[firstArg()] = secondArg()
            Unit
        }
        every { secrets.read(any()) } answers { files[firstArg()].orEmpty() }
        every { secrets.clear(any()) } answers { files.remove(firstArg()); Unit }
        configureAiVault(vault, selectedVault)
        configureLegacyAiStorage(secrets, files)
        repository = SettingsRepository(ApplicationProvider.getApplicationContext<Context>(), vault, secrets, ai.runtime)
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
        assertTrue(runCatching { repository.geminiKey.first() }.isFailure)
        repository.manageAiCredential(firstVault, AiManagedSlot.GEMINI, AiManagementAction.MIGRATE)
        repository.manageAiCredential(firstVault, AiManagedSlot.CLOUDFLARE, AiManagementAction.MIGRATE)
        assertEquals("old-gemini", repository.geminiKey.first())
        assertEquals("old-cloudflare", repository.cfToken.first())
        assertEquals("sync password with spaces", repository.syncPassword.first())
        val after = dataStore.data.first()
        assertNull(after[SettingsRepository.GEMINI_KEY])
        assertNull(after[SettingsRepository.CF_TOKEN])
        assertNull(after[SettingsRepository.SYNC_PASSWORD])
        assertEquals(2, ai.values.size)
        assertEquals(1, files.size)
        assertTrue(files.keys.all { it.startsWith("vaachak_credential_v1:SYNC_PASSWORD:") })
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
        assertEquals(0, ai.authCalls)
    }

    @Test fun failedMigrationKeepsLegacyDataAndCanRetry() = runBlocking {
        val dataStore = store()
        dataStore.edit { it[SettingsRepository.GEMINI_KEY] = "recoverable-key" }
        ai.authenticated = false
        assertTrue(runCatching { repository.manageAiCredential(firstVault, AiManagedSlot.GEMINI, AiManagementAction.MIGRATE) }.isFailure)
        assertEquals("recoverable-key", dataStore.data.first()[SettingsRepository.GEMINI_KEY])
        assertTrue(ai.values.isEmpty())
        ai.authenticated = true
        repository.manageAiCredential(firstVault, AiManagedSlot.GEMINI, AiManagementAction.MIGRATE)
        assertEquals("recoverable-key", repository.geminiKey.first())
        assertNull(dataStore.data.first()[SettingsRepository.GEMINI_KEY])
    }

    @Test fun blankUiFieldCannotDeleteAnUnmigratedLegacyKey() = runBlocking {
        val dataStore = store()
        dataStore.edit { it[SettingsRepository.CF_TOKEN] = "legacy-protected" }
        repository.saveSettings("", "https://example", "", false)
        assertEquals(0, ai.authCalls)
        assertEquals("legacy-protected", dataStore.data.first()[SettingsRepository.CF_TOKEN])
    }

    @Test fun lockedCiphertextCannotBeClearedByAnEmptyAiForm() = runBlocking {
        repository.saveSettings("gemini-saved", "https://example", "cf-saved", false)
        ai.eligible = false
        repository.saveSettings("", "https://example", "", false)
        assertTrue(ai.values.values.contains("gemini-saved"))
        assertTrue(ai.values.values.contains("cf-saved"))
        assertEquals(AiCredentialStatus.UNAVAILABLE, repository.aiCredentialStatuses(firstVault).first()[AiManagedSlot.GEMINI])
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

    @Test fun blankEditorKeepsBothCiphertextsWithoutReadingLockedSlots() = runBlocking {
        repository.saveSettings("gemini-saved", "https://example", "cf-saved", false)
        every { ai.storage.read(any(), any()) } throws GatewayCredentialUnavailableException("locked")
        repository.saveAiEditorSettings("", "https://new.example", "", false)
        assertTrue(ai.values.values.contains("gemini-saved"))
        assertTrue(ai.values.values.contains("cf-saved"))
        assertEquals("https://new.example", repository.cfUrl.first())
    }

    @Test fun modelSelectionIsPerVault() = runBlocking {
        repository.saveGeminiModel(firstVault, "gemini-3.6-flash")
        assertEquals("gemini-3.6-flash", repository.geminiModel.first())
        selectedVault.value = secondVault
        assertEquals("", repository.geminiModel.first())
        repository.saveGeminiModel(secondVault, "gemini-flash-latest")
        selectedVault.value = firstVault
        assertEquals("gemini-3.6-flash", repository.geminiModel.first())
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
    @Test fun lockedReplacementDoesNotChangeSavedCredentialOrRevision() = runBlocking {
        repository.saveSettings("old-key", "https://cf.example", "", false)
        repository.setOfflineMode(false)
        val before = repository.loadAiRequest(AiRequestKind.TEXT, firstVault)
        ai.eligible = false
        assertTrue(runCatching { repository.manageAiCredential(firstVault, AiManagedSlot.GEMINI, AiManagementAction.REPLACE, "new-key") }.isFailure)
        ai.eligible = true
        assertEquals("old-key", repository.geminiKey.first())
        assertFalse(before.invalidated.first())
    }

    @Test fun staleHostCallbackCannotPublishCredential() = runBlocking {
        store().edit { it[SettingsRepository.GEMINI_KEY] = "legacy-key" }
        ai.beforeAuth = { ai.managementEpoch++ }
        assertTrue(runCatching { repository.manageAiCredential(firstVault, AiManagedSlot.GEMINI, AiManagementAction.MIGRATE) }.isFailure)
        assertTrue(ai.values.isEmpty())
        assertEquals(AiCredentialStatus.MIGRATION_REQUIRED, repository.aiCredentialStatuses(firstVault).first()[AiManagedSlot.GEMINI])
    }

    @Test fun changedVaultDuringAuthenticationCannotWriteEitherVault() = runBlocking {
        store().edit { it[SettingsRepository.GEMINI_KEY] = "legacy-key" }
        ai.beforeAuth = { selectedVault.value = secondVault }
        assertTrue(runCatching { repository.manageAiCredential(firstVault, AiManagedSlot.GEMINI, AiManagementAction.MIGRATE) }.isFailure)
        assertTrue(ai.values.isEmpty())
    }

    @Test fun routineManagementDoesNotPromptButRejectsLockedAndWrongVault() = runBlocking {
        ai.authenticated = false
        repository.manageAiCredential(firstVault, AiManagedSlot.GEMINI, AiManagementAction.REPLACE, "first-key")
        repository.manageAiCredential(firstVault, AiManagedSlot.GEMINI, AiManagementAction.REPLACE, "second-key")
        assertEquals("second-key", repository.geminiKey.first())
        ai.eligible = false
        assertTrue(runCatching { repository.manageAiCredential(firstVault, AiManagedSlot.GEMINI, AiManagementAction.REMOVE) }.isFailure)
        ai.eligible = true
        selectedVault.value = secondVault
        assertTrue(runCatching { repository.manageAiCredential(firstVault, AiManagedSlot.GEMINI, AiManagementAction.REMOVE) }.isFailure)
        selectedVault.value = firstVault
        assertEquals("second-key", repository.geminiKey.first())
        repository.manageAiCredential(firstVault, AiManagedSlot.GEMINI, AiManagementAction.REMOVE)
        assertEquals("", repository.geminiKey.first())
        assertEquals(0, ai.authCalls)
    }

    @Test fun encryptedLegacyMigrationRetiresOnlyExactSlot() = runBlocking {
        val id = "vaachak_credential_v1:GEMINI:\u0000$firstVault"
        val syncId = "vaachak_credential_v1:SYNC_PASSWORD:\u0000$firstVault"
        files[id] = "legacy-key"
        files[syncId] = "sync-key"
        assertEquals(AiCredentialStatus.MIGRATION_REQUIRED, repository.aiCredentialStatuses(firstVault).first()[AiManagedSlot.GEMINI])
        repository.manageAiCredential(firstVault, AiManagedSlot.GEMINI, AiManagementAction.MIGRATE)
        assertEquals("legacy-key", repository.geminiKey.first())
        assertFalse(files.containsKey(id))
        assertEquals("sync-key", files[syncId])
    }

    @Test fun interruptedRemovalCannotResurrectLegacyCredential() = runBlocking {
        repository.saveSettings("old-key", "https://cf.example", "", false)
        val legacyId = "vaachak_credential_v1:GEMINI:\u0000$firstVault"
        files[legacyId] = "legacy-key"
        ai.failRemove = true
        assertTrue(runCatching { repository.manageAiCredential(firstVault, AiManagedSlot.GEMINI, AiManagementAction.REMOVE) }.isFailure)
        assertEquals(AiCredentialStatus.REMOVAL_PENDING, repository.aiCredentialStatuses(firstVault).first()[AiManagedSlot.GEMINI])
        assertTrue(runCatching { repository.geminiKey.first() }.isFailure)
        ai.failRemove = false
        repository.manageAiCredential(firstVault, AiManagedSlot.GEMINI, AiManagementAction.REMOVE)
        assertEquals("", repository.geminiKey.first())
        assertFalse(files.containsKey(legacyId))
    }

    @Test fun retirementFailureKeepsVerifiedNewCredentialForAuthenticatedCleanup() = runBlocking {
        val dataStore = store()
        dataStore.edit { it[SettingsRepository.GEMINI_KEY] = "legacy-key" }
        every { secrets.retireAiCredential(any()) } throws IllegalStateException("Fixture cleanup failed")
        assertTrue(runCatching { repository.manageAiCredential(firstVault, AiManagedSlot.GEMINI, AiManagementAction.MIGRATE) }.isFailure)
        assertEquals("legacy-key", ai.values[firstVault to AiManagedSlot.GEMINI])
        assertEquals(AiCredentialStatus.CLEANUP_REQUIRED, repository.aiCredentialStatuses(firstVault).first()[AiManagedSlot.GEMINI])
        assertTrue(runCatching { repository.geminiKey.first() }.isFailure)
        configureLegacyAiStorage(secrets, files)
        repository.manageAiCredential(firstVault, AiManagedSlot.GEMINI, AiManagementAction.MIGRATE)
        assertEquals("legacy-key", repository.geminiKey.first())
        assertNull(dataStore.data.first()[SettingsRepository.GEMINI_KEY])
    }

    @Test fun savedCredentialNeedsNoFurtherAuthenticationAndBackgroundBlocksDispatch() = runBlocking {
        repository.saveSettings("test-key", "https://cf.example", "", false)
        repository.setOfflineMode(false)
        val count = ai.authCalls
        val config = repository.loadAiRequest(AiRequestKind.TEXT, firstVault)
        assertEquals("test-key", config.credential)
        assertEquals("test-key", repository.loadAiRequest(AiRequestKind.TEXT, firstVault).credential)
        assertEquals(count, ai.authCalls)
        ai.eligible = false
        var sent = false
        assertTrue(runCatching { config.runGuarded { sent = true } }.isFailure)
        assertFalse(sent)
        assertTrue(runCatching { repository.loadAiRequest(AiRequestKind.TEXT, firstVault) }.isFailure)
    }

}
