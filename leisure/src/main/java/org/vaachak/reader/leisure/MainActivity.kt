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

package org.vaachak.reader.leisure

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.node.DelegatableNode
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.vaachak.reader.core.domain.model.BookEntity
import org.vaachak.reader.core.domain.model.ThemeMode
import org.vaachak.reader.leisure.navigation.Screen
import org.vaachak.reader.leisure.ui.bookshelf.BookshelfScreen
import org.vaachak.reader.leisure.ui.bookshelf.BookshelfUiState
import org.vaachak.reader.leisure.ui.bookshelf.BookshelfViewModel
import org.vaachak.reader.leisure.ui.catalog.CatalogBrowserScreen
import org.vaachak.reader.leisure.ui.catalog.CatalogManagerScreen
import org.vaachak.reader.leisure.ui.highlights.AllHighlightsScreen
import org.vaachak.reader.leisure.ui.login.LoginScreen
import org.vaachak.reader.leisure.ui.reader.ReaderScreen
import org.vaachak.reader.leisure.ui.reader.components.VaachakNavigationFooter
import org.vaachak.reader.leisure.ui.settings.AiConfigScreen
import org.vaachak.reader.leisure.ui.settings.AppAppearanceScreen
import org.vaachak.reader.leisure.ui.settings.LibrarySettingsScreen
import org.vaachak.reader.leisure.ui.settings.DefaultAppearanceScreen
import org.vaachak.reader.leisure.ui.settings.DictionarySettingsScreen

import org.vaachak.reader.leisure.ui.settings.SettingsScreen
import org.vaachak.reader.leisure.ui.settings.SettingsViewModel
import org.vaachak.reader.leisure.ui.settings.SyncSettingsScreen
import org.vaachak.reader.leisure.ui.settings.TtsSettingsScreen
import org.vaachak.reader.leisure.ui.theme.VaachakTheme
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipFile
import androidx.compose.runtime.saveable.rememberSaveable
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val settingsViewModel: SettingsViewModel by viewModels()
    private val bookshelfViewModel: BookshelfViewModel by viewModels()
    private var isTtsActive = false

    // HELPER: Extracts a flat list of all books from the KMP UI State
    private val BookshelfUiState.allBooksFlat: List<BookEntity>
        get() = listOfNotNull(heroBook) + groupedLibrary.values.flatten()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (savedInstanceState == null && intent?.action == Intent.ACTION_VIEW) {
            processExternalIntent(intent)
        }

        setContent {
            val navController = rememberNavController()

            val uiState by settingsViewModel.uiState.collectAsState()
            val currentTheme = uiState.themeMode
            val einkContrast = uiState.einkContrast
            val isEink = currentTheme == ThemeMode.E_INK

            CompositionLocalProvider(
                LocalIndication provides if (isEink) NoIndication else LocalIndication.current
            ) {
                VaachakTheme(themeMode = currentTheme, contrast = einkContrast) {
                    Scaffold(
                        containerColor = if (isEink) Color.White else MaterialTheme.colorScheme.background,
                        bottomBar = {
                            val showFooter = false // Bookshelf handles its own nav
                            if (showFooter) {
                                VaachakNavigationFooter(
                                    onBookshelfClick = {
                                        navController.navigate(Screen.Library.route) { popUpTo(Screen.Library.route) { inclusive = true } }
                                    },
                                    onHighlightsClick = {
                                        navController.navigate(Screen.Highlights.route) { launchSingleTop = true }
                                    },
                                    onAboutClick = {
                                        navController.navigate(Screen.Settings.route)
                                    },
                                    isEink = isEink
                                )
                            }
                        }
                    ) { padding ->
                        Surface(
                            modifier = Modifier.padding(padding).fillMaxSize(),
                            color = if (isEink) Color.White else MaterialTheme.colorScheme.background
                        ) {
                            NavHost(
                                navController = navController,
                                startDestination = Screen.Library.route
                            ) {
                                // 1. APP Shelf
                                composable(Screen.Library.route) {
                                    MainAppScreen(
                                        navController = navController,
                                        bookshelfViewModel = bookshelfViewModel,
                                        onBookClick = { uri ->
                                            val currentState = bookshelfViewModel.uiState.value
                                            val book = currentState.allBooksFlat.find { it.localUri == uri }
                                            if (book != null) {
                                                navController.navigate(Screen.Reader.createRoute(book.bookHash, book.lastCfiLocation))
                                            } else {
                                                Toast.makeText(this@MainActivity, "Loading book...", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        onCatalogClick = { navController.navigate(Screen.CatalogBrowser.route) },
                                        onHighlightsClick = { navController.navigate(Screen.Highlights.route) }
                                    )
                                }

                                // 2. READER
                                composable(
                                    route = Screen.Reader.route,
                                    arguments = listOf(
                                        navArgument("bookId") { type = NavType.StringType },
                                        navArgument("locator") { type = NavType.StringType; nullable = true }
                                    )
                                ) { backStackEntry ->
                                    val bookHash = backStackEntry.arguments?.getString("bookId") ?: ""
                                    val locatorJson = backStackEntry.arguments?.getString("locator")

                                    val bookshelfState by bookshelfViewModel.uiState.collectAsState()
                                    val book = bookshelfState.allBooksFlat.find { it.bookHash == bookHash }

                                    if (book != null) {
                                        ReaderScreen(
                                            bookHash = bookHash,
                                            initialUri = book.localUri,
                                            initialLocatorJson = locatorJson,
                                            onBack = {
                                                isTtsActive = false
                                                navController.popBackStack()
                                            },
                                            onTtsStatusChange = { active ->
                                                isTtsActive = active
                                            }
                                        )
                                    } else {
                                        Box(Modifier.fillMaxSize()) {
                                            Text("Loading Book...", Modifier.align(Alignment.Center))
                                        }
                                        LaunchedEffect(Unit) {
                                            delay(1000)
                                            if (bookshelfViewModel.uiState.value.allBooksFlat.none { it.bookHash == bookHash }) {
                                                navController.popBackStack()
                                            }
                                        }
                                    }
                                }

                                // 3. HIGHLIGHTS
                                composable(Screen.Highlights.route) {
                                    AllHighlightsScreen(
                                        onBack = { navController.popBackStack() },
                                        onHighlightClick = { bookId, locator ->
                                            navController.navigate(Screen.Reader.createRoute(bookId, locator))
                                        }
                                    )
                                }

                                // 4. CATALOG BROWSER
                                composable(Screen.CatalogBrowser.route) {
                                    CatalogBrowserScreen(
                                        onBack = { navController.popBackStack() },
                                        onReadBook = { uri ->
                                            val currentState = bookshelfViewModel.uiState.value
                                            val book = currentState.allBooksFlat.find { it.localUri == uri }
                                            if (book != null) {
                                                navController.popBackStack(Screen.Library.route, false)
                                                navController.navigate(Screen.Reader.createRoute(book.bookHash))
                                            }
                                        },
                                        onGoToBookshelf = {
                                            navController.popBackStack(Screen.Library.route, false)
                                        }
                                    )
                                }

                                // 5 - 12 SETTINGS & SPOKES
                                composable(Screen.Settings.route) {
                                    SettingsScreen(
                                        navController = navController
                                    )
                                }
                                composable(Screen.Login.route) { LoginScreen(navController = navController) }
                                composable(Screen.CatalogManage.route) { CatalogManagerScreen(onBack = { navController.popBackStack() }) }
                                composable(Screen.AiConfig.route) { AiConfigScreen(onBack = { navController.popBackStack() }) }
                                composable(Screen.SyncSettings.route) { SyncSettingsScreen(navController = navController) }
                                composable(Screen.Appearance.route) { DefaultAppearanceScreen(onBack = { navController.popBackStack() }) }
                                composable(Screen.TTS.route) { TtsSettingsScreen(onBack = { navController.popBackStack() }) }
                                composable(Screen.AppAppearance.route) { AppAppearanceScreen(onBack = { navController.popBackStack() }) }
                                composable(Screen.Dictionary.route) { DictionarySettingsScreen(onBack = { navController.popBackStack() }) }
                                composable(Screen.Bookshelf.route) { LibrarySettingsScreen(navController = navController) }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        processExternalIntent(intent)
    }

    private fun processExternalIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            val contentUri = intent.data!!

            lifecycleScope.launch {
                try {
                    Toast.makeText(this@MainActivity, "Importing book...", Toast.LENGTH_SHORT).show()

                    val localFile = withContext(Dispatchers.IO) { copyToExternalStorage(contentUri) }

                    if (localFile != null && localFile.exists() && localFile.length() > 0) {
                        val tempTitle = withContext(Dispatchers.IO) { getEpubTitle(localFile) } ?: ""

                        waitForDatabaseToLoad()

                        val allKnownBooks = bookshelfViewModel.uiState.value.allBooksFlat
                        val existingBook = allKnownBooks.find { book ->
                            book.title.trim().equals(tempTitle.trim(), ignoreCase = true)
                        }

                        if (existingBook != null) {
                            Timber.d("Duplicate found: '${existingBook.title}'")
                            withContext(Dispatchers.IO) { localFile.delete() }
                            Toast.makeText(this@MainActivity, "Book already exists", Toast.LENGTH_SHORT).show()
                        } else {
                            Timber.d("Importing New Book")
                            val localUri = Uri.fromFile(localFile)
                            bookshelfViewModel.importBook(localUri)
                            Toast.makeText(this@MainActivity, "Import Successful", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(this@MainActivity, "Failed to load file", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error opening book")
                    Toast.makeText(this@MainActivity, "Error opening book", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private suspend fun waitForDatabaseToLoad() {
        withTimeoutOrNull(2000) {
            while (bookshelfViewModel.uiState.value.allBooksFlat.isEmpty()) {
                delay(100)
            }
        }
    }

    private fun copyToExternalStorage(uri: Uri): File? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val booksDir = getExternalFilesDir("imported_books") ?: return null
            if (!booksDir.exists()) booksDir.mkdirs()

            val fileName = "book_${System.currentTimeMillis()}_${UUID.randomUUID()}.epub"
            val destFile = File(booksDir, fileName)

            FileOutputStream(destFile).use { output ->
                inputStream.copyTo(output)
            }
            destFile
        } catch (e: Exception) {
            Timber.e(e, "Error copying to external storage")
            null
        }
    }

    private fun getEpubTitle(epubFile: File): String? {
        return try {
            ZipFile(epubFile).use { zip ->
                val containerEntry = zip.getEntry("META-INF/container.xml") ?: return null
                val containerXml = zip.getInputStream(containerEntry).bufferedReader().use { it.readText() }
                val opfPathMatch = Regex("full-path=\"([^\"]+)\"").find(containerXml)
                val opfPath = opfPathMatch?.groupValues?.get(1) ?: return null
                val opfEntry = zip.getEntry(opfPath) ?: return null
                val opfXml = zip.getInputStream(opfEntry).bufferedReader().use { it.readText() }
                val titleMatch = Regex("<dc:title[^>]*>(.*?)</dc:title>", RegexOption.DOT_MATCHES_ALL).find(opfXml)
                titleMatch?.groupValues?.get(1)
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val isVolumeDown = keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        val isVolumeUp = keyCode == KeyEvent.KEYCODE_VOLUME_UP
        val isPageDown = keyCode == KeyEvent.KEYCODE_PAGE_DOWN
        val isPageUp = keyCode == KeyEvent.KEYCODE_PAGE_UP

        if (isVolumeDown || isVolumeUp || isPageDown || isPageUp) {
            if (isTtsActive && (isVolumeDown || isVolumeUp)) {
                return super.onKeyDown(keyCode, event)
            }

            val fragment = supportFragmentManager.findFragmentByTag("EPUB_READER_FRAGMENT") as? EpubNavigatorFragment

            fragment?.let { navigator ->
                if (isVolumeDown || isPageDown) {
                    navigator.goForward(animated = false)
                } else {
                    navigator.goBackward(animated = false)
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private object NoIndication : IndicationNodeFactory {
        override fun create(interactionSource: InteractionSource): DelegatableNode {
            return object : Modifier.Node(), DelegatableNode {}
        }
        override fun hashCode(): Int = -1
        override fun equals(other: Any?): Boolean = other === this
    }
}
@Composable
fun MainAppScreen(
    navController: androidx.navigation.NavController,
    bookshelfViewModel: BookshelfViewModel,
    onBookClick: (String) -> Unit,
    onCatalogClick: () -> Unit,
    onHighlightsClick: () -> Unit
) {
    val tabs = listOf("Books", "Comics", "Audio", "Settings")
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }

    androidx.activity.compose.BackHandler(enabled = selectedTabIndex != 0) {
        selectedTabIndex = 0
    }

    Scaffold(
        topBar = {
            PrimaryTabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                tabs.forEachIndexed { index, title ->
                    val icon = when (index) {
                        0 -> Icons.Default.Book
                        1 -> Icons.Outlined.Photo
                        2 -> Icons.Default.Audiotrack
                        3 -> Icons.Default.Settings
                        else -> Icons.Default.Book
                    }
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(title) },
                        icon = { Icon(icon, contentDescription = title) }
                    )
                }
            }
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            when (selectedTabIndex) {
                0 -> BookshelfScreen(
                    viewModel = bookshelfViewModel,
                    onBookClick = onBookClick,
                   onCatalogClick = onCatalogClick,
                    onHighlightsClick = onHighlightsClick
                )
                1 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("ComicShelf (Coming Soon)") }
                2 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("AudioShelf (Coming Soon)") }
                3 -> SettingsScreen(
                    navController = navController // This tells the back arrow to switch back to the Books tab!
                )
            }
        }
    }
}
