package org.vaachak.reader.leisure.ui.settings

import android.content.Context
import app.cash.turbine.test
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.vaachak.reader.core.data.repository.OpdsRepository
import org.vaachak.reader.core.data.repository.SettingsRepository
import org.vaachak.reader.core.data.repository.SyncRepository
import org.vaachak.reader.core.data.repository.VaultRepository
import org.vaachak.reader.core.domain.model.BookshelfPreferences
import org.vaachak.reader.core.domain.model.ThemeMode
import org.vaachak.reader.core.domain.model.TtsSettings

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private lateinit var settingsRepo: SettingsRepository
    private lateinit var syncRepo: SyncRepository
    private lateinit var opdsRepo: OpdsRepository
    private lateinit var vaultRepository: VaultRepository
    private lateinit var mockContext: Context

    private lateinit var viewModel: SettingsViewModel
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        settingsRepo = mockk(relaxed = true)
        syncRepo = mockk(relaxed = true)
        opdsRepo = mockk(relaxed = true)
        vaultRepository = mockk(relaxed = true)
        mockContext = mockk(relaxed = true)

        // Mock Default Flows
        every { vaultRepository.activeVaultId } returns flowOf("profile_1")
        every { settingsRepo.syncUsername } returns flowOf("Piyush")
        every { settingsRepo.deviceName } returns flowOf("Leaf 3C")
        every { settingsRepo.lastSyncTimestamp } returns flowOf(100L)
        every { settingsRepo.isOfflineModeEnabled } returns flowOf(false)
        every { settingsRepo.geminiKey } returns flowOf("key123")
        every { settingsRepo.cfUrl } returns flowOf("https://cloudflare.com")
        every { settingsRepo.cfToken } returns flowOf("token")
        every { settingsRepo.isAutoSaveRecapsEnabled } returns flowOf(true)
        every { settingsRepo.themeMode } returns flowOf(ThemeMode.LIGHT)
        every { settingsRepo.einkContrast } returns flowOf(1.0f)
        every { settingsRepo.bookshelfPreferences } returns flowOf(BookshelfPreferences())
        every { settingsRepo.ttsSettings } returns flowOf(TtsSettings())
        every { opdsRepo.catalogs } returns flowOf(emptyList())

        viewModel = SettingsViewModel(settingsRepo, syncRepo, opdsRepo, vaultRepository, mockContext)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `switchVault cleans input and updates VaultRepository`() = runTest {
        // When user switches vault with messy input
        viewModel.switchVault("  New-Vault_!  ")
        advanceUntilIdle()

        // Then it strips invalid characters, lowers case, and saves
        coVerify { vaultRepository.setActiveVaultId("new_vault__") }
    }

    @Test
    fun `toggleEinkMode updates ThemeMode in SettingsRepository`() = runTest {
        viewModel.toggleEinkMode(true)
        advanceUntilIdle()
        coVerify { settingsRepo.setThemeMode(ThemeMode.E_INK) }

        viewModel.toggleEinkMode(false)
        advanceUntilIdle()
        coVerify { settingsRepo.setThemeMode(ThemeMode.LIGHT) }
    }

    @Test
    fun `saveAiConfig successfully saves all parameters`() = runTest {
        viewModel.saveAiConfig("new_key", "new_url", "new_token", false)
        advanceUntilIdle()

        coVerify { settingsRepo.saveSettings("new_key", "new_url", "new_token", any()) }
        coVerify { settingsRepo.setAutoSaveRecaps(false) }

        // Verifies the UI state resets properly after save
        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(SettingsSection.NONE, state.activeEditSection)
            assertNull(state.errorMessage)
        }
    }

    @Test
    fun `logout clears sync profile and resets vault to default`() = runTest {
        viewModel.logout()
        advanceUntilIdle()

        coVerify { settingsRepo.updateSyncProfile("", "", "") }
        coVerify { settingsRepo.setLastSyncTimestamp(0L) }
        coVerify { vaultRepository.setActiveVaultId(VaultRepository.DEFAULT_VAULT_ID) }
    }
}