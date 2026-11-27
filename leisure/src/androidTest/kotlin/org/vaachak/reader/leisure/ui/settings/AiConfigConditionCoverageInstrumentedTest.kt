package org.vaachak.reader.leisure.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.vaachak.reader.core.data.repository.AiManagedSlot
import org.vaachak.reader.core.data.repository.AiCredentialStatus
import org.vaachak.reader.core.domain.model.AiConfig
import org.vaachak.reader.core.domain.model.AiTextProvider
import org.vaachak.reader.core.domain.model.ThemeMode
import org.vaachak.reader.leisure.testing.ComposeTestHostActivity
import org.vaachak.reader.leisure.ui.testability.Tid

/** Additional AI form condition tests without actual gateway or Gemini requests. */
@RunWith(AndroidJUnit4::class)
class AiConfigConditionCoverageInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()
    private var openedProvider by mutableStateOf<AiTextProvider?>(null)

    private fun openProvider(provider: AiTextProvider) {
        compose.runOnIdle { openedProvider = provider }
    }

    @Test fun invalidGatewayThenCorrectedEndpointCanBeSaved() {
        val submissions = mutableListOf<AiConfigSaveRequest>()
        render(onSave = { submissions.add(it) })
        openProvider(AiTextProvider.PRIVATE_GATEWAY)
        compose.onNodeWithTag("ai_config_gateway_url").performScrollTo().performTextReplacement("http://public.example.org/v1")
        compose.onNodeWithTag("ai_config_save").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(submissions.isEmpty()) }
        compose.onNodeWithTag("ai_config_gateway_url")
            .performScrollTo().performTextReplacement("https://reader.demo.ts.net:8443/v1")
        compose.onNodeWithTag("ai_config_gateway_model").performScrollTo().performTextReplacement("local-chat")
        compose.onNodeWithTag("ai_config_save").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, submissions.size)
            assertEquals(AiTextProvider.PRIVATE_GATEWAY, submissions.single().gateway.provider)
            assertEquals("https://reader.demo.ts.net:8443/v1", submissions.single().gateway.url)
            assertEquals("local-chat", submissions.single().gateway.model)
            assertEquals("", submissions.single().gateway.token)
        }
    }

    @Test fun emptyGatewayModelCannotSubmitUntilRepaired() {
        val submissions = mutableListOf<AiConfigSaveRequest>()
        render(onSave = { submissions.add(it) })
        openProvider(AiTextProvider.PRIVATE_GATEWAY)
        compose.onNodeWithTag("ai_config_gateway_url")
            .performScrollTo().performTextReplacement("https://reader.demo.ts.net:8443/v1")
        compose.onNodeWithTag("ai_config_gateway_model").performScrollTo().performTextReplacement("")
        compose.onNodeWithTag("ai_config_save").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(submissions.isEmpty()) }
        compose.onNodeWithTag("ai_config_gateway_model").performScrollTo().performTextReplacement("safe-model-1")
        compose.onNodeWithTag("ai_config_save").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, submissions.size)
            assertEquals("safe-model-1", submissions.single().gateway.model)
        }
    }

    @Test fun updatedViewModelConfigurationRefreshesEditableFields() {
        var state by mutableStateOf(
            SettingsUiState(aiConfig = AiConfig(
                geminiKey = "initial-test-value",
                cloudflareUrl = "https://example.invalid/old",
                authToken = "initial-token",
                autoSaveRecaps = false
            ))
        )
        var submission: AiConfigSaveRequest? = null
        render(state = { state }, onSave = { submission = it })
        compose.runOnIdle {
            state = state.copy(aiConfig = AiConfig(
                geminiKey = "new-test-value",
                cloudflareUrl = "https://example.invalid/new",
                authToken = "replacement-token",
                autoSaveRecaps = true
            ))
        }
        compose.waitForIdle() // Wait for LaunchedEffect(state.aiConfig) to reset the form.
        compose.onNodeWithTag("ai_config_save").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("", submission?.geminiKey)
            assertEquals("https://example.invalid/new", submission?.cloudflareUrl)
            assertEquals("", submission?.cloudflareToken)
            assertTrue(submission?.autoSaveRecaps == true)
        }
    }

    @Test fun headerBackAndMaskStateRespondAcrossBothThemes() {
        var theme by mutableStateOf(ThemeMode.E_INK)
        var backClicks = 0
        render(
            state = { SettingsUiState(themeMode = theme) },
            onBack = { backClicks++ }
        )
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("ai_credential_gemini_mask").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, backClicks) }
        compose.onNodeWithContentDescription("Hide new draft").assertExists()
        compose.runOnIdle { theme = ThemeMode.LIGHT }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("ai_credential_gemini_mask").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(2, backClicks) }
        compose.onNodeWithContentDescription("Hide new draft").assertDoesNotExist()
    }

    private fun render(
        state: () -> SettingsUiState = { SettingsUiState() },
        onSave: (AiConfigSaveRequest) -> Unit = {},
        onBack: () -> Unit = {}
    ) {
        compose.setContent {
            MaterialTheme {
                val current = state()
                val provider = openedProvider ?: current.aiConfig.textProvider
                androidx.compose.runtime.key(provider) {
                    AiConfigForm(
                        state = current,
                        provider = provider,
                        onBack = onBack,
                        statuses = AiManagedSlot.entries.associateWith { AiCredentialStatus.SAVED },
                        onManage = { _, _, _ -> },
                        onSave = onSave
                    )
                }
            }
        }
        compose.waitUntil(timeoutMillis = 10_000L) {
            compose.onAllNodesWithTag(Tid.Screen.aiConfig, useUnmergedTree = true)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
    }
}
