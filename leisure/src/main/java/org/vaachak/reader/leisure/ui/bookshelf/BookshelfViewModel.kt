package org.vaachak.reader.leisure.ui.bookshelf

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.vaachak.reader.core.data.local.BookDao
import org.vaachak.reader.core.data.local.HighlightDao
import org.vaachak.reader.core.data.repository.*
import org.vaachak.reader.core.domain.model.BookEntity
import org.vaachak.reader.core.domain.model.BookshelfPreferences
import org.vaachak.reader.core.domain.model.HighlightEntity
import javax.inject.Inject

data class BookshelfUiState(
    val isLoading: Boolean = false,
    val searchQuery: String = "",
    val activeFilter: String = "All",
    val availableFilters: List<String> = listOf("All"),
    val filterCounts: Map<String, Int> = emptyMap(),
    val sortOrder: SortOrder = SortOrder.PROGRESS,
    val isEink: Boolean = false,
    val isOfflineMode: Boolean = false,
    val lastSyncTime: Long = 0L,
    val syncUsername: String = "",
    val snackbarMessage: String? = null,
    val bookshelfPrefs: BookshelfPreferences = BookshelfPreferences(),

    val heroBook: BookEntity? = null,
    val groupedLibrary: Map<String, List<BookEntity>> = emptyMap(),

    // NEW: NeoReader Folder Drill-down state
    val selectedStackName: String? = null,

    val recapState: Map<String, String> = emptyMap(),
    val loadingRecapUri: String? = null,
    val bookmarksSheetUri: String? = null,
    val selectedBookmarks: List<HighlightEntity> = emptyList(),
    val booksWithBookmarks: Set<String> = emptySet()
)

enum class SortOrder { TITLE, AUTHOR, DATE_ADDED, PROGRESS }

private data class FilterState(val query: String, val filter: String, val sort: SortOrder, val selectedStack: String?)
private data class PrefsState(val isEink: Boolean, val isOfflineMode: Boolean, val lastSyncTime: Long, val syncUsername: String, val bookshelfPrefs: BookshelfPreferences)
private data class DialogState(val snackbar: String?, val recap: Map<String, String>, val loadingUri: String?, val sheetUri: String?, val bookmarksSet: Set<String>)

@HiltViewModel
class BookshelfViewModel @Inject constructor(
    private val bookDao: BookDao,
    private val libraryRepository: LibraryRepository,
    private val highlightDao: HighlightDao,
    private val aiRepository: AiRepository,
    settingsRepo: SettingsRepository,
    private val syncRepository: SyncRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    private val _activeFilter = MutableStateFlow("All")
    private val _sortOrder = MutableStateFlow(SortOrder.PROGRESS)
    private val _isRefreshing = MutableStateFlow(false)
    private val _snackbarMessage = MutableStateFlow<String?>(null)

    // NeoReader Drill-down StateFlow
    private val _selectedStackName = MutableStateFlow<String?>(null)

    private val _recapState = MutableStateFlow<Map<String, String>>(emptyMap())
    private val _loadingRecapUri = MutableStateFlow<String?>(null)
    private val _bookmarksSheetBookUri = MutableStateFlow<String?>(null)

    private val filterFlow = combine(
        _searchQuery, _activeFilter, _sortOrder, _selectedStackName
    ) { query, filter, sort, stack -> FilterState(query, filter, sort, stack) }

    private val prefsFlow = combine(
        settingsRepo.isEinkEnabled, settingsRepo.isOfflineModeEnabled, settingsRepo.lastSyncTimestamp, settingsRepo.syncUsername, settingsRepo.bookshelfPreferences
    ) { eink, offline, lastSync, username, shelfPrefs -> PrefsState(eink, offline, lastSync, username, shelfPrefs) }

    private val dialogsFlow = combine(
        _snackbarMessage, _recapState, _loadingRecapUri, _bookmarksSheetBookUri, highlightDao.getBooksWithBookmarks()
    ) { snackbar, recap, loadingUri, sheetUri, bookHashes -> DialogState(snackbar, recap, loadingUri, sheetUri, bookHashes.toSet()) }

    val uiState: StateFlow<BookshelfUiState> = combine(
        bookDao.getAllBooksSortedByRecent(), filterFlow, prefsFlow, dialogsFlow
    ) { books, filters, prefs, dialogs ->

        val searchedBooks = if (filters.query.isNotBlank()) {
            books.filter { it.title.contains(filters.query, ignoreCase = true) || it.author.contains(filters.query, ignoreCase = true) }
        } else books

        val filterCounts = mutableMapOf("All" to searchedBooks.size)
        searchedBooks.mapNotNull { it.language?.takeIf { l -> l.isNotBlank() } }
            .groupingBy { it }
            .eachCount()
            .forEach { (lang, count) -> filterCounts[lang] = count }

        val allFilters = listOf("All") + filterCounts.keys.filter { it != "All" }.sorted()

        val languageFilteredBooks = if (filters.filter != "All") {
            searchedBooks.filter { it.language.equals(filters.filter, ignoreCase = true) }
        } else searchedBooks

        val heroBook = if (filters.query.isBlank() && filters.filter == "All") {
            languageFilteredBooks.firstOrNull { it.progress > 0.0 && it.progress < 0.99 }
        } else null

        var libraryList =  languageFilteredBooks

        libraryList = when (filters.sort) {
            SortOrder.TITLE -> libraryList.sortedBy { it.title }
            SortOrder.AUTHOR -> libraryList.sortedBy { it.author }
            SortOrder.DATE_ADDED -> libraryList.sortedByDescending { it.addedDate }
            SortOrder.PROGRESS -> libraryList.sortedByDescending { it.progress }
        }

        val groupedLibrary = if (prefs.bookshelfPrefs.groupBySeries) {
            libraryList.groupBy { it.author }
        } else {
            mapOf("All Books" to libraryList)
        }

        BookshelfUiState(
            isLoading = _isRefreshing.value,
            searchQuery = filters.query,
            activeFilter = filters.filter,
            availableFilters = allFilters,
            filterCounts = filterCounts,
            sortOrder = filters.sort,
            isEink = prefs.isEink,
            isOfflineMode = prefs.isOfflineMode,
            lastSyncTime = prefs.lastSyncTime,
            syncUsername = prefs.syncUsername,
            snackbarMessage = dialogs.snackbar,
            bookshelfPrefs = prefs.bookshelfPrefs,
            heroBook = heroBook,
            groupedLibrary = groupedLibrary,
            selectedStackName = filters.selectedStack, // Passes the folder name to the UI
            recapState = dialogs.recap,
            loadingRecapUri = dialogs.loadingUri,
            bookmarksSheetUri = dialogs.sheetUri,
            booksWithBookmarks = dialogs.bookmarksSet
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BookshelfUiState())

    init { triggerSilentSync() }
    private fun triggerSilentSync() = viewModelScope.launch { try { syncRepository.sync() } catch (_: Exception) {} }

    // --- NEOREADER FOLDER ACTIONS ---
    fun openStack(stackName: String) {
        _selectedStackName.value = stackName
    }

    fun closeStack() {
        _selectedStackName.value = null
    }

    fun refreshLibrary() {
        viewModelScope.launch {
            _isRefreshing.value = true
            val result = syncRepository.sync()
            _isRefreshing.value = false
            result.onFailure { _snackbarMessage.value = "Sync failed: Check connection" }
        }
    }

    fun updateSearchQuery(query: String) { _searchQuery.value = query }
    fun setFilter(filter: String) { _activeFilter.value = filter }
    fun updateSortOrder(order: SortOrder) { _sortOrder.value = order }
    fun clearSnackbarMessage() { _snackbarMessage.value = null }
    fun deleteBookByUri(uri: String) = viewModelScope.launch { bookDao.deleteBookByUri(uri) }
    fun openBookmarksSheet(uri: String) { _bookmarksSheetBookUri.value = uri }
    fun dismissBookmarksSheet() { _bookmarksSheetBookUri.value = null }
    fun clearRecap(uri: String) { _recapState.value -= uri }
    fun importBook(uri: Uri) = viewModelScope.launch { libraryRepository.importBook(uri).onSuccess { _snackbarMessage.value = it }.onFailure { _snackbarMessage.value = it.message ?: "Failed to import" } }

    fun getQuickRecap(book: BookEntity) {
        if (uiState.value.isOfflineMode) { _snackbarMessage.value = "Offline Mode enabled. Connect to use Recall."; return }
        viewModelScope.launch {
            _loadingRecapUri.value = book.localUri
            try {
                val highlights = highlightDao.getHighlightsForBook(book.bookHash).first().take(10).joinToString("\n") { it.text }
                val summary = aiRepository.getRecallSummary(book.title, highlights)
                _recapState.value += (book.bookHash to summary)
            } finally { _loadingRecapUri.value = null }
        }
    }

    fun getGlobalRecap() {
        if (uiState.value.isOfflineMode) { _snackbarMessage.value = "Offline Mode enabled. Connect to use Recall."; return }
        viewModelScope.launch {
            _loadingRecapUri.value = "GLOBAL"
            try {
                val activeBooks = bookDao.getAllBooksSortedByRecent().first().filter { it.progress > 0.05f }
                if (activeBooks.isEmpty()) { _snackbarMessage.value = "No books with > 5% progress found to recap."; return@launch }

                val allHighlights = mutableListOf<String>()
                for (book in activeBooks) {
                    val highlights = highlightDao.getHighlightsForBook(book.bookHash).first().take(5)
                    if (highlights.isNotEmpty()) allHighlights.add("From '${book.title}':\n" + highlights.joinToString("\n") { "- ${it.text}" })
                }

                if (allHighlights.isEmpty()) { _snackbarMessage.value = "No highlights found in your active books."; return@launch }

                val summary = aiRepository.getRecallSummary("My Active Reading List", allHighlights.joinToString("\n\n"))
                _recapState.value += ("GLOBAL" to summary)
            } catch (e: Exception) {
                _snackbarMessage.value = "Failed to generate global recall."
            } finally {
                _loadingRecapUri.value = null
            }
        }
    }
}