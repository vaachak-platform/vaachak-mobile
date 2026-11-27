package org.vaachak.reader.leisure.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
import org.vaachak.reader.core.data.repository.AiManagedSlot
import org.vaachak.reader.core.data.repository.AiManagementAction
import org.vaachak.reader.core.data.repository.AiCredentialStatus
import org.vaachak.reader.core.domain.model.AiTextProvider
import org.vaachak.reader.core.domain.model.ThemeMode

/** Exercises the same AI form as production without a real API key, network or Hilt graph. */
@RunWith(AndroidJUnit4::class)
class AiConfigComposeInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()
    private var openedProvider by mutableStateOf<AiTextProvider?>(null)

    private fun openProvider(provider: AiTextProvider) {
        compose.runOnIdle { openedProvider = provider }
    }

    @Test fun geminiFormCanBeEditedAndSaved() {
        var submitted: AiConfigSaveRequest? = null
        val managed = mutableMapOf<AiManagedSlot, String>()
        render(onSave = { submitted = it }, statuses = AiManagedSlot.entries.associateWith { AiCredentialStatus.MISSING },
            onManage = { slot, action, value ->
            assertEquals(AiManagementAction.REPLACE, action); managed[slot] = value
        })
        compose.onNodeWithTag("ai_credential_gemini_draft").performScrollTo().performTextReplacement("test-key-not-real")
        compose.onNodeWithTag("ai_credential_gemini_replace").performScrollTo().performClick()
        compose.onNodeWithTag("ai_config_cloudflare_url").performScrollTo().performTextReplacement("https://example.invalid/worker")
        compose.onNodeWithTag("ai_credential_cloudflare_draft").performScrollTo().performTextReplacement("example-token")
        compose.onNodeWithTag("ai_credential_cloudflare_replace").performScrollTo().performClick()
        compose.onNodeWithTag("ai_config_auto_save").performScrollTo().performClick()
        compose.onNodeWithTag("ai_config_save").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("test-key-not-real", managed[AiManagedSlot.GEMINI])
            assertEquals("", submitted?.geminiKey)
            assertEquals("https://example.invalid/worker", submitted?.cloudflareUrl)
            assertEquals("example-token", managed[AiManagedSlot.CLOUDFLARE])
            assertEquals("", submitted?.cloudflareToken)
            assertTrue(submitted?.autoSaveRecaps == true)
            assertEquals(AiTextProvider.GEMINI, submitted?.gateway?.provider)
        }
    }

    @Test fun invalidPrivateGatewayIsRejectedBeforeSaving() {
        var saves = 0
        render(onSave = { saves++ })
        openProvider(AiTextProvider.PRIVATE_GATEWAY)
        compose.onNodeWithTag("ai_config_gateway_url").performScrollTo().performTextReplacement("http://public.example.com/v1")
        compose.onNodeWithTag("ai_config_save").performScrollTo().performClick()
        compose.onNodeWithText("Use a private Tailscale HTTPS hostname").assertExists()
        compose.runOnIdle { assertEquals(0, saves) }
    }

    @Test fun validPrivateGatewaySavesModelAndCredentialWithoutDisplayingIt() {
        var submitted: AiConfigSaveRequest? = null
        var replacement = ""
        render(onSave = { submitted = it }, onManage = { slot, action, value ->
            assertEquals(AiManagedSlot.GATEWAY, slot); assertEquals(AiManagementAction.REPLACE, action); replacement = value
        })
        openProvider(AiTextProvider.PRIVATE_GATEWAY)
        compose.onNodeWithTag("ai_config_gateway_url").performScrollTo().performTextReplacement("https://reader.demo.ts.net:8443/v1")
        compose.onNodeWithTag("ai_config_gateway_model").performScrollTo().performTextReplacement("local-chat")
        compose.onNodeWithTag("ai_credential_gateway_draft").performScrollTo().performTextReplacement("test-token-only")
        compose.onNodeWithTag("ai_credential_gateway_replace").performScrollTo().performClick()
        compose.onNodeWithText("Replace", substring = false).performClick()
        compose.onNodeWithTag("ai_config_save").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(AiTextProvider.PRIVATE_GATEWAY, submitted?.gateway?.provider)
            assertEquals("local-chat", submitted?.gateway?.model)
            assertEquals("test-token-only", replacement)
            assertEquals("", submitted?.gateway?.token)
        }
    }

    @Test fun cancelReplacementKeepsOriginalAndDoesNotInvokeManagement() {
        var calls = 0
        render(onManage = { _, _, _ -> calls++ })
        compose.onNodeWithTag("ai_credential_gemini_draft").performScrollTo().performTextReplacement("synthetic-replacement")
        compose.onNodeWithTag("ai_credential_gemini_replace").performScrollTo().performClick()
        compose.onNodeWithText("Replace saved credential?").assertExists()
        compose.onNodeWithText("Keep", substring = false).performClick()
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onNodeWithTag("ai_credential_gemini_replace").performScrollTo().performClick()
        compose.onNodeWithText("Replace", substring = false).performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun maskingAndRemoveSavedKeyUseSeparateCallbacks() {
        var cleared = 0
        render(state = { SettingsUiState(themeMode = ThemeMode.E_INK) }, onManage = { slot, action, value ->
            assertEquals(AiManagedSlot.GATEWAY, slot); assertEquals(AiManagementAction.REMOVE, action)
            assertEquals("", value); cleared++
        })
        compose.onNodeWithTag("ai_credential_gemini_mask").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Hide new draft").assertExists()
        compose.onNodeWithTag("ai_credential_gemini_mask").performScrollTo().performClick()
        openProvider(AiTextProvider.PRIVATE_GATEWAY)
        compose.onNodeWithTag("ai_credential_gateway_remove").performScrollTo().performClick()
        compose.onNodeWithText("Keep", substring = false).performClick()
        compose.runOnIdle { assertEquals(0, cleared) }
        compose.onNodeWithTag("ai_credential_gateway_remove").performScrollTo().performClick()
        compose.onNodeWithText("Remove", substring = false).performClick()
        compose.runOnIdle { assertEquals(1, cleared) }
    }

    @Test fun invalidGatewayModelIsBlockedWithAReadableError() {
        var saves = 0
        render(onSave = { saves++ })
        openProvider(AiTextProvider.PRIVATE_GATEWAY)
        compose.onNodeWithTag("ai_config_gateway_url")
            .performScrollTo().performTextReplacement("https://reader.demo.ts.net:8443/v1")
        compose.onNodeWithTag("ai_config_gateway_model").performScrollTo().performTextReplacement("invalid model with spaces")
        compose.onNodeWithTag("ai_config_save").performScrollTo().performClick()
        compose.onNodeWithText("Invalid model alias").assertExists()
        compose.runOnIdle { assertEquals(0, saves) }
    }

    @Test fun switchingBackToGeminiBypassesRejectedGatewayInput() {
        var submitted: AiConfigSaveRequest? = null
        render(onSave = { submitted = it })
        openProvider(AiTextProvider.PRIVATE_GATEWAY)
        compose.onNodeWithTag("ai_config_gateway_url")
            .performScrollTo().performTextReplacement("http://invalid.example/v1")
        openProvider(AiTextProvider.GEMINI)
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
        compose.onNodeWithTag("ai_credential_gateway_draft").assertExists()
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
            assertEquals("", submitted?.geminiKey)
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
        onManage: suspend (AiManagedSlot, AiManagementAction, String) -> Unit = { _, _, _ -> },
        statuses: Map<AiManagedSlot, AiCredentialStatus> = AiManagedSlot.entries.associateWith { AiCredentialStatus.SAVED },
    ) {
        compose.setContent {
            MaterialTheme {
                val current = state()
                val provider = openedProvider ?: current.aiConfig.textProvider
                androidx.compose.runtime.key(provider) {
                    AiConfigForm(
                        state = current,
                        provider = provider,
                        onBack = {},
                        statuses = statuses,
                        onManage = onManage,
                        onSave = onSave
                    )
                }
            }
        }
    }
}
