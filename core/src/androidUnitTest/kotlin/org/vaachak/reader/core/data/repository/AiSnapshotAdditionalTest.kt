package org.vaachak.reader.core.data.repository

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.vaachak.reader.core.data.di.AiModule
import org.vaachak.reader.core.domain.model.AiTextProvider
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class AiSnapshotAdditionalTest {
    @Test fun persistedIdentityReflectsActualDataStoreRatherThanStartupPlaceholder() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = File(context.cacheDir, "fixture-${UUID.randomUUID()}.preferences_pb")
        val store = PreferenceDataStoreFactory.create(scope = scope) { file }
        try {
            val vault = VaultRepository(store)
            assertEquals(VaultRepository.DEFAULT_VAULT_ID, vault.persistedVaultIdentity.first())
            vault.setActiveVaultId("persisted-fixture")
            assertEquals("persisted-fixture", vault.persistedVaultIdentity.first())
        } finally {
            scope.cancel()
        }
    }

    @Test fun snapshotInvalidatesWhenEachRoutingFieldChangesAndKeepsProviderCredentialsIndependent() = runBlocking {
        val id = MutableStateFlow("fixture-${UUID.randomUUID()}")
        val vault = mockk<VaultRepository>()
        every { vault.activeVaultId } returns id
        every { vault.persistedVaultIdentity } returns id
        val secrets = mockk<PrivateGatewaySecrets>()
        val values = mutableMapOf<String, String>()
        every { secrets.read(any()) } answers { values[firstArg()].orEmpty() }
        every { secrets.write(any(), any()) } answers { values[firstArg()] = secondArg(); Unit }
        every { secrets.clear(any()) } answers { values.remove(firstArg()); Unit }
        configureAiVault(vault, id)
        configureLegacyAiStorage(secrets, values)
        val ai = AiCredentialRuntimeFixture()
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext<Context>(), vault, secrets, ai.runtime)
        val gateway = "https://fixture.tailnet.ts.net:8443/v1"
        settings.setOfflineMode(false)
        settings.saveSettings("gemini-fixture", "https://fixture.invalid/image", "image-fixture", true)
        settings.savePrivateGatewaySettings(gateway, "local-chat", "gateway-fixture")
        val gemini = settings.loadAiRequest(AiRequestKind.TEXT, null)
        assertEquals("gemini-fixture", gemini.credential)
        assertFalse(gemini.invalidated.first())
        settings.setAiTextProvider(AiTextProvider.PRIVATE_GATEWAY)
        assertTrue(gemini.invalidated.first())
        val image = settings.loadAiRequest(AiRequestKind.IMAGE, id.value)
        assertEquals("image-fixture", image.credential)
        assertEquals("https://fixture.invalid/image", image.url)
        val text = settings.loadAiRequest(AiRequestKind.TEXT, id.value)
        assertEquals("gateway-fixture", text.credential)
        settings.savePrivateGatewaySettings(gateway, "changed-model", "gateway-fixture")
        assertTrue(text.invalidated.first())
        assertTrue(image.invalidated.first())
        val modelChanged = settings.loadAiRequest(AiRequestKind.TEXT, id.value)
        settings.savePrivateGatewaySettings("https://other.tailnet.ts.net:8443/v1", "changed-model", "gateway-fixture")
        assertTrue(modelChanged.invalidated.first())
        val endpointChanged = settings.loadAiRequest(AiRequestKind.TEXT, id.value)
        settings.saveSettings("gemini-fixture", "https://fixture.invalid/other", "image-fixture", true)
        assertTrue(endpointChanged.invalidated.first())
        val beforeRemoval = settings.loadAiRequest(AiRequestKind.TEXT, id.value)
        settings.savePrivateGatewayToken("")
        assertTrue(beforeRemoval.invalidated.first())
        assertEquals("", settings.loadAiRequest(AiRequestKind.TEXT, id.value).credential)
        settings.setAiTextProvider(AiTextProvider.GEMINI)
        assertEquals("gemini-fixture", settings.loadAiRequest(AiRequestKind.TEXT, id.value).credential)
        assertEquals("image-fixture", settings.loadAiRequest(AiRequestKind.IMAGE, id.value).credential)
    }

    @Test fun alreadyInvalidatedSnapshotNeverInvokesTransport() = runBlocking {
        val config = AiRequestConfig(AiTextProvider.PRIVATE_GATEWAY, "", "", "", flowOf(true))
        var invoked = false
        try {
            config.runGuarded { invoked = true; "unexpected" }
            fail("Invalidated configuration returned a result")
        } catch (_: CancellationException) {
            assertFalse(invoked)
        }
    }

    @Test fun cloudflareFactoryBuildsApiWithoutChangingSharedClientRedirectPolicy() {
        val client = AiModule.provideOkHttpClient()
        val before = client.followRedirects
        assertNotNull(AiModule.provideCloudflareAiApi(client))
        assertEquals(before, client.followRedirects)
    }
}
