package org.vaachak.reader.leisure.ui.comics

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
}
