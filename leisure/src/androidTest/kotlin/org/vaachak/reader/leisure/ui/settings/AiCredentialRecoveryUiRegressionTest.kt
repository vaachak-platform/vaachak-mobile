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
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.vaachak.reader.core.data.repository.AiCredentialStatus
import org.vaachak.reader.core.data.repository.AiManagedSlot
import org.vaachak.reader.core.data.repository.AiManagementAction
import org.vaachak.reader.leisure.testing.ComposeTestHostActivity

/** Synthetic UI operations; never touches real credentials or simulates OS auth success. */
@RunWith(AndroidJUnit4::class)
class AiCredentialRecoveryUiRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test fun everyStatusReportsTruthfulPresenceAndExplicitRecovery() {
        var status by mutableStateOf<AiCredentialStatus?>(null)
        var busy by mutableStateOf(false)
        val actions = mutableListOf<AiManagementAction>()
        compose.setContent {
            MaterialTheme {
                AiCredentialPanel(AiManagedSlot.GEMINI, status, busy, { busy = it }, {}) { action, _ ->
                    actions.add(action)
                }
            }
        }
        val statusNode = compose.onNodeWithTag("ai_credential_gemini_status")
        compose.onNodeWithText("Checking saved credential…").assertExists()
        compose.onNodeWithTag("ai_credential_gemini_replace").assertIsNotEnabled()
        for ((value, message) in listOf(
            AiCredentialStatus.MISSING to "Not configured",
            AiCredentialStatus.SAVED to "Saved (not yet verified with provider)",
            AiCredentialStatus.UNAVAILABLE to "Saved but unavailable. Unlock your secure device.",
            AiCredentialStatus.RECOVERY_REQUIRED to "Recovery required. Replace or remove explicitly.",
            AiCredentialStatus.REMOVAL_PENDING to "Removal incomplete. Confirm removal again to finish cleanup."
        )) {
            compose.runOnIdle { status = value }
            statusNode.assertExists()
            compose.onNodeWithText(message).assertExists()
        }
        compose.runOnIdle { status = AiCredentialStatus.RECOVERY_REQUIRED }
        compose.onNodeWithTag("ai_credential_gemini_replace").assertIsNotEnabled()
        compose.onNodeWithTag("ai_credential_gemini_draft").performTextReplacement("synthetic-recovery")
        compose.onNodeWithTag("ai_credential_gemini_replace").assertIsEnabled().performClick()
        compose.onNodeWithText("Replace", substring = false).performClick()
        compose.onNodeWithText("Credential saved.").assertExists()
        compose.onNodeWithTag("ai_credential_gemini_remove").performClick()
        compose.onNodeWithText("Keep", substring = false).performClick()
        compose.runOnIdle { assertEquals(listOf(AiManagementAction.REPLACE), actions) }
        compose.onNodeWithTag("ai_credential_gemini_remove").performClick()
        compose.onNodeWithText("Remove", substring = false).performClick()
        compose.onNodeWithText("Credential removed.").assertExists()
        compose.runOnIdle { assertEquals(listOf(AiManagementAction.REPLACE, AiManagementAction.REMOVE), actions) }
    }

    @Test fun failedReplacementClearsDraftAndShowsSafeMessage() {
        var busy by mutableStateOf(false)
        var dirty = false
        var submitted = 0
        compose.setContent {
            MaterialTheme {
                AiCredentialPanel(AiManagedSlot.CLOUDFLARE, AiCredentialStatus.MISSING, busy,
                    { busy = it }, { dirty = it }) { action, value ->
                    assertEquals(AiManagementAction.REPLACE, action)
                    assertEquals("synthetic-replacement", value)
                    submitted++
                    throw IllegalStateException("synthetic sensitive error")
                }
            }
        }
        val replace = compose.onNodeWithTag("ai_credential_cloudflare_replace")
        replace.assertIsNotEnabled()
        compose.onNodeWithTag("ai_credential_cloudflare_draft").performTextReplacement("synthetic-replacement")
        compose.runOnIdle { assertTrue(dirty) }
        replace.assertIsEnabled().performClick()
        compose.onNodeWithText("Operation not completed. Check status; unlock the device or retry recovery.").assertExists()
        compose.onNodeWithText("synthetic sensitive error").assertDoesNotExist()
        replace.assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, submitted); assertFalse(dirty); assertFalse(busy) }
    }
}
