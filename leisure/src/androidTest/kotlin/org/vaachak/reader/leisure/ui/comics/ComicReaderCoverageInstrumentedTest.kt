package org.vaachak.reader.leisure.ui.comics

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.vaachak.reader.core.domain.model.ComicPreferences
import org.vaachak.reader.core.domain.model.ComicReadingDirection
import org.vaachak.reader.core.domain.model.ComicReadingMode
import org.vaachak.reader.leisure.testing.ComposeTestHostActivity

/** Test the real reader canvas and chrome without SAF or RAR fixtures.
 * Android CI instruments these production composables for JaCoCo coverage.
 */
@RunWith(AndroidJUnit4::class)
class ComicReaderCoverageInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    private val comic = ComicShelfStore.Comic(
        id = "reader-test", title = "A Fullscreen Comic", uri = "content://tests/sample",
        pageCount = 3, lastPage = 0, format = "CBZ"
    )

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(60, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(AndroidColor.BLUE)
        return ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
        }
    }

    private fun awaitPage() {
        compose.waitUntil(20_000) {
            compose.onAllNodesWithContentDescription(
                "Comic page image; pinch to zoom, double tap to magnify"
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun chromeShowsNavigationTitleAndBack() {
        var previous = 0
        var next = 0
        var backed = false
        compose.setContent {
            MaterialTheme {
                ComicReaderChrome(
                    comic = comic, index = 1, pageCount = 3, vertical = false,
                    modeMenu = false, onBack = { backed = true },
                    onModeMenuChange = {}, onReadingDirection = {}, onVertical = {},
                    onFitChange = {}, onPrevious = { previous++ }, onNext = { next++ },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        compose.onNodeWithText("A Fullscreen Comic").assertExists()
        compose.onNodeWithText("2 / 3").assertExists()
        compose.onNodeWithContentDescription("Previous comic page").performClick()
        compose.onNodeWithContentDescription("Next comic page").performClick()
        compose.onNodeWithContentDescription("Back to comics").performClick()
        compose.runOnIdle {
            assertEquals(1, previous)
            assertEquals(1, next)
            assertTrue(backed)
        }
    }

    @Test fun chromeReadingOptionsDispatchEachChoice() {
        var menu by mutableStateOf(false)
        val directions = mutableListOf<Boolean>()
        val fits = mutableListOf<ComicZoomFit>()
        var vertical = 0
        compose.setContent {
            MaterialTheme {
                ComicReaderChrome(
                    comic = comic, index = 0, pageCount = 3, vertical = false,
                    modeMenu = menu, onBack = {}, modifier = Modifier.fillMaxSize(),
                    onModeMenuChange = { menu = it },
                    onReadingDirection = { directions += it; menu = false },
                    onVertical = { vertical++; menu = false },
                    onFitChange = { fits += it; menu = false },
                    onPrevious = {}, onNext = {}
                )
            }
        }
        for (choice in listOf(
            "Pages · Left to right", "Manga · Right to left", "Continuous vertical scroll",
            "Fit page · no cropping", "Fit width · pan taller pages",
            "Fill screen · cropped, pannable"
        )) {
            compose.onNodeWithContentDescription("Comic reading and zoom options").performClick()
            compose.onNodeWithText(choice).performClick()
        }
        compose.runOnIdle {
            assertEquals(listOf(false, true), directions)
            assertEquals(listOf(ComicZoomFit.PAGE, ComicZoomFit.WIDTH, ComicZoomFit.FILL), fits)
            assertEquals(1, vertical)
            assertFalse(menu)
        }
    }

    @Test fun newReaderModeAndResetActionsAreReachable() {
        var menu by mutableStateOf(false)
        val actions = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                ComicReaderChrome(comic = comic, index = 1, pageCount = 3, vertical = false,
                    modeMenu = menu, onBack = {}, modifier = Modifier.fillMaxSize(),
                    onModeMenuChange = { menu = it }, onReadingDirection = {}, onVertical = {},
                    onFitChange = {}, onPrevious = {}, onNext = {},
                    onVerticalPager = { actions += "pager"; menu = false },
                    onWebtoon = { actions += "webtoon"; menu = false },
                    onReset = { actions += "reset"; menu = false })
            }
        }
        for (choice in listOf("Vertical pager", "Webtoon", "Reset to global defaults")) {
            compose.onNodeWithContentDescription("Comic reading and zoom options").performClick()
            compose.onNodeWithText(choice).performClick()
        }
        compose.runOnIdle { assertEquals(listOf("pager", "webtoon", "reset"), actions) }
    }

    @Test fun realReaderChangesModeFitAndResetsOverride() {
        val image = png()
        val choices = mutableListOf<ComicViewOverride?>()
        compose.setContent {
            MaterialTheme {
                ComicReader(comic = comic.copy(viewOverride = ComicViewOverride(ComicViewMode.PAGED_RTL, ComicZoomFit.PAGE)),
                    pageNames = { listOf("1.png", "2.png", "3.png") }, pageBytes = { image },
                    preferences = ComicPreferences(), onBack = {}, onPageChange = {},
                    modifier = Modifier.fillMaxSize(), onViewOverride = { choices += it })
            }
        }
        awaitPage()
        compose.onNodeWithContentDescription("Comic page image; pinch to zoom, double tap to magnify")
            .performTouchInput { click(center) }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Comic reading and zoom options").fetchSemanticsNodes().isNotEmpty()
        }
        fun select(label: String) {
            compose.onNodeWithContentDescription("Comic reading and zoom options").performClick()
            compose.onNodeWithText(label).performClick()
        }
        select("Pages · Left to right")
        select("Manga · Right to left")
        select("Vertical pager")
        select("Fit width · pan taller pages")
        select("Continuous vertical scroll")
        select("Webtoon")
        compose.waitUntil(20_000) {
            compose.onAllNodesWithContentDescription("Webtoon page 1").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Next comic page").assertIsNotEnabled()
        select("Reset to global defaults")
        awaitPage()
        compose.runOnIdle {
            assertEquals(listOf(
                ComicViewOverride(ComicViewMode.PAGED_LTR, ComicZoomFit.PAGE),
                ComicViewOverride(ComicViewMode.PAGED_RTL, ComicZoomFit.PAGE),
                ComicViewOverride(ComicViewMode.VERTICAL_PAGER, ComicZoomFit.PAGE),
                ComicViewOverride(ComicViewMode.VERTICAL_PAGER, ComicZoomFit.WIDTH),
                ComicViewOverride(ComicViewMode.CONTINUOUS_VERTICAL, ComicZoomFit.WIDTH),
                ComicViewOverride(ComicViewMode.WEBTOON, ComicZoomFit.WIDTH), null
            ), choices)
        }
    }

    @Test fun verticalPagerTapZonesTurnByHeight() {
        var previous = 0
        var next = 0
        var toggles = 0
        val bitmap = Bitmap.createBitmap(120, 200, Bitmap.Config.ARGB_8888)
        compose.setContent {
            MaterialTheme {
                ZoomableComicPage(bitmap = bitmap, pageKey = "vertical-pager", fit = ComicZoomFit.PAGE,
                    tapToTurn = true, rtl = false, verticalPager = true, onPrevious = { previous++ },
                    onNext = { next++ }, onToggleChrome = { toggles++ }, modifier = Modifier.fillMaxSize())
            }
        }
        val page = compose.onNodeWithContentDescription("Comic page image; pinch to zoom, double tap to magnify")
        page.performTouchInput { click(Offset(center.x, center.y * .24f)) }
        compose.waitUntil(10_000) { previous == 1 }
        page.performTouchInput { click(Offset(center.x, center.y * 1.76f)) }
        compose.waitUntil(10_000) { next == 1 }
        page.performTouchInput { click(center) }
        compose.waitUntil(10_000) { toggles == 1 }
        page.performTouchInput { swipeUp() }
        compose.waitUntil(10_000) { next == 2 }
        page.performTouchInput { swipeDown() }
        compose.waitUntil(10_000) { previous == 2 }
        compose.runOnIdle { assertEquals(2, previous); assertEquals(2, next); assertEquals(1, toggles) }
    }

    @Test fun verticalChromeHasNoPageTurnAndEmptyChromeIsLoading() {
        compose.setContent {
            MaterialTheme {
                ComicReaderChrome(
                    comic = comic, index = 0, pageCount = 0, vertical = true,
                    modeMenu = false, onBack = {}, onModeMenuChange = {},
                    onReadingDirection = {}, onVertical = {}, onFitChange = {},
                    onPrevious = {}, onNext = {}, modifier = Modifier.fillMaxSize()
                )
            }
        }
        compose.onNodeWithText("Loading…").assertExists()
        compose.onNodeWithContentDescription("Previous comic page").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Next comic page").assertIsNotEnabled()
    }

    @Test fun tapToTurnRespectsLeftToRightAndCenterChrome() {
        var previous = 0
        var next = 0
        var toggles = 0
        val bitmap = Bitmap.createBitmap(120, 200, Bitmap.Config.ARGB_8888)
        compose.setContent {
            MaterialTheme {
                ZoomableComicPage(
                    bitmap = bitmap, pageKey = "ltr", fit = ComicZoomFit.PAGE,
                    tapToTurn = true, rtl = false, onPrevious = { previous++ },
                    onNext = { next++ }, onToggleChrome = { toggles++ },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        val page = compose.onNodeWithContentDescription("Comic page image; pinch to zoom, double tap to magnify")
        // detectTapGestures uses onDoubleTap, so onTap fires only after the
        // double-tap timeout. Wait after EACH click to avoid merging two taps.
        page.performTouchInput { click(Offset(center.x * .24f, center.y)) }
        compose.waitUntil(10_000) { previous == 1 }
        page.performTouchInput { click(Offset(center.x * 1.76f, center.y)) }
        compose.waitUntil(10_000) { next == 1 }
        page.performTouchInput { click(center) }
        compose.waitUntil(10_000) { toggles == 1 }
        compose.runOnIdle {
            assertEquals(1, previous)
            assertEquals(1, next)
            assertEquals(1, toggles)
        }
    }

    @Test fun mangaReversesTapDirectionAndDisabledTapToTurnShowsChrome() {
        var previous = 0
        var next = 0
        var toggles = 0
        val bitmap = Bitmap.createBitmap(120, 200, Bitmap.Config.ARGB_8888)
        compose.setContent {
            MaterialTheme {
                ZoomableComicPage(
                    bitmap = bitmap, pageKey = "rtl", fit = ComicZoomFit.WIDTH,
                    tapToTurn = true, rtl = true, onPrevious = { previous++ },
                    onNext = { next++ }, onToggleChrome = { toggles++ },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        val page = compose.onNodeWithContentDescription("Comic page image; pinch to zoom, double tap to magnify")
        page.performTouchInput { click(Offset(center.x * .24f, center.y)) }
        compose.waitUntil(10_000) { next == 1 }
        page.performTouchInput { click(Offset(center.x * 1.76f, center.y)) }
        compose.waitUntil(10_000) { previous == 1 }
        compose.runOnIdle { assertEquals(1, previous); assertEquals(1, next) }
    }

    @Test fun zoomDisablesTurningAndDoubleTapResets() {
        var turns = 0
        var toggles = 0
        val bitmap = Bitmap.createBitmap(120, 200, Bitmap.Config.ARGB_8888)
        compose.setContent {
            MaterialTheme {
                ZoomableComicPage(
                    bitmap = bitmap, pageKey = "zoom", fit = ComicZoomFit.FILL,
                    tapToTurn = true, rtl = false, onPrevious = { turns++ },
                    onNext = { turns++ }, onToggleChrome = { toggles++ },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        val page = compose.onNodeWithContentDescription("Comic page image; pinch to zoom, double tap to magnify")
        page.performTouchInput { doubleClick(center) }
        compose.waitForIdle()
        page.performTouchInput { click(Offset(center.x * 1.8f, center.y)) }
        compose.waitUntil(10_000) { toggles == 1 }
        compose.runOnIdle { assertEquals(0, turns) }
        page.performTouchInput { doubleClick(center) }
        compose.waitForIdle()
        page.performTouchInput { click(Offset(center.x * 1.8f, center.y)) }
        compose.waitUntil(10_000) { turns == 1 }
        compose.runOnIdle { assertEquals(1, turns) }
    }

    @Test fun fullReaderLoadsPagesAndUsesOverlayPageControls() {
        val image = png()
        val changed = mutableListOf<Int>()
        compose.setContent {
            MaterialTheme {
                ComicReader(
                    comic = comic, pageNames = { listOf("1.png", "2.png", "3.png") },
                    pageBytes = { image }, preferences = ComicPreferences(),
                    onBack = {}, onPageChange = { changed += it },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        awaitPage()
        compose.onNodeWithContentDescription("Comic page image; pinch to zoom, double tap to magnify")
            .performTouchInput { click(center) }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("1 / 3").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("1 / 3").assertExists()
        compose.onNodeWithContentDescription("Next comic page").performClick()
        compose.runOnIdle { assertEquals(listOf(1), changed) }
    }

    @Test fun readerDisplaysArchiveFailuresWithoutCrash() {
        compose.setContent {
            MaterialTheme {
                ComicReader(
                    comic = comic, pageNames = { error("Archive unavailable") },
                    pageBytes = { error("Not loaded") }, preferences = ComicPreferences(),
                    onBack = {}, onPageChange = {}
                )
            }
        }
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Archive unavailable").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Archive unavailable").assertExists()
    }

    @Test fun readerShowsDecodeErrorForUnreadableImage() {
        compose.setContent {
            MaterialTheme {
                ComicReader(
                    comic = comic, pageNames = { listOf("damaged.png") },
                    pageBytes = { byteArrayOf(1, 2, 3) }, preferences = ComicPreferences(),
                    onBack = {}, onPageChange = {}
                )
            }
        }
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Comic image dimensions are invalid")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Comic image dimensions are invalid").assertExists()
    }

    @Test fun delayedVerticalReaderRestoresPositionWithoutPrematureScroll() {
        val image = png()
        val release = CountDownLatch(1)
        val changed = mutableListOf<Int>()

        try {
            compose.setContent {
                MaterialTheme {
                    ComicReader(
                        comic = comic.copy(lastPage = 1),
                        pageNames = {
                            check(release.await(20, TimeUnit.SECONDS))
                            listOf("1.png", "2.png", "3.png")
                        },
                        pageBytes = { image },
                        preferences = ComicPreferences(
                            readingMode = ComicReadingMode.VERTICAL_SCROLL
                        ),
                        onBack = {},
                        onPageChange = { changed += it }
                    )
                }
            }

            compose.onNodeWithText("Opening page…").assertExists()

            assertTrue(
                compose.onAllNodesWithContentDescription(
                    "Comic page image; pinch to zoom, double tap to magnify"
                ).fetchSemanticsNodes().isEmpty()
            )
        } finally {
            release.countDown()
        }

        awaitPage()

        compose.runOnIdle {
            assertEquals(
                "Initial loading must not rewrite saved progress",
                emptyList<Int>(), changed
            )
        }

        compose.onAllNodesWithContentDescription(
            "Comic page image; pinch to zoom, double tap to magnify"
        ).onFirst().performTouchInput { click(center) }

        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("2 / 3")
                .fetchSemanticsNodes().isNotEmpty()
        }

        compose.runOnIdle {
            assertTrue(changed.isEmpty())
        }

        compose.onNode(hasScrollAction()).performScrollToIndex(0)

        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("1 / 3")
                .fetchSemanticsNodes().isNotEmpty()
        }

        compose.runOnIdle {
            assertEquals(listOf(0), changed)
        }
    }

    @Test fun verticalReaderRestoresLastPageWithoutBackfill() {
        val image = png()
        val changed = mutableListOf<Int>()

        compose.setContent {
            MaterialTheme {
                ComicReader(
                    comic = comic.copy(lastPage = 2),
                    pageNames = { listOf("1.png", "2.png", "3.png") },
                    pageBytes = { image },
                    preferences = ComicPreferences(
                        readingMode = ComicReadingMode.VERTICAL_SCROLL
                    ),
                    onBack = {},
                    onPageChange = { changed += it }
                )
            }
        }

        awaitPage()

        compose.runOnIdle {
            assertEquals(
                "Restoring the last page must not move progress backwards",
                emptyList<Int>(), changed
            )
        }

        compose.onAllNodesWithContentDescription(
            "Comic page image; pinch to zoom, double tap to magnify"
        ).onFirst().performTouchInput { click(center) }

        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("3 / 3")
                .fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithContentDescription("Next comic page")
            .assertIsNotEnabled()

        compose.runOnIdle {
            assertTrue(changed.isEmpty())
        }

        compose.onNode(hasScrollAction()).performScrollToIndex(0)

        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("1 / 3")
                .fetchSemanticsNodes().isNotEmpty()
        }

        compose.runOnIdle {
            assertEquals(listOf(0), changed)
        }
    }

    @Test fun emptyWebtoonShowsRecoverableArchiveError() {
        compose.setContent {
            MaterialTheme {
                ComicReader(
                    comic = comic,
                    pageNames = { emptyList() },
                    pageBytes = { byteArrayOf() },
                    preferences = ComicPreferences(
                        readingMode = ComicReadingMode.WEBTOON
                    ),
                    onBack = {},
                    onPageChange = {}
                )
            }
        }

        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(
                "No supported comic images were found"
            ).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText(
            "No supported comic images were found"
        ).assertExists()
    }

    @Test fun verticalReaderDisplaysMultiplePagesAndDisablesPaging() {
        val image = png()
        compose.setContent {
            MaterialTheme {
                ComicReader(
                    comic = comic, pageNames = { listOf("1.png", "2.png") },
                    pageBytes = { image },
                    preferences = ComicPreferences(readingMode = ComicReadingMode.VERTICAL_SCROLL,
                        readingDirection = ComicReadingDirection.RIGHT_TO_LEFT),
                    onBack = {}, onPageChange = {}
                )
            }
        }
        awaitPage()
        // Continuous mode deliberately renders multiple identical image nodes.
        // Pick the first visible page rather than requiring exactly one match.
        compose.onAllNodesWithContentDescription(
            "Comic page image; pinch to zoom, double tap to magnify"
        ).onFirst().performTouchInput { click(center) }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Next comic page")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Next comic page").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Previous comic page").assertIsNotEnabled()
    }

    @Test fun disabledTapToTurnShowsChromeInLeftAndRightZones() {
        var previous = 0
        var next = 0
        var toggles = 0
        val bitmap = Bitmap.createBitmap(120, 200, Bitmap.Config.ARGB_8888)
        compose.setContent {
            MaterialTheme {
                ZoomableComicPage(
                    bitmap = bitmap, pageKey = "no-turn", fit = ComicZoomFit.PAGE,
                    tapToTurn = false, rtl = false, onPrevious = { previous++ },
                    onNext = { next++ }, onToggleChrome = { toggles++ },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        val page = compose.onNodeWithContentDescription("Comic page image; pinch to zoom, double tap to magnify")
        page.performTouchInput { click(Offset(center.x * .20f, center.y)) }
        compose.waitUntil(10_000) { toggles == 1 }
        page.performTouchInput { click(Offset(center.x * 1.80f, center.y)) }
        compose.waitUntil(10_000) { toggles == 2 }
        compose.runOnIdle {
            assertEquals(0, previous)
            assertEquals(0, next)
            assertEquals(2, toggles)
        }
    }

    @Test fun emptyReaderShowsRecoverableError() {
        compose.setContent {
            MaterialTheme {
                ComicReader(comic = comic, pageNames = { emptyList() }, pageBytes = { byteArrayOf() },
                    preferences = ComicPreferences(), onBack = {}, onPageChange = {})
            }
        }
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("No supported comic images were found")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("No supported comic images were found").assertExists()
    }
}
