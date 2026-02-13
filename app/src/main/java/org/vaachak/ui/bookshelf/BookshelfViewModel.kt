/*
 *  Copyright (c) 2026 Piyush Daiya
 *  *
 *  * Permission is hereby granted, free of charge, to any person obtaining a copy
 *  * of this software and associated documentation files (the "Software"), to deal
 *  * in the Software without restriction, including without limitation the rights
 *  * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  * copies of the Software, and to permit persons to whom the Software is
 *  * furnished to do so, subject to the following conditions:
 *  *
 *  * The above copyright notice and this permission notice shall be included in all
 *  * copies or substantial portions of the Software.
 *  *
 *  * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  * SOFTWARE.
 */

package org.vaachak.ui.bookshelf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import org.vaachak.data.local.BookDao
import org.vaachak.data.local.BookEntity
import org.vaachak.data.local.HighlightDao
import org.vaachak.data.local.HighlightEntity
import org.vaachak.data.repository.AiRepository
import org.vaachak.data.repository.LibraryRepository
import org.vaachak.data.repository.SettingsRepository
import org.vaachak.data.repository.SyncRepository
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

/**
 * Enum representing the sorting order for books.
 */
enum class SortOrder { TITLE, AUTHOR, DATE_ADDED }

/**
 * ViewModel for the Bookshelf screen.
 * Manages the list of books, importing new books, sorting, and sync.
 */
@HiltViewModel
class BookshelfViewModel @Inject constructor(
    private val bookDao: BookDao,
    private val libraryRepository: LibraryRepository,
    private val highlightDao: HighlightDao,
    private val aiRepository: AiRepository,
    private val settingsRepo: SettingsRepository,
    private val syncRepository: SyncRepository, // NEW: Sync Logic
    @ApplicationContext private val context: Context
) : ViewModel() {

    // In BookshelfViewModel.kt
    val lastSyncTime: StateFlow<Long> = settingsRepo.lastSyncTimestamp
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0L
        )
    val syncUserName: StateFlow<String> = settingsRepo.syncUsername
        .stateIn(
            scope = viewModelScope, SharingStarted.WhileSubscribed(5000),"")
    // --- STATE: THEME ---
    val isEinkEnabled: StateFlow<Boolean> = settingsRepo.isEinkEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isOfflineModeEnabled: StateFlow<Boolean> = settingsRepo.isOfflineModeEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // --- STATE: SYNC ---
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    // --- STATE: SNACKBAR ---
    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage = _snackbarMessage.asStateFlow()

    fun clearSnackbarMessage() { _snackbarMessage.value = null }

    // --- STATE: BOOKS ---
    val allBooks: StateFlow<List<BookEntity>> = bookDao.getAllBooksSortedByRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _sortOrder = MutableStateFlow(SortOrder.DATE_ADDED)
    val sortOrder = _sortOrder.asStateFlow()

    val filteredLibraryBooks: StateFlow<List<BookEntity>> =
        combine(allBooks, searchQuery, _sortOrder) { books, query, order ->
            val filtered = books.filter { book ->
                // A book stays in Library ONLY if:
                // 1. Progress is exactly 0
                // 2. AND there is no saved CFI location (meaning it's never been opened or synced)
                val hasNotBeenOpened = book.progress <= 0.0 && book.lastCfiLocation.isNullOrBlank()
                val isFinished = book.progress >= 0.99

                (hasNotBeenOpened || isFinished) && book.title.contains(query, ignoreCase = true)
            }
            when (order) {
                SortOrder.TITLE -> filtered.sortedBy { it.title }
                SortOrder.AUTHOR -> filtered.sortedBy { it.author }
                SortOrder.DATE_ADDED -> filtered.sortedByDescending { it.addedDate }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentBooks: StateFlow<List<BookEntity>> = allBooks.map { books ->
        books.filter { // A book moves to "Continue Reading" ONLY if:
            // It has a CFI location (from opening locally OR from a sync)
            // AND it's not finished yet.
            val hasStarted = !it.lastCfiLocation.isNullOrBlank() || it.progress > 0.0
            val isNotFinished = it.progress < 0.99

            hasStarted && isNotFinished }
            .sortedByDescending { it.lastRead }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- RECAP STATE ---
    private val _recapState = MutableStateFlow<Map<String, String>>(emptyMap())
    val recapState: StateFlow<Map<String, String>> = _recapState.asStateFlow()

    private val _isLoadingRecap = MutableStateFlow<String?>(null)
    val isLoadingRecap: StateFlow<String?> = _isLoadingRecap.asStateFlow()

    // --- NEW: BOOKMARKS SHEET STATE ---
    private val _bookmarksSheetBookUri = MutableStateFlow<String?>(null)
    val bookmarksSheetBookUri = _bookmarksSheetBookUri.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val selectedBookBookmarks: StateFlow<List<HighlightEntity>> = _bookmarksSheetBookUri
        .flatMapLatest { uri ->
            if (uri == null) {
                flowOf(emptyList())
            } else {
                highlightDao.getHighlightsForBook(uri).map { list ->
                    list.filter { it.tag == "BOOKMARK" }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())


    init {
        triggerSilentSync()
    }

    // --- SYNC ACTIONS ---

    /**
     * Rule 1: Silent Sync on App Start
     */
    private fun triggerSilentSync() = viewModelScope.launch {
        try {
            syncRepository.sync()
        } catch (e: Exception) {
            e.printStackTrace()
            // We do not show errors on silent sync to avoid annoyance
        }
    }

    /**
     * Rule 4: Manual "Pull to Refresh"
     */
    fun refreshLibrary() {
        viewModelScope.launch {
            _isRefreshing.value = true
            val result = syncRepository.sync()
            _isRefreshing.value = false

            result.onFailure {
                _snackbarMessage.value = "Sync failed: Check your connection"
            }
        }
    }

    // --- ACTIONS ---

    fun updateSearchQuery(query: String) { _searchQuery.value = query }
    fun updateSortOrder(order: SortOrder) { _sortOrder.value = order }

    fun importBook(uri: Uri) {
        viewModelScope.launch {
            val result = libraryRepository.importBook(uri)
            result.onSuccess { msg ->
                _snackbarMessage.value = msg
            }.onFailure { e ->
                _snackbarMessage.value = e.message ?: "Failed to import book"
            }
        }
    }

    fun deleteBookByUri(uri: String) = viewModelScope.launch { bookDao.deleteBookByUri(uri) }

    fun getQuickRecap(book: BookEntity) {
        if (isOfflineModeEnabled.value) {
            _snackbarMessage.value = "Offline Mode enabled. Connect to use Recall."
            return
        }
        viewModelScope.launch {
            _isLoadingRecap.value = book.uriString
            try {
                val contextHighlights = highlightDao.getHighlightsForBook(book.uriString).first().take(10).joinToString("\n") { it.text }
                val summary = aiRepository.getRecallSummary(book.title, contextHighlights)
                _recapState.value = _recapState.value + (book.uriString to summary)
            } finally { _isLoadingRecap.value = null }
        }
    }

    fun clearRecap(uri: String) { _recapState.value = _recapState.value - uri }

    // --- BOOKMARKS ACTIONS ---

    fun openBookmarksSheet(uri: String) { _bookmarksSheetBookUri.value = uri }
    fun dismissBookmarksSheet() { _bookmarksSheetBookUri.value = null }
}