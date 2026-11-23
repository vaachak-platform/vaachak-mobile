package org.vaachak.reader.leisure.ui.settings

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.vaachak.reader.core.common.AppCoroutineConfig
import org.vaachak.reader.core.data.local.ProfileDao
import org.vaachak.reader.core.data.repository.OpdsRepository
import org.vaachak.reader.core.data.repository.SettingsRepository
import org.vaachak.reader.core.data.repository.SyncRepository
import org.vaachak.reader.core.data.repository.VaultRepository
import org.vaachak.reader.core.domain.model.AiTextProvider
import org.vaachak.reader.core.domain.model.BookshelfPreferences
import org.vaachak.reader.core.domain.model.ComicPreferences
import org.vaachak.reader.core.domain.model.ReaderPreferences
import org.vaachak.reader.core.domain.model.ThemeMode
import org.vaachak.reader.core.domain.model.TtsSettings
import org.vaachak.reader.leisure.testutil.MainDispatcherRule

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AiSettingsCompatibilityTest {
    @get:Rule val dispatcherRule = MainDispatcherRule()

    private lateinit var settings: SettingsRepository
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        AppCoroutineConfig.mainOverride = dispatcherRule.dispatcher
        AppCoroutineConfig.ioOverride = dispatcherRule.dispatcher
        AppCoroutineConfig.defaultOverride = dispatcherRule.dispatcher
        AppCoroutineConfig.sharingStartedOverride = kotlinx.coroutines.flow.SharingStarted.Eagerly

        settings = mockk(relaxed = true)
        val vault = mockk<VaultRepository>(relaxed = true)
        val dao = mockk<ProfileDao>(relaxed = true)
        val opds = mockk<OpdsRepository>(relaxed = true)

        every { vault.hasCompletedOnboarding } returns MutableStateFlow(true)
        every { vault.isOfflineMode } returns MutableStateFlow(false)
        every { vault.activeVaultId } returns MutableStateFlow("test-vault")
        every { vault.isMultiUserMode } returns MutableStateFlow(false)
        every { dao.getAllProfiles() } returns flowOf(emptyList())

        every { settings.syncUsername } returns flowOf("")
        every { settings.deviceName } returns flowOf("test device")
        every { settings.lastSyncTimestamp } returns flowOf(0L)
        every { settings.isOfflineModeEnabled } returns flowOf(false)
        every { settings.geminiKey } returns flowOf("")
        every { settings.cfUrl } returns flowOf("")
        every { settings.cfToken } returns flowOf("")
        every { settings.isAutoSaveRecapsEnabled } returns flowOf(false)
        every { settings.aiTextProvider } returns flowOf(AiTextProvider.PRIVATE_GATEWAY)
        every { settings.privateGatewayUrl } returns flowOf("https://reader.tailnet.ts.net:8443/v1")
        every { settings.privateGatewayModel } returns flowOf("local-chat")
        every { settings.themeMode } returns flowOf(ThemeMode.LIGHT)
        every { settings.einkContrast } returns flowOf(1f)
        every { settings.bookshelfPreferences } returns flowOf(BookshelfPreferences())
        every { settings.readerPreferences } returns flowOf(ReaderPreferences())
        every { settings.comicPreferences } returns flowOf(ComicPreferences())
        every { settings.ttsSettings } returns flowOf(mockk<TtsSettings>(relaxed = true))
        every { opds.catalogs } returns flowOf(emptyList())
        every { settings.isSyncEnabled } returns flowOf(false)
        every { settings.syncCloudUrl } returns flowOf("")
        every { settings.localServerUrl } returns flowOf("")
        every { settings.useLocalServer } returns flowOf(false)
        every { settings.getUseEmbeddedDictionary() } returns flowOf(false)
        every { settings.getDictionaryFolder() } returns flowOf("")

        viewModel = SettingsViewModel(
            settingsRepo = settings,
            syncRepo = mockk<SyncRepository>(relaxed = true),
            opdsRepo = opds,
            vaultRepository = vault,
            profileDao = dao,
            context = mockk<Context>(relaxed = true)
        )
    }

    @After
    fun tearDown() = AppCoroutineConfig.reset()

    @Test
    fun `extended save updates provider and gateway and invokes callback after save`() = runTest(dispatcherRule.dispatcher) {
        coEvery { settings.loadPrivateGatewayToken() } returns "previous-secret"
        var saves = 0
        viewModel.saveAiConfig(
            AiConfigSaveRequest(
                geminiKey = "gemini-key", cloudflareUrl = "https://worker.example",
                cloudflareToken = "cf-token", autoSaveRecaps = true,
                gateway = AiGatewayUpdate(
                    provider = AiTextProvider.PRIVATE_GATEWAY, url = "https://private.example",
                    model = "model-id", token = "new-secret"
                )
            ),
            onSaved = { saves++ }
        )
        advanceUntilIdle()

        coVerify(exactly = 1) { settings.setAiTextProvider(AiTextProvider.PRIVATE_GATEWAY) }
        coVerify(exactly = 1) {
            settings.savePrivateGatewaySettings("https://private.example", "model-id", "new-secret")
        }
        assertEquals(1, saves)
    }

    @Test
    fun `blank token keeps prior gateway token`() = runTest(dispatcherRule.dispatcher) {
        coEvery { settings.loadPrivateGatewayToken() } returns "retained-secret"
        viewModel.saveAiConfig(
            AiConfigSaveRequest(
                geminiKey = "", cloudflareUrl = "", cloudflareToken = "", autoSaveRecaps = false,
                gateway = AiGatewayUpdate(
                    provider = AiTextProvider.PRIVATE_GATEWAY, url = "https://private.example",
                    model = "model", token = ""
                )
            )
        )
        advanceUntilIdle()

        coVerify(exactly = 1) {
            settings.savePrivateGatewaySettings("https://private.example", "model", "retained-secret")
        }
    }

    @Test
    fun `clear token does not affect unrelated settings`() = runTest(dispatcherRule.dispatcher) {
        viewModel.clearPrivateGatewayToken()
        advanceUntilIdle()
        coVerify(exactly = 1) { settings.savePrivateGatewayToken("") }
    }

    @Test
    fun `selected gateway configuration is visible in Settings UI state`() = runTest(dispatcherRule.dispatcher) {
        advanceUntilIdle()
        val config = viewModel.uiState.value.aiConfig
        assertEquals(AiTextProvider.PRIVATE_GATEWAY, config.textProvider)
        assertTrue(config.isEnabled)
        assertEquals("https://reader.tailnet.ts.net:8443/v1", config.gatewayUrl)
        assertEquals("local-chat", config.gatewayModel)
    }

    @Test
    fun `failed gateway save does not invoke callback or expose credentials`() = runTest(dispatcherRule.dispatcher) {
        coEvery { settings.savePrivateGatewaySettings(any(), any(), any()) } throws
            IllegalStateException("secret-do-not-show")
        var callbackInvoked = false
        viewModel.saveAiConfig(
            AiConfigSaveRequest(
                geminiKey = "gemini-key", cloudflareUrl = "https://worker.example",
                cloudflareToken = "cf-token", autoSaveRecaps = true,
                gateway = AiGatewayUpdate(
                    provider = AiTextProvider.PRIVATE_GATEWAY, url = "https://reader.tailnet.ts.net:8443/v1",
                    model = "local-chat", token = "secret-do-not-show"
                )
            ),
            onSaved = { callbackInvoked = true }
        )
        advanceUntilIdle()
        assertFalse(callbackInvoked)
        assertTrue(viewModel.uiState.value.errorMessage.orEmpty().contains("Could not save"))
        assertFalse(viewModel.uiState.value.errorMessage.orEmpty().contains("secret-do-not-show"))
    }

    @Test
    fun `legacy save does not change gateway provider`() = runTest(dispatcherRule.dispatcher) {
        viewModel.saveAiConfig("gemini-key", "", "", false)
        advanceUntilIdle()
        coVerify(exactly = 0) { settings.setAiTextProvider(any()) }
        coVerify(exactly = 0) { settings.savePrivateGatewaySettings(any(), any(), any()) }
    }
}
