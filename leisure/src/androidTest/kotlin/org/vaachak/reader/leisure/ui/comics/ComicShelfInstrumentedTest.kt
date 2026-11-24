package org.vaachak.reader.leisure.ui.comics

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.vaachak.reader.leisure.testing.ComposeTestHostActivity

/** User-visible Comics shelf semantics and actions, without a document picker or live files. */
@RunWith(AndroidJUnit4::class)
class ComicShelfInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test fun emptyShelfExplainsImportAndLaunchesPickerAction() {
        var imported = false
        compose.setContent {
            MaterialTheme {
                ComicShelfContent(
                    comics = emptyList(), isEink = true, busy = false, message = null,
                    onImport = { imported = true }, onOpen = {}, onRemove = {}
                )
            }
        }
        compose.onNodeWithText("No comics imported for this reader profile.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Import CBZ or CBR comic").performClick()
        compose.runOnIdle { assertTrue(imported) }
    }

    @Test fun shelfOpensAndRemovesSelectedComicWithoutMixingAnotherProfile() {
        var opened = ""
        var removed = ""
        val comics = listOf(
            ComicShelfStore.Comic("book-1", "Demo Comic", "content://test/comic", 12, 3)
        )
        compose.setContent {
            MaterialTheme {
                ComicShelfContent(
                    comics = comics, isEink = false, busy = false, message = "Import complete",
                    onImport = {}, onOpen = { opened = it }, onRemove = { removed = it }
                )
            }
        }
        compose.onNodeWithText("33% · 4/12").assertExists()
        compose.onNodeWithText("Import complete").assertExists()
        compose.onNodeWithContentDescription("Open comic Demo Comic").performClick()
        compose.runOnIdle { assertEquals("book-1", opened) }
        compose.onNodeWithContentDescription("More options for Demo Comic").performClick()
        compose.onNodeWithText("Remove").performClick()
        compose.runOnIdle { assertEquals("book-1", removed) }
    }

    @Test fun searchStartsCollapsedAndCanBeOpened() {
        compose.setContent {
            MaterialTheme {
                ComicShelfContent(
                    comics = listOf(ComicShelfStore.Comic("a", "Demo", "content://test/comic", 5, 0)),
                    isEink = false, busy = false, message = null,
                    onImport = {}, onOpen = {}, onRemove = {}
                )
            }
        }
        compose.onNodeWithContentDescription("Search comics").performClick()
        compose.onNodeWithText("Search comics").assertExists()
    }

    @Test fun readingTabShowsOnlyComicsThatWereStarted() {
        val comics = listOf(
            ComicShelfStore.Comic("fresh", "Not Started", "content://test/fresh", 12, 0),
            ComicShelfStore.Comic("started", "Started Today", "content://test/started", 12, 0,
                lastOpenedAt = 1732400000000L)
        )
        compose.setContent {
            MaterialTheme {
                ComicShelfContent(comics = comics, isEink = false, busy = false, message = null,
                    onImport = {}, onOpen = {}, onRemove = {})
            }
        }
        compose.onNodeWithContentDescription("Recently read comics").performClick()
        compose.onNodeWithText("Recent Reading: 1").assertExists()
        compose.onNodeWithContentDescription("Open comic Started Today").assertExists()
        compose.onNodeWithContentDescription("Open comic Not Started").assertDoesNotExist()
        compose.onNodeWithContentDescription("All comics").performClick()
        compose.onNodeWithContentDescription("Open comic Not Started").assertExists()
    }

    @Test fun comicsShelfUsesBooksStyleSortMenuWithoutStatusChips() {
        compose.setContent {
            MaterialTheme {
                ComicShelfContent(comics = emptyList(), isEink = false, busy = false,
                    message = null, onImport = {}, onOpen = {}, onRemove = {})
            }
        }
        compose.onNodeWithContentDescription("Sort comics").performClick()
        compose.onNodeWithText("Sort by Progress").assertExists()
        compose.onNodeWithText("Sort by Recent").assertExists()
        compose.onNodeWithText("Sort by Title").assertExists()
        compose.onNodeWithText("Unread").assertDoesNotExist()
        compose.onNodeWithText("Completed").assertDoesNotExist()
    }

    @Test fun busyImportDisablesPicker() {
        compose.setContent {
            MaterialTheme {
                // The real import flow clears the previous message before setting busy=true.
                // Do not inject a second "Importing…" message alongside the button label.
                ComicShelfContent(
                    comics = emptyList(), isEink = false, busy = true, message = null,
                    onImport = {}, onOpen = {}, onRemove = {}
                )
            }
        }
        compose.onNodeWithContentDescription("Import CBZ or CBR comic").assertIsNotEnabled()
        compose.onNodeWithText("Importing…").assertExists()
    }

    @Test fun anEmptyRecentShelfExplainsThatNothingHasBeenOpened() {
        compose.setContent {
            MaterialTheme {
                ComicShelfContent(comics = listOf(
                    ComicShelfStore.Comic("unread", "Never Opened", "content://test/unread", 3, 0)
                ), isEink = false, busy = false, message = null,
                    onImport = {}, onOpen = {}, onRemove = {})
            }
        }
        compose.onNodeWithContentDescription("Recently read comics").performClick()
        compose.onNodeWithText("No recently read comics.").assertExists()
        compose.onNodeWithText("Recent Reading: 0").assertExists()
        compose.onNodeWithContentDescription("All comics").performClick()
        compose.onNodeWithContentDescription("Open comic Never Opened").assertExists()
    }

    @Test fun shelfSearchFiltersTitlesShowsNoResultsAndClearsSafely() {
        compose.setContent {
            MaterialTheme {
                ComicShelfContent(comics = listOf(
                    ComicShelfStore.Comic("a", "Alpha Comics", "content://test/a", 10, 0),
                    ComicShelfStore.Comic("b", "Bravo", "content://test/b", 6, 0)
                ), isEink = false, busy = false, message = null,
                    onImport = {}, onOpen = {}, onRemove = {})
            }
        }
        compose.onNodeWithContentDescription("Search comics").performClick()
        compose.onNodeWithText("Search comics").performTextInput("zzz-not-in-library")
        compose.onNodeWithText("No comics match your search.").assertExists()
        compose.onNodeWithContentDescription("Clear comic search").performClick()
        compose.onNodeWithContentDescription("Open comic Alpha Comics").assertExists()
        compose.onNodeWithContentDescription("Open comic Bravo").assertExists()
    }

    @Test fun allSortingModesCanBeSelectedAndContinueMenuOpensTheReader() {
        var opened = ""
        compose.setContent {
            MaterialTheme {
                ComicShelfContent(comics = listOf(
                    ComicShelfStore.Comic("older", "Issue 10", "content://test/10", 10, 5,
                        format = "CBR", lastOpenedAt = 99L),
                    ComicShelfStore.Comic("newer", "Issue 2", "content://test/2", 10, 0,
                        format = "CBZ", lastOpenedAt = 100L)
                ), isEink = false, busy = false, message = null,
                    onImport = {}, onOpen = { opened = it }, onRemove = {})
            }
        }
        for (label in listOf("Sort by Title", "Sort by Progress", "Sort by Recent")) {
            compose.onNodeWithContentDescription("Sort comics").performClick()
            compose.onNodeWithText(label).performClick()
        }
        compose.onNodeWithText("60% · 6/10").assertExists()
        compose.onNodeWithText("CBR").assertExists()
        compose.onNodeWithContentDescription("More options for Issue 10").performClick()
        compose.onNodeWithText("Read / Continue").performClick()
        compose.runOnIdle { assertEquals("older", opened) }
    }

    @Test fun emptyImportMessageIsVisibleWithoutBlockingActions() {
        var opened = ""
        compose.setContent {
            MaterialTheme {
                ComicShelfContent(comics = listOf(
                    ComicShelfStore.Comic("a", "Finished", "content://test/a", 4, 3)
                ), isEink = true, busy = false, message = "Already imported",
                    onImport = {}, onOpen = { opened = it }, onRemove = {})
            }
        }
        compose.onNodeWithText("Already imported").assertExists()
        compose.onNodeWithText("100% · 4/4").assertExists()
        compose.onNodeWithContentDescription("Open comic Finished").performClick()
        compose.runOnIdle { assertEquals("a", opened) }
    }

    @Test fun missingComicReaderShowsBackToComicsInsteadOfCrashing() {
        var didGoBack = false
        compose.setContent {
            MaterialTheme {
                ComicReaderScreen(
                    vaultId = "test-comic-vault-${System.nanoTime()}",
                    comicId = "missing-comic",
                    preferences = org.vaachak.reader.core.domain.model.ComicPreferences(),
                    onBack = { didGoBack = true }
                )
            }
        }
        compose.onNodeWithText("Comic no longer exists in this profile").assertExists()
        compose.onNodeWithText("Back to Comics").performClick()
        compose.runOnIdle { assertTrue(didGoBack) }
    }
}
