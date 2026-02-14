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
package org.vaachak.reader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.node.DelegatableNode
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.vaachak.reader.navigation.Screen
import org.vaachak.reader.ui.bookshelf.BookshelfScreen
import org.vaachak.reader.ui.bookshelf.BookshelfViewModel
import org.vaachak.reader.ui.catalog.CatalogBrowserScreen
import org.vaachak.reader.ui.catalog.CatalogManagerScreen
import org.vaachak.reader.ui.highlights.AllHighlightsScreen
import org.vaachak.reader.ui.login.LoginScreen
import org.vaachak.reader.ui.reader.ReaderScreen
import org.vaachak.reader.ui.reader.components.VaachakNavigationFooter
import org.vaachak.reader.ui.settings.SettingsScreen
import org.vaachak.reader.ui.settings.SettingsViewModel
import org.vaachak.reader.ui.settings.AiConfigScreen
import org.vaachak.reader.ui.settings.SyncSettingsScreen
import org.vaachak.reader.ui.theme.ThemeMode
import org.vaachak.reader.ui.theme.VaachakTheme
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipFile

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    // We keep Activity-scoped ViewModels to handle "Import Book" intents
    // that might happen before UI loads.
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val bookshelfViewModel: BookshelfViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Handle "Open With" intents on cold start
        if (savedInstanceState == null && intent?.action == Intent.ACTION_VIEW) {
            processExternalIntent(intent)
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            val navController = rememberNavController()
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = navBackStackEntry?.destination?.route

            // 1. COLLECT THE FULL STATE (This fixes "Unresolved reference")
            val uiState by settingsViewModel.uiState.collectAsState()

            // 2. READ PROPERTIES FROM STATE
            val currentTheme = uiState.themeMode
            val einkContrast = uiState.einkContrast
            val isEink = currentTheme == ThemeMode.E_INK
            // Fix for E-Ink ghosting on touch
            CompositionLocalProvider(
                LocalIndication provides if (isEink) NoIndication else LocalIndication.current
            ) {
                VaachakTheme(themeMode = currentTheme, contrast = einkContrast) {
                    Scaffold(
                        containerColor = if (isEink) Color.White else MaterialTheme.colorScheme.background,
                        bottomBar = {
                            // Only show Footer on top-level tabs
                            val showFooter = currentRoute in listOf(
                                Screen.Library.route,
                                Screen.Highlights.route,
                                Screen.CatalogBrowser.route
                            )

                            if (showFooter) {
                                VaachakNavigationFooter(
                                    onBookshelfClick = {
                                        navController.navigate(Screen.Library.route) {
                                            popUpTo(Screen.Library.route) { inclusive = true }
                                        }
                                    },
                                    onHighlightsClick = {
                                        navController.navigate(Screen.Highlights.route) {
                                            launchSingleTop = true
                                        }
                                    },
                                    onAboutClick = {
                                        // "About" is now inside Settings
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
                            // --- NAVIGATION GRAPH ---
                            NavHost(
                                navController = navController,
                                startDestination = Screen.Library.route
                            ) {
                                // 1. BOOKSHELF (Home)
                                composable(Screen.Library.route) {
                                    // BookshelfScreen likely collects state internally, so passing VM is fine
                                    BookshelfScreen(
                                        viewModel = bookshelfViewModel,
                                        onBookClick = { uri ->
                                            // It is SAFE to read .value inside a click listener (lambda)
                                            val book = bookshelfViewModel.allBooks.value.find { it.uriString == uri }
                                            if (book != null) {
                                                navController.navigate(Screen.Reader.createRoute(book.id))
                                            } else {
                                                Toast.makeText(this@MainActivity, "Loading book...", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        onRecallClick = { /* Navigate to History */ },
                                        onSettingsClick = { navController.navigate(Screen.Settings.route) },
                                        onBookmarkClick = { uri, _ ->
                                            val book = bookshelfViewModel.allBooks.value.find { it.uriString == uri }
                                            if (book != null) {
                                                navController.navigate(Screen.Reader.createRoute(book.id))
                                            }
                                        },
                                        onCatalogClick = { navController.navigate(Screen.CatalogBrowser.route) }
                                    )
                                }

                                // 2. READER (With ID Argument)
                                composable(
                                    route = Screen.Reader.route,
                                    arguments = listOf(navArgument("bookId") { type = NavType.LongType })
                                ) { backStackEntry ->
                                    val bookId = backStackEntry.arguments?.getLong("bookId") ?: 0L

                                    // --- FIX: Collect state here instead of reading .value ---
                                    val allBooks by bookshelfViewModel.allBooks.collectAsState()
                                    val book = allBooks.find { it.id == bookId }

                                    if (book != null) {
                                        ReaderScreen(
                                            bookId = bookId,
                                            initialUri = book.uriString,
                                            // Pass null so ReaderViewModel loads the last saved position from DB
                                            initialLocatorJson = null,
                                            onBack = { navController.popBackStack() }
                                        )
                                    } else {
                                        // Fallback logic
                                        Box(Modifier.fillMaxSize()) {
                                            Text("Loading Book...", Modifier.align(Alignment.Center))
                                        }
                                        // If book is missing (e.g. fresh start), pop back after a moment
                                        LaunchedEffect(Unit) {
                                            delay(1000)
                                            navController.popBackStack()
                                        }
                                    }
                                }

                                // ... The rest of your routes (Highlights, Catalog, Settings) remain the same ...

                                // 3. HIGHLIGHTS
                                composable(Screen.Highlights.route) {
                                    AllHighlightsScreen(
                                        onBack = { navController.popBackStack() },
                                        onHighlightClick = { _, _ -> /* Navigate to reader */ }
                                    )
                                }

                                // 4. CATALOG BROWSER
                                composable(Screen.CatalogBrowser.route) {
                                    CatalogBrowserScreen(
                                        onBack = { navController.navigate(Screen.Library.route) },
                                        onReadBook = { uri -> /* Trigger download/read */ },
                                        onGoToBookshelf = { navController.navigate(Screen.Library.route) }
                                    )
                                }

                                // 5. SETTINGS HUB
                                composable(Screen.Settings.route) {
                                    SettingsScreen(navController = navController)
                                }

                                // 6. LOGIN SPOKE
                                composable(Screen.Login.route) {
                                    LoginScreen(navController = navController)
                                }

                                // 7. CATALOG MANAGER SPOKE
                                composable(Screen.CatalogManage.route) {
                                    CatalogManagerScreen(
                                        onNavigateBack = { navController.popBackStack() },
                                        onCatalogSelected = { /* Open specific feed */ }
                                    )
                                }
                                //8.AI Config Screen
                                composable(Screen.AiConfig.route) {
                                    AiConfigScreen(onBack = { navController.popBackStack() })
                                }

                                //9. Sync Settings Screen
                                composable(Screen.SyncSettings.route) {
                                    SyncSettingsScreen(navController = navController)}
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

    // --- HELPER: IMPORT LOGIC (Preserved) ---
    private fun processExternalIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            val contentUri = intent.data!!

            lifecycleScope.launch {
                try {
                    Toast.makeText(this@MainActivity, "Importing book...", Toast.LENGTH_SHORT).show()

                    val localFile = withContext(Dispatchers.IO) {
                        copyToExternalStorage(contentUri)
                    }

                    if (localFile != null && localFile.exists() && localFile.length() > 0) {
                        val tempTitle = withContext(Dispatchers.IO) { getEpubTitle(localFile) } ?: ""

                        waitForDatabaseToLoad()

                        val allKnownBooks = bookshelfViewModel.allBooks.value
                        val existingBook = allKnownBooks.find { book ->
                            book.title.trim().equals(tempTitle.trim(), ignoreCase = true)
                        }

                        if (existingBook != null) {
                            Log.d("VaachakMain", "Duplicate found: '${existingBook.title}'")
                            withContext(Dispatchers.IO) { localFile.delete() }
                            Toast.makeText(this@MainActivity, "Book already exists", Toast.LENGTH_SHORT).show()
                        } else {
                            Log.d("VaachakMain", "Importing New Book")
                            val localUri = Uri.fromFile(localFile)
                            bookshelfViewModel.importBook(localUri)
                            Toast.makeText(this@MainActivity, "Import Successful", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(this@MainActivity, "Failed to load file", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(this@MainActivity, "Error opening book", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private suspend fun waitForDatabaseToLoad() {
        withTimeoutOrNull(2000) {
            while (bookshelfViewModel.allBooks.value.isEmpty()) {
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
            e.printStackTrace()
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

    // --- HARDWARE BUTTONS (Volume Page Turn) ---
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val isPageForward = keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_PAGE_DOWN
        val isPageBackward = keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_PAGE_UP

        if (isPageForward || isPageBackward) {
            val fragment = supportFragmentManager.findFragmentByTag("EPUB_READER_FRAGMENT") as? EpubNavigatorFragment
            fragment?.let { navigator ->
                if (isPageForward) navigator.goForward(animated = false)
                else navigator.goBackward(animated = false)
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

