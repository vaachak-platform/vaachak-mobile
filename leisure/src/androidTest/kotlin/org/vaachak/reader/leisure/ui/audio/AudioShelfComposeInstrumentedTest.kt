package org.vaachak.reader.leisure.ui.audio

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performClick
import androidx.compose.foundation.layout.Column
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.vaachak.reader.leisure.testing.ComposeTestHostActivity
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AudioShelfComposeInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()
    private fun fixture(sample: Short): ByteArray = ByteBuffer.allocate(44 + 32000).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(32036); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(32000); repeat(16000) { putShort(sample) }
    }.array()
    @Test fun continueShelfDisplaysUnfinishedTitle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AudioStore.get(context)
        val vault = "audio-ui-${UUID.randomUUID()}"
        val books = runBlocking {
            val importer = AudioImporter(context, store)
            val started = importer.importSources(vault, listOf(AudioInput("One.wav") { ByteArrayInputStream(fixture(10)) }), "Continue sample")
            val finished = importer.importSources(vault, listOf(AudioInput("Two.wav") { ByteArrayInputStream(fixture(20)) }), "Finished sample")
            store.progress(vault, started.id, 500L, 20L, false)
            store.progress(vault, finished.id, finished.duration, 30L, true)
            listOf(started, finished)
        }
        try {
            compose.setContent { MaterialTheme { AudioShelfScreen(vault) } }
            compose.waitUntil(15000L) { compose.onAllNodesWithTag("audio_continue_heading").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("audio_continue_heading").assertExists()
            compose.onNodeWithTag("audio_shelf").performScrollToIndex(1)
            compose.onNodeWithText("Continue listening").assertExists()
            compose.onNodeWithTag("audio_continue_continue_${books[0].id}").assertExists()
            compose.onNodeWithTag("audio_continue_continue_${books[1].id}").assertDoesNotExist()
        } finally { books.forEach { store.remove(vault, it.id) } }
    }
    @Test fun restorePickerResultsAndStorageInspectionPreserveTitle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AudioStore.get(context)
        val vault = "audio-recovery-ui-${UUID.randomUUID()}"
        val book = runBlocking { AudioImporter(context, store).importSources(vault,
            listOf(AudioInput("Original.wav") { ByteArrayInputStream(fixture(42)) }), "Recovery fixture") }
        val file = store.mediaFile(vault, book.id, book.tracks.single().file)
        check(file.delete())
        val before = store.load(vault)
        val picker = org.vaachak.reader.leisure.testing.MediaPickerFixtureRegistry()
        var original: android.net.Uri? = null
        try {
            original = org.vaachak.reader.leisure.testing.publishPickerFixture(context,
                "audio-${UUID.randomUUID()}.wav", "audio/wav", fixture(42))
            compose.setContent {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.activity.compose.LocalActivityResultRegistryOwner provides picker
                ) { MaterialTheme { AudioShelfScreen(vault) } }
            }
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Check local storage").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Check local storage").performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("1 audiobook(s) with missing media; 0 unreferenced or staging folder(s). No files deleted.")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            fun restore() {
                compose.onNodeWithTag("audio_shelf").performScrollToIndex(3)
                compose.onNodeWithTag("audio_restore_${book.id}").performClick()
                compose.onNodeWithText("Restore original audio").assertExists()
            }
            restore()
            compose.onNodeWithText("Cancel").performClick()
            restore()
            compose.onNodeWithText("Select files").performClick()
            compose.runOnIdle { picker.complete("OpenMultipleDocuments", null) }
            restore()
            compose.onNodeWithText("Select folder").performClick()
            compose.runOnIdle { picker.complete("OpenDocumentTree", android.net.Uri.fromFile(file.parentFile!!)) }
            compose.onNodeWithTag("audio_shelf").performScrollToIndex(0)
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Restore failed. Select the original tracks in their original order and close playback.")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            org.junit.Assert.assertEquals(before, store.load(vault))
            org.junit.Assert.assertFalse(file.exists())
            restore()
            compose.onNodeWithText("Select files").performClick()
            compose.runOnIdle { picker.complete("OpenMultipleDocuments", original) }
            compose.onNodeWithTag("audio_shelf").performScrollToIndex(0)
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Audiobook restored. Listening position retained.")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            org.junit.Assert.assertEquals(before, store.load(vault))
            org.junit.Assert.assertArrayEquals(fixture(42), file.readBytes())
        } finally {
            original?.let { context.contentResolver.delete(it, null, null) }
            store.remove(vault, book.id)
        }
    }

    @Test fun dedicatedDefaultsPersistWithoutChangingAnotherVault() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AudioStore.get(context)
        val vault = "audio-defaults-${UUID.randomUUID()}"
        val other = "audio-other-${UUID.randomUUID()}"
        compose.setContent { MaterialTheme { Column { AudioDefaultsPanel(vault) } } }
        compose.waitUntil(15000L) { compose.onAllNodesWithTag("audio_default_speed").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("audio_default_speed").performClick()
        compose.onNodeWithText("1.25×").performClick()
        compose.waitUntil(15000L) { store.load(vault).defaults.speed == 1.25f }
        org.junit.Assert.assertEquals(AudioPreferences(), store.load(other).defaults)
    }

}
