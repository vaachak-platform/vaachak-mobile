package org.vaachak.reader.leisure.ui.audio

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.vaachak.reader.leisure.MainActivity
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AudioIntegrationInstrumentedTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun wav(seconds: Int, sample: Short): ByteArray {
        val frames = seconds * 16000
        return ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + frames * 2); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(frames * 2)
            repeat(frames) { putShort(if (it % 2 == 0) sample else (-sample).toShort()) }
        }.array()
    }
    private fun source(name: String, bytes: ByteArray) = AudioInput(name) { ByteArrayInputStream(bytes) }

    @Test fun actualImporterStorageAndContinueAreIsolated() = runBlocking {
        val root = File(context.cacheDir, "audio-test-${UUID.randomUUID()}")
        val store = AudioStore(root)
        try {
            val importer = AudioImporter(context, store)
            val first = wav(3, 11)
            val second = wav(4, 22)
            val book = importer.importSources("vault-A", listOf(source("10.wav", second), source("2.wav", first)), "Synthetic book")
            assertEquals(listOf("2", "10"), book.tracks.map { it.name })
            assertTrue(book.duration in 6900L..7100L)
            assertTrue(book.tracks.all { store.mediaFile("vault-A", book.id, it.file).isFile })
            assertTrue(store.load("vault-B").books.isEmpty())
            assertTrue(runCatching { importer.importSources("vault-A", listOf(source("2.wav", first), source("10.wav", second)), null) }.isFailure)
            store.progress("vault-A", book.id, 3500L, 20L, false)
            val reopened = AudioStore(root)
            assertEquals(3500L, reopened.load("vault-A").recent().single().position)
            reopened.setTitlePreferences("vault-A", book.id, AudioPreferences(1.25f, 5, 60))
            reopened.setDefaults("vault-A", AudioPreferences(2f, 10, 15))
            assertEquals(1.25f, reopened.load("vault-A").find(book.id)!!.titlePreferences!!.speed)
            assertEquals(AudioPreferences(), reopened.load("vault-B").defaults)
            reopened.setTitlePreferences("vault-A", book.id, null)
            assertNull(reopened.load("vault-A").find(book.id)!!.titlePreferences)
            reopened.progress("vault-A", book.id, book.duration, 30L, true)
            assertTrue(reopened.load("vault-A").recent().isEmpty())
            reopened.remove("vault-A", book.id)
            assertTrue(reopened.load("vault-A").books.isEmpty())
            assertFalse(reopened.mediaFile("vault-A", book.id, book.tracks.first().file).exists())
        } finally { root.deleteRecursively() }
    }
    private suspend fun until(condition: () -> Boolean) = withTimeout(20000L) {
        while (!condition()) delay(100)
    }
    @Test fun actualServicePlaybackPauseSeekAndReopenResume() = runBlocking {
        assertTrue(context.packageName.endsWith(".debug"))
        // The device runner uses a fresh disposable emulator and a default-vault debug installation.
        val store = AudioStore.get(context)
        val sample = (System.nanoTime() % 1000).toShort()
        val book = AudioImporter(context, store).importSources("default", listOf(source("fixture.wav", wav(40, sample))), "Audio service fixture")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                fun send(action: String, value: Long = 0L) = scenario.onActivity {
                    AudioPlayback.send(it, "default", action, book.id, value)
                }
                send("open")
                until { AudioPlayback.state.value?.let { it.id == book.id && it.playing } == true }
                send("seek", 12000L)
                until { AudioPlayback.state.value?.let { it.id == book.id && it.position >= 12000 } == true }
                send("toggle")
                until { AudioPlayback.state.value?.let { it.id == book.id && !it.playing } == true }
                until { store.load("default").find(book.id)?.continueListening == true &&
                    (store.load("default").find(book.id)?.position ?: 0L) >= 12000 }
                send("stop")
                until { AudioPlayback.state.value == null }
                val saved = store.load("default").find(book.id)!!.position
                send("open")
                until { AudioPlayback.state.value?.let { it.id == book.id && it.playing && it.position >= saved - 500 } == true }
                val resumed = AudioPlayback.state.value!!.position
                assertTrue(resumed in (saved - 500)..(saved + 5000))
                send("stop")
                until { AudioPlayback.state.value == null }
            }
        } finally {
            AudioPlayback.stopForProfilePicker(context)
            store.remove("default", book.id)
        }
    }
}
