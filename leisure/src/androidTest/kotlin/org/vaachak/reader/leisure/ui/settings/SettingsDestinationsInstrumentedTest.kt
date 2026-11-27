package org.vaachak.reader.leisure.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.vaachak.reader.core.domain.model.AiConfig
import org.vaachak.reader.core.domain.model.AiTextProvider
import org.vaachak.reader.core.domain.model.ComicPreferences
import org.vaachak.reader.leisure.navigation.Screen
import org.vaachak.reader.leisure.testing.ComposeTestHostActivity
import org.vaachak.reader.leisure.ui.testability.Tid

/** P0-10: exercise the production settings screen and its real navigation callbacks. */
@RunWith(AndroidJUnit4::class)
class SettingsDestinationsInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()
    private var navController: NavHostController? = null

    @Test fun allSixDestinationsRemainReachableWithStableAutomationIds() {
        render()
        val categories = listOf(
            Tid.Settings.tabGlobal to "General",
            Tid.Settings.tabContent to "Library & Sync",
            Tid.Settings.tabBook to "Reading & TTS",
            "settings_category_comics" to "Comics",
            "settings_category_audio" to "Audiobooks",
            Tid.Settings.tabIntelligence to "AI & Providers"
        )
        categories.forEach { (tag, label) ->
            compose.onNodeWithTag(tag).assertExists()
            // Unmerged text confirms the user-facing category label independently
            // of the stable semantic tag used by existing Maestro tests.
            assertTrue(
                "Missing settings destination $label",
                compose.onAllNodesWithText(label, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            )
        }
    }

    @Test fun libraryAndSyncContainsExistingRoutes() {
        render()
        select(Tid.Settings.tabContent)
        compose.onNodeWithText("SOURCES").assertExists()
        compose.onNodeWithContentDescription(
            "Library Settings (Visuals), E-ink optimization, Cover Styles, Smart Stacks"
        ).assertExists()
        compose.onNodeWithContentDescription(
            "Manage Catalogs, Add or remove OPDS feeds"
        ).assertExists()
        compose.onNodeWithContentDescription(
            "Sync Configuration, Manage end-to-end encrypted vaults"
        ).performScrollTo().performClick()
        compose.onNodeWithText("Destination: ${Screen.SyncSettings.route}").assertExists()
        compose.runOnIdle { assertTrue(requireNotNull(navController).popBackStack()) }
        select(Tid.Settings.tabContent)
        compose.onNodeWithContentDescription(
            "Manage Catalogs, Add or remove OPDS feeds"
        ).performScrollTo().performClick()
        compose.onNodeWithText("Destination: ${Screen.CatalogManage.route}").assertExists()
    }

    @Test fun providerConfigurationRemainsReachableWithoutEnablingNetworkAi() {
        render(offline = true)
        select(Tid.Settings.tabIntelligence)
        compose.onNodeWithContentDescription(
            "Intelligence features are disabled while Offline Mode is active."
        ).assertExists()
        assertProviderMenu()
        compose.onNodeWithContentDescription("External Providers, Gemini text and Cloudflare Visualize").performScrollTo().performClick()
        compose.onNodeWithText("External Providers").assertExists()
        compose.onNodeWithTag("ai_config_gemini_panel").assertExists()
        compose.onNodeWithTag("ai_config_cloudflare_panel").assertExists()
        compose.onNodeWithTag("ai_config_internal_panel").assertDoesNotExist()
        compose.onNodeWithTag("ai_config_provider_gateway").assertDoesNotExist()
        compose.runOnIdle { assertTrue(requireNotNull(navController).popBackStack()) }
        select(Tid.Settings.tabIntelligence)
        assertProviderMenu()
        compose.onNodeWithContentDescription("Internal — AI-G732, Private Tailscale text gateway").performScrollTo().performClick()
        compose.onNodeWithText("Internal — AI-G732").assertExists()
        compose.onNodeWithTag("ai_config_internal_panel").assertExists()
        compose.onNodeWithTag("ai_config_gemini_panel").assertDoesNotExist()
        compose.onNodeWithTag("ai_config_cloudflare_panel").assertDoesNotExist()
        compose.onNodeWithTag("ai_config_auto_save").assertDoesNotExist()
        compose.onNodeWithTag("ai_config_provider_gemini").assertDoesNotExist()
    }

    private fun assertProviderMenu() {
        compose.onNodeWithContentDescription("External Providers, Gemini text and Cloudflare Visualize").assertExists()
        compose.onNodeWithContentDescription("Internal — AI-G732, Private Tailscale text gateway").assertExists()
        compose.onNodeWithContentDescription("Gemini Integration, Manage API keys for Global Recall and Smart Summaries").assertDoesNotExist()
        compose.onNodeWithContentDescription("Cloudflare Visualize, Configure the direct image provider").assertDoesNotExist()
        compose.onNodeWithContentDescription("Private Text Gateway, Optional self-hosted text provider settings").assertDoesNotExist()
    }

    private fun select(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun render(offline: Boolean = false) {
        val widthPixels = compose.activity.resources.displayMetrics.widthPixels.coerceAtLeast(1)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(widthPixels.toFloat() / 1280f, 1f)) {
                MaterialTheme {
                    val controller = rememberNavController()
                    navController = controller
                    NavHost(navController = controller, startDestination = Screen.Settings.route) {
                        composable(Screen.Settings.route) {
                            SettingsScreenContent(
                                model = SettingsContentState(
                                    settings = SettingsUiState(isOfflineMode = offline),
                                    activeVaultId = "local",
                                    isMultiUserMode = false,
                                    comics = ComicPreferences()
                                ),
                                navController = controller,
                                actions = SettingsUiActions(
                                    saveComicPreferences = {},
                                    setMultiUserMode = {},
                                    switchVault = {},
                                    toggleOfflineMode = {}
                                ),
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        listOf(
                            Screen.SyncSettings.route,
                            Screen.Bookshelf.route,
                            Screen.CatalogManage.route
                        ).forEach { route ->
                            composable(route) { Text("Destination: $route") }
                        }
                        // Use the production route registration and real editor with a fixed saved
                        // provider, deliberately different from the External destination.
                        aiProviderDestinations(controller) { provider, back ->
                            AiConfigForm(
                                state = SettingsUiState(isOfflineMode = offline,
                                    aiConfig = AiConfig(textProvider = AiTextProvider.PRIVATE_GATEWAY)),
                                provider = provider, onBack = back, statuses = emptyMap(),
                                onManage = { _, _, _ -> error("Unexpected credential operation") },
                                onSave = { error("Opening a configuration page must not save") }
                            )
                        }
                    }
                }
            }
        }
        compose.waitUntil(timeoutMillis = 10_000L) {
            compose.onAllNodesWithTag(Tid.Screen.settings, useUnmergedTree = true)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
    }
}
