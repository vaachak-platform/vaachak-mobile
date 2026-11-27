package org.vaachak.reader.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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

/**
 * Contract tests for PR #58's encrypted gateway credential migration.
 *
 * The temporary legacy preference is inserted into the repository's own DataStore:
 * creating a second PreferenceDataStoreFactory for the same file is prohibited by
 * DataStore and would make this test invalid. Reflection is intentionally scoped
 * to the private map of stores; the public save/load methods remain under test.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryCoverageUnitTest {
    private val vaultId = MutableStateFlow("coverage_${UUID.randomUUID()}")
    private val vault = mockk<VaultRepository>()
    private val secrets = mockk<PrivateGatewaySecrets>()
    private val encryptedTokens = mutableMapOf<String, String>()
    private val repository: SettingsRepository

    private val ai = AiCredentialRuntimeFixture()

    init {
        every { vault.activeVaultId } returns vaultId
        every { secrets.write(any(), any()) } answers {
            encryptedTokens[firstArg()] = secondArg()
            Unit
        }
        every { secrets.read(any()) } answers { encryptedTokens[firstArg()].orEmpty() }
        every { secrets.clear(any()) } answers {
            encryptedTokens.remove(firstArg())
            Unit
        }
        configureAiVault(vault, vaultId)
        configureLegacyAiStorage(secrets, encryptedTokens)
        repository = SettingsRepository(ApplicationProvider.getApplicationContext<Context>(), vault, secrets, ai.runtime)
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun activePreferences(): DataStore<Preferences> {
        // Force creation through the repository first to avoid two active DataStores
        // owning the same path (which DataStore correctly rejects).
        repository.geminiKey.first()
        val field = SettingsRepository::class.java.getDeclaredField("activeVaults")
        field.isAccessible = true
        val stores = field.get(repository) as MutableMap<String, DataStore<Preferences>>
        return requireNotNull(stores[vaultId.value])
    }

    @Test
    fun explicitReplacementDeletesObsoletePlaintext() = runBlocking {
        val store = activePreferences()
        store.edit { it[SettingsRepository.PRIVATE_GATEWAY_TOKEN] = "legacy-test-secret" }
        assertTrue(runCatching { repository.loadPrivateGatewayToken() }.isFailure)
        assertEquals(AiCredentialStatus.RECOVERY_REQUIRED, repository.aiCredentialStatuses(vaultId.value).first()[AiManagedSlot.GATEWAY])
        repository.manageAiCredential(vaultId.value, AiManagedSlot.GATEWAY, AiManagementAction.REPLACE, "replacement-key")
        assertEquals("replacement-key", repository.loadPrivateGatewayToken())
        assertNull(store.data.first()[SettingsRepository.PRIVATE_GATEWAY_TOKEN])
        verify(exactly = 0) { secrets.write(any(), any()) }
    }

    @Test
    fun failedReplacementKeepsLegacyTokenUntilExplicitRetry() = runBlocking {
        val store = activePreferences()
        store.edit { it[SettingsRepository.PRIVATE_GATEWAY_TOKEN] = "recovery-test-secret" }
        ai.failWrite = true
        assertTrue(runCatching { repository.manageAiCredential(vaultId.value, AiManagedSlot.GATEWAY, AiManagementAction.REPLACE, "replacement-key") }.isFailure)
        assertEquals("recovery-test-secret", store.data.first()[SettingsRepository.PRIVATE_GATEWAY_TOKEN])
        assertTrue(runCatching { repository.loadPrivateGatewayToken() }.isFailure)
        ai.failWrite = false
        repository.manageAiCredential(vaultId.value, AiManagedSlot.GATEWAY, AiManagementAction.REPLACE, "replacement-key")
        assertEquals("replacement-key", repository.loadPrivateGatewayToken())
        assertNull(store.data.first()[SettingsRepository.PRIVATE_GATEWAY_TOKEN])
    }

    @Test
    fun emptyLegacyTokenIsMissingWithoutKeyGenerationOrRead() = runBlocking {
        val store = activePreferences()
        store.edit { it[SettingsRepository.PRIVATE_GATEWAY_TOKEN] = "" }
        assertEquals("", repository.loadPrivateGatewayToken())
        assertEquals(AiCredentialStatus.MISSING, repository.aiCredentialStatuses(vaultId.value).first()[AiManagedSlot.GATEWAY])
        verify(exactly = 0) { secrets.write(any(), any()) }
        verify(exactly = 0) { secrets.read(vaultId.value) }
    }

    @Test
    fun savingGatewayMetadataWithEmptyTokenKeepsEncryptedCredential() = runBlocking {
        repository.savePrivateGatewayToken("saved-test-key")
        repository.savePrivateGatewaySettings(" https://reader.demo.ts.net:8443/v1 ", " local-chat ", "  ")
        assertEquals("https://reader.demo.ts.net:8443/v1", repository.privateGatewayUrl.first())
        assertEquals("local-chat", repository.privateGatewayModel.first())
        assertEquals("saved-test-key", repository.loadPrivateGatewayToken())
        assertNull(activePreferences().data.first()[SettingsRepository.PRIVATE_GATEWAY_TOKEN])
    }

    @Test
    fun savingGatewayMetadataWithKeyNeverStoresItInPlaintext() = runBlocking {
        repository.savePrivateGatewaySettings(" https://reader.demo.ts.net:8443/v1 ", " local-chat ", "  gateway-test-key  ")
        assertEquals("gateway-test-key", repository.loadPrivateGatewayToken())
        assertNull(activePreferences().data.first()[SettingsRepository.PRIVATE_GATEWAY_TOKEN])
        assertEquals("gateway-test-key", ai.values[vaultId.value to AiManagedSlot.GATEWAY])
        verify(exactly = 0) { secrets.write(any(), any()) }
    }

    @Test
    fun clearingGatewayTokenAlsoDeletesLegacyPlaintextCopy() = runBlocking {
        val store = activePreferences()
        store.edit { it[SettingsRepository.PRIVATE_GATEWAY_TOKEN] = "old-test-token" }
        encryptedTokens[vaultId.value] = "old-test-token"
        repository.savePrivateGatewayToken("  ")
        assertNull(store.data.first()[SettingsRepository.PRIVATE_GATEWAY_TOKEN])
        assertEquals("", repository.loadPrivateGatewayToken())
        verify(exactly = 1) { secrets.retireAiCredential(vaultId.value) }
        assertFalse(encryptedTokens.containsKey(vaultId.value))
        assertEquals(AiCredentialStatus.MISSING, repository.aiCredentialStatuses(vaultId.value).first()[AiManagedSlot.GATEWAY])
    }

    @Test
    fun failedEncryptedSaveDoesNotCreatePlaintextFallback() = runBlocking {
        ai.failWrite = true
        val failure = runCatching {
            repository.savePrivateGatewaySettings("https://reader.demo.ts.net:8443/v1", "local-chat", "test-secret")
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertNull(activePreferences().data.first()[SettingsRepository.PRIVATE_GATEWAY_TOKEN])
        assertEquals("", repository.privateGatewayUrl.first())
        assertTrue(runCatching { repository.loadPrivateGatewayToken() }.isFailure)
    }

    @Test
    fun unknownSavedProviderFallsBackToGeminiAndBothChoicesPersist() = runBlocking {
        val store = activePreferences()
        store.edit { it[SettingsRepository.AI_TEXT_PROVIDER] = "obsolete-v1-provider" }
        assertEquals(AiTextProvider.GEMINI, repository.aiTextProvider.first())

        repository.setAiTextProvider(AiTextProvider.PRIVATE_GATEWAY)
        assertEquals(AiTextProvider.PRIVATE_GATEWAY, repository.aiTextProvider.first())
        repository.setAiTextProvider(AiTextProvider.GEMINI)
        assertEquals(AiTextProvider.GEMINI, repository.aiTextProvider.first())
    }

    @Test
    fun optionalAiEnabledFlagIsNotChangedWhenNullButIsSavedWhenExplicit() = runBlocking {
        repository.setAiEnabled(false)
        repository.saveSettings(" demo-key ", "https://example.invalid", " auth ", true)
        assertFalse(repository.isAiEnabled.first())
        assertEquals("demo-key", repository.geminiKey.first())
        assertEquals("auth", repository.cfToken.first())

        repository.saveSettings("demo-key", "https://example.invalid", "auth", true, isAiEnabled = true)
        assertTrue(repository.isAiEnabled.first())
    }
}
