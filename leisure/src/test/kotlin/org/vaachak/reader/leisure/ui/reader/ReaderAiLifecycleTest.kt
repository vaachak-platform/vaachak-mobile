package org.vaachak.reader.leisure.ui.reader

import android.app.Application
import android.net.Uri
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
import org.readium.r2.shared.publication.Locator
import org.vaachak.reader.core.common.AppCoroutineConfig
import org.vaachak.reader.core.data.local.BookDao
import org.vaachak.reader.core.data.local.HighlightDao
import org.vaachak.reader.core.data.repository.AiRepository
import org.vaachak.reader.core.data.repository.AiVaultScope
import org.vaachak.reader.core.data.repository.DictionaryRepository
import org.vaachak.reader.core.data.repository.ReadiumManager
import org.vaachak.reader.core.data.repository.SettingsRepository
import org.vaachak.reader.core.data.repository.SyncRepository
import org.vaachak.reader.core.data.repository.VaultRepository
import org.vaachak.reader.core.domain.model.BookEntity
import org.vaachak.reader.core.domain.model.HighlightEntity
import org.vaachak.reader.core.domain.model.ReaderPreferences
import org.vaachak.reader.core.domain.model.TtsSettings
import org.vaachak.reader.leisure.testutil.MainDispatcherRule

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderAiLifecycleTest {
    @get:Rule val main = MainDispatcherRule()
    private val vaultId = MutableStateFlow("reader-a")
    private val offline = MutableStateFlow(false)
    private val ai = mockk<AiRepository>()
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private val vault = mockk<VaultRepository>()
    private val readium = mockk<ReadiumManager>(relaxed = true)
    private val highlights = mockk<HighlightDao>(relaxed = true)
    private val books = mockk<BookDao>(relaxed = true)
    private val sync = mockk<SyncRepository>(relaxed = true)
    private lateinit var vm: ReaderViewModel
    private val book = BookEntity("hash-a", "reader-a", "Synthetic book", "Author")
    private val note = HighlightEntity(bookHashId = "hash-a", profileId = "reader-a", locatorJson = "{}", text = "synthetic note", color = 0)

    @Before fun setup() {
        AppCoroutineConfig.mainOverride = main.dispatcher
        AppCoroutineConfig.ioOverride = main.dispatcher
        AppCoroutineConfig.defaultOverride = main.dispatcher
        AppCoroutineConfig.sharingStartedOverride = kotlinx.coroutines.flow.SharingStarted.Eagerly
        every { vault.activeVaultId } returns vaultId
        every { settings.isOfflineModeEnabled } returns offline
        every { settings.isEinkEnabled } returns flowOf(false)
        every { settings.readerPreferences } returns flowOf(ReaderPreferences())
        every { settings.ttsSettings } returns flowOf(TtsSettings(1f, true, "underline", "default", false, 0, 0.5f, "default"))
        every { highlights.getHighlightsForBook(any(), any()) } returns flowOf(listOf(note))
        coEvery { books.getBookByUri(any(), any()) } returns book
        coEvery { readium.openEpubFromUri(any()) } returns null
        coEvery { sync.sync() } returns Result.success(Unit)
        vm = ReaderViewModel(mockk<Application>(relaxed = true), ai, readium, highlights, settings,
            mockk<DictionaryRepository>(relaxed = true), sync, books, vault)
    }

    @After fun cleanup() {
        vm.viewModelScope.cancel()
        AppCoroutineConfig.reset()
    }

    private fun open() = vm.onFileSelected(Uri.parse("content://fixture/book.epub"))

    @Test fun actionsUseCapturedSelectionAndVaultAndDoNotMixImageState() = runTest(main.dispatcher) {
        open(); advanceUntilIdle()
        coEvery { ai.explainContext("first") } coAnswers {
            assertEquals("reader-a", currentCoroutineContext()[AiVaultScope]?.vaultId)
            "explanation"
        }
        coEvery { ai.whoIsThis("second", "", "") } returns "identity"
        coEvery { ai.visualizeText("third") } returns "image-data"
        vm.onTextSelected("first"); vm.onActionExplain(); advanceUntilIdle()
        assertEquals("explanation", vm.aiResponse.value)
        assertFalse(vm.isImageResponse.value)
        vm.onTextSelected("second"); vm.onActionWhoIsThis(); advanceUntilIdle()
        assertEquals("identity", vm.aiResponse.value)
        vm.onTextSelected("third"); vm.onActionVisualize(); advanceUntilIdle()
        assertEquals("image-data", vm.aiResponse.value)
        assertTrue(vm.isImageResponse.value)
        vm.dismissBottomSheet()
        assertEquals("", vm.aiResponse.value)
        assertFalse(vm.isImageResponse.value)
    }

    @Test fun supersessionDismissalDictionaryAndCloseCancelUnderlyingWork() = runTest(main.dispatcher) {
        open(); advanceUntilIdle()
        var cancelled = 0
        coEvery { ai.explainContext(any()) } coAnswers {
            try { awaitCancellation() } finally { cancelled++ }
        }
        vm.onTextSelected("first"); vm.onActionExplain(); runCurrent()
        assertEquals("Thinking...", vm.aiResponse.value)
        vm.onActionExplain(); runCurrent()
        assertEquals(1, cancelled)
        vm.onTextSelected("replacement"); runCurrent()
        assertEquals(2, cancelled)
        assertEquals("", vm.aiResponse.value)
        vm.onActionExplain(); runCurrent()
        vm.dismissBottomSheet(); runCurrent()
        assertEquals(3, cancelled)
        vm.onTextSelected("again"); vm.onActionExplain(); runCurrent()
        vm.lookupWord("one two three four five six"); runCurrent()
        assertEquals(4, cancelled)
        assertTrue(vm.isDictionaryLookup.value)
        vm.onActionExplain(); runCurrent()
        vm.closeBook(); advanceUntilIdle()
        assertEquals(5, cancelled)
        assertEquals("", vm.aiResponse.value)
        vm.onActionExplain(); advanceUntilIdle()
        coVerify(exactly = 5) { ai.explainContext(any()) }
    }

    @Test fun vaultChangeClearsBothRequestsAndOfflineOpeningBlocksActions() = runTest(main.dispatcher) {
        open(); advanceUntilIdle()
        var stopped = 0
        coEvery { ai.explainContext(any()) } coAnswers { try { awaitCancellation() } finally { stopped++ } }
        coEvery { ai.generateRecap(any(), any(), any()) } coAnswers { try { awaitCancellation() } finally { stopped++ } }
        vm.onTextSelected("passage"); vm.onActionExplain(); vm.getQuickRecap(); runCurrent()
        assertTrue(vm.isRecapLoading.value)
        vaultId.value = "reader-b"
        // Guard before the vault collector is dispatched.
        vm.onActionExplain(); vm.getQuickRecap(); runCurrent()
        assertEquals(2, stopped)
        assertEquals("", vm.aiResponse.value)
        assertNull(vm.recapText.value)
        assertFalse(vm.isRecapLoading.value)
        offline.value = true
        open(); advanceUntilIdle()
        vm.onActionExplain(); vm.getQuickRecap(); advanceUntilIdle()
        coVerify(exactly = 1) { ai.explainContext(any()) }
        coVerify(exactly = 1) { ai.generateRecap(any(), any(), any()) }
    }

    @Test fun errorsAreRedactedAndExplicitRetryCanSucceed() = runTest(main.dispatcher) {
        open(); advanceUntilIdle()
        coEvery { ai.explainContext(any()) } throws IllegalStateException("secret fixture")
        vm.onTextSelected("passage"); vm.onActionExplain(); advanceUntilIdle()
        assertEquals("Error: AI request failed. Retry explicitly.", vm.aiResponse.value)
        coEvery { ai.explainContext(any()) } throws CancellationException("private fixture")
        vm.onActionExplain(); advanceUntilIdle()
        assertEquals("Request cancelled. Retry explicitly.", vm.aiResponse.value)
        coEvery { ai.explainContext(any()) } returns "retried"
        vm.onActionExplain(); advanceUntilIdle()
        assertEquals("retried", vm.aiResponse.value)
    }

    @Test fun recapSuccessFailureAndDismissalKeepLoadingAndResultsConsistent() = runTest(main.dispatcher) {
        vm.getQuickRecap(); advanceUntilIdle()
        coVerify(exactly = 0) { ai.generateRecap(any(), any(), any()) }
        open(); advanceUntilIdle()
        coEvery { ai.generateRecap(any(), any(), any()) } coAnswers {
            assertEquals("reader-a", currentCoroutineContext()[AiVaultScope]?.vaultId)
            assertTrue(secondArg<String>().contains("synthetic note"))
            "recap result"
        }
        vm.onRecapClicked(); vm.getQuickRecap(); advanceUntilIdle()
        assertEquals("recap result", vm.recapText.value)
        assertFalse(vm.isRecapLoading.value)
        assertFalse(vm.showRecapConfirmation.value)
        vm.dismissRecap(); assertNull(vm.recapText.value)
        coEvery { ai.generateRecap(any(), any(), any()) } throws IllegalStateException("secret fixture")
        vm.getQuickRecap(); advanceUntilIdle()
        assertEquals("Error: Unable to generate recap.", vm.recapText.value)
        assertFalse(vm.isRecapLoading.value)
        var stopped = 0
        coEvery { ai.generateRecap(any(), any(), any()) } coAnswers { try { awaitCancellation() } finally { stopped++ } }
        vm.getQuickRecap(); runCurrent()
        vm.getQuickRecap(); runCurrent()
        assertEquals(1, stopped)
        vm.dismissRecapResult(); runCurrent()
        assertEquals(2, stopped)
        assertFalse(vm.isRecapLoading.value)
        assertNull(vm.recapText.value)
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun saveRecapWritesOnlyToCapturedVault() = runTest(main.dispatcher) {
        open(); advanceUntilIdle()
        val locator = mockk<Locator>(relaxed = true)
        every { locator.toJSON().toString() } returns "{}"
        val field = ReaderViewModel::class.java.getDeclaredField("_currentLocator")
        field.isAccessible = true
        (field.get(vm) as MutableStateFlow<Locator?>).value = locator
        coEvery { ai.generateRecap(any(), any(), any()) } returns "recap result"
        vm.getQuickRecap(); advanceUntilIdle()
        vm.saveRecapAsHighlight(); advanceUntilIdle()
        coVerify(exactly = 1) { highlights.insertHighlight(match { it.profileId == "reader-a" && it.bookHashId == "hash-a" && it.text == "RECAP: recap result" }) }
        assertNull(vm.recapText.value)
        vm.getQuickRecap(); advanceUntilIdle()
        vaultId.value = "reader-b"
        vm.saveRecapAsHighlight(); advanceUntilIdle()
        coVerify(exactly = 1) { highlights.insertHighlight(any()) }
    }
}
