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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.vaachak.reader.core.domain.model.ComicPreferences
import org.vaachak.reader.core.domain.model.ThemeMode
import org.vaachak.reader.core.domain.model.UserProfile
import org.vaachak.reader.leisure.testing.ComposeTestHostActivity
import org.vaachak.reader.leisure.ui.testability.Tid

/** Extra real-screen branches, with no Hilt, secrets, network, or production changes. */
@RunWith(AndroidJUnit4::class)
class SettingsConditionCoverageInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test fun compactBackHandlerReturnsToCategoryChooser() {
        render()
        choose(Tid.Settings.tabBook)
        compose.onNodeWithContentDescription("All settings").assertExists()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("All settings").assertDoesNotExist()
        compose.onNodeWithTag(Tid.Settings.tabGlobal).assertExists()
        choose(Tid.Settings.tabIntelligence)
        compose.onNodeWithText("AI CONFIGURATION").assertExists()
        compose.onNodeWithContentDescription(
            "Gemini Integration, Manage API keys for Global Recall and Smart Summaries"
        ).assertExists()
    }

    @Test fun offlineChangesArePreservedWhenSwitchingBetweenCompactCategories() {
        var offline by mutableStateOf(false)
        render(state = { SettingsUiState(isOfflineMode = offline) }, onOffline = { offline = it })
        choose(Tid.Settings.tabGlobal)
        val toggle = "Offline Mode, Default: On. Cloud features disabled."
        compose.onNodeWithContentDescription(toggle).performScrollTo().performClick()
        compose.runOnIdle { assertTrue(offline) }
        showCategoryChooser()
        choose(Tid.Settings.tabIntelligence)
        compose.onNodeWithContentDescription(
            "Intelligence features are disabled while Offline Mode is active."
        ).assertExists()
        showCategoryChooser()
        choose(Tid.Settings.tabGlobal)
        compose.onNodeWithContentDescription(toggle).performScrollTo().performClick()
        compose.runOnIdle { assertFalse(offline) }
        showCategoryChooser()
        choose(Tid.Settings.tabIntelligence)
        compose.onNodeWithContentDescription(
            "Gemini Integration, Manage API keys for Global Recall and Smart Summaries"
        ).assertExists()
    }

    @Test fun lcdWidePaneSupportsAllCategorySelectionsAndReturnsToGeneral() {
        render(state = { SettingsUiState(themeMode = ThemeMode.LIGHT) }, wide = true)
        val expected = listOf(
            Tid.Settings.tabBook to "READING EXPERIENCE",
            "settings_category_audio" to "SPEECH & NARRATION",
            Tid.Settings.tabContent to "SOURCES",
            Tid.Settings.tabIntelligence to "AI CONFIGURATION",
            "settings_category_comics" to "READING DIRECTION",
            Tid.Settings.tabGlobal to "ACCOUNT & PROFILES"
        )
        expected.forEach { (category, heading) ->
            choose(category)
            compose.onNodeWithText(heading).assertExists()
            compose.onNodeWithTag(Tid.Settings.tabGlobal).assertExists()
        }
    }

    @Test fun activeWidePaneRespondsWhenThemeChangesBetweenLcdAndEink() {
        var theme by mutableStateOf(ThemeMode.LIGHT)
        render(state = { SettingsUiState(themeMode = theme) }, wide = true)
        choose("settings_category_comics")
        compose.onNodeWithText("READING DIRECTION").assertExists()
        compose.runOnIdle { theme = ThemeMode.E_INK }
        compose.onNodeWithText("READING DIRECTION").assertExists()
        choose(Tid.Settings.tabGlobal)
        compose.onNodeWithText("ACCOUNT & PROFILES").assertExists()
        compose.runOnIdle { theme = ThemeMode.LIGHT }
        compose.onNodeWithTag("settings_category_comics").assertExists()
        choose("settings_category_comics")
        compose.onNodeWithText("READING DIRECTION").assertExists()
    }

    @Test fun localMultiUserAndOfflineFlagsRemainIndependentInWideMode() {
        var multiUser by mutableStateOf(false)
        var offline by mutableStateOf(false)
        render(
            state = { SettingsUiState(isOfflineMode = offline) },
            multiUser = { multiUser },
            onMultiUser = { multiUser = it },
            onOffline = { offline = it },
            wide = true
        )
        val multiUserToggle = "Local Multi-User Mode, Isolate libraries for different offline readers"
        val offlineToggle = "Offline Mode, Default: On. Cloud features disabled."
        compose.onNodeWithContentDescription("Active Profile, Default").assertDoesNotExist()
        compose.onNodeWithContentDescription(multiUserToggle).performScrollTo().performClick()
        compose.runOnIdle { assertTrue(multiUser); assertFalse(offline) }
        compose.onNodeWithContentDescription("Active Profile, Default").assertExists()
        compose.onNodeWithContentDescription(offlineToggle).performScrollTo().performClick()
        compose.runOnIdle { assertTrue(multiUser); assertTrue(offline) }
        compose.onNodeWithContentDescription(multiUserToggle).performScrollTo().performClick()
        compose.runOnIdle { assertFalse(multiUser); assertTrue(offline) }
        compose.onNodeWithContentDescription("Active Profile, Default").assertDoesNotExist()
        choose(Tid.Settings.tabIntelligence)
        compose.onNodeWithContentDescription(
            "Intelligence features are disabled while Offline Mode is active."
        ).assertExists()
    }

    @Test fun explicitlySignedOutAccountMaySwitchLocalVault() {
        var switchedTo = ""
        render(
            state = { SettingsUiState(userProfile = UserProfile(username = "offline-user", isAuthenticated = false)) },
            multiUser = { true },
            activeVault = "family",
            onSwitch = { switchedTo = it }
        )
        choose(Tid.Settings.tabGlobal)
        compose.onNodeWithContentDescription("Active Profile, Family").performClick()
        compose.onNodeWithText("Switch Local Profile").assertExists()
        // The entered nonempty vault name must pass through the existing callback.
        compose.onNodeWithText("Profile Name").performTextInput("Guests")
        compose.onNodeWithText("Switch").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals("Guests", switchedTo) }
    }

    private fun choose(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun showCategoryChooser() {
        compose.onNodeWithContentDescription("All settings").performClick()
    }

    private fun render(
        state: () -> SettingsUiState = { SettingsUiState() },
        multiUser: () -> Boolean = { false },
        activeVault: String = "default",
        onMultiUser: (Boolean) -> Unit = {},
        onOffline: (Boolean) -> Unit = {},
        onSwitch: (String) -> Unit = {},
        wide: Boolean = false
    ) {
        val wideDensity = if (wide) {
            compose.activity.resources.displayMetrics.widthPixels.coerceAtLeast(1).toFloat() / 1200f
        } else 1f
        compose.setContent {
            MaterialTheme {
                val content: @androidx.compose.runtime.Composable () -> Unit = {
                    SettingsScreenContent(
                        model = SettingsContentState(
                            settings = state(),
                            activeVaultId = activeVault,
                            isMultiUserMode = multiUser(),
                            comics = ComicPreferences()
                        ),
                        navController = rememberNavController(),
                        actions = SettingsUiActions(
                            saveComicPreferences = {},
                            setMultiUserMode = onMultiUser,
                            switchVault = onSwitch,
                            toggleOfflineMode = onOffline
                        ),
                        modifier = Modifier.fillMaxSize()
                    )
                }
                if (wide) {
                    CompositionLocalProvider(LocalDensity provides Density(wideDensity, 1f)) {
                        content()
                    }
                } else content()
            }
        }
        compose.waitUntil(timeoutMillis = 10_000L) {
            compose.onAllNodesWithTag(Tid.Screen.settings, useUnmergedTree = true)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
    }
}
