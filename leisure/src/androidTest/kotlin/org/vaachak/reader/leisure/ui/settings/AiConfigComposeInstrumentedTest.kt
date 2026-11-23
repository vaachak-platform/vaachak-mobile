package org.vaachak.reader.leisure.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.vaachak.reader.leisure.testing.ComposeTestHostActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.vaachak.reader.core.domain.model.AiTextProvider
import org.vaachak.reader.core.domain.model.ThemeMode

/** Exercises the same AI form as production without a real API key, network or Hilt graph. */
@RunWith(AndroidJUnit4::class)
class AiConfigComposeInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test fun geminiFormCanBeEditedAndSaved() {
        var submitted: AiConfigSaveRequest? = null
        render(onSave = { submitted = it })
        compose.onNodeWithTag("ai_config_gemini_key").performTextReplacement("test-key-not-real")
        compose.onNodeWithTag("ai_config_cloudflare_url").performTextReplacement("https://example.invalid/worker")
        compose.onNodeWithTag("ai_config_cloudflare_token").performTextReplacement("example-token")
        compose.onNodeWithTag("ai_config_auto_save").performScrollTo().performClick()
        compose.onNodeWithTag("ai_config_save").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("test-key-not-real", submitted?.geminiKey)
            assertEquals("https://example.invalid/worker", submitted?.cloudflareUrl)
            assertEquals("example-token", submitted?.cloudflareToken)
            assertTrue(submitted?.autoSaveRecaps == true)
            assertEquals(AiTextProvider.GEMINI, submitted?.gateway?.provider)
        }
    }

    @Test fun invalidPrivateGatewayIsRejectedBeforeSaving() {
        var saves = 0
        render(onSave = { saves++ })
        compose.onNodeWithTag("ai_config_provider_gateway").performClick()
        compose.onNodeWithTag("ai_config_gateway_url").performTextReplacement("http://public.example.com/v1")
        compose.onNodeWithTag("ai_config_save").performScrollTo().performClick()
        compose.onNodeWithText("Use a private Tailscale HTTPS hostname").assertExists()
        compose.runOnIdle { assertEquals(0, saves) }
    }

    @Test fun validPrivateGatewaySavesModelAndCredentialWithoutDisplayingIt() {
        var submitted: AiConfigSaveRequest? = null
        render(onSave = { submitted = it })
        compose.onNodeWithTag("ai_config_provider_gateway").performClick()
        compose.onNodeWithTag("ai_config_gateway_url")
            .performTextReplacement("https://reader.demo.ts.net:8443/v1")
        compose.onNodeWithTag("ai_config_gateway_model").performTextReplacement("local-chat")
        compose.onNodeWithTag("ai_config_gateway_key").performTextReplacement("test-token-only")
        compose.onNodeWithTag("ai_config_save").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(AiTextProvider.PRIVATE_GATEWAY, submitted?.gateway?.provider)
            assertEquals("local-chat", submitted?.gateway?.model)
            assertEquals("test-token-only", submitted?.gateway?.token)
            assertFalse(submitted?.gateway?.token.isNullOrBlank())
        }
    }

    @Test fun maskingAndRemoveSavedKeyUseSeparateCallbacks() {
        var masked by mutableStateOf(true)
        var cleared = 0
        render(
            state = { SettingsUiState(isAiMasked = masked, themeMode = ThemeMode.E_INK) },
            onMask = { masked = it },
            onClear = { cleared++ }
        )
        compose.onNodeWithTag("ai_config_toggle_mask").performClick()
        compose.runOnIdle { assertFalse(masked) }
        compose.onNodeWithTag("ai_config_toggle_mask").performClick()
        compose.runOnIdle { assertTrue(masked) }
        compose.onNodeWithTag("ai_config_provider_gateway").performClick()
        compose.onNodeWithText("Remove saved Vaachak gateway key")
            .performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, cleared) }
    }

    @Test fun invalidGatewayModelIsBlockedWithAReadableError() {
        var saves = 0
        render(onSave = { saves++ })
        compose.onNodeWithTag("ai_config_provider_gateway").performClick()
        compose.onNodeWithTag("ai_config_gateway_url")
            .performTextReplacement("https://reader.demo.ts.net:8443/v1")
        compose.onNodeWithTag("ai_config_gateway_model").performTextReplacement("invalid model with spaces")
        compose.onNodeWithTag("ai_config_save").performScrollTo().performClick()
        compose.onNodeWithText("Invalid model alias").assertExists()
        compose.runOnIdle { assertEquals(0, saves) }
    }

    @Test fun switchingBackToGeminiBypassesRejectedGatewayInput() {
        var submitted: AiConfigSaveRequest? = null
        render(onSave = { submitted = it })
        compose.onNodeWithTag("ai_config_provider_gateway").performClick()
        compose.onNodeWithTag("ai_config_gateway_url")
            .performTextReplacement("http://invalid.example/v1")
        compose.onNodeWithTag("ai_config_provider_gemini").performClick()
        compose.onNodeWithTag("ai_config_gateway_url").assertDoesNotExist()
        compose.onNodeWithTag("ai_config_save").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(AiTextProvider.GEMINI, submitted?.gateway?.provider) }
    }

    @Test fun existingGatewaySettingsCanBeSavedWithoutReplacingStoredCredential() {
        var submitted: AiConfigSaveRequest? = null
        render(
            state = { SettingsUiState(aiConfig = org.vaachak.reader.core.domain.model.AiConfig(
                textProvider = AiTextProvider.PRIVATE_GATEWAY,
                gatewayUrl = "https://reader.demo.ts.net:8443/v1",
                gatewayModel = "local-chat"
            )) },
            onSave = { submitted = it }
        )
        compose.onNodeWithTag("ai_config_gateway_key").assertExists()
        compose.onNodeWithTag("ai_config_save").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(AiTextProvider.PRIVATE_GATEWAY, submitted?.gateway?.provider)
            assertEquals("https://reader.demo.ts.net:8443/v1", submitted?.gateway?.url)
            assertEquals("local-chat", submitted?.gateway?.model)
            assertEquals("", submitted?.gateway?.token)
        }
    }

    @Test fun autosaveCanBeTurnedOffWithoutChangingOtherAiFields() {
        var submitted: AiConfigSaveRequest? = null
        render(
            state = { SettingsUiState(aiConfig = org.vaachak.reader.core.domain.model.AiConfig(
                geminiKey = "example-nonsecret-key", autoSaveRecaps = true
            )) },
            onSave = { submitted = it }
        )
        compose.onNodeWithTag("ai_config_auto_save").performScrollTo().performClick()
        compose.onNodeWithTag("ai_config_save").performScrollTo().performClick()
        compose.runOnIdle {
            assertFalse(submitted?.autoSaveRecaps ?: true)
            assertEquals("example-nonsecret-key", submitted?.geminiKey)
        }
    }

    @Test fun serverErrorIsVisibleUntilStateClearsIt() {
        var error by mutableStateOf<String?>("Could not save AI configuration")
        render(state = { SettingsUiState(errorMessage = error) })
        compose.onNodeWithText("Could not save AI configuration").assertExists()
        compose.runOnIdle { error = null }
        compose.onNodeWithText("Could not save AI configuration").assertDoesNotExist()
    }

    private fun render(
        state: () -> SettingsUiState = { SettingsUiState() },
        onSave: (AiConfigSaveRequest) -> Unit = {},
        onClear: () -> Unit = {},
        onMask: (Boolean) -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                AiConfigForm(
                    state = state(),
                    onBack = {},
                    onMaskChange = onMask,
                    onClearGatewayKey = onClear,
                    onSave = onSave
                )
            }
        }
    }
}
