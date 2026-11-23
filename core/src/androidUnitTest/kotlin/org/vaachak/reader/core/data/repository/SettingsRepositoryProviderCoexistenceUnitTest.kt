package org.vaachak.reader.core.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.vaachak.reader.core.domain.model.AiTextProvider

/** P0-01, P0-02, P0-05: provider selection does not delete other providers' keys. */
@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryProviderCoexistenceUnitTest {
    private val firstVault = "provider_coexist_${UUID.randomUUID()}"
    private val secondVault = "provider_coexist_${UUID.randomUUID()}"
    private val activeVault = MutableStateFlow(firstVault)
    private val tokens = mutableMapOf<String, String>()
    private val vault = mockk<VaultRepository>()
    private val secrets = mockk<PrivateGatewaySecrets>()
    private val settings: SettingsRepository

    init {
        every { vault.activeVaultId } returns activeVault
        every { secrets.write(any(), any()) } answers { tokens[firstArg()] = secondArg(); Unit }
        every { secrets.read(any()) } answers { tokens[firstArg()].orEmpty() }
        every { secrets.clear(any()) } answers { tokens.remove(firstArg()); Unit }
        settings = SettingsRepository(ApplicationProvider.getApplicationContext<Context>(), vault, secrets)
    }

    @Test fun switchingTextProviderNeverDeletesGeminiCloudflareOrGatewayCredentials() = runBlocking {
        settings.saveSettings(
            "direct-gemini-one", "https://cloudflare.example/image", "direct-cloudflare-one", false, true
        )
        settings.savePrivateGatewaySettings("https://reader.example.ts.net:8443/v1", "local-chat", "gateway-one")
        settings.setAiTextProvider(AiTextProvider.PRIVATE_GATEWAY)
        assertEquals(AiTextProvider.PRIVATE_GATEWAY, settings.aiTextProvider.first())
        assertEquals("direct-gemini-one", settings.geminiKey.first())
        assertEquals("direct-cloudflare-one", settings.cfToken.first())
        assertEquals("gateway-one", settings.loadPrivateGatewayToken())

        settings.setAiTextProvider(AiTextProvider.GEMINI)
        assertEquals(AiTextProvider.GEMINI, settings.aiTextProvider.first())
        assertEquals("direct-gemini-one", settings.geminiKey.first())
        assertEquals("direct-cloudflare-one", settings.cfToken.first())
        assertEquals("gateway-one", settings.loadPrivateGatewayToken())
        settings.setAiTextProvider(AiTextProvider.PRIVATE_GATEWAY)
        assertEquals("gateway-one", settings.loadPrivateGatewayToken())
        verify(exactly = 0) { secrets.clear(any()) }
    }

    @Test fun switchingVaultIsolatesAllThreeProvidersAndOfflinePreference() = runBlocking {
        settings.saveSettings("gemini-first", "https://first.example/image", "cf-first", false)
        settings.savePrivateGatewaySettings("https://reader.example.ts.net:8443/v1", "local-chat", "gateway-first")
        settings.setAiTextProvider(AiTextProvider.PRIVATE_GATEWAY)
        settings.setOfflineMode(false)

        activeVault.value = secondVault
        assertEquals(AiTextProvider.GEMINI, settings.aiTextProvider.first())
        assertEquals("", settings.geminiKey.first())
        assertEquals("", settings.cfUrl.first())
        assertEquals("", settings.cfToken.first())
        assertEquals("", settings.privateGatewayUrl.first())
        assertEquals("", settings.loadPrivateGatewayToken())
        assertTrue(settings.isOfflineModeEnabled.first())

        settings.saveSettings("gemini-second", "https://second.example/image", "cf-second", false)
        settings.savePrivateGatewaySettings("https://second.example.ts.net:8443/v1", "local-chat", "gateway-second")
        settings.setOfflineMode(true)

        activeVault.value = firstVault
        assertEquals(AiTextProvider.PRIVATE_GATEWAY, settings.aiTextProvider.first())
        assertEquals("gemini-first", settings.geminiKey.first())
        assertEquals("cf-first", settings.cfToken.first())
        assertEquals("gateway-first", settings.loadPrivateGatewayToken())
        assertFalse(settings.isOfflineModeEnabled.first())

        activeVault.value = secondVault
        assertEquals("gemini-second", settings.geminiKey.first())
        assertEquals("cf-second", settings.cfToken.first())
        assertEquals("gateway-second", settings.loadPrivateGatewayToken())
    }
}
