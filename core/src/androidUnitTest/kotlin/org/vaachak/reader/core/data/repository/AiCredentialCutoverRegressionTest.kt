package org.vaachak.reader.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.verify
import androidx.datastore.preferences.core.stringPreferencesKey
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

/** Exact cutover and nonsecret configuration contracts. OS authentication is mocked by the fixture. */
@RunWith(RobolectricTestRunner::class)
class AiCredentialCutoverRegressionTest {
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


    @Test fun failedReplacementIsRecoveryRequiredAndNeverFallsBack() = runBlocking {
        val id = "vaachak_credential_v1:GEMINI:\u0000$firstVault"
        files[id] = "legacy-fixture"
        ai.failWrite = true
        assertTrue(runCatching { repository.manageAiCredential(firstVault, AiManagedSlot.GEMINI, AiManagementAction.REPLACE, "new-key") }.isFailure)
        assertEquals(AiCredentialStatus.RECOVERY_REQUIRED, repository.aiCredentialStatuses(firstVault).first()[AiManagedSlot.GEMINI])
        assertTrue(runCatching { repository.geminiKey.first() }.isFailure)
        assertEquals("legacy-fixture", files[id])
        ai.failWrite = false
        repository.manageAiCredential(firstVault, AiManagedSlot.GEMINI, AiManagementAction.REPLACE, "new-key")
        assertEquals("new-key", repository.geminiKey.first())
        assertFalse(files.containsKey(id))
    }

    @Test fun interruptedMigrationAndUnknownMarkerAreFailClosed() = runBlocking {
        val data = store()
        data.edit { it[SettingsRepository.CF_TOKEN] = "old-fixture" }
        ai.failWrite = true
        assertTrue(runCatching { repository.manageAiCredential(firstVault, AiManagedSlot.CLOUDFLARE, AiManagementAction.MIGRATE) }.isFailure)
        assertEquals(AiCredentialStatus.MIGRATION_REQUIRED, repository.aiCredentialStatuses(firstVault).first()[AiManagedSlot.CLOUDFLARE])
        assertEquals("old-fixture", data.data.first()[SettingsRepository.CF_TOKEN])
        ai.failWrite = false
        repository.manageAiCredential(firstVault, AiManagedSlot.CLOUDFLARE, AiManagementAction.MIGRATE)
        assertEquals(2, ai.authCalls)
        assertEquals("old-fixture", repository.cfToken.first())
        data.edit { it[stringPreferencesKey("ai_credential_v3_cloudflare")] = "unknown-future-version" }
        assertEquals(AiCredentialStatus.RECOVERY_REQUIRED, repository.aiCredentialStatuses(firstVault).first()[AiManagedSlot.CLOUDFLARE])
        assertTrue(runCatching { repository.cfToken.first() }.isFailure)
    }

    @Test fun missingV3EnvelopeIsRecoveryNotMissingAndOtherVaultHasNoStatuses() = runBlocking {
        repository.manageAiCredential(firstVault, AiManagedSlot.GEMINI, AiManagementAction.REPLACE, "synthetic-key")
        ai.values.remove(firstVault to AiManagedSlot.GEMINI)
        assertEquals(AiCredentialStatus.RECOVERY_REQUIRED, repository.aiCredentialStatuses(firstVault).first()[AiManagedSlot.GEMINI])
        assertTrue(runCatching { repository.geminiKey.first() }.isFailure)
        assertTrue(repository.aiCredentialStatuses(secondVault).first().isEmpty())
    }

    @Test fun pinnedNonsecretSettingsKeepCredentialsWithoutReadingOrAuthenticating() = runBlocking {
        repository.manageAiCredential(firstVault, AiManagedSlot.GEMINI, AiManagementAction.REPLACE, "synthetic-key")
        val auths = ai.authCalls
        ai.eligible = false
        repository.saveAiConfiguration(firstVault, AiConfigurationUpdate(AiTextProvider.PRIVATE_GATEWAY,
            " https://fixture.invalid/image ", " https://fixture.invalid/v1 ", " local-model ", false))
        assertEquals(AiTextProvider.PRIVATE_GATEWAY, repository.aiTextProvider.first())
        assertEquals("https://fixture.invalid/image", repository.cfUrl.first())
        assertEquals("https://fixture.invalid/v1", repository.privateGatewayUrl.first())
        assertEquals("local-model", repository.privateGatewayModel.first())
        assertFalse(repository.isAutoSaveRecapsEnabled.first())
        assertEquals(auths, ai.authCalls)
        assertEquals("synthetic-key", ai.values[firstVault to AiManagedSlot.GEMINI])
        verify(exactly = 0) { ai.storage.read(any(), any()) }
        assertTrue(runCatching { repository.saveAiConfiguration(secondVault, AiConfigurationUpdate(AiTextProvider.GEMINI,"","","",true)) }.isFailure)
        assertTrue(runCatching { repository.saveGeminiModel(secondVault,"gemini-flash-latest") }.isFailure)
        assertEquals(AiTextProvider.PRIVATE_GATEWAY, repository.aiTextProvider.first())
    }

    @Test fun conflictingLegacyValuesAndInvalidReplacementsNeverPublish() = runBlocking {
        val data = store()
        data.edit { it[SettingsRepository.GEMINI_KEY] = "plain-fixture" }
        files["vaachak_credential_v1:GEMINI:\u0000$firstVault"] = "different-fixture"
        assertTrue(runCatching { repository.manageAiCredential(firstVault,AiManagedSlot.GEMINI,AiManagementAction.MIGRATE) }.isFailure)
        assertTrue(ai.values.isEmpty())
        assertEquals("plain-fixture",data.data.first()[SettingsRepository.GEMINI_KEY])
        val calls = ai.authCalls
        for (bad in listOf("", "with space", "\u0000", "x".repeat(4097))) {
            assertTrue(runCatching { repository.manageAiCredential(firstVault,AiManagedSlot.GEMINI,AiManagementAction.REPLACE,bad) }.isFailure)
        }
        assertTrue(runCatching { repository.manageAiCredential(firstVault,AiManagedSlot.GATEWAY,AiManagementAction.REPLACE,"x".repeat(513)) }.isFailure)
        assertTrue(runCatching { repository.manageAiCredential(firstVault,AiManagedSlot.GEMINI,AiManagementAction.REMOVE,"unexpected") }.isFailure)
        assertEquals(calls, ai.authCalls)
        assertTrue(ai.values.isEmpty())
    }

    @Test fun saveChangesPersistsModelAndRoutingTogetherWithoutTouchingSecrets() = runBlocking {
        repository.manageAiCredential(firstVault, AiManagedSlot.GEMINI, AiManagementAction.REPLACE, "synthetic-key")
        val auths = ai.authCalls
        ai.eligible = false
        repository.saveAiConfiguration(firstVault, AiConfigurationUpdate(AiTextProvider.GEMINI,
            "https://fixture.invalid/image", "", "local-chat", false, " models/gemini-b "))
        val data = store().data.first()
        assertEquals("gemini-b", data[SettingsRepository.GEMINI_MODEL])
        assertEquals("https://fixture.invalid/image", data[SettingsRepository.CF_URL])
        assertEquals(false, data[SettingsRepository.AUTO_SAVE_RECAPS_KEY])
        val snapshot = requireNotNull(repository.aiEditorSnapshot(firstVault).first())
        assertEquals("gemini-b", snapshot.geminiModel)
        assertEquals("https://fixture.invalid/image", snapshot.configuration.cloudflareUrl)
        assertEquals("", snapshot.configuration.geminiKey)
        assertEquals("", snapshot.configuration.authToken)
        repository.saveAiConfiguration(firstVault, AiConfigurationUpdate(AiTextProvider.GEMINI,"","","",true))
        assertEquals("gemini-b", repository.geminiModel.first())
        assertEquals(auths, ai.authCalls)
        assertEquals("synthetic-key", ai.values[firstVault to AiManagedSlot.GEMINI])
        verify(exactly = 0) { ai.storage.read(any(), any()) }
    }

    @Test fun invalidModelAbortsAllWritesAndInactiveVaultSnapshotIsUnavailable() = runBlocking {
        repository.saveAiConfiguration(firstVault, AiConfigurationUpdate(AiTextProvider.GEMINI,"old-url","","",true,"gemini-a"))
        val before = store().data.first()
        assertTrue(runCatching { repository.saveAiConfiguration(firstVault,
            AiConfigurationUpdate(AiTextProvider.PRIVATE_GATEWAY,"new-url","","",false,"not-a-model")) }.isFailure)
        assertEquals(before, store().data.first())
        selectedVault.value = secondVault
        assertNull(repository.aiEditorSnapshot(firstVault).first())
        val other = requireNotNull(repository.aiEditorSnapshot(secondVault).first())
        assertEquals("", other.geminiModel)
        assertEquals("", other.configuration.cloudflareUrl)
        assertTrue(runCatching { repository.saveAiConfiguration(firstVault,
            AiConfigurationUpdate(AiTextProvider.GEMINI,"new-url","","",false,"gemini-b")) }.isFailure)
        selectedVault.value = firstVault
        assertEquals("gemini-a", requireNotNull(repository.aiEditorSnapshot(firstVault).first()).geminiModel)
        assertEquals(before, store().data.first())
    }

    @Test fun groupedSavePreservesInactiveMetadataAndCredentials() = runBlocking {
        repository.saveAiConfiguration(firstVault, AiConfigurationUpdate(AiTextProvider.GEMINI,
            "https://fixture.invalid/image", "https://fixture.tailnet.ts.net:8443/v1", "local-chat", true, "gemini-a"))
        repository.manageAiCredential(firstVault, AiManagedSlot.GEMINI, AiManagementAction.REPLACE, "synthetic-text")
        repository.manageAiCredential(firstVault, AiManagedSlot.CLOUDFLARE, AiManagementAction.REPLACE, "synthetic-image")
        val auths = ai.authCalls
        ai.eligible = false
        repository.saveAiConfiguration(firstVault, AiConfigurationUpdate(AiTextProvider.PRIVATE_GATEWAY,
            "discarded-image", "https://second.tailnet.ts.net:8443/v1", "local-chat", false, "gemini-b", preserveInactive = true))
        val internal = requireNotNull(repository.aiEditorSnapshot(firstVault).first())
        assertEquals(AiTextProvider.PRIVATE_GATEWAY, internal.configuration.textProvider)
        assertEquals("https://fixture.invalid/image", internal.configuration.cloudflareUrl)
        assertEquals("gemini-a", internal.geminiModel)
        assertTrue(internal.configuration.autoSaveRecaps)
        assertEquals("https://second.tailnet.ts.net:8443/v1", internal.configuration.gatewayUrl)
        repository.saveAiConfiguration(firstVault, AiConfigurationUpdate(AiTextProvider.GEMINI,
            "https://updated.invalid/image", "discarded-gateway", "discarded-model", false, "gemini-b", preserveInactive = true))
        val external = requireNotNull(repository.aiEditorSnapshot(firstVault).first())
        assertEquals(AiTextProvider.GEMINI, external.configuration.textProvider)
        assertEquals("gemini-b", external.geminiModel)
        assertEquals("https://updated.invalid/image", external.configuration.cloudflareUrl)
        assertFalse(external.configuration.autoSaveRecaps)
        assertEquals("https://second.tailnet.ts.net:8443/v1", external.configuration.gatewayUrl)
        assertEquals("local-chat", external.configuration.gatewayModel)
        assertEquals(auths, ai.authCalls)
        assertEquals("synthetic-text", ai.values[firstVault to AiManagedSlot.GEMINI])
        assertEquals("synthetic-image", ai.values[firstVault to AiManagedSlot.CLOUDFLARE])
        verify(exactly = 0) { ai.storage.read(any(), any()) }
    }

}
