package org.vaachak.reader.core.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID
import org.vaachak.reader.core.domain.model.ComicPreferences
import org.vaachak.reader.core.domain.model.ComicReadingDirection
import org.vaachak.reader.core.domain.model.ComicReadingMode
import org.vaachak.reader.core.domain.model.ComicPageFit
import org.vaachak.reader.core.domain.model.AiTextProvider

@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryPrivateGatewayTest {
    private val vaultId = MutableStateFlow("gateway_${UUID.randomUUID()}")
    private val savedTokens = mutableMapOf<String, String>()
    private val secrets = mockk<PrivateGatewaySecrets>()
    private val vault = mockk<VaultRepository>()
    private val settings: SettingsRepository

    private val ai = AiCredentialRuntimeFixture()

    init {
        every { vault.activeVaultId } returns vaultId
        every { secrets.write(any(), any()) } answers { savedTokens[firstArg()] = secondArg(); Unit }
        every { secrets.read(any()) } answers { savedTokens[firstArg()].orEmpty() }
        every { secrets.clear(any()) } answers { savedTokens.remove(firstArg()); Unit }
        configureAiVault(vault, vaultId)
        configureLegacyAiStorage(secrets, savedTokens)
        settings = SettingsRepository(ApplicationProvider.getApplicationContext<Context>(), vault, secrets, ai.runtime)
    }

    @Test fun encryptedTokenIsUsedForReadWriteAndClear() = runBlocking {
        settings.savePrivateGatewayToken("my-secret")
        assertEquals("my-secret", settings.loadPrivateGatewayToken())
        assertEquals("my-secret", ai.values[vaultId.value to AiManagedSlot.GATEWAY])
        settings.savePrivateGatewayToken("")
        assertEquals("", settings.loadPrivateGatewayToken())
        verify(exactly = 2) { secrets.retireAiCredential(vaultId.value) }
    }

    @Test fun nonSecretGatewayFieldsPersistInPreferences() = runBlocking {
        settings.savePrivateGatewaySettings(" https://reader.tailnet.ts.net:8443/v1 ", " local-chat ", "key123")
        assertEquals("https://reader.tailnet.ts.net:8443/v1", settings.privateGatewayUrl.first())
        assertEquals("local-chat", settings.privateGatewayModel.first())
        assertEquals("key123", settings.loadPrivateGatewayToken())
    }

    @Test fun credentialsAreScopedToActiveVault() = runBlocking {
        val firstVault = vaultId.value
        settings.savePrivateGatewayToken("first-key")
        vaultId.value = "gateway_${UUID.randomUUID()}"
        assertEquals("", settings.loadPrivateGatewayToken())
        settings.savePrivateGatewayToken("second-key")
        vaultId.value = firstVault
        assertEquals("first-key", settings.loadPrivateGatewayToken())
    }

    @Test fun comicPreferencesUsePerVaultDefaultsAndPersistAllFields() = runBlocking {
        assertEquals(ComicPreferences(), settings.comicPreferences.first())
        val preferences = ComicPreferences(
            readingDirection = ComicReadingDirection.RIGHT_TO_LEFT,
            readingMode = ComicReadingMode.VERTICAL_SCROLL,
            pageFit = ComicPageFit.FIT_WIDTH,
            tapToTurn = false,
            useVolumeKeys = true,
            preloadNextPage = false,
            reduceMotion = false
        )
        settings.saveComicPreferences(preferences)
        assertEquals(preferences, settings.comicPreferences.first())
        vaultId.value = "gateway_${UUID.randomUUID()}"
        assertEquals(ComicPreferences(), settings.comicPreferences.first())
    }

    @Test fun providerSelectionIsSavedAndDefaultsToGeminiInAnotherVault() = runBlocking {
        assertEquals(AiTextProvider.GEMINI, settings.aiTextProvider.first())
        settings.setAiTextProvider(AiTextProvider.PRIVATE_GATEWAY)
        assertEquals(AiTextProvider.PRIVATE_GATEWAY, settings.aiTextProvider.first())
        vaultId.value = "gateway_${UUID.randomUUID()}"
        assertEquals(AiTextProvider.GEMINI, settings.aiTextProvider.first())
    }
}
