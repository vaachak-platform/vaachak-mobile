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
import org.vaachak.reader.core.data.repository.OpdsMigrationReport
import androidx.compose.ui.test.assertIsNotEnabled
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

    @Test fun emptyManagerAllowsAnonymousCreationAndBack() {
        var backs = 0
        var saves = 0
        compose.setContent {
            MaterialTheme {
                CatalogManagerContent(emptyList(), true, onBack = { backs++ },
                    onSave = { existing, title, url, action, user, pass ->
                        check(existing == null && title == "Public fixture" && url == "https://example.org/opds")
                        check(action == CatalogCredentialAction.REMOVE && user == null && pass == null)
                        saves++
                        Result.success("Saved")
                    }, onDelete = { error("Unexpected delete") }, onMigrate = { error("Unexpected migration") })
            }
        }
        compose.onNodeWithText("No catalogs added yet.").assertExists()
        compose.onNodeWithTag(Tid.CatalogManager.add).performClick()
        compose.onNodeWithTag(Tid.CatalogManager.dialogSave).assertIsNotEnabled()
        compose.onNodeWithTag(Tid.CatalogManager.dialogTitle).performScrollTo().performTextReplacement("Public fixture")
        compose.onNodeWithTag(Tid.CatalogManager.dialogUrl).performScrollTo().performTextReplacement("https://example.org/opds")
        compose.onNodeWithTag(Tid.CatalogManager.dialogSave).performClick()
        compose.runOnIdle { check(saves == 1) }
        compose.onNodeWithTag(Tid.CatalogManager.back).performClick()
        compose.runOnIdle { check(backs == 1) }
    }

    @Test fun managerMigrationReportsFailureThenRemainingCount() {
        var attempts = 0
        compose.setContent {
            MaterialTheme {
                CatalogManagerContent(listOf(row), false, onBack = {},
                    onSave = { _, _, _, _, _, _ -> error("Unexpected save") },
                    onDelete = { error("Unexpected delete") }, onMigrate = {
                        attempts++
                        if (attempts == 1) Result.failure(IllegalStateException("synthetic-private-diagnostic"))
                        else Result.success(OpdsMigrationReport(1, 0))
                    })
            }
        }
        compose.onNodeWithText("Migrate saved credentials").performClick()
        compose.onNodeWithText("Migration unavailable. Unlock your device and retry.").assertExists()
        compose.onNodeWithText("synthetic-private-diagnostic").assertDoesNotExist()
        compose.onNodeWithText("Migrate saved credentials").performClick()
        compose.onNodeWithText("Migrated 1; 0 catalog(s) still need migration.").assertExists()
        compose.runOnIdle { check(attempts == 2) }
    }

    @Test fun managerEditKeepAndConfirmedDeletePreserveIdentity() {
        val active = row.copy(username = null, password = null, credentialGeneration = "00000000-0000-0000-0000-000000000001")
        var rows by mutableStateOf(listOf(active))
        var saves = 0
        var deletes = 0
        compose.setContent {
            MaterialTheme {
                CatalogManagerContent(rows, false, onBack = {},
                    onSave = { existing, _, _, action, user, pass ->
                        check(existing == active && action == CatalogCredentialAction.KEEP && user == null && pass == null)
                        saves++
                        Result.success("Saved")
                    }, onDelete = { feed ->
                        check(feed == active)
                        deletes++
                        if (deletes == 1) Result.failure(IllegalStateException("fixture"))
                        else { rows = emptyList(); Result.success("Deleted") }
                    }, onMigrate = { error("Unexpected migration") })
            }
        }
        compose.onNodeWithTag(Tid.CatalogManager.edit(active.id.toString())).performClick()
        compose.onNodeWithText("Encrypted credentials saved; they may be locked or unavailable.").assertExists()
        compose.onNodeWithTag(Tid.CatalogManager.dialogSave).performClick()
        compose.runOnIdle { check(saves == 1) }
        compose.onNodeWithTag(Tid.CatalogManager.delete(active.id.toString())).performClick()
        compose.onNodeWithText("Cancel", substring = false).performClick()
        compose.runOnIdle { check(deletes == 0) }
        compose.onNodeWithTag(Tid.CatalogManager.delete(active.id.toString())).performClick()
        compose.onNodeWithText("Delete", substring = false).performClick()
        compose.onNodeWithText("Delete unavailable. Refresh and retry.").assertExists()
        compose.onNodeWithTag(Tid.CatalogManager.delete(active.id.toString())).performClick()
        compose.onNodeWithText("Delete", substring = false).performClick()
        compose.onNodeWithText("Catalog deleted.").assertExists()
        compose.runOnIdle { check(deletes == 2 && rows.isEmpty()) }
    }

    @Test fun switchingAwayFromReplacementClearsEnteredPairAndCancelRemoveKeepsDialog() {
        var saved = false
        compose.setContent {
            MaterialTheme {
                AddCatalogDialog(row, {}) { _, _, action, user, pass ->
                    check(action == CatalogCredentialAction.KEEP && user == null && pass == null)
                    saved = true
                    Result.success("Saved")
                }
            }
        }
        compose.onNodeWithTag("catalog_credential_replace").performScrollTo().performClick()
        compose.onNodeWithTag(Tid.CatalogManager.dialogUser).performScrollTo().performTextReplacement("fixture-user")
        compose.onNodeWithTag(Tid.CatalogManager.dialogPass).performScrollTo().performTextReplacement("fixture-pass")
        compose.onNodeWithTag("catalog_credential_keep").performScrollTo().performClick()
        compose.onNodeWithTag("catalog_credential_replace").performScrollTo().performClick()
        compose.onNodeWithTag(Tid.CatalogManager.dialogSave).assertIsNotEnabled()
        compose.onNodeWithTag("catalog_credential_remove").performScrollTo().performClick()
        compose.onNodeWithTag(Tid.CatalogManager.dialogSave).performClick()
        compose.onNodeWithText("Remove credentials?").assertExists()
        compose.onNodeWithTag("catalog_remove_confirm_cancel").performClick()
        compose.onNodeWithText("Remove credentials?").assertDoesNotExist()
        compose.onNodeWithTag("catalog_credential_keep").performScrollTo().performClick()
        compose.onNodeWithTag(Tid.CatalogManager.dialogSave).performClick()
        compose.runOnIdle { check(saved) }
    }

}
