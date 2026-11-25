package org.vaachak.reader.leisure.ui.comics

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.vaachak.reader.core.domain.model.ComicPreferences
import org.vaachak.reader.leisure.testing.ComposeTestHostActivity

/** Actual app-private CBZ fixture: verifies shelf -> reader -> back and vault persistence. */
@RunWith(AndroidJUnit4::class)
class ComicVaultReaderInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun vaultKey(vault: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(vault.toByteArray(Charsets.UTF_8))
        return "vault_" + hash.joinToString("") { "%02x".format(it) }
    }

    private fun fixtureArchive(file: File) {
        val bitmap = Bitmap.createBitmap(120, 180, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(AndroidColor.GREEN)
        val image = ByteArrayOutputStream().use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
            out.toByteArray()
        }
        bitmap.recycle()
        ZipOutputStream(file.outputStream()).use { zip ->
            for (name in listOf("page01.png", "page02.png")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(image)
                zip.closeEntry()
            }
        }
    }

    @Test fun emptyVaultUsesRealShelfEntrypoint() {
        val vault = "empty-vault-" + UUID.randomUUID()
        compose.setContent {
            MaterialTheme {
                ComicShelfScreen(vaultId = vault, isEink = true, onOpenComic = {})
            }
        }
        compose.onNodeWithText("No comics imported for this reader profile.").assertExists()
        compose.onNodeWithContentDescription("Import CBZ or CBR comic").assertExists()
        compose.onNodeWithContentDescription("Recently read comics").performClick()
        compose.onNodeWithText("No recently read comics.").assertExists()
    }

    @Test fun realCbzOpensAndReturnsToSameVaultShelfWithSessionRecorded() {
        val vault = "comic-vault-" + UUID.randomUUID()
        val id = "comic-fixture-1"
        val archive = File(context.cacheDir, "fixture-${UUID.randomUUID()}.cbz")
        fixtureArchive(archive)
        val prefs = context.getSharedPreferences("vaachak_comics_v1", Context.MODE_PRIVATE)
        val record = JSONObject()
            .put("id", id)
            .put("title", "Vault Test Comic")
            .put("uri", Uri.fromFile(archive).toString())
            .put("pageCount", 2)
            .put("lastPage", 0)
            .put("format", "CBZ")
            .put("lastOpenedAt", 0L)
        check(prefs.edit().putString(vaultKey(vault), JSONArray().put(record).toString()).commit())
        try {
            var openedId by mutableStateOf<String?>(null)
            compose.setContent {
                MaterialTheme {
                    if (openedId == null) {
                        ComicShelfScreen(vaultId = vault, isEink = false,
                            onOpenComic = { openedId = it })
                    } else {
                        ComicReaderScreen(vaultId = vault, comicId = openedId!!,
                            preferences = ComicPreferences(), onBack = { openedId = null })
                    }
                }
            }
            compose.onNodeWithContentDescription("Open comic Vault Test Comic").performClick()
            compose.waitUntil(20_000) {
                compose.onAllNodesWithContentDescription(
                    "Comic page image; pinch to zoom, double tap to magnify"
                ).fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitUntil(20_000) {
                ComicShelfStore(context).list(vault).firstOrNull()?.lastOpenedAt?.let { it > 0L } == true
            }
            compose.onNodeWithContentDescription("Comic page image; pinch to zoom, double tap to magnify")
                .performTouchInput { click(center) }
            // Center tap is intentionally deferred until the double-tap zoom
            // gesture timeout expires. Wait for immersive chrome before Back.
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("Back to comics")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Back to comics").performClick()
            compose.onNodeWithContentDescription("Recently read comics").performClick()
            compose.onNodeWithContentDescription("Open comic Vault Test Comic").assertExists()
            compose.runOnIdle { assertEquals(null, openedId) }
            assertTrue(ComicShelfStore(context).list(vault).single().lastOpenedAt > 0L)
        } finally {
            check(prefs.edit().remove(vaultKey(vault)).commit())
            if (archive.exists()) check(archive.delete())
        }
    }
}
