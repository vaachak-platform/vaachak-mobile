package org.vaachak.reader.leisure.ui.reader.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.vaachak.reader.leisure.testing.ComposeTestHostActivity

@RunWith(AndroidJUnit4::class)
class AiImageDisplayRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()
    @Test fun validImageCorruptImageAndProviderErrorStayDistinct() {
        var result by mutableStateOf("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFklEQVR4nGMU0bBhYGBgYmBgYGBgAAAFogB8q5SvRgAAAABJRU5ErkJggg==")
        compose.setContent {
            MaterialTheme {
                AiBottomSheet(responseText = result, isImage = true, isEink = true,
                    onExplain = {}, onWhoIsThis = {}, onVisualize = {}, onDismiss = {})
            }
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription("AI Visualization").fetchSemanticsNodes(false).isNotEmpty()
        }
        compose.runOnIdle { result = "not valid base64" }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Failed to decode image response.", substring = true).fetchSemanticsNodes(false).isNotEmpty()
        }
        compose.runOnIdle { result = "Cloudflare Error 402: Check provider billing or credits." }
        compose.onNodeWithText("Cloudflare Error 402: Check provider billing or credits.", substring = true).assertExists()
    }
}
