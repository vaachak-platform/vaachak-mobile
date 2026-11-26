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
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
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
import org.vaachak.reader.core.domain.model.UserProfile

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
        compose.onNodeWithText("Audiobook defaults are independent of text-to-speech. Title overrides are kept until reset.").assertExists()
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
        compose.onNodeWithText("Tap edges to turn pages").performScrollTo().performClick()
        compose.onNodeWithText("Preload next page").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(ComicReadingDirection.RIGHT_TO_LEFT, current.readingDirection)
            assertEquals(ComicReadingMode.VERTICAL_SCROLL, current.readingMode)
            assertEquals(ComicPageFit.FIT_WIDTH, current.pageFit)
            assertTrue(current.useVolumeKeys)
            assertFalse(current.reduceMotion)
            assertFalse(current.tapToTurn)
            assertFalse(current.preloadNextPage)
        }
        compose.onNodeWithText("Vertical pager").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ComicReadingMode.VERTICAL_PAGER, current.readingMode) }
        compose.onNodeWithText("Webtoon").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ComicReadingMode.WEBTOON, current.readingMode) }
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

    @Test fun comicSwitchesRoundTripToOriginalValues() {
        var current by mutableStateOf(ComicPreferences())
        val original = current
        render(comics = { current }, onComics = { current = it })
        selectCategory("Comics")
        val labels = listOf(
            "Tap edges to turn pages",
            "Use volume keys for page turns",
            "Preload next page",
            "Reduce motion for e-ink"
        )
        labels.forEach { title ->
            compose.onNodeWithText(title).performScrollTo().performClick()
        }
        compose.runOnIdle { assertTrue(current != original) }
        labels.forEach { title ->
            compose.onNodeWithText(title).performScrollTo().performClick()
        }
        compose.runOnIdle { assertEquals(original, current) }
    }

    @Test fun widePaneKeepsCategoryChooserVisibleAcrossEverySection() {
        render(wide = true)
        compose.onNodeWithText("ACCOUNT & PROFILES").assertExists()
        val sections = listOf(
            Tid.Settings.tabBook to "READING EXPERIENCE",
            "settings_category_comics" to "READING DIRECTION",
            "settings_category_audio" to "SPEECH & NARRATION",
            Tid.Settings.tabContent to "SOURCES",
            Tid.Settings.tabIntelligence to "AI CONFIGURATION",
            Tid.Settings.tabGlobal to "ACCOUNT & PROFILES"
        )
        sections.forEach { (tag, expectedHeading) ->
            compose.onNodeWithTag(tag).performScrollTo().performClick()
            compose.onNodeWithText(expectedHeading).assertExists()
            compose.onNodeWithTag(Tid.Settings.tabGlobal).assertExists()
        }
    }

    @Test fun wideGeneralOfflineModeCanBeTurnedOnAndBackOff() {
        var offline by mutableStateOf(false)
        render(
            state = { SettingsUiState(isOfflineMode = offline) },
            wide = true,
            onOffline = { offline = it }
        )
        val description = "Offline Mode, Default: On. Cloud features disabled."
        compose.onNodeWithContentDescription(description).performScrollTo().performClick()
        compose.runOnIdle { assertTrue(offline) }
        compose.onNodeWithContentDescription(description).performScrollTo().performClick()
        compose.runOnIdle { assertFalse(offline) }
        compose.onNodeWithTag(Tid.Settings.tabGlobal).assertExists()
    }

    @Test fun whitespaceOnlyProfileNameDoesNotSwitchVault() {
        var switchedTo = ""
        render(
            isMultiUser = true,
            activeVault = "test_profile",
            onSwitchVault = { switchedTo = it }
        )
        selectCategory("General")
        compose.onNodeWithContentDescription("Active Profile, Test_profile").performClick()
        compose.onNodeWithText("Profile Name").performTextInput("   ")
        compose.onNodeWithText("Switch").performClick()
        compose.runOnIdle { assertEquals("", switchedTo) }
        compose.onNodeWithText("Switch Local Profile").assertDoesNotExist()
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

    @Test fun cloudAccountMustLogOutBeforeSwitchingLocalProfile() {
        var switchedTo = ""
        render(
            state = { SettingsUiState(userProfile = UserProfile(username = "reader", isAuthenticated = true)) },
            isMultiUser = true,
            activeVault = "test_profile",
            onSwitchVault = { switchedTo = it }
        )
        selectCategory("General")
        compose.onNodeWithContentDescription("Active Profile, Test_profile").performClick()
        compose.onNodeWithText("Cloud Account Active").assertIsDisplayed()
        compose.onNodeWithText("To switch local profiles safely, please log out of your current cloud account first to prevent mixing sync data.").assertExists()
        compose.onNodeWithText("OK").performClick()
        compose.runOnIdle { assertEquals("", switchedTo) }
    }

    @Test fun localProfileDialogAcceptsNonblankName() {
        var switchedTo = ""
        render(isMultiUser = true, activeVault = "test_profile", onSwitchVault = { switchedTo = it })
        selectCategory("General")
        compose.onNodeWithContentDescription("Active Profile, Test_profile").performClick()
        compose.onNodeWithText("Profile Name").performTextInput("Evening Reader")
        compose.onNodeWithText("Switch").performClick()
        compose.runOnIdle { assertEquals("Evening Reader", switchedTo) }
    }

    @Test fun multiUserToggleRevealsAndHidesLocalProfileControls() {
        var multiUser by mutableStateOf(false)
        render(
            multiUserState = { multiUser },
            onMultiUser = { multiUser = it }
        )
        selectCategory("General")
        compose.onNodeWithContentDescription("Active Profile, Default").assertDoesNotExist()
        compose.onNodeWithContentDescription("Local Multi-User Mode, Isolate libraries for different offline readers").performClick()
        compose.runOnIdle { assertTrue(multiUser) }
        compose.onNodeWithContentDescription("Active Profile, Default").assertExists()
        compose.onNodeWithContentDescription("Local Multi-User Mode, Isolate libraries for different offline readers").performClick()
        compose.runOnIdle { assertFalse(multiUser) }
        compose.onNodeWithContentDescription("Active Profile, Default").assertDoesNotExist()
    }

    @Test fun lcdThemeKeepsSettingsCategoriesFunctional() {
        render(SettingsUiState(themeMode = ThemeMode.LIGHT))
        selectCategory("Books & reading")
        compose.onNodeWithText("READING EXPERIENCE").assertExists()
        compose.onNodeWithContentDescription("All settings").performClick()
        selectCategory("Library & content")
        compose.onNodeWithText("SOURCES").assertExists()
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
        // Both must be visible *without choosing a category*: this distinguishes
        // the real two-pane layout from compact mode on a narrow CI emulator.
        compose.onNodeWithTag(Tid.Settings.tabGlobal).assertIsDisplayed()
        compose.onNodeWithText("ACCOUNT & PROFILES").assertIsDisplayed()
        // Changing the detail pane must leave the navigation pane visible.
        compose.onNodeWithTag("settings_category_comics").performClick()
        compose.onNodeWithTag(Tid.Settings.tabGlobal).assertIsDisplayed()
        compose.onNodeWithTag("settings_category_comics").assertExists()
        compose.onNodeWithText("READING DIRECTION").assertIsDisplayed()
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
        multiUserState: () -> Boolean = { isMultiUser },
        activeVault: String = "default",
        onComics: (ComicPreferences) -> Unit = {},
        onMultiUser: (Boolean) -> Unit = {},
        onOffline: (Boolean) -> Unit = {},
        onSwitchVault: (String) -> Unit = {},
        wide: Boolean = false
    ) {
        // Device hardware profiles differ between Samsung, BOOX and the generic
        // GitHub Actions AVD. Translate the actual screen width into a stable
        // ~1200 dp Compose viewport only for the wide-layout test. The regular
        // tests keep the device's real density and screen constraints.
        val wideTestDensity = if (wide) {
            compose.activity.resources.displayMetrics.widthPixels
                .coerceAtLeast(1).toFloat() / 1200f
        } else {
            1f
        }
        compose.setContent {
            MaterialTheme {
                val screen: @androidx.compose.runtime.Composable () -> Unit = {
                    SettingsScreenContent(
                        model = SettingsContentState(
                            settings = state(),
                            activeVaultId = activeVault,
                            isMultiUserMode = multiUserState(),
                            comics = comics()
                        ),
                        navController = rememberNavController(),
                        actions = SettingsUiActions(
                            saveComicPreferences = onComics,
                            setMultiUserMode = onMultiUser,
                            switchVault = onSwitchVault,
                            toggleOfflineMode = onOffline
                        ),
                        modifier = Modifier.fillMaxSize()
                    )
                }
                if (wide) {
                    // Only this test synthesizes a wide DP viewport; the host
                    // Activity, AVD display and production layout remain unchanged.
                    CompositionLocalProvider(
                        LocalDensity provides Density(wideTestDensity, 1f)
                    ) { screen() }
                } else {
                    screen()
                }
            }
        }

        // The host Activity can reach RESUMED before a Compose root is attached
        // on a busy device. Synchronize to the *real production Settings root*
        // once per test instead of racing the first SemanticsNodeInteraction.
        // atLeastOneRootRequired=false lets this bounded wait handle a root that
        // has not been attached yet, but still fails if the UI never appears.
        compose.waitUntil(timeoutMillis = 10_000L) {
            compose.onAllNodesWithTag(Tid.Screen.settings, useUnmergedTree = true)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isNotEmpty()
        }
    }
}
