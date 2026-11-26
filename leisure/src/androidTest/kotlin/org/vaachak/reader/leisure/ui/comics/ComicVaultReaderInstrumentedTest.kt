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
import androidx.compose.ui.test.onAllNodesWithText
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

    @Test fun pickerCancellationRejectionRestoreAndRemovalPreserveIdentity() {
        val vault = "picker-fixture-${UUID.randomUUID()}"
        val dir = File(context.noBackupFilesDir, "comics/${vaultKey(vault).removePrefix("vault_")}")
        check(dir.mkdirs())
        val staged = File(dir, "fixture.cbz").also { fixtureArchive(it) }
        val hash = staged.inputStream().use { ComicFileFingerprint.calculate(it) }
        val archive = File(dir, "$hash.cbz")
        check(staged.renameTo(archive))
        val prefs = context.getSharedPreferences("vaachak_comics_v1", Context.MODE_PRIVATE)
        val row = JSONObject().put("id", "picker").put("title", "Picker fixture")
            .put("uri", Uri.fromFile(archive).toString()).put("pageCount", 2).put("lastPage", 1)
            .put("format", "CBZ").put("lastOpenedAt", 1L).put("sha256", hash)
        check(prefs.edit().putString(vaultKey(vault), JSONArray().put(row).toString()).commit())
        val before = ComicShelfStore(context).list(vault)
        val picker = org.vaachak.reader.leisure.testing.MediaPickerFixtureRegistry()
        var original: Uri? = null
        try {
            original = org.vaachak.reader.leisure.testing.publishPickerFixture(context,
                "comic-${UUID.randomUUID()}.cbz", "application/zip", archive.readBytes())
            compose.setContent {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.activity.compose.LocalActivityResultRegistryOwner provides picker
                ) { MaterialTheme { ComicShelfScreen(vault, false, {}) } }
            }
            fun complete(kind: String, uri: Uri?) = compose.runOnIdle { picker.complete(kind, uri) }
            fun awaitMessage(message: String) = compose.waitUntil(20_000) {
                compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty()
            }
            fun relink() {
                compose.onNodeWithContentDescription("More options for Picker fixture").performClick()
                compose.onNodeWithText("Relink original media").performClick()
            }
            relink()
            complete("OpenDocument", null)
            compose.onNodeWithContentDescription("Import CBZ or CBR comic").performClick()
            complete("OpenDocument", Uri.fromFile(archive))
            awaitMessage("Comic could not be opened or verified. Existing shelf retained.")
            assertEquals(before, ComicShelfStore(context).list(vault))
            compose.onNodeWithText("Import image folder").performClick()
            complete("OpenDocumentTree", null)
            compose.onNodeWithText("Import image folder").performClick()
            complete("OpenDocumentTree", Uri.fromFile(dir))
            awaitMessage("Image folder could not be imported or verified. Existing shelf retained.")
            assertEquals(before, ComicShelfStore(context).list(vault))
            relink()
            complete("OpenDocument", Uri.fromFile(archive))
            awaitMessage("Comic could not be opened or verified. Existing shelf retained.")
            assertEquals(before, ComicShelfStore(context).list(vault))
            check(archive.delete())
            relink()
            complete("OpenDocument", original)
            awaitMessage("Comic restored; reading progress retained.")
            val restored = ComicShelfStore(context).list(vault).single()
            val restoredUri = Uri.parse(restored.uri)
            assertEquals("file", restoredUri.scheme)
            val restoredFile = File(checkNotNull(restoredUri.path))
            // Android can return /data/user/0 or /data/data for the same private file.
            assertEquals(archive.canonicalFile, restoredFile.canonicalFile)
            assertEquals(before.single(), restored.copy(uri = before.single().uri))
            assertTrue(restoredFile.isFile)
            assertEquals(hash, restoredFile.inputStream().use { ComicFileFingerprint.calculate(it) })
            assertEquals(listOf("page01.png", "page02.png"), ComicShelfStore(context).pageNames(restored))
            compose.onNodeWithContentDescription("More options for Picker fixture").performClick()
            compose.onNodeWithText("Remove").performClick()
            compose.waitUntil(20_000) { ComicShelfStore(context).list(vault).isEmpty() }
            compose.waitUntil(20_000) { !archive.exists() }
        } finally {
            original?.let { context.contentResolver.delete(it, null, null) }
            check(prefs.edit().remove(vaultKey(vault)).commit())
            if (archive.exists()) check(archive.delete())
            check(dir.delete())
        }
    }

    @Test fun realCbzOpensAndReturnsToSameVaultShelfWithSessionRecorded() {
        val vault = "comic-vault-" + UUID.randomUUID()
        val id = "comic-fixture-1"
        val archiveDirectory = File(context.noBackupFilesDir, "comics/${vaultKey(vault).removePrefix("vault_")}")
        check(archiveDirectory.mkdirs())
        val archive = File(archiveDirectory, "fixture-${UUID.randomUUID()}.cbz")
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
            for (choice in listOf("Vertical pager", "Fit width · pan taller pages", "Manga · Right to left")) {
                compose.onNodeWithContentDescription("Comic reading and zoom options").performClick()
                compose.onNodeWithText(choice).performClick()
            }
            compose.onNodeWithContentDescription("Back to comics").performClick()
            compose.waitUntil(20_000) { openedId == null }
            assertEquals(ComicViewOverride(ComicViewMode.PAGED_RTL, ComicZoomFit.WIDTH),
                ComicShelfStore(context).list(vault).single().viewOverride)
            compose.onNodeWithContentDescription("Recently read comics").performClick()
            compose.onNodeWithContentDescription("Open comic Vault Test Comic").assertExists()
            compose.runOnIdle { assertEquals(null, openedId) }
            assertTrue(ComicShelfStore(context).list(vault).single().lastOpenedAt > 0L)
        } finally {
            check(prefs.edit().remove(vaultKey(vault)).commit())
            if (archive.exists()) check(archive.delete())
            check(archiveDirectory.delete())
        }
    }
}
