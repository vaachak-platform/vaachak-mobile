package org.vaachak.reader.leisure.ui.bookshelf

import app.cash.turbine.test
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.vaachak.reader.core.data.local.BookDao
import org.vaachak.reader.core.data.local.HighlightDao
import org.vaachak.reader.core.data.repository.AiRepository
import org.vaachak.reader.core.data.repository.LibraryRepository
import org.vaachak.reader.core.data.repository.SettingsRepository
import org.vaachak.reader.core.data.repository.SyncRepository
import org.vaachak.reader.core.data.repository.VaultRepository
import org.vaachak.reader.core.domain.model.BookEntity
import org.vaachak.reader.core.domain.model.BookshelfPreferences

@OptIn(ExperimentalCoroutinesApi::class)
class BookshelfViewModelTest {

    private lateinit var bookDao: BookDao
    private lateinit var libraryRepository: LibraryRepository
    private lateinit var highlightDao: HighlightDao
    private lateinit var aiRepository: AiRepository
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var syncRepository: SyncRepository
    private lateinit var vaultRepository: VaultRepository

    private lateinit var viewModel: BookshelfViewModel
    private val testDispatcher = StandardTestDispatcher()

    // Fake StateFlows to control repository emissions
    private val fakeVaultId = MutableStateFlow("profile_piyush")
    private val fakeOfflineMode = MutableStateFlow(false)
    private val fakeSearchQuery = MutableStateFlow("")

    private val dummyBook1 = BookEntity(bookHash = "1", profileId = "profile_piyush", title = "Dune", author = "Frank Herbert", progress = 0.5, language = "en")
    private val dummyBook2 = BookEntity(bookHash = "2", profileId = "profile_piyush", title = "Foundation", author = "Isaac Asimov", progress = 0.1, language = "en")

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        bookDao = mockk(relaxed = true)
        libraryRepository = mockk(relaxed = true)
        highlightDao = mockk(relaxed = true)
        aiRepository = mockk(relaxed = true)
        settingsRepo = mockk(relaxed = true)
        syncRepository = mockk(relaxed = true)
        vaultRepository = mockk(relaxed = true)

        // Mock Settings
        every { settingsRepo.isEinkEnabled } returns flowOf(false)
        every { settingsRepo.isOfflineModeEnabled } returns fakeOfflineMode
        every { settingsRepo.lastSyncTimestamp } returns flowOf(0L)
        every { settingsRepo.syncUsername } returns flowOf("Piyush")
        every { settingsRepo.bookshelfPreferences } returns flowOf(BookshelfPreferences())

        // Mock Data
        every { vaultRepository.activeVaultId } returns fakeVaultId
        every { bookDao.getAllBooksSortedByRecent(any()) } returns flowOf(listOf(dummyBook1, dummyBook2))
        every { highlightDao.getBooksWithBookmarks(any()) } returns flowOf(emptyList())

        viewModel = BookshelfViewModel(
            bookDao, libraryRepository, highlightDao, aiRepository, settingsRepo, syncRepository, vaultRepository
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `uiState emits correctly filtered and grouped books`() = runTest {
        viewModel.uiState.test {
            // Wait for the combine operators to process
            val state = awaitItem()

            // Should have 2 books grouped by Author (Default BookshelfPreferences behavior)
            assertEquals(2, state.groupedLibrary.values.flatten().size)
            assertTrue(state.groupedLibrary.keys.contains("Frank Herbert"))
            assertEquals("Dune", state.groupedLibrary["Frank Herbert"]?.first()?.title)
        }
    }

    @Test
    fun `updateSearchQuery filters the library list correctly`() = runTest {
        viewModel.updateSearchQuery("Dune")
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("Dune", state.searchQuery)
            assertEquals(1, state.groupedLibrary.values.flatten().size)
            assertEquals("Dune", state.groupedLibrary.values.flatten().first().title)
        }
    }

    @Test
    fun `getQuickRecap blocks AI request when Offline Mode is enabled`() = runTest {
        // Given offline mode is ON
        fakeOfflineMode.value = true
        advanceUntilIdle()

        // When requesting an AI recap
        viewModel.getQuickRecap(dummyBook1)
        advanceUntilIdle()

        // Then it shows an error snackbar and NEVER calls the AI repo
        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("Offline Mode enabled. Connect to use Recall.", state.snackbarMessage)
        }
        coVerify(exactly = 0) { aiRepository.getRecallSummary(any(), any()) }
    }

    @Test
    fun `deleteBookByUri securely uses active vault ID`() = runTest {
        viewModel.deleteBookByUri("content://dummy/1")
        advanceUntilIdle()

        // Verifies the DAO is called specifically with the secure vault ID, not a hardcoded string
        coVerify { bookDao.deleteBookByUri("content://dummy/1", "profile_piyush") }
    }
}