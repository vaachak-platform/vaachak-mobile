package org.vaachak.reader.leisure.ui.bookshelf

import androidx.lifecycle.viewModelScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.vaachak.reader.core.common.AppCoroutineConfig
import org.vaachak.reader.core.data.local.BookDao
import org.vaachak.reader.core.data.local.HighlightDao
import org.vaachak.reader.core.data.repository.AiRepository
import org.vaachak.reader.core.data.repository.AiVaultScope
import org.vaachak.reader.core.data.repository.LibraryRepository
import org.vaachak.reader.core.data.repository.SettingsRepository
import org.vaachak.reader.core.data.repository.SyncRepository
import org.vaachak.reader.core.data.repository.VaultRepository
import org.vaachak.reader.core.domain.model.BookEntity
import org.vaachak.reader.core.domain.model.BookshelfPreferences
import org.vaachak.reader.core.domain.model.HighlightEntity
import org.vaachak.reader.leisure.testutil.MainDispatcherRule

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BookshelfAiLifecycleTest {
    @get:Rule val main = MainDispatcherRule()
    private val vaultId = MutableStateFlow("shelf-a")
    private val offline = MutableStateFlow(false)
    private val ai = mockk<AiRepository>()
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private val vault = mockk<VaultRepository>()
    private val highlights = mockk<HighlightDao>(relaxed = true)
    private val books = mockk<BookDao>(relaxed = true)
    private val sync = mockk<SyncRepository>(relaxed = true)
    private val book = BookEntity("a", "shelf-a", "Synthetic title", "Author", localUri = "content://fixture/a", progress = 0.5)
    private val book2 = book.copy(bookHash = "b", title = "Second title", localUri = "content://fixture/b")
    private val note = HighlightEntity(bookHashId = "a", profileId = "shelf-a", locatorJson = "{}", text = "synthetic highlight", color = 0)
    private lateinit var vm: BookshelfViewModel

    @Before fun setup() {
        AppCoroutineConfig.mainOverride = main.dispatcher
        AppCoroutineConfig.ioOverride = main.dispatcher
        AppCoroutineConfig.defaultOverride = main.dispatcher
        AppCoroutineConfig.sharingStartedOverride = kotlinx.coroutines.flow.SharingStarted.Eagerly
        AppCoroutineConfig.bookshelfStartupSyncDelayMsOverride = 0L
        every { vault.activeVaultId } returns vaultId
        every { settings.isOfflineModeEnabled } returns offline
        every { settings.isEinkEnabled } returns flowOf(false)
        every { settings.lastSyncTimestamp } returns flowOf(0L)
        every { settings.syncUsername } returns flowOf("")
        every { settings.bookshelfPreferences } returns flowOf(BookshelfPreferences())
        every { books.getAllBooksSortedByRecent(any()) } returns flowOf(listOf(book))
        every { highlights.getBooksWithBookmarks(any()) } returns flowOf(emptyList())
        every { highlights.getHighlightsForBook(any(), any()) } returns flowOf(listOf(note))
        coEvery { sync.sync() } returns Result.success(Unit)
        vm = BookshelfViewModel(books, mockk<LibraryRepository>(relaxed = true), highlights, ai, settings, sync, vault)
    }
    @After fun cleanup() { vm.viewModelScope.cancel(); AppCoroutineConfig.reset() }

    @Test fun quickAndGlobalRecallBindVaultAndPublishOnlyExpectedResults() = runTest(main.dispatcher) {
        advanceUntilIdle()
        coEvery { ai.getRecallSummary(any(), any()) } coAnswers {
            assertEquals("shelf-a", currentCoroutineContext()[AiVaultScope]?.vaultId)
            assertTrue(secondArg<String>().contains("synthetic highlight"))
            "summary for ${firstArg<String>()}"
        }
        vm.getQuickRecap(book); advanceUntilIdle()
        assertEquals("summary for Synthetic title", vm.uiState.value.recapState["a"])
        assertNull(vm.uiState.value.loadingRecapUri)
        vm.getGlobalRecap(); advanceUntilIdle()
        assertEquals("summary for My Active Reading List", vm.uiState.value.recapState["GLOBAL"])
        assertNull(vm.uiState.value.loadingRecapUri)
        vm.clearRecap("a"); advanceUntilIdle()
        assertFalse(vm.uiState.value.recapState.containsKey("a"))
        vaultId.value = "shelf-b"; advanceUntilIdle()
        assertTrue(vm.uiState.value.recapState.isEmpty())
        vm.getQuickRecap(book); advanceUntilIdle()
        coVerify(exactly = 2) { ai.getRecallSummary(any(), any()) }
    }

    @Test fun supersededRecallCannotClearLoadingForReplacement() = runTest(main.dispatcher) {
        advanceUntilIdle()
        var stopped = 0
        coEvery { ai.getRecallSummary(any(), any()) } coAnswers { try { awaitCancellation() } finally { stopped++ } }
        vm.getQuickRecap(book); runCurrent()
        assertEquals(book.localUri, vm.uiState.value.loadingRecapUri)
        vm.getQuickRecap(book2); runCurrent()
        assertEquals(1, stopped)
        assertEquals(book2.localUri, vm.uiState.value.loadingRecapUri)
        vm.getGlobalRecap(); runCurrent()
        assertEquals(2, stopped)
        assertEquals("GLOBAL", vm.uiState.value.loadingRecapUri)
        vm.clearRecap("GLOBAL"); runCurrent()
        assertEquals(3, stopped)
        assertNull(vm.uiState.value.loadingRecapUri)
        assertTrue(vm.uiState.value.recapState.isEmpty())
        vm.getQuickRecap(book); runCurrent()
        vaultId.value = "shelf-b"; runCurrent()
        assertEquals(4, stopped)
        assertNull(vm.uiState.value.loadingRecapUri)
        assertTrue(vm.uiState.value.recapState.isEmpty())
    }

    @Test fun quickAndGlobalFailuresAreRedactedAndRetryWorks() = runTest(main.dispatcher) {
        advanceUntilIdle()
        coEvery { ai.getRecallSummary(any(), any()) } throws IllegalStateException("secret fixture")
        vm.getQuickRecap(book); advanceUntilIdle()
        assertEquals("Failed to generate recall.", vm.uiState.value.snackbarMessage)
        assertNull(vm.uiState.value.loadingRecapUri)
        vm.getGlobalRecap(); advanceUntilIdle()
        assertEquals("Failed to generate global recall.", vm.uiState.value.snackbarMessage)
        assertNull(vm.uiState.value.loadingRecapUri)
        coEvery { ai.getRecallSummary(any(), any()) } throws CancellationException("secret fixture")
        vm.getQuickRecap(book); advanceUntilIdle()
        assertNull(vm.uiState.value.loadingRecapUri)
        vm.getGlobalRecap(); advanceUntilIdle()
        assertNull(vm.uiState.value.loadingRecapUri)
        coEvery { ai.getRecallSummary(any(), any()) } returns "retried"
        vm.getQuickRecap(book); advanceUntilIdle()
        assertEquals("retried", vm.uiState.value.recapState["a"])
    }

    @Test fun offlineAndEmptyGlobalRecallNeverContactProvider() = runTest(main.dispatcher) {
        advanceUntilIdle()
        offline.value = true; advanceUntilIdle()
        vm.getQuickRecap(book); vm.getGlobalRecap(); advanceUntilIdle()
        assertEquals("Offline Mode enabled. Connect to use Recall.", vm.uiState.value.snackbarMessage)
        offline.value = false; advanceUntilIdle()
        every { books.getAllBooksSortedByRecent(any()) } returns flowOf(emptyList())
        vm.getGlobalRecap(); advanceUntilIdle()
        assertEquals("No books with > 5% progress found to recap.", vm.uiState.value.snackbarMessage)
        every { books.getAllBooksSortedByRecent(any()) } returns flowOf(listOf(book))
        every { highlights.getHighlightsForBook(any(), any()) } returns flowOf(emptyList())
        vm.getGlobalRecap(); advanceUntilIdle()
        assertEquals("No highlights found in your active books.", vm.uiState.value.snackbarMessage)
        assertNull(vm.uiState.value.loadingRecapUri)
        coVerify(exactly = 0) { ai.getRecallSummary(any(), any()) }
    }
}
