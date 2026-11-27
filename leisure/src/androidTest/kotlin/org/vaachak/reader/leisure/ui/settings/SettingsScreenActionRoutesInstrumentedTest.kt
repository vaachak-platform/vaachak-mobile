package org.vaachak.reader.leisure.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.vaachak.reader.core.domain.model.ComicPreferences
import org.vaachak.reader.core.domain.model.UserProfile
import org.vaachak.reader.leisure.navigation.Screen
import org.vaachak.reader.leisure.testing.ComposeTestHostActivity
import org.vaachak.reader.leisure.ui.testability.Tid

/** Exercise real SettingsScreen.kt dialog branches, tile actions and navigation routes. */
@RunWith(AndroidJUnit4::class)
class SettingsScreenActionRoutesInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test fun profileDialogsExerciseCloudGuardCancelBlankInputSwitchAndMultiUserToggle() {
        var signedIn by mutableStateOf(true)
        var multiUser by mutableStateOf(true)
        var offline by mutableStateOf(false)
        val switched = mutableListOf<String>()
        val density = wideTestDensity()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
                MaterialTheme {
                    SettingsScreenContent(
                        model = SettingsContentState(
                            settings = SettingsUiState(
                                userProfile = UserProfile(username = "reader", isAuthenticated = signedIn),
                                isOfflineMode = offline
                            ),
                            activeVaultId = "family",
                            isMultiUserMode = multiUser,
                            comics = ComicPreferences()
                        ),
                        navController = rememberNavController(),
                        actions = SettingsUiActions(
                            saveComicPreferences = {},
                            setMultiUserMode = { multiUser = it },
                            switchVault = { switched.add(it) },
                            toggleOfflineMode = { offline = it }
                        ),
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        awaitSettingsRoot()

        val profile = "Active Profile, Family"
        val multi = "Local Multi-User Mode, Isolate libraries for different offline readers"
        val network = "Offline Mode, Default: On. Cloud features disabled."

        compose.onNodeWithContentDescription(profile).performScrollTo().performClick()
        compose.onNodeWithText("Cloud Account Active").assertExists()
        compose.onNodeWithText("OK").performClick()
        compose.runOnIdle { assertTrue(switched.isEmpty()); signedIn = false }

        compose.onNodeWithContentDescription(profile).performScrollTo().performClick()
        compose.onNodeWithText("Switch Local Profile").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue(switched.isEmpty()) }

        compose.onNodeWithContentDescription(profile).performScrollTo().performClick()
        compose.onNodeWithText("Profile Name").performTextInput("   ")
        compose.onNodeWithText("Switch").performClick()
        compose.runOnIdle { assertTrue(switched.isEmpty()) }

        compose.onNodeWithContentDescription(profile).performScrollTo().performClick()
        compose.onNodeWithText("Profile Name").performTextInput("Guest Library")
        compose.onNodeWithText("Switch").performClick()
        compose.runOnIdle { assertEquals(listOf("Guest Library"), switched) }

        compose.onNodeWithContentDescription(multi).performScrollTo().performClick()
        compose.runOnIdle { assertFalse(multiUser) }
        compose.onNodeWithContentDescription(profile).assertDoesNotExist()
        compose.onNodeWithContentDescription(multi).performScrollTo().performClick()
        compose.runOnIdle { assertTrue(multiUser) }
        compose.onNodeWithContentDescription(profile).assertExists()

        compose.onNodeWithContentDescription(network).performScrollTo().performClick()
        compose.runOnIdle { assertTrue(offline) }
        compose.onNodeWithContentDescription(network).performScrollTo().performClick()
        compose.runOnIdle { assertFalse(offline) }
    }

    private data class RouteCase(val category: String, val tile: String, val route: String)

    @Test fun eachSettingsTileNavigatesToItsRegisteredDestination() {
        // A tiny real NavHost catches wrong route strings; plain rememberNavController
        // without a graph cannot safely exercise SettingsTile onClick callbacks.
        val routes = listOf(
            RouteCase(Tid.Settings.tabGlobal,
                "Reader Profiles, Manage local profiles and credentials", "reader_profiles"),
            RouteCase(Tid.Settings.tabGlobal,
                "Sync Configuration, Manage end-to-end encrypted vaults", Screen.SyncSettings.route),
            RouteCase(Tid.Settings.tabGlobal,
                "App-wide Theme, E-ink vs LCD, Sharpness", "settings/app_appearance"),
            RouteCase(Tid.Settings.tabBook,
                "Library Settings (Visuals), E-ink optimization, Cover Styles, Smart Stacks", Screen.Bookshelf.route),
            RouteCase(Tid.Settings.tabBook,
                "Default Book Appearance, Fonts, margins, publisher styles, page color", "settings/appearance"),
            RouteCase(Tid.Settings.tabBook,
                "Speech & Narration, Language, Speed, Pitch, Sleep-Timer", "settings/tts"),
            RouteCase(Tid.Settings.tabBook,
                "Dictionary Settings, Manage local and custom dictionaries", "settings/dictionary"),
            RouteCase("settings_category_audio",
                "Text-to-speech preferences, Voice, reading speed, pitch and sleep timer", Screen.TTS.route),
            RouteCase(Tid.Settings.tabContent,
                "Manage Catalogs, Add or remove OPDS feeds", "catalog_manage"),
            RouteCase(Tid.Settings.tabIntelligence,
                "External Providers, Gemini text and Cloudflare Visualize", Screen.AiConfig.route),
            RouteCase(Tid.Settings.tabIntelligence,
                "Internal — AI-G732, Private Tailscale text gateway", Screen.InternalAiConfig.route)
        )
        var controller: NavHostController? = null
        val density = wideTestDensity()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
                MaterialTheme {
                    val navController = rememberNavController()
                    controller = navController
                    NavHost(navController = navController, startDestination = Screen.Settings.route) {
                        composable(Screen.Settings.route) {
                            SettingsScreenContent(
                                model = SettingsContentState(
                                    settings = SettingsUiState(isOfflineMode = false),
                                    activeVaultId = "local",
                                    isMultiUserMode = false,
                                    comics = ComicPreferences()
                                ),
                                navController = navController,
                                actions = SettingsUiActions(
                                    saveComicPreferences = {},
                                    setMultiUserMode = {},
                                    switchVault = {},
                                    toggleOfflineMode = {}
                                ),
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        routes.map { it.route }.distinct().forEach { destination ->
                            composable(destination) { Text("Arrived at $destination") }
                        }
                    }
                }
            }
        }
        awaitSettingsRoot()

        for (case in routes) {
            compose.onNodeWithTag(case.category).performScrollTo().performClick()
            compose.onNodeWithContentDescription(case.tile).performScrollTo().performClick()
            compose.onNodeWithText("Arrived at ${case.route}").assertExists()
            compose.runOnIdle { assertTrue(controller!!.popBackStack()) }
            compose.onNodeWithTag(Tid.Settings.tabGlobal).assertExists()
        }
    }

    private fun wideTestDensity(): Float =
        compose.activity.resources.displayMetrics.widthPixels.coerceAtLeast(1).toFloat() / 1280f

    private fun awaitSettingsRoot() {
        compose.waitUntil(timeoutMillis = 10_000L) {
            compose.onAllNodesWithTag(Tid.Screen.settings, useUnmergedTree = true)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
    }
}
