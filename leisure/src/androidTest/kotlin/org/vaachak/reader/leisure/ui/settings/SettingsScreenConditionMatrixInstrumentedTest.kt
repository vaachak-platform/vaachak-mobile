package org.vaachak.reader.leisure.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.vaachak.reader.core.domain.model.ComicPageFit
import org.vaachak.reader.core.domain.model.ComicPreferences
import org.vaachak.reader.core.domain.model.ComicReadingDirection
import org.vaachak.reader.core.domain.model.ComicReadingMode
import org.vaachak.reader.core.domain.model.ThemeMode
import org.vaachak.reader.core.domain.model.UserProfile
import org.vaachak.reader.leisure.testing.ComposeTestHostActivity
import org.vaachak.reader.leisure.ui.testability.Tid

/**
 * Data-driven coverage of actual SettingsScreen.kt Compose conditions.
 *
 * Production composables, callback contracts and view models are unchanged.
 * This suite explicitly exercises 144 wide category states, 36 compact category
 * states and all 128 combinations of the seven comic preference fields.
 * Those are input scenarios, not a prediction of Sonar's covered-condition count.
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenConditionMatrixInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    private val sections = listOf(
        Tid.Settings.tabGlobal to "ACCOUNT & PROFILES",
        Tid.Settings.tabBook to "READING EXPERIENCE",
        "settings_category_comics" to "READING DIRECTION",
        "settings_category_audio" to "SPEECH & NARRATION",
        Tid.Settings.tabContent to "SOURCES",
        Tid.Settings.tabIntelligence to "AI CONFIGURATION"
    )

    @Test fun wideCategoryMatrixExercisesThemeOfflineMultiUserAndAuthenticationBranches() {
        var current by mutableStateOf(SettingsUiState())
        var multiUser by mutableStateOf(false)
        render(targetWidthDp = 1280f, state = { current }, multiUser = { multiUser })

        var cases = 0
        for (theme in ThemeMode.entries) {
            for (offline in listOf(false, true)) {
                for (multipleReaders in listOf(false, true)) {
                    for (signedIn in listOf(false, true)) {
                        compose.runOnIdle {
                            current = SettingsUiState(
                                themeMode = theme,
                                isOfflineMode = offline,
                                userProfile = UserProfile(username = "reader", isAuthenticated = signedIn)
                            )
                            multiUser = multipleReaders
                        }
                        for ((tag, heading) in sections) {
                            choose(tag)
                            if (tag == Tid.Settings.tabIntelligence && offline) {
                                compose.onNodeWithContentDescription(
                                    "Intelligence features are disabled while Offline Mode is active."
                                ).assertExists()
                            } else {
                                compose.onNodeWithText(heading).assertExists()
                            }
                            if (tag == Tid.Settings.tabGlobal) {
                                val profile = compose.onNodeWithContentDescription("Active Profile, Local")
                                if (multipleReaders) profile.assertExists() else profile.assertDoesNotExist()
                            }
                            // A two-pane view keeps the chooser mounted for every category.
                            compose.onNodeWithTag(Tid.Settings.tabGlobal).assertExists()
                            cases++
                        }
                    }
                }
            }
        }
        assertEquals(144, cases)
    }

    @Test fun compactCategoryMatrixExercisesBackNavigationAndAllThemes() {
        var current by mutableStateOf(SettingsUiState())
        // Explicitly force a compact viewport even on Samsung/BOOX tablets.
        render(targetWidthDp = 410f, fontScale = 1.45f, state = { current })

        var cases = 0
        for (theme in ThemeMode.entries) {
            for (offline in listOf(false, true)) {
                compose.runOnIdle { current = SettingsUiState(themeMode = theme, isOfflineMode = offline) }
                for ((tag, heading) in sections) {
                    choose(tag)
                    if (tag == Tid.Settings.tabIntelligence && offline) {
                        compose.onNodeWithContentDescription(
                            "Intelligence features are disabled while Offline Mode is active."
                        ).assertExists()
                    } else {
                        compose.onNodeWithText(heading).assertExists()
                    }
                    compose.onNodeWithContentDescription("All settings").performClick()
                    compose.onNodeWithTag(Tid.Settings.tabGlobal).assertExists()
                    compose.onNodeWithContentDescription("All settings").assertDoesNotExist()
                    cases++
                }
            }
        }
        assertEquals(36, cases)
    }

    @Test fun everyComicPreferenceCombinationRendersAndActionsKeepAllOtherFields() {
        var preferences by mutableStateOf(ComicPreferences())
        render(targetWidthDp = 1280f, comics = { preferences }, onComics = { preferences = it })
        choose("settings_category_comics")

        // Seven independent binary choices: three enum pairs plus four switches.
        // Rendering all 2^7 combinations drives both true and false outcomes of
        // each equality/toggle condition in ComicsSettingsTab.
        for (mask in 0 until 128) {
            val scenario = ComicPreferences(
                readingDirection = if (mask and 1 != 0) ComicReadingDirection.RIGHT_TO_LEFT
                    else ComicReadingDirection.LEFT_TO_RIGHT,
                readingMode = if (mask and 2 != 0) ComicReadingMode.VERTICAL_SCROLL
                    else ComicReadingMode.SINGLE_PAGE,
                pageFit = if (mask and 4 != 0) ComicPageFit.FIT_WIDTH
                    else ComicPageFit.FIT_PAGE,
                tapToTurn = mask and 8 != 0,
                useVolumeKeys = mask and 16 != 0,
                preloadNextPage = mask and 32 != 0,
                reduceMotion = mask and 64 != 0
            )
            compose.runOnIdle { preferences = scenario }
            compose.onNodeWithText("READING DIRECTION").assertExists()
            compose.onNodeWithText(
                if (scenario.readingDirection == ComicReadingDirection.RIGHT_TO_LEFT)
                    "Right to left (manga)" else "Left to right"
            ).assertExists()
            compose.onNodeWithText("Reduce motion for e-ink").assertExists()
        }

        // Last matrix input is mask 127 (all switches on). Verify real callbacks,
        // not just composition, and show unrelated fields are preserved.
        val before = preferences
        compose.onNodeWithText("Reduce motion for e-ink").performScrollTo().performClick()
        compose.runOnIdle {
            assertFalse(preferences.reduceMotion)
            assertEquals(before.copy(reduceMotion = false), preferences)
        }
        compose.onNodeWithText("Use volume keys for page turns").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(preferences.useVolumeKeys) }
        compose.onNodeWithText("Preload next page").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(preferences.preloadNextPage) }
        compose.onNodeWithText("Tap edges to turn pages").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(preferences.tapToTurn) }
        compose.onNodeWithText("Left to right").performScrollTo().performClick()
        compose.onNodeWithText("One page at a time").performScrollTo().performClick()
        compose.onNodeWithText("Fit whole page").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(ComicReadingDirection.LEFT_TO_RIGHT, preferences.readingDirection)
            assertEquals(ComicReadingMode.SINGLE_PAGE, preferences.readingMode)
            assertEquals(ComicPageFit.FIT_PAGE, preferences.pageFit)
            assertFalse(preferences.tapToTurn)
            assertFalse(preferences.useVolumeKeys)
            assertFalse(preferences.preloadNextPage)
            assertFalse(preferences.reduceMotion)
        }
    }

    @Test fun largeFontWideLayoutKeepsChooserAndDetailVisible() {
        // The policy's large-font threshold is 920 dp, rather than 720 dp.
        render(targetWidthDp = 1280f, fontScale = 1.5f)
        compose.onNodeWithText("ACCOUNT & PROFILES").assertExists()
        compose.onNodeWithTag(Tid.Settings.tabGlobal).assertExists()
        choose("settings_category_comics")
        compose.onNodeWithText("READING DIRECTION").assertExists()
        compose.onNodeWithTag(Tid.Settings.tabGlobal).assertExists()
    }

    private fun choose(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun render(
        targetWidthDp: Float,
        fontScale: Float = 1f,
        state: () -> SettingsUiState = { SettingsUiState() },
        multiUser: () -> Boolean = { false },
        comics: () -> ComicPreferences = { ComicPreferences() },
        onComics: (ComicPreferences) -> Unit = {}
    ) {
        val pixels = compose.activity.resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val density = pixels.toFloat() / targetWidthDp
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                MaterialTheme {
                    SettingsScreenContent(
                        model = SettingsContentState(
                            settings = state(),
                            activeVaultId = "local",
                            isMultiUserMode = multiUser(),
                            comics = comics()
                        ),
                        navController = rememberNavController(),
                        actions = SettingsUiActions(
                            saveComicPreferences = onComics,
                            setMultiUserMode = {},
                            switchVault = {},
                            toggleOfflineMode = {}
                        ),
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        compose.waitUntil(timeoutMillis = 10_000L) {
            compose.onAllNodesWithTag(Tid.Screen.settings, useUnmergedTree = true)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
    }
}
