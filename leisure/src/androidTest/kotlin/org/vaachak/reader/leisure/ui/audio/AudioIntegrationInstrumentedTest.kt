package org.vaachak.reader.leisure.ui.audio

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
    @Test fun actualMediaMetadataAndBoundedArtwork() = runBlocking {
        val root = File(context.cacheDir, "audio-meta-${UUID.randomUUID()}")
        check(root.mkdirs())
        try {
            val file = File(root,"sample.mp3")
            InstrumentationRegistry.getInstrumentation().context.assets.open("audio-quality-fixture.mp3").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            val cover = File(root,"cover.jpg")
            val meta = PlatformAudioMetadataReader.read(file,cover)
            assertEquals("Synthetic Track",meta.title)
            assertEquals("Synthetic Album",meta.album)
            assertEquals("Synthetic Author",meta.artist)
            assertTrue(meta.duration > 0L)
            assertTrue(cover.isFile)
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(cover.path,bounds)
            assertTrue(bounds.outWidth in 1..512 && bounds.outHeight in 1..512)
            val noCover = PlatformAudioMetadataReader.read(file,null)
            assertEquals(meta,noCover)
            val broken = File(root,"broken.wav").apply { writeText("not audio") }
            assertTrue(runCatching { PlatformAudioMetadataReader.read(broken,cover) }.isFailure)
        } finally { root.deleteRecursively() }
    }

    // Keep the original timeout; report which observable condition failed instead of
    // emitting an unlabeled coroutine timeout. Only synthetic fixture state is reported.
    private suspend fun awaitTransport(stage: String, condition: () -> Boolean) {
        val reached = withTimeoutOrNull(20000L) {
            while (!condition()) delay(100)
            true
        } ?: false
        assertTrue("Transport stage '$stage' failed; state=${AudioPlayback.state.value}; error=${AudioPlayback.error.value}", reached)
    }

    @Test fun serviceTransportSettingsSleepCompletionAndRejectedOpen() = runBlocking {
        assertTrue(context.packageName.endsWith(".debug"))
        val store = AudioStore.get(context)
        val sample = (System.nanoTime() % 1000).toShort()
        val book = AudioImporter(context,store).importSources("default",listOf(
            source("01.wav",wav(12,sample)), source("02.wav",wav(12,(sample+1).toShort()))),"Transport fixture")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                fun send(action: String, value: Long = 0L, id: String = book.id, vault: String = "default") = scenario.onActivity {
                    AudioPlayback.send(it,vault,action,id,value)
                }
                send("open",id=UUID.randomUUID().toString())
                awaitTransport("missing-title error") { AudioPlayback.error.value?.message?.contains("unavailable") == true }
                send("open")
                awaitTransport("open starts playback") { AudioPlayback.state.value?.let { it.id==book.id && it.playing } == true }
                send("seek", 2000L)
                awaitTransport("initial seek") { AudioPlayback.state.value?.let { it.id == book.id && it.position in 2000L..7000L } == true }
                send("open") // re-opening active title must not reset it
                send("toggle")
                awaitTransport("pause active title") { AudioPlayback.state.value?.let { it.id==book.id && !it.playing && it.position >= 2000L } == true }
                send("next")
                awaitTransport("next chapter") { (AudioPlayback.state.value?.position ?: 0L) >= 12000L }
                send("previous")
                awaitTransport("previous chapter") { (AudioPlayback.state.value?.position ?: Long.MAX_VALUE) < 1000L }
                store.setTitlePreferences("default",book.id,AudioPreferences(2f,5,10))
                send("settings")
                awaitTransport("apply title preferences") { AudioPlayback.state.value?.preferences == AudioPreferences(2f,5,10) }
                send("forward")
                awaitTransport("forward ten seconds") { (AudioPlayback.state.value?.position ?: 0L) >= 10000L }
                send("back")
                awaitTransport("back five seconds") { (AudioPlayback.state.value?.position ?: Long.MAX_VALUE) in 4900L..5100L }
                send("sleep",15)
                awaitTransport("start timed sleep") { AudioPlayback.state.value?.sleep?.let { it != "Off" && it != "End of chapter" } == true }
                send("sleep",0)
                awaitTransport("cancel timed sleep") { AudioPlayback.state.value?.sleep == "Off" }
                send("sleepChapter")
                awaitTransport("arm end-of-chapter sleep") { AudioPlayback.state.value?.sleep == "End of chapter" }
                send("seek",11900)
                awaitTransport("seek just before chapter boundary") { AudioPlayback.state.value?.let { !it.playing && it.position in 11800L..11999L } == true }
                send("toggle")
                awaitTransport("pause at chapter boundary") { AudioPlayback.state.value?.let { !it.playing && it.sleep=="Off" && it.position >= 12000L } == true }
                send("seek",book.duration-100)
                send("toggle")
                awaitTransport("reach natural end") { AudioPlayback.state.value?.ended == true }
                awaitTransport("persist finished status") { store.load("default").find(book.id)?.finished == true }
                assertFalse(store.load("default").recent().any { it.id==book.id })
                send("toggle")
                awaitTransport("replay with one toggle") { AudioPlayback.state.value?.let { it.playing && it.position < 5000L } == true }
                awaitTransport("persist replay progress") { store.load("default").find(book.id)?.let { !it.finished && it.position < 5000L } == true }
                send("stop",vault="wrong-vault")
                // Same active book remains; an unauthorized vault cannot change transport.
                delay(600)
                assertEquals(book.id,AudioPlayback.state.value?.id)
                scenario.onActivity { AudioPlayback.stopForProfilePicker(it) }
                awaitTransport("profile picker stops playback") { AudioPlayback.state.value == null }
            }
        } finally {
            AudioPlayback.stopForProfilePicker(context)
            store.remove("default",book.id)
        }
    }
}
