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

/**
 * Settings ViewModel regression tests against the current Vaachak repository API.
 *
 * Repository gateway settings are exposed as separate provider, URL, model
 * and token APIs rather than a combined legacy property. Tests verify via
 * the public ViewModel actions and repository methods.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val testDispatcher get() = mainDispatcherRule.dispatcher

    private lateinit var settingsRepo: SettingsRepository
    private lateinit var syncRepo: SyncRepository
    private lateinit var opdsRepo: OpdsRepository
    private lateinit var vaultRepository: VaultRepository
    private lateinit var profileDao: ProfileDao
    private lateinit var context: Context
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setup() {
        AppCoroutineConfig.mainOverride = testDispatcher
        AppCoroutineConfig.ioOverride = testDispatcher
        AppCoroutineConfig.defaultOverride = testDispatcher
        AppCoroutineConfig.sharingStartedOverride = kotlinx.coroutines.flow.SharingStarted.Eagerly

        settingsRepo = mockk(relaxed = true)
        syncRepo = mockk(relaxed = true)
        opdsRepo = mockk(relaxed = true)
        vaultRepository = mockk(relaxed = true)
        profileDao = mockk(relaxed = true)
        context = mockk(relaxed = true)

        every { vaultRepository.hasCompletedOnboarding } returns MutableStateFlow(true)
        every { vaultRepository.isOfflineMode } returns MutableStateFlow(false)
        every { vaultRepository.activeVaultId } returns MutableStateFlow("profile_1")
        every { vaultRepository.isMultiUserMode } returns MutableStateFlow(true)

        every { profileDao.getAllProfiles() } returns flowOf(emptyList())

        every { settingsRepo.syncUsername } returns flowOf("Test user")
        every { settingsRepo.deviceName } returns flowOf("E-reader")
        every { settingsRepo.lastSyncTimestamp } returns flowOf(0L)
        every { settingsRepo.isOfflineModeEnabled } returns flowOf(false)

        every { settingsRepo.isAiEnabled } returns flowOf(true)
        every { settingsRepo.cfUrl } returns flowOf("")
        every { settingsRepo.isAutoSaveRecapsEnabled } returns flowOf(false)
        every { settingsRepo.aiTextProvider } returns flowOf(AiTextProvider.PRIVATE_GATEWAY)
        every { settingsRepo.privateGatewayUrl } returns flowOf("https://reader.tailnet.ts.net:8443/v1")
        every { settingsRepo.privateGatewayModel } returns flowOf("local-chat")

        every { settingsRepo.themeMode } returns flowOf(ThemeMode.LIGHT)
        every { settingsRepo.einkContrast } returns flowOf(1f)
        every { settingsRepo.bookshelfPreferences } returns flowOf(BookshelfPreferences())
        every { settingsRepo.readerPreferences } returns flowOf(ReaderPreferences())
        every { settingsRepo.comicPreferences } returns flowOf(ComicPreferences())

        every { settingsRepo.ttsSettings } returns flowOf(mockk<TtsSettings>(relaxed = true))
        every { opdsRepo.catalogs } returns flowOf(emptyList())

        every { settingsRepo.isSyncEnabled } returns flowOf(false)
        every { settingsRepo.syncCloudUrl } returns flowOf("")
        every { settingsRepo.localServerUrl } returns flowOf("")
        every { settingsRepo.useLocalServer } returns flowOf(false)

        every { settingsRepo.getUseEmbeddedDictionary() } returns flowOf(false)
        every { settingsRepo.getDictionaryFolder() } returns flowOf("")

        viewModel = SettingsViewModel(
            settingsRepo = settingsRepo,
            syncRepo = syncRepo,
            opdsRepo = opdsRepo,
            vaultRepository = vaultRepository,
            profileDao = profileDao,
            context = context
        )
    }

    @After
    fun tearDown() {
        AppCoroutineConfig.reset()
    }

    @Test
    fun `logout clears sync profile and resets vault to default`() = runTest(testDispatcher) {
        viewModel.logout()
        advanceUntilIdle()

        coVerify(exactly = 1) { settingsRepo.updateSyncProfile("", "", "") }
        coVerify(exactly = 1) { settingsRepo.setLastSyncTimestamp(0L) }
        coVerify(exactly = 1) {
            vaultRepository.setActiveVaultId(VaultRepository.DEFAULT_VAULT_ID)
        }
    }

    @Test
    fun `comic preferences are saved through the repository`() = runTest(testDispatcher) {
        val preferences = ComicPreferences()

        viewModel.saveComicPreferences(preferences)
        advanceUntilIdle()

        coVerify(exactly = 1) { settingsRepo.saveComicPreferences(preferences) }
    }

    @Test
    fun `legacy AI settings save remains supported`() = runTest(testDispatcher) {
        viewModel.saveAiConfig("gemini-test-key", "https://worker.example", "cf-token", true)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            settingsRepo.saveSettings("gemini-test-key", "https://worker.example", "cf-token", false, null)
        }
        coVerify(exactly = 1) { settingsRepo.setAutoSaveRecaps(true) }
    }

    @Test
    fun `private gateway settings save preserves the selected provider`() = runTest(testDispatcher) {
        var callbackCount = 0
        viewModel.saveAiConfig(
            AiConfigSaveRequest(
                geminiKey = "", cloudflareUrl = "", cloudflareToken = "", autoSaveRecaps = false,
                gateway = AiGatewayUpdate(
                    provider = AiTextProvider.PRIVATE_GATEWAY, url = "https://private.example",
                    model = "gateway-model", token = "new-token"
                )
            ),
            onSaved = { callbackCount++ }
        )
        advanceUntilIdle()

        coVerify(exactly = 1) { settingsRepo.setAiTextProvider(AiTextProvider.PRIVATE_GATEWAY) }
        coVerify(exactly = 1) {
            settingsRepo.savePrivateGatewaySettings("https://private.example", "gateway-model", "new-token")
        }
        assertEquals(1, callbackCount)
    }

    @Test
    fun `empty gateway token keeps previously stored token`() = runTest(testDispatcher) {


        viewModel.saveAiConfig(
            AiConfigSaveRequest(
                geminiKey = "", cloudflareUrl = "", cloudflareToken = "", autoSaveRecaps = false,
                gateway = AiGatewayUpdate(
                    provider = AiTextProvider.PRIVATE_GATEWAY, url = "https://private.example",
                    model = "gateway-model", token = ""
                )
            )
        )
        advanceUntilIdle()

        coVerify(exactly = 1) {
            settingsRepo.savePrivateGatewaySettings(
                "https://private.example", "gateway-model", ""
            )
        }
    }

    @Test
    fun `clear gateway token leaves other preferences intact`() = runTest(testDispatcher) {
        viewModel.clearPrivateGatewayToken()
        advanceUntilIdle()

        coVerify(exactly = 1) { settingsRepo.savePrivateGatewayToken("") }
        coVerify(exactly = 0) {
            settingsRepo.savePrivateGatewaySettings(any(), any(), any())
        }
    }

    @Test
    fun `save changes forwards model with nonsecret configuration to pinned vault`() = runTest(testDispatcher) {
        coEvery { settingsRepo.saveAiConfiguration("profile_1", any()) } returns Unit
        viewModel.saveConfiguration("profile_1", AiConfigSaveRequest("", "https://fixture.invalid/image", "", false,
            AiGatewayUpdate(AiTextProvider.GEMINI, "", "local-chat", ""), "gemini-b")).awaitAiOperation()
        coVerify(exactly = 1) {
            settingsRepo.saveAiConfiguration("profile_1", match {
                it.preserveInactive && it.geminiModel == "gemini-b" && it.provider == AiTextProvider.GEMINI &&
                    it.cloudflareUrl == "https://fixture.invalid/image" && !it.autoSaveRecaps
            })
        }
        coVerify(exactly = 0) { settingsRepo.saveGeminiModel(any(), any()) }
        coVerify(exactly = 0) { settingsRepo.manageAiCredential(any(), any(), any(), any()) }
    }

}
