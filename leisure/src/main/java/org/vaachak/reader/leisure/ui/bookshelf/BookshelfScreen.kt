package org.vaachak.reader.leisure.ui.bookshelf

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import org.vaachak.reader.core.domain.model.BookEntity
import org.vaachak.reader.core.domain.model.CoverAspectRatio
import org.vaachak.reader.core.domain.model.DitheringMode
import org.vaachak.reader.leisure.ui.utils.EinkDitherTransformation

import java.io.File

// --- NEW: UNIFIED GRID ITEM TYPE ---
sealed class LibraryItem {
    data class Book(val entity: BookEntity) : LibraryItem()
    data class Stack(val name: String, val books: List<BookEntity>) : LibraryItem()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BookshelfScreen(
    onBookClick: (String) -> Unit,
    onCatalogClick: () -> Unit,
    onHighlightsClick: () -> Unit,
    viewModel: BookshelfViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // TAB STATE: 0 = Reading (Clock), 1 = Bookshelf (Folder)
    var selectedTab by remember { mutableIntStateOf(1) }
    var isSearchActive by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { viewModel.importBook(it) }
    }

    // Intercept Back button for Search OR Folder Drill-down
    BackHandler(enabled = isSearchActive || state.selectedStackName != null) {
        if (isSearchActive) {
            isSearchActive = false
            viewModel.updateSearchQuery("")
        } else if (state.selectedStackName != null) {
            viewModel.closeStack()
        }
    }

    val containerColor = if (state.isEink) Color.White else MaterialTheme.colorScheme.background
    val contentColor = if (state.isEink) Color.Black else MaterialTheme.colorScheme.onBackground

    // 1. Data Prep for Tab 1 (Reading)
    val readingItems = state.groupedLibrary.values.flatten()
        .filter { it.progress > 0f }
        .sortedByDescending { it.progress }
        .map { LibraryItem.Book(it) }

    // 2. Data Prep for Tab 2 (Bookshelf Mixed Folders/Books)
    val shelfItems = remember(state.groupedLibrary, state.bookshelfPrefs.groupBySeries) {
        if (state.bookshelfPrefs.groupBySeries) {
            state.groupedLibrary.map { (author, books) ->
                if (books.size > 1) LibraryItem.Stack(author, books)
                else LibraryItem.Book(books.first())
            }
        } else {
            state.groupedLibrary.values.flatten().map { LibraryItem.Book(it) }
        }
    }

    Scaffold(
        containerColor = containerColor,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column(modifier = Modifier.background(containerColor)) {
                Row(modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {

                    // LEFT: Tab Switchers
                    IconButton(onClick = { selectedTab = 0; viewModel.closeStack() }) {
                        Icon(if (selectedTab == 0) Icons.Filled.Schedule else Icons.Outlined.Schedule, "Reading", tint = contentColor)
                    }
                    IconButton(onClick = { selectedTab = 1 }) {
                        Icon(if (selectedTab == 1) Icons.Filled.Folder else Icons.Outlined.Folder, "Bookshelf", tint = contentColor)
                    }
                    Spacer(Modifier.weight(1f))

                    // RIGHT: Contextual Actions
                    if (selectedTab == 0) {
                        IconButton(onClick = { viewModel.getGlobalRecap() }) {
                            if (state.loadingRecapUri == "GLOBAL") CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.AutoAwesome, "Global Recall", tint = contentColor)
                        }
                    } else {
                        if (isSearchActive) {
                            TextField(
                                value = state.searchQuery,
                                onValueChange = { viewModel.updateSearchQuery(it) },
                                modifier = Modifier.weight(1f).height(50.dp),
                                placeholder = { Text("Search...") },
                                singleLine = true,
                                trailingIcon = { IconButton(onClick = { isSearchActive = false; viewModel.updateSearchQuery("") }) { Icon(Icons.Default.Close, null) } },
                                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent)
                            )
                        } else {
                            IconButton(onClick = { isSearchActive = true }) { Icon(Icons.Default.Search, "Search", tint = contentColor) }
                            IconButton(onClick = { viewModel.refreshLibrary() }) { Icon(Icons.Default.Sync, "Sync", tint = contentColor) }
                            IconButton(onClick = onCatalogClick) { Icon(Icons.Default.Public, "Catalog", tint = contentColor) }
                            IconButton(onClick = onHighlightsClick) { Icon(Icons.Default.EditNote, "Highlights", tint = contentColor) }
                            var menuExpanded by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { menuExpanded = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort", tint = contentColor) }
                                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                    DropdownMenuItem(text = { Text("Sort by Progress") }, onClick = { viewModel.updateSortOrder(SortOrder.PROGRESS); menuExpanded = false })
                                    DropdownMenuItem(text = { Text("Sort by Recent") }, onClick = { viewModel.updateSortOrder(SortOrder.DATE_ADDED); menuExpanded = false })
                                    DropdownMenuItem(text = { Text("Sort by Title") }, onClick = { viewModel.updateSortOrder(SortOrder.TITLE); menuExpanded = false })
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(color = contentColor.copy(alpha = 0.1f))
            }
        },
        floatingActionButton = {
            if (selectedTab == 1 && state.selectedStackName == null) {
                FloatingActionButton(
                    onClick = { launcher.launch(arrayOf("application/epub+zip")) },
                    containerColor = if (state.isEink) Color.Black else MaterialTheme.colorScheme.primaryContainer,
                    contentColor = if (state.isEink) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
                ) { Icon(Icons.Default.Add, "Add Book") }
            }
        }
    ) { paddingValues ->

        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            val cardWidth = 110.dp
            val cardHeight = if (state.bookshelfPrefs.coverAspectRatio == CoverAspectRatio.UNIFORM) 180.dp else 200.dp
            val columns = maxOf(3, (maxWidth.value / cardWidth.value).toInt())
            val rows = maxOf(2, ((maxHeight.value - 60f) / cardHeight.value).toInt())
            val itemsPerPage = columns * rows

            if (selectedTab == 0) {
                // --- TAB 1: READING SECTION ---
                PaginatedGrid(
                    items = readingItems,
                    itemsPerPage = itemsPerPage,
                    columns = columns,
                    state = state,
                    contentColor = contentColor,
                    viewModel = viewModel,
                    onBookClick = onBookClick,
                    bottomBarTextLeft = "Recent Reading: ${readingItems.size}"
                )
            } else {
                // --- TAB 2: BOOKSHELF SECTION ---
                Column(modifier = Modifier.fillMaxSize()) {

                    // IF A FOLDER IS OPEN (Drill-down)
                    if (state.selectedStackName != null) {
                        val stackBooks = state.groupedLibrary[state.selectedStackName]?.map { LibraryItem.Book(it) } ?: emptyList()

                        // Folder Header
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.closeStack() }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = contentColor)
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(state.selectedStackName ?: "", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = contentColor)
                                Text("${stackBooks.size} Books", style = MaterialTheme.typography.bodySmall, color = contentColor.copy(alpha = 0.6f))
                            }
                        }
                        HorizontalDivider(color = contentColor.copy(alpha = 0.1f))

                        PaginatedGrid(
                            items = stackBooks,
                            itemsPerPage = itemsPerPage,
                            columns = columns,
                            state = state,
                            contentColor = contentColor,
                            viewModel = viewModel,
                            onBookClick = onBookClick,
                            bottomBarTextLeft = "Books in series: ${stackBooks.size}"
                        )
                    }
                    // ELSE MAIN LIBRARY
                    else {
                        LazyRow(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(state.availableFilters) { filter ->
                                val isSelected = state.activeFilter == filter
                                Text(
                                    text = filter,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) contentColor else contentColor.copy(alpha = 0.5f),
                                    modifier = Modifier.clickable { viewModel.setFilter(filter) }.padding(8.dp)
                                )
                            }
                        }

                        PaginatedGrid(
                            items = shelfItems,
                            itemsPerPage = itemsPerPage,
                            columns = columns,
                            state = state,
                            contentColor = contentColor,
                            viewModel = viewModel,
                            onBookClick = onBookClick,
                            bottomBarTextLeft = "Total Library: ${shelfItems.size}"
                        )
                    }
                }
            }
        }

        // --- DIALOGS & BOTTOM SHEETS (Unchanged) ---
        // (Keep the recapState and bookmarksSheetUri dialogs exactly as they were in the previous file)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PaginatedGrid(
    items: List<LibraryItem>,
    itemsPerPage: Int,
    columns: Int,
    state: BookshelfUiState,
    contentColor: Color,
    viewModel: BookshelfViewModel,
    onBookClick: (String) -> Unit,
    bottomBarTextLeft: String
) {
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No books found", color = contentColor.copy(alpha = 0.5f)) }
        return
    }

    val pages = items.chunked(itemsPerPage)
    val pagerState = rememberPagerState(pageCount = { pages.size })

    Column(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth()) { pageIndex ->
            val pageItems = pages[pageIndex]
            val rows = pageItems.chunked(columns)

            Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                for (rowItems in rows) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        for (item in rowItems) {
                            Box(modifier = Modifier.weight(1f)) {
                                when (item) {
                                    is LibraryItem.Book -> NeoReaderCoverCard(book = item.entity, state = state, onClick = { onBookClick(item.entity.localUri ?: "") })
                                    is LibraryItem.Stack -> StackGridItem(stack = item, state = state, onClick = { viewModel.openStack(item.name) })
                                }
                            }
                        }
                        repeat(columns - rowItems.size) { Spacer(modifier = Modifier.weight(1f)) }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(bottomBarTextLeft, style = MaterialTheme.typography.labelMedium, color = contentColor.copy(alpha = 0.7f))
            Text("${pagerState.currentPage + 1}/${pages.size}", style = MaterialTheme.typography.labelMedium, color = contentColor)
        }
    }
}

// --- BOOK CARD ---
@Composable
fun NeoReaderCoverCard(book: BookEntity, state: BookshelfUiState, onClick: () -> Unit) {
    val prefs = state.bookshelfPrefs
    val isEink = state.isEink
    val shouldDither = prefs.ditheringMode == DitheringMode.ALWAYS_ON || (prefs.ditheringMode == DitheringMode.AUTO && isEink)
    val hasBookmarks = state.booksWithBookmarks.contains(book.bookHash)

    val modifier = if (prefs.coverAspectRatio == CoverAspectRatio.UNIFORM) Modifier.fillMaxWidth().aspectRatio(0.7f)
    else Modifier.fillMaxWidth().heightIn(min = 150.dp, max = 220.dp)

    Column(modifier = Modifier.clickable { onClick() }) {
        Card(
            modifier = modifier, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.3f)),
            colors = CardDefaults.cardColors(containerColor = if (isEink) Color.White else Color.LightGray.copy(0.2f))
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (book.coverPath != null && File(book.coverPath).exists()) {
                    AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(File(book.coverPath)).crossfade(true).apply { if (shouldDither) transformations(EinkDitherTransformation()) }.build(), contentDescription = "Cover", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else { Box(Modifier.fillMaxSize().background(Color.LightGray.copy(0.5f)), contentAlignment = Alignment.Center) { Text(book.title.take(1).uppercase(), style = MaterialTheme.typography.headlineMedium) } }

                if (prefs.showProgressBadge && book.progress > 0f) {
                    Box(modifier = Modifier.align(Alignment.TopStart).background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(bottomEnd = 8.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) { Text("${(book.progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = Color.Black, fontWeight = FontWeight.Bold) }
                }
                if (prefs.showFavoriteIcon && hasBookmarks) {
                    Icon(Icons.Default.Star, contentDescription = "Bookmark", tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp))
                }
                if (prefs.showFormatBadge) {
                    Box(modifier = Modifier.align(Alignment.BottomStart).background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(topEnd = 8.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) { Text("EPUB", style = MaterialTheme.typography.labelSmall, color = Color.Black, fontWeight = FontWeight.Bold) }
                }
                if (prefs.showSyncStatus) {
                    Box(modifier = Modifier.align(Alignment.BottomEnd).background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(topStart = 8.dp)).padding(4.dp)) { Icon(Icons.Default.CloudDone, contentDescription = "Synced", tint = Color.Black, modifier = Modifier.size(14.dp)) }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(book.title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = if(isEink) Color.Black else MaterialTheme.colorScheme.onBackground)
    }
}

// --- NEW: STACK / FOLDER CARD ---
@Composable
fun StackGridItem(stack: LibraryItem.Stack, state: BookshelfUiState, onClick: () -> Unit) {
    val isEink = state.isEink
    val firstBook = stack.books.firstOrNull()
    val unreadCount = stack.books.count { it.progress < 0.99f }
    val modifier = if (state.bookshelfPrefs.coverAspectRatio == CoverAspectRatio.UNIFORM) Modifier.fillMaxWidth().aspectRatio(0.7f)
    else Modifier.fillMaxWidth().heightIn(min = 150.dp, max = 220.dp)

    Column(modifier = Modifier.clickable { onClick() }) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            // Visual Stack Effect (Background Card)
            Card(modifier = Modifier.fillMaxSize().padding(top = 8.dp, start = 8.dp, end = 8.dp), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.4f)), colors = CardDefaults.cardColors(containerColor = if (isEink) Color.LightGray else Color.Gray.copy(alpha = 0.3f))) {}

            // Front Book Cover
            if (firstBook != null) {
                Card(modifier = Modifier.fillMaxSize().padding(bottom = 8.dp, end = 12.dp), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.5f))) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (firstBook.coverPath != null && File(firstBook.coverPath).exists()) {
                            AsyncImage(model = File(firstBook.coverPath), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        } else {
                            Box(Modifier.fillMaxSize().background(Color.LightGray), contentAlignment = Alignment.Center) { Text(firstBook.title.take(1).uppercase()) }
                        }
                    }
                }
            }

            // [0/Total] Badge exactly like NeoReader
            Box(modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 12.dp).background(Color.White.copy(alpha = 0.95f), RoundedCornerShape(topEnd = 8.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text("${unreadCount}/${stack.books.size}", style = MaterialTheme.typography.labelSmall, color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(stack.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if(isEink) Color.Black else MaterialTheme.colorScheme.onBackground)
    }
}