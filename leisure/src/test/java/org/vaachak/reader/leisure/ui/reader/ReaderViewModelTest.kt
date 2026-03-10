package org.vaachak.reader.leisure.ui.reader

import android.app.Application
import app.cash.turbine.test
import io.mockk.coEvery
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
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.readium.r2.shared.publication.Locator
import org.vaachak.reader.core.data.local.BookDao
import org.vaachak.reader.core.data.local.HighlightDao
import org.vaachak.reader.core.data.repository.*
import org.vaachak.reader.core.domain.model.TtsSettings

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {

    private lateinit var application: Application
    private lateinit var aiRepository: AiRepository
    private lateinit var readiumManager: ReadiumManager
    private lateinit var highlightDao: HighlightDao
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var dictionaryRepository: DictionaryRepository
    private lateinit var syncRepository: SyncRepository
    private lateinit var bookDao: BookDao
    private lateinit var vaultRepository: VaultRepository

    private lateinit var viewModel: ReaderViewModel
    private val testDispatcher = StandardTestDispatcher()

    // Fake StateFlows for reactive testing
    private val fakeVaultId = MutableStateFlow("profile_piyush")
    private val fakeOfflineMode = MutableStateFlow(false)
    private val fakeEinkMode = MutableStateFlow(true)
    private val fakeEmbeddedDict = MutableStateFlow(true)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        application = mockk(relaxed = true)
        aiRepository = mockk(relaxed = true)
        readiumManager = mockk(relaxed = true)
        highlightDao = mockk(relaxed = true)
        settingsRepo = mockk(relaxed = true)
        dictionaryRepository = mockk(relaxed = true)
        syncRepository = mockk(relaxed = true)
        bookDao = mockk(relaxed = true)
        vaultRepository = mockk(relaxed = true)

        // Setup base settings
        every { vaultRepository.activeVaultId } returns fakeVaultId
        every { settingsRepo.isOfflineModeEnabled } returns fakeOfflineMode
        every { settingsRepo.isEinkEnabled } returns fakeEinkMode
        every { settingsRepo.getUseEmbeddedDictionary() } returns fakeEmbeddedDict
        every { settingsRepo.ttsSettings } returns flowOf(TtsSettings())

        // Setup Reader Preferences Flows
        every { settingsRepo.readerTheme } returns flowOf("light")
        every { settingsRepo.readerFontFamily } returns flowOf("default")
        every { settingsRepo.readerFontSize } returns flowOf(1.0)
        every { settingsRepo.readerTextAlign } returns flowOf("start")
        every { settingsRepo.readerLineHeight } returns flowOf(1.2)
        every { settingsRepo.readerPublisherStyles } returns flowOf(true)
        every { settingsRepo.readerLetterSpacing } returns flowOf(0.0)
        every { settingsRepo.readerParaSpacing } returns flowOf(0.0)
        every { settingsRepo.readerMarginSide } returns flowOf(1.0)

        // Initialize ViewModel
        viewModel = ReaderViewModel(
            application, aiRepository, readiumManager, highlightDao,
            settingsRepo, dictionaryRepository, syncRepository, bookDao, vaultRepository
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `isAiEnabled dynamically reacts to Global Offline Mode`() = runTest {
        viewModel.isAiEnabled.test {
            val initialState = awaitItem()
            assertTrue(initialState) // By default, AI is enabled

            // When global offline mode turns ON
            fakeOfflineMode.value = true
            val updatedState = awaitItem()

            // Then AI should automatically disable to prevent network calls
            assertFalse(updatedState)
        }
    }

    @Test
    fun `lookupWord rejects selections over 5 words`() = runTest {
        // When selecting a very long string
        val longText = "This is a very long sentence that exceeds the five word limit"
        viewModel.lookupWord(longText)
        advanceUntilIdle()

        // Then it should immediately reject it without hitting the repository
        assertTrue(viewModel.isDictionaryLookup.value)
        assertEquals("Selection too long for dictionary.", viewModel.aiResponse.value)
        coVerify(exactly = 0) { dictionaryRepository.getDefinition(any()) }
        coVerify(exactly = 0) { dictionaryRepository.getjsonDefinition(any()) }
    }

    @Test
    fun `lookupWord successfully routes to embedded dictionary`() = runTest {
        // Given embedded dictionary is enabled
        fakeEmbeddedDict.value = true
        coEvery { dictionaryRepository.getDefinition("obfuscate") } returns "To make unclear."

        // When looking up a valid word
        viewModel.lookupWord("obfuscate")
        advanceUntilIdle()

        // Then it should query the local repository and emit the definition
        assertEquals("To make unclear.", viewModel.aiResponse.value)
        coVerify(exactly = 1) { dictionaryRepository.getDefinition("obfuscate") }
    }

    @Test
    fun `getQuickRecap halts and shows snackbar if AI is disabled`() = runTest {
        // Given AI is manually toggled off
        viewModel.toggleBookAi(false)
        advanceUntilIdle()

        // When requesting a recap
        viewModel.getQuickRecap()
        advanceUntilIdle()

        // Then the recap state does not load and repository is untouched
        assertFalse(viewModel.isRecapLoading.value)
        coVerify(exactly = 0) { aiRepository.generateRecap(any(), any(), any()) }
    }

    @Test
    fun `closeBook securely saves progress using active vault profile`() = runTest {
        // Given a simulated active book state
        val mockUri = "content://dummy/book.epub"
        val mockLocator = mockk<Locator>(relaxed = true)
        every { mockLocator.toJSON().toString() } returns "{\"mock\":\"json\"}"
        every { mockLocator.locations.totalProgression } returns 0.75

        // We inject the state directly for testing the teardown sequence
        val uriField = ReaderViewModel::class.java.getDeclaredField("_currentLocalUri")
        uriField.isAccessible = true
        (uriField.get(viewModel) as MutableStateFlow<String?>).value = mockUri

        val locatorField = ReaderViewModel::class.java.getDeclaredField("_currentLocator")
        locatorField.isAccessible = true
        (locatorField.get(viewModel) as MutableStateFlow<Locator?>).value = mockLocator

        // When closing the book
        viewModel.closeBook()
        advanceUntilIdle()

        // Then it updates the database with the vault ID, triggers sync, and clears Readium
        coVerify { bookDao.updateBookProgressByUri(mockUri, "profile_piyush", 0.75, "{\"mock\":\"json\"}", any()) }
        coVerify { readiumManager.closePublication() }
        assertNull(viewModel.publication.value)
    }
}