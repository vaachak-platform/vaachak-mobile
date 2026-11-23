package org.vaachak.reader.leisure.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.vaachak.reader.leisure.testing.ComposeTestHostActivity

/** Proves the Compose host mounts a semantics tree before running screen tests. */
@RunWith(AndroidJUnit4::class)
class ComposeHostSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test fun debugHostMountsComposeHierarchy() {
        compose.setContent {
            MaterialTheme {
                Text("Vaachak Compose test host ready")
            }
        }
        compose.onNodeWithText("Vaachak Compose test host ready").assertIsDisplayed()
    }
}
