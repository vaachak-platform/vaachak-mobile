package org.vaachak.reader.leisure.ui.audio

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

class AudioModelTest {
    private fun track(name: String, duration: Long, chapters: List<AudioChapter> = emptyList()) =
        AudioTrack(name, "000.mp3", "a".repeat(64), duration, chapters)
    private fun book(id: String, pos: Long, opened: Long, finished: Boolean = false) =
        AudioBook(id, "Sample $id", "Author", listOf(track("Track", 60000)), pos, opened, finished)

    @Test fun continueMatchesOpenedUnfinishedPositiveProgressInRecentOrder() {
        val library = AudioLibrary(books = listOf(book("new", 0, 100), book("finished", 60000, 99, true),
            book("older", 12345, 20), book("recent", 3000, 30), book("unopened", 1000, 0)))
        assertEquals(listOf("recent", "older"), library.recent().map { it.id })
    }
    @Test fun positionPersistsAndCanFinishOrReplay() {
        val started = book("a", 0, 0).progress(3000, 10, false)
        assertTrue(started.continueListening)
        val ended = started.progress(100000, 20, true)
        assertEquals(60000L, ended.position)
        assertFalse(ended.continueListening)
        assertFalse(ended.progress(0, 30, false).continueListening)
        assertEquals(0L, ended.progress(-1, 30, false).position)
        assertEquals(20L, ended.progress(50, 10, false).opened)
    }
    @Test fun globalTimelineMapsAcrossFilesAndBounds() {
        val line = AudioTimeline(listOf(track("1", 10000), track("2", 20000)))
        assertEquals(AudioLocation(0, 0), line.locate(-5))
        assertEquals(AudioLocation(1, 0), line.locate(10000))
        assertEquals(AudioLocation(1, 20000), line.locate(40000))
        assertEquals(12500L, line.global(1, 2500))
        assertEquals(10000L, line.global(-1, 30000))
        assertEquals(30000L, line.next(10000))
        assertEquals(0L, line.previous(11000))
        assertEquals(10000L, line.previous(15000))
    }
    @Test fun chaptersGetStartAndAreOrderedDeduplicatedAndClamped() {
        val line = AudioTimeline(listOf(track("First", 10000, listOf(AudioChapter("B", 8000), AudioChapter("A", 4000),
            AudioChapter("duplicate", 4000), AudioChapter("invalid", 20000))), track("Second", 20000)))
        assertEquals(listOf(0L, 4000L, 8000L, 10000L), line.chapters.map { it.start })
        assertEquals("A", line.chapter(5000).title)
        assertEquals("Second", line.chapter(10000).title)
    }
    @Test fun naturalOrderAndSearch() {
        assertEquals(listOf("1.mp3", "2.mp3", "10.mp3", "999999999999999999999.mp3"),
            listOf("10.mp3", "999999999999999999999.mp3", "2.mp3", "1.mp3").sortedWith(audioNaturalOrder))
        assertTrue(audioMatches(book("ABC", 0, 0), "author abc"))
        assertFalse(audioMatches(book("ABC", 0, 0), "missing"))
        assertEquals("1:02:03", audioClock(3723000))
        assertEquals("0:00", audioClock(-1000))
    }
    @Test fun defaultsAndOverridesAreIndependentAndConstrained() {
        assertEquals(AudioPreferences(1f, 15, 30), AudioPreferences())
        assertEquals(10, AUDIO_SPEEDS.size)
        val title = book("a", 1, 2).copy(titlePreferences = AudioPreferences(1.25f, 5, 60))
        val library = AudioLibrary(AudioPreferences(2f, 10, 15), listOf(title))
        assertEquals(1.25f, library.find("a")!!.titlePreferences!!.speed)
        assertEquals(2f, library.defaults.speed)
        assertTrue(runCatching { AudioPreferences(4f, 15, 30) }.isFailure)
        assertTrue(runCatching { AudioPreferences(1f, 1, 30) }.isFailure)
        assertTrue(runCatching { AudioPreferences(1f, 15, 1) }.isFailure)
        assertTrue(runCatching { AudioTimeline(emptyList()) }.isFailure)
        assertTrue(runCatching { AudioTimeline(listOf(track("bad", 0))) }.isFailure)
        assertTrue(runCatching { AudioTimeline(listOf(track("bad", MAX_AUDIO_DURATION), track("too", 1))) }.isFailure)
    }
    private fun box(type: String, payload: ByteArray): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { out -> out.writeInt(payload.size + 8); out.writeBytes(type); out.write(payload) }
    }.toByteArray()
    @Test fun readsBoundedNeroChaptersAndRejectsTruncation() {
        val chpl = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            out.writeInt(0x01000000); out.writeInt(0); out.writeByte(2)
            out.writeLong(0); out.writeByte(3); out.writeBytes("One")
            out.writeLong(50000000); out.writeByte(3); out.writeBytes("Two")
        } }.toByteArray()
        val file = File.createTempFile("vaachak-audio", ".m4b")
        try {
            val valid = box("moov", box("udta", box("chpl", chpl)))
            file.writeBytes(valid)
            assertEquals(listOf(AudioChapter("One", 0), AudioChapter("Two", 5000)), AudioChapters.read(file, 10000))
            assertEquals(listOf(AudioChapter("One", 0)), AudioChapters.read(file, 3000))
            file.writeBytes(valid.copyOf(valid.size - 1))
            assertTrue(AudioChapters.read(file, 10000).isEmpty())
            file.writeBytes(byteArrayOf(0, 0, 0, 1, 109, 111, 111, 118))
            assertTrue(AudioChapters.read(file, 10000).isEmpty())
        } finally { file.delete() }
    }
}
