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
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.vaachak.reader.core.data.repository.GatewayDiagnostic
import org.vaachak.reader.core.data.repository.GatewayDiagnosticReport
import org.vaachak.reader.leisure.testing.ComposeTestHostActivity

@RunWith(AndroidJUnit4::class)
class GatewayDiagnosticsComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test fun explicitProbeIsDisabledForUnsavedOrOfflineSettingsAndRetryIsManual() {
        var enabled by mutableStateOf(false)
        var calls = 0
        compose.setContent {
            MaterialTheme {
                GatewayDiagnosticsPanel(enabled) {
                    calls++
                    GatewayDiagnosticReport(GatewayDiagnostic.MODEL_LISTED)
                }
            }
        }
        val action = compose.onNodeWithTag("ai_config_gateway_test")
        action.assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, calls); enabled = true }
        action.assertIsEnabled().performClick()
        compose.onNodeWithText(GatewayDiagnostic.MODEL_LISTED.label).assertExists()
        compose.runOnIdle { assertEquals(1, calls) }
        action.performClick()
        compose.runOnIdle { assertEquals(2, calls); enabled = false }
        action.assertIsNotEnabled()
        compose.onNodeWithText(GatewayDiagnostic.MODEL_LISTED.label).assertDoesNotExist()
        compose.runOnIdle { enabled = true }
        compose.onNodeWithText("Not tested in this screen.").assertExists()
        compose.runOnIdle { assertEquals(2, calls) }
    }
}
