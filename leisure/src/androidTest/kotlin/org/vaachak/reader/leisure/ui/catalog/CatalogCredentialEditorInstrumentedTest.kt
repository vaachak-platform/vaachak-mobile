package org.vaachak.reader.leisure.ui.catalog

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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.vaachak.reader.core.data.repository.CatalogCredentialAction
import org.vaachak.reader.core.domain.model.OpdsEntity
import org.vaachak.reader.leisure.testing.ComposeTestHostActivity
import org.vaachak.reader.leisure.ui.testability.Tid

/** Tests the actual editor, using synthetic input and no storage/network. */
@RunWith(AndroidJUnit4::class)
class CatalogCredentialEditorInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()
    private val row = OpdsEntity(id = 1, title = "Fixture", url = "https://catalog.example/opds",
        username = "legacy-fixture-user", password = "legacy-fixture-password")

    @Test fun keepDoesNotPrefillOrSubmitLegacyCredentials() {
        var saved = false
        var visible by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (visible) AddCatalogDialog(row, { visible = false }) { _, _, action, user, pass ->
                    check(action == CatalogCredentialAction.KEEP && user == null && pass == null)
                    saved = true
                    Result.success("Saved")
                }
            }
        }
        compose.onNodeWithTag(Tid.CatalogManager.dialogUser).assertDoesNotExist()
        compose.onNodeWithTag(Tid.CatalogManager.dialogPass).assertDoesNotExist()
        compose.onNodeWithText("legacy-fixture-password").assertDoesNotExist()
        compose.onNodeWithTag(Tid.CatalogManager.dialogSave).performClick()
        compose.runOnIdle { check(saved && !visible) }
    }

    @Test fun failedReplacementKeepsDialogAndRetryCanSucceed() {
        var attempts = 0
        var visible by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (visible) AddCatalogDialog(row, { visible = false }) { _, _, action, user, pass ->
                    check(action == CatalogCredentialAction.REPLACE && user == "new-fixture-user" && pass == "new-fixture-password")
                    attempts++
                    if (attempts == 1) Result.failure(IllegalStateException("synthetic-sensitive-diagnostic"))
                    else Result.success("Saved")
                }
            }
        }
        compose.onNodeWithTag("catalog_credential_replace").performScrollTo().performClick()
        compose.onNodeWithTag(Tid.CatalogManager.dialogUser).performScrollTo().performTextReplacement("new-fixture-user")
        compose.onNodeWithTag(Tid.CatalogManager.dialogPass).performScrollTo().performTextReplacement("new-fixture-password")
        compose.onNodeWithTag(Tid.CatalogManager.dialogSave).performClick()
        compose.runOnIdle { check(attempts == 1 && visible) }
        compose.onNodeWithText("synthetic-sensitive-diagnostic").assertDoesNotExist()
        compose.onNodeWithTag(Tid.CatalogManager.dialogSave).performClick()
        compose.runOnIdle { check(attempts == 2 && !visible) }
    }

    @Test fun removeRequiresSeparateConfirmationAndNoSecretPair() {
        var removed = false
        var visible by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (visible) AddCatalogDialog(row, { visible = false }) { _, _, action, user, pass ->
                    check(action == CatalogCredentialAction.REMOVE && user == null && pass == null)
                    removed = true
                    Result.success("Saved")
                }
            }
        }
        compose.onNodeWithTag("catalog_credential_remove").performScrollTo().performClick()
        compose.onNodeWithTag(Tid.CatalogManager.dialogSave).performClick()
        compose.onNodeWithText("Remove credentials?").assertExists()
        compose.runOnIdle { check(!removed && visible) }
        compose.onNodeWithText("Remove", substring = false).performClick()
        compose.runOnIdle { check(removed && !visible) }
    }
}
