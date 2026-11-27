package org.vaachak.reader.leisure.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.vaachak.reader.core.data.repository.AiManagedSlot
import org.vaachak.reader.core.data.repository.AiManagementAction
import org.vaachak.reader.core.data.repository.AiCredentialStatus
import org.vaachak.reader.core.data.repository.GatewayDiagnostic
import org.vaachak.reader.core.data.repository.GatewayDiagnosticReport
import org.vaachak.reader.core.domain.model.AiConfig
import org.vaachak.reader.core.domain.model.AiTextProvider
import org.vaachak.reader.leisure.testing.ComposeTestHostActivity

@RunWith(AndroidJUnit4::class)
class AiConfigDiagnosticsIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test fun onlySavedOnlineGatewayCanBeProbedAndEditsInvalidateEvidence() {
        val base = "https://fixture.tailnet.ts.net:8443/v1"
        var state by mutableStateOf(SettingsUiState(aiConfig = AiConfig(
            textProvider = AiTextProvider.PRIVATE_GATEWAY, gatewayUrl = base, gatewayModel = "local-chat"
        )))
        var calls = 0
        var clears = 0
        var saves = 0
        var provider by mutableStateOf(AiTextProvider.PRIVATE_GATEWAY)
        compose.setContent {
            MaterialTheme {
                androidx.compose.runtime.key(provider) {
                AiConfigForm(state, provider = provider, onBack = {}, statuses = AiManagedSlot.entries.associateWith { AiCredentialStatus.SAVED },
                    onManage = { slot, action, _ ->
                        assertEquals(AiManagedSlot.GATEWAY, slot); assertEquals(AiManagementAction.REMOVE, action); clears++
                    },
                    onSave = { saves++ }, onTestGateway = {
                        calls++
                        GatewayDiagnosticReport(GatewayDiagnostic.MODEL_LISTED)
                    })
                }
            }
        }
        val test = compose.onNodeWithTag("ai_config_gateway_test")
        test.performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText(GatewayDiagnostic.MODEL_LISTED.label).assertExists()
        compose.runOnIdle { assertEquals(1, calls) }
        compose.onNodeWithTag("ai_config_gateway_url").performScrollTo().performTextReplacement("https://other.tailnet.ts.net:8443/v1")
        test.performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("ai_config_gateway_url").performScrollTo().performTextReplacement(base)
        test.performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Not tested in this screen.").assertExists()
        compose.onNodeWithTag("ai_config_gateway_model").performScrollTo().performTextReplacement("different-model")
        test.performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("ai_config_gateway_model").performScrollTo().performTextReplacement("local-chat")
        compose.onNodeWithTag("ai_credential_gateway_draft").performScrollTo().performTextReplacement("new-fixture-token")
        test.performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("ai_credential_gateway_draft").performScrollTo().performTextReplacement("")
        test.performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("ai_credential_gateway_remove").performScrollTo().performClick()
        compose.onNodeWithText("Remove", substring = false).performClick()
        compose.onNodeWithText("Not tested in this screen.").assertExists()
        compose.runOnIdle { assertEquals(1, clears); assertEquals(2, calls); state = state.copy(isOfflineMode = true) }
        test.performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(isOfflineMode = false) }
        test.performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("ai_config_save").performScrollTo().performClick()
        compose.onNodeWithText("Not tested in this screen.").assertExists()
        compose.runOnIdle { assertEquals(1, saves); assertEquals(3, calls) }
        compose.runOnIdle { provider = AiTextProvider.GEMINI }
        test.assertDoesNotExist()
        compose.runOnIdle { provider = AiTextProvider.PRIVATE_GATEWAY }
        test.performScrollTo().assertIsEnabled()
        compose.runOnIdle { assertEquals(3, calls) }
    }
}
