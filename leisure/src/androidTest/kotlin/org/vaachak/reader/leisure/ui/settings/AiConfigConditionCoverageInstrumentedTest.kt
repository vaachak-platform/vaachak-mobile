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
import org.vaachak.reader.core.domain.model.AiConfig
import org.vaachak.reader.core.domain.model.AiTextProvider
import org.vaachak.reader.core.domain.model.ThemeMode
import org.vaachak.reader.leisure.testing.ComposeTestHostActivity
import org.vaachak.reader.leisure.ui.testability.Tid

/** Additional AI form condition tests without actual gateway or Gemini requests. */
@RunWith(AndroidJUnit4::class)
class AiConfigConditionCoverageInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test fun invalidGatewayThenCorrectedEndpointCanBeSaved() {
        val submissions = mutableListOf<AiConfigSaveRequest>()
        render(onSave = { submissions.add(it) })
        compose.onNodeWithTag("ai_config_provider_gateway").performClick()
        compose.onNodeWithTag("ai_config_gateway_url").performTextReplacement("http://public.example.org/v1")
        compose.onNodeWithTag("ai_config_save").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(submissions.isEmpty()) }
        compose.onNodeWithTag("ai_config_gateway_url")
            .performTextReplacement("https://reader.demo.ts.net:8443/v1")
        compose.onNodeWithTag("ai_config_gateway_model").performTextReplacement("local-chat")
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
        compose.onNodeWithTag("ai_config_provider_gateway").performClick()
        compose.onNodeWithTag("ai_config_gateway_url")
            .performTextReplacement("https://reader.demo.ts.net:8443/v1")
        compose.onNodeWithTag("ai_config_gateway_model").performTextReplacement("")
        compose.onNodeWithTag("ai_config_save").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(submissions.isEmpty()) }
        compose.onNodeWithTag("ai_config_gateway_model").performTextReplacement("safe-model-1")
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
            assertEquals("new-test-value", submission?.geminiKey)
            assertEquals("https://example.invalid/new", submission?.cloudflareUrl)
            assertEquals("replacement-token", submission?.cloudflareToken)
            assertTrue(submission?.autoSaveRecaps == true)
        }
    }

    @Test fun headerBackAndMaskStateRespondAcrossBothThemes() {
        var theme by mutableStateOf(ThemeMode.E_INK)
        var masked by mutableStateOf(false)
        var backClicks = 0
        render(
            state = { SettingsUiState(themeMode = theme, isAiMasked = masked) },
            onBack = { backClicks++ },
            onMask = { masked = it }
        )
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("ai_config_toggle_mask").performClick()
        compose.runOnIdle { assertEquals(1, backClicks); assertTrue(masked) }
        compose.runOnIdle { theme = ThemeMode.LIGHT }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("ai_config_toggle_mask").performClick()
        compose.runOnIdle { assertEquals(2, backClicks); assertFalse(masked) }
    }

    private fun render(
        state: () -> SettingsUiState = { SettingsUiState() },
        onSave: (AiConfigSaveRequest) -> Unit = {},
        onMask: (Boolean) -> Unit = {},
        onBack: () -> Unit = {}
    ) {
        compose.setContent {
            MaterialTheme {
                AiConfigForm(
                    state = state(),
                    onBack = onBack,
                    onMaskChange = onMask,
                    onClearGatewayKey = {},
                    onSave = onSave
                )
            }
        }
        compose.waitUntil(timeoutMillis = 10_000L) {
            compose.onAllNodesWithTag(Tid.Screen.aiConfig, useUnmergedTree = true)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
    }
}
