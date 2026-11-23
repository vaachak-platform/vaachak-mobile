package org.vaachak.reader.leisure.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.vaachak.reader.leisure.testing.ComposeTestHostActivity
import org.vaachak.reader.leisure.ui.testability.Tid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.vaachak.reader.core.domain.model.ComicPageFit
import org.vaachak.reader.core.domain.model.ComicPreferences
import org.vaachak.reader.core.domain.model.ComicReadingDirection
import org.vaachak.reader.core.domain.model.ComicReadingMode
import org.vaachak.reader.core.domain.model.ThemeMode

/** Instrument the actual production Settings screen; callbacks replace Hilt and persistent storage. */
@RunWith(AndroidJUnit4::class)
class SettingsComposeInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test fun compactCategoriesExposeComicsAndAudioWithoutWrappedTabs() {
        render(SettingsUiState(themeMode = ThemeMode.E_INK))
        compose.onNodeWithTag(Tid.Settings.tabGlobal).assertExists()
        selectCategory("Comics")
        compose.onNodeWithText("READING DIRECTION").assertExists()
        compose.onNodeWithText("Right to left (manga)").assertExists()
        compose.onNodeWithContentDescription("All settings").performClick()
        selectCategory("Audio")
        compose.onNodeWithText("Audiobook playback settings will be added with the audiobook engine.").assertExists()
    }

    @Test fun comicsChoicesUpdatePerVaultPreferences() {
        var current by mutableStateOf(ComicPreferences())
        render(comics = { current }, onComics = { current = it })
        selectCategory("Comics")
        compose.onNodeWithText("Right to left (manga)").performScrollTo().performClick()
        compose.onNodeWithText("Continuous vertical scroll").performScrollTo().performClick()
        compose.onNodeWithText("Fit page width").performScrollTo().performClick()
        compose.onNodeWithText("Use volume keys for page turns").performScrollTo().performClick()
        compose.onNodeWithText("Reduce motion for e-ink").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(ComicReadingDirection.RIGHT_TO_LEFT, current.readingDirection)
            assertEquals(ComicReadingMode.VERTICAL_SCROLL, current.readingMode)
            assertEquals(ComicPageFit.FIT_WIDTH, current.pageFit)
            assertTrue(current.useVolumeKeys)
            assertFalse(current.reduceMotion)
        }
        // Exercise reverse choices and preserve the explicit user setting.
        compose.onNodeWithText("Left to right").performScrollTo().performClick()
        compose.onNodeWithText("One page at a time").performScrollTo().performClick()
        compose.onNodeWithText("Fit whole page").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(ComicReadingDirection.LEFT_TO_RIGHT, current.readingDirection)
            assertEquals(ComicReadingMode.SINGLE_PAGE, current.readingMode)
            assertEquals(ComicPageFit.FIT_PAGE, current.pageFit)
        }
    }

    @Test fun generalSettingsSupportOfflineModeAndLocalProfileDialog() {
        var offline by mutableStateOf(false)
        var switchedTo = ""
        render(
            state = { SettingsUiState(isOfflineMode = offline) },
            isMultiUser = true,
            activeVault = "test_profile",
            onOffline = { offline = it },
            onSwitchVault = { switchedTo = it }
        )
        selectCategory("General")
        compose.onNodeWithContentDescription("Offline Mode, Default: On. Cloud features disabled.")
            .performClick()
        compose.runOnIdle { assertTrue(offline) }
        compose.onNodeWithContentDescription("Active Profile, Test_profile").performClick()
        compose.onNodeWithText("Switch Local Profile").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithContentDescription("Active Profile, Test_profile").performClick()
        compose.onNodeWithText("Switch").performClick()
        compose.runOnIdle { assertEquals("", switchedTo) }
    }

    @Test fun intelligenceShowsOfflineMessageAndOnlineConfiguration() {
        var offline by mutableStateOf(true)
        render(state = { SettingsUiState(isOfflineMode = offline) })
        selectCategory("Intelligence")
        compose.onNodeWithContentDescription("Intelligence features are disabled while Offline Mode is active.")
            .assertExists()
        compose.runOnIdle { offline = false }
        compose.onNodeWithContentDescription(
            "Gemini Integration, Manage API keys for Global Recall and Smart Summaries"
        ).assertExists()
    }

    @Test fun wideEinkSettingsShowsCategoryListAndDetailsTogether() {
        render(
            state = { SettingsUiState(themeMode = ThemeMode.E_INK) },
            wide = true
        )
        // In two-pane mode, the category chooser and General detail are both visible.
        compose.onNodeWithTag(Tid.Settings.tabGlobal).assertExists()
        compose.onNodeWithContentDescription("Offline Mode, Default: On. Cloud features disabled.")
            .assertExists()
        // Switching details must keep the category chooser mounted.
        compose.onNodeWithTag("settings_category_comics").performClick()
        compose.onNodeWithTag(Tid.Settings.tabGlobal).assertExists()
        compose.onNodeWithTag("settings_category_comics").assertExists()
        compose.onNodeWithText("Right to left (manga)").assertExists()
    }

    @Test fun booksAndContentCategoriesKeepTheirExistingRoutesVisible() {
        render()
        selectCategory("Books & reading")
        compose.onNodeWithContentDescription(
            "Default Book Appearance, Fonts, margins, publisher styles, page color"
        ).assertExists()
        compose.onNodeWithContentDescription("All settings").performClick()
        selectCategory("Library & content")
        compose.onNodeWithContentDescription("Manage Catalogs, Add or remove OPDS feeds").assertExists()
    }

    private fun selectCategory(label: String) {
        // Vaachak's .tid() sets both testTag and contentDescription. A subsequent
        // semantics modifier can change which description is exposed after merging,
        // but the tag remains the stable test contract.
        val tag = when (label) {
            "General" -> Tid.Settings.tabGlobal
            "Books & reading" -> Tid.Settings.tabBook
            "Comics" -> "settings_category_comics"
            "Audio" -> "settings_category_audio"
            "Library & content" -> Tid.Settings.tabContent
            "Intelligence" -> Tid.Settings.tabIntelligence
            else -> error("Unknown Settings category: $label")
        }
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun render(
        initialState: SettingsUiState = SettingsUiState(),
        state: () -> SettingsUiState = { initialState },
        comics: () -> ComicPreferences = { ComicPreferences() },
        isMultiUser: Boolean = false,
        activeVault: String = "default",
        onComics: (ComicPreferences) -> Unit = {},
        onOffline: (Boolean) -> Unit = {},
        onSwitchVault: (String) -> Unit = {},
        wide: Boolean = false
    ) {
        compose.setContent {
            MaterialTheme {
                val screen: @androidx.compose.runtime.Composable () -> Unit = {
                    SettingsScreenContent(
                        model = SettingsContentState(
                            settings = state(),
                            activeVaultId = activeVault,
                            isMultiUserMode = isMultiUser,
                            comics = comics()
                        ),
                        navController = rememberNavController(),
                        actions = SettingsUiActions(
                            saveComicPreferences = onComics,
                            setMultiUserMode = {},
                            switchVault = onSwitchVault,
                            toggleOfflineMode = onOffline
                        ),
                        modifier = Modifier.fillMaxSize()
                    )
                }
                if (wide) {
                    // Simulate a high-density large e-reader viewport without changing device state.
                    CompositionLocalProvider(LocalDensity provides Density(0.75f, 1f)) { screen() }
                } else {
                    screen()
                }
            }
        }
    }
}
