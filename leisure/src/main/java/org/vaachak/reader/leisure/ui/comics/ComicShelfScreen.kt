package org.vaachak.reader.leisure.ui.comics

import android.graphics.Bitmap
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vaachak.reader.core.domain.model.ComicPageFit
import org.vaachak.reader.core.domain.model.ComicPreferences
import org.vaachak.reader.core.domain.model.ComicReadingDirection
import org.vaachak.reader.core.domain.model.ComicReadingMode

/**
 * Comics shelf mirrors Books' navigation vocabulary: Schedule for reading,
 * Folder for the entire collection, and Search / Sort only for the folder view.
 * The EPUB Books screen and its behaviors are not modified.
 */
@Composable
fun ComicShelfScreen(
    vaultId: String,
    isEink: Boolean,
    onOpenComic: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { ComicShelfStore(context) }
    val scope = rememberCoroutineScope()
    var comics by remember(vaultId) { mutableStateOf(store.list(vaultId)) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val coverLoader: suspend (ComicShelfStore.Comic) -> Bitmap? = remember(store) {
        { comic ->
            val first = store.pageNames(comic).firstOrNull()
            if (first != null) decodeBoundedBitmap(store.readPage(comic, first), 420) else null
        }
    }

    // SAF filters by provider MIME, not by filename extension; the importer
    // additionally validates .cbz/.cbr and the archive's real signature.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            message = null
            val result = runCatching {
                withContext(Dispatchers.IO) { store.importComic(vaultId, uri) }
            }
            result.onSuccess {
                comics = store.list(vaultId)
                message = "Imported ${it.title} (${it.format}, ${it.pageCount} pages)"
            }.onFailure { message = it.message ?: "Unable to import comic" }
            busy = false
        }
    }

    ComicShelfContent(
        comics = comics, isEink = isEink, busy = busy, message = message,
        onImport = { launcher.launch(ComicDocumentTypes.pickerMimeTypes.copyOf()) },
        onOpen = onOpenComic,
        onRemove = { id ->
            scope.launch {
                withContext(Dispatchers.IO) { store.remove(vaultId, id) }
                comics = store.list(vaultId)
            }
        },
        loadCover = coverLoader,
        modifier = modifier
    )
}

/** Distinct navigation destination: the app's main tab bar is not part of this screen. */
@Composable
fun ComicReaderScreen(
    vaultId: String,
    comicId: String,
    preferences: ComicPreferences,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { ComicShelfStore(context) }
    val scope = rememberCoroutineScope()
    val comic = remember(vaultId, comicId) {
        store.list(vaultId).firstOrNull { it.id == comicId }
    }
    LaunchedEffect(vaultId, comicId) {
        if (comic != null) {
            withContext(Dispatchers.IO) { store.markOpened(vaultId, comicId) }
        }
    }
    if (comic == null) {
        BackHandler(onBack = onBack)
        Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Text("Comic no longer exists in this profile")
            TextButton(onClick = onBack) { Text("Back to Comics") }
        }
        return
    }
    ComicReader(
        comic = comic, store = store, preferences = preferences,
        onBack = onBack,
        onPageChange = { page ->
            scope.launch { withContext(Dispatchers.IO) { store.setPage(vaultId, comic.id, page) } }
        },
        modifier = modifier
    )
}

/** Render-only view enables Compose tests without SAF, RAR decoder, or Hilt. */
@Composable
internal fun ComicShelfContent(
    comics: List<ComicShelfStore.Comic>,
    isEink: Boolean,
    busy: Boolean,
    message: String?,
    onImport: () -> Unit,
    onOpen: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
    loadCover: (suspend (ComicShelfStore.Comic) -> Bitmap?)? = null
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(1) } // Books opens Folder by default.
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf("Recent") }
    var isSearchActive by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }

    BackHandler(enabled = isSearchActive) {
        isSearchActive = false
        query = ""
    }

    val readingComics = comics.filter { it.lastOpenedAt > 0L || it.lastPage > 0 }
        .asReversed().sortedByDescending { it.lastOpenedAt }
    val visible = if (selectedTab == 0) {
        readingComics
    } else {
        comics.filter { it.title.contains(query, ignoreCase = true) }.let { matches ->
            when (sort) {
                "Title" -> matches.sortedBy { it.title.lowercase() }
                "Progress" -> matches.sortedByDescending {
                    (it.lastPage + 1).toFloat() / it.pageCount.coerceAtLeast(1)
                }
                else -> matches.asReversed().sortedByDescending { it.lastOpenedAt }
            }
        }
    }
    val contentColor = if (isEink) Color.Black else MaterialTheme.colorScheme.onBackground

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { selectedTab = 0; isSearchActive = false; query = "" },
                modifier = Modifier.semantics { contentDescription = "Recently read comics" }
            ) {
                Icon(if (selectedTab == 0) Icons.Filled.Schedule else Icons.Outlined.Schedule,
                    contentDescription = null, tint = contentColor)
            }
            IconButton(
                onClick = { selectedTab = 1 },
                modifier = Modifier.semantics { contentDescription = "All comics" }
            ) {
                Icon(if (selectedTab == 1) Icons.Filled.Folder else Icons.Outlined.Folder,
                    contentDescription = null, tint = contentColor)
            }
            Spacer(Modifier.weight(1f))
            if (selectedTab == 1) {
                if (isSearchActive) {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search comics") },
                        modifier = Modifier.weight(1f).height(50.dp),
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { isSearchActive = false; query = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear comic search")
                            }
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent
                        )
                    )
                } else {
                    IconButton(
                        onClick = { isSearchActive = true },
                        modifier = Modifier.semantics { contentDescription = "Search comics" }
                    ) { Icon(Icons.Default.Search, contentDescription = null, tint = contentColor) }
                    Box {
                        IconButton(
                            onClick = { sortMenu = true },
                            modifier = Modifier.semantics { contentDescription = "Sort comics" }
                        ) { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, tint = contentColor) }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            listOf(
                                "Progress" to "Sort by Progress",
                                "Recent" to "Sort by Recent",
                                "Title" to "Sort by Title"
                            ).forEach { (key, label) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = {
                                    sort = key
                                    sortMenu = false
                                })
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = contentColor.copy(alpha = 0.1f))
        if (message != null) {
            Text(message, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (selectedTab == 0) "No recently read comics." else if (query.isNotBlank())
                        "No comics match your search." else "No comics imported for this reader profile.")
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val minWidth = if (isEink) 108f else 105f
                    val columns = (maxWidth.value / minWidth).toInt().coerceIn(2, 8)
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(visible, key = { it.id }) { comic ->
                            ComicCoverCard(comic, loadCover, onOpen, onRemove)
                        }
                    }
                }
            }
            if (selectedTab == 1) {
                Button(
                    onClick = onImport, enabled = !busy,
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .then(if (busy) Modifier.width(108.dp).height(56.dp) else Modifier.size(56.dp))
                        .semantics { contentDescription = "Import CBZ or CBR comic" }
                ) {
                    if (busy) Text("Importing…", style = MaterialTheme.typography.labelSmall)
                    else Icon(Icons.Default.Add, contentDescription = null)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (selectedTab == 0) "Recent Reading: ${readingComics.size}"
                 else "Total Comics: ${comics.size}",
                style = MaterialTheme.typography.labelSmall, color = contentColor.copy(alpha = 0.65f))
            Text("${visible.size}/${if (selectedTab == 0) readingComics.size else comics.size}",
                style = MaterialTheme.typography.labelSmall, color = contentColor.copy(alpha = 0.65f))
        }
    }
}

@Composable
private fun ComicCoverCard(
    comic: ComicShelfStore.Comic,
    loadCover: (suspend (ComicShelfStore.Comic) -> Bitmap?)?,
    onOpen: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    var bitmap by remember(comic.id) { mutableStateOf<Bitmap?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(comic.id, comic.format, loadCover) {
        if (loadCover != null) {
            bitmap = runCatching { withContext(Dispatchers.IO) { loadCover(comic) } }.getOrNull()
        }
    }
    val percent = if (comic.lastPage == 0) 0 else
        (100 * (comic.lastPage + 1) / comic.pageCount.coerceAtLeast(1)).coerceIn(0, 100)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.69f)
                .clip(RoundedCornerShape(9.dp)).clickable { onOpen(comic.id) }
                .semantics { contentDescription = "Open comic ${comic.title}" },
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(bitmap = bitmap!!.asImageBitmap(), contentDescription = "Cover for ${comic.title}",
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center) {
                    Text(comic.title.take(1).uppercase(), style = MaterialTheme.typography.headlineLarge)
                }
            }
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
            ) {
                Text(comic.format, Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall)
            }
            if (comic.lastPage > 0) {
                Surface(modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f)) {
                    Text("▶ Continue", Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        Text(comic.title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("$percent% · ${comic.lastPage + 1}/${comic.pageCount}",
                modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(32.dp)
                    .semantics { contentDescription = "More options for ${comic.title}" }) {
                    Icon(Icons.Default.MoreVert, contentDescription = null)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text("Read / Continue") }, onClick = {
                        menuExpanded = false
                        onOpen(comic.id)
                    })
                    DropdownMenuItem(text = { Text("Remove") }, onClick = {
                        menuExpanded = false
                        onRemove(comic.id)
                    })
                }
            }
        }
        LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth().height(3.dp))
    }
}

/** The reader is an independent navigation destination, not another panel within the Comics tab. */
@Composable
private fun ComicImmersiveSystemBars() {
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(context, view) {
        val activity = context.findComicActivity()
        if (activity == null) {
            onDispose { }
        } else {
            val controller = WindowCompat.getInsetsController(activity.window, view)
            val previousBehavior = controller.systemBarsBehavior
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            onDispose {
                controller.show(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = previousBehavior
            }
        }
    }
}

private tailrec fun Context.findComicActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findComicActivity()
    else -> null
}

/**
 * Comic pages occupy the whole navigation destination. The phone's status/navigation bars
 * are temporarily hidden; center tap toggles compact overlay controls and Android Back always exits.
 * Fit Page retains the entire artwork; Fill Screen is optional because it necessarily crops.
 */
@Composable
private fun ComicReader(
    comic: ComicShelfStore.Comic,
    store: ComicShelfStore,
    preferences: ComicPreferences,
    onBack: () -> Unit,
    onPageChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var pages by remember(comic.id) { mutableStateOf<List<String>>(emptyList()) }
    var index by rememberSaveable(comic.id) { mutableIntStateOf(comic.lastPage) }
    var bitmap by remember(comic.id) { mutableStateOf<Bitmap?>(null) }
    var message by remember(comic.id) { mutableStateOf<String?>(null) }
    var chromeVisible by rememberSaveable(comic.id) { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    var rtl by rememberSaveable(comic.id) {
        mutableStateOf(preferences.readingDirection == ComicReadingDirection.RIGHT_TO_LEFT)
    }
    var vertical by rememberSaveable(comic.id) {
        mutableStateOf(preferences.readingMode == ComicReadingMode.VERTICAL_SCROLL)
    }
    var fitMode by rememberSaveable(comic.id) {
        mutableStateOf(if (preferences.pageFit == ComicPageFit.FIT_WIDTH)
            ComicZoomFit.WIDTH.name else ComicZoomFit.PAGE.name)
    }
    val fit = ComicZoomFit.valueOf(fitMode)
    ComicImmersiveSystemBars()
    BackHandler(onBack = onBack)

    LaunchedEffect(comic.id) {
        val result = runCatching { withContext(Dispatchers.IO) { store.pageNames(comic) } }
        result.onSuccess { pages = it; index = index.coerceIn(0, it.lastIndex) }
            .onFailure { message = it.message ?: "Comic cannot be opened; please import again" }
    }
    LaunchedEffect(comic.id, index, pages, vertical) {
        if (pages.isNotEmpty() && !vertical) {
            bitmap = null
            message = null
            val page = pages[index.coerceIn(0, pages.lastIndex)]
            val result = runCatching {
                withContext(Dispatchers.IO) { decodeBoundedBitmap(store.readPage(comic, page), 2048) }
            }
            result.onSuccess { bitmap = it }
                .onFailure { message = it.message ?: "Comic page could not be decoded" }
        }
    }

    fun advance(delta: Int) {
        if (pages.isEmpty()) return
        val next = (index + delta).coerceIn(0, pages.lastIndex)
        if (next != index) {
            index = next
            chromeVisible = false
            onPageChange(next)
        }
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (vertical) {
            val scroll = rememberLazyListState(initialFirstVisibleItemIndex = index)
            LaunchedEffect(scroll, pages) {
                if (pages.isNotEmpty()) {
                    snapshotFlow { scroll.firstVisibleItemIndex }.distinctUntilChanged().collect { page ->
                        if (page in pages.indices && page != index) {
                            index = page
                            onPageChange(page)
                        }
                    }
                }
            }
            LazyColumn(state = scroll, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(pages) { pageIndex, name ->
                    VerticalComicPage(comic, store, name, pageIndex,
                        onToggleChrome = { chromeVisible = !chromeVisible })
                }
            }
        } else {
            val image = bitmap
            if (image != null) {
                ZoomableComicPage(
                    bitmap = image,
                    pageKey = "${comic.id}:$index",
                    fit = fit,
                    tapToTurn = preferences.tapToTurn,
                    rtl = rtl,
                    onPrevious = { advance(-1) },
                    onNext = { advance(1) },
                    onToggleChrome = { chromeVisible = !chromeVisible },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(Modifier.fillMaxSize().clickable { chromeVisible = !chromeVisible },
                    contentAlignment = Alignment.Center) {
                    Text(message ?: "Opening page…", color = Color.White)
                }
            }
        }

        if (chromeVisible) {
            Surface(modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.91f)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.semantics {
                        contentDescription = "Back to comics"
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                    Text(comic.title, modifier = Modifier.weight(1f), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Box {
                        IconButton(onClick = { modeMenu = true }, modifier = Modifier.semantics {
                            contentDescription = "Comic reading and zoom options"
                        }) {
                            Icon(Icons.Default.Tune, contentDescription = null)
                        }
                        DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                            DropdownMenuItem(text = { Text("Pages · Left to right") }, onClick = {
                                vertical = false; rtl = false; modeMenu = false
                            })
                            DropdownMenuItem(text = { Text("Manga · Right to left") }, onClick = {
                                vertical = false; rtl = true; modeMenu = false
                            })
                            DropdownMenuItem(text = { Text("Continuous vertical scroll") }, onClick = {
                                vertical = true; modeMenu = false
                            })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Fit page · no cropping") }, onClick = {
                                fitMode = ComicZoomFit.PAGE.name; modeMenu = false
                            })
                            DropdownMenuItem(text = { Text("Fit width · pan taller pages") }, onClick = {
                                fitMode = ComicZoomFit.WIDTH.name; modeMenu = false
                            })
                            DropdownMenuItem(text = { Text("Fill screen · cropped, pannable") }, onClick = {
                                fitMode = ComicZoomFit.FILL.name; modeMenu = false
                            })
                        }
                    }
                }
            }
            Surface(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.91f)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = { advance(if (rtl) 1 else -1) },
                        enabled = pages.isNotEmpty() && !vertical) {
                        Icon(Icons.Default.ArrowBackIosNew, contentDescription = "Previous comic page")
                    }
                    Text(if (pages.isEmpty()) "Loading…" else "${index + 1} / ${pages.size}",
                        style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = { advance(if (rtl) -1 else 1) },
                        enabled = pages.isNotEmpty() && !vertical) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForwardIos,
                            contentDescription = "Next comic page")
                    }
                }
            }
        }
    }
}

/**
 * Shared pinch/drag/double-tap behavior for paged and vertical comic images.
 * Fits do not distort pixel aspect ratio. Panning is clamped so the page never drifts away.
 * While zoomed, taps reveal controls instead of unexpectedly advancing pages.
 */
@Composable
internal fun ZoomableComicPage(
    bitmap: Bitmap,
    pageKey: String,
    fit: ComicZoomFit,
    tapToTurn: Boolean,
    rtl: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleChrome: () -> Unit,
    modifier: Modifier = Modifier
) {
    var zoom by remember(pageKey, fit) { mutableFloatStateOf(1f) }
    var pan by remember(pageKey, fit) { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(modifier.clipToBounds()) {
        val viewportWidth = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val viewportHeight = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val geometry = ComicZoomMath.geometry(bitmap.width, bitmap.height,
            viewportWidth, viewportHeight, fit, zoom)
        val gestureState = rememberTransformableState { zoomChange, panChange, _ ->
            val nextZoom = (zoom * zoomChange).coerceIn(1f, ComicZoomMath.MAX_ZOOM)
            val bounds = ComicZoomMath.geometry(bitmap.width, bitmap.height,
                viewportWidth, viewportHeight, fit, nextZoom)
            zoom = nextZoom
            pan = Offset(
                (pan.x + panChange.x).coerceIn(-bounds.maxPanX, bounds.maxPanX),
                (pan.y + panChange.y).coerceIn(-bounds.maxPanY, bounds.maxPanY)
            )
        }
        Box(
            modifier = Modifier.fillMaxSize()
                .transformable(state = gestureState,
                    canPan = { zoom > 1.001f || geometry.maxPanX > 0f || geometry.maxPanY > 0f })
                .pointerInput(pageKey, tapToTurn, rtl, fit) {
                    detectTapGestures(
                        onDoubleTap = { point ->
                            if (zoom > 1.01f) {
                                zoom = 1f
                                pan = Offset.Zero
                            } else {
                                val target = 2.5f
                                val bounds = ComicZoomMath.geometry(bitmap.width, bitmap.height,
                                    viewportWidth, viewportHeight, fit, target)
                                zoom = target
                                pan = Offset(
                                    ((viewportWidth / 2f - point.x) * (target - 1f))
                                        .coerceIn(-bounds.maxPanX, bounds.maxPanX),
                                    ((viewportHeight / 2f - point.y) * (target - 1f))
                                        .coerceIn(-bounds.maxPanY, bounds.maxPanY)
                                )
                            }
                        },
                        onTap = { point ->
                            if (zoom > 1.01f || !tapToTurn) {
                                onToggleChrome()
                            } else {
                                val third = viewportWidth / 3f
                                when {
                                    point.x < third -> if (rtl) onNext() else onPrevious()
                                    point.x > third * 2 -> if (rtl) onPrevious() else onNext()
                                    else -> onToggleChrome()
                                }
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Comic page image; pinch to zoom, double tap to magnify",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = geometry.scale
                    scaleY = geometry.scale
                    translationX = pan.x
                    translationY = pan.y
                }
            )
        }
    }
}

@Composable
private fun VerticalComicPage(
    comic: ComicShelfStore.Comic,
    store: ComicShelfStore,
    name: String,
    index: Int,
    onToggleChrome: () -> Unit
) {
    var bitmap by remember(comic.id, name) { mutableStateOf<Bitmap?>(null) }
    var error by remember(comic.id, name) { mutableStateOf<String?>(null) }
    LaunchedEffect(comic.id, name) {
        runCatching {
            withContext(Dispatchers.IO) { decodeBoundedBitmap(store.readPage(comic, name), 2048) }
        }.onSuccess { bitmap = it }
            .onFailure { error = it.message ?: "Page unavailable" }
    }
    val image = bitmap
    if (image != null) {
        ZoomableComicPage(
            bitmap = image, pageKey = "${comic.id}:$index", fit = ComicZoomFit.PAGE,
            tapToTurn = false, rtl = false, onPrevious = {}, onNext = {},
            onToggleChrome = onToggleChrome,
            modifier = Modifier.fillMaxWidth().aspectRatio(
                image.width.toFloat() / image.height.coerceAtLeast(1))
        )
    } else {
        Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
            Text(error ?: "Loading page ${index + 1}…", color = Color.White)
        }
    }
}

/** Validate image dimensions before allocating a sampled bitmap. */
private fun decodeBoundedBitmap(data: ByteArray, maxDimension: Int): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0 || width > 20_000 || height > 20_000) {
        throw SafeCbzArchive.UnsafeArchive("Comic image dimensions are invalid")
    }
    var sample = 1
    while (width / sample > maxDimension || height / sample > maxDimension ||
        width.toLong() * height / (sample.toLong() * sample) > 5_000_000L
    ) sample *= 2
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return BitmapFactory.decodeByteArray(data, 0, data.size, opts)
        ?: throw SafeCbzArchive.UnsafeArchive("Comic image cannot be decoded")
}
