package org.vaachak.reader.leisure.ui.audio

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class AudioQualityModelTest {
    private fun book(id: String, title: String, at: Long, opened: Long, finished: Boolean = false) =
        AudioBook(id, title, "Narrator", listOf(AudioTrack("Track", "000.wav", "a".repeat(64), 10000)), at, opened, finished)

    @Test fun shelfFilterAndSortRespectFinishedAndSearch() {
        val a = book("a", "Book 10", 5000, 20)
        val b = book("b", "Book 2", 1000, 30)
        val c = book("c", "Book 3", 10000, 40, true)
        val d = book("d", "Other", 0, 0)
        val lib = AudioLibrary(books = listOf(a,b,c,d))
        assertEquals(listOf(b,c,a,d), audioShelfSelection(lib,"", "All", "Title"))
        assertEquals(listOf(c,a,b,d), audioShelfSelection(lib,"", "All", "Progress"))
        assertEquals(listOf(b,a), audioShelfSelection(lib,"book narrator", "Continue", "Recent"))
        assertEquals(listOf(c), audioShelfSelection(lib,"", "Finished", "Recent"))
        assertTrue(audioShelfSelection(lib,"absent", "All", "Recent").isEmpty())
        assertNull(lib.find("missing"))
    }
    @Test fun timeBoundsAndNaturalTieBreaks() {
        assertEquals(0f, AudioBook("x", "x", "", emptyList()).fraction)
        assertEquals(1f, book("a", "a", 20000, 1).fraction)
        assertEquals(0f, book("a", "a", -1, 1).fraction)
        assertEquals(0, audioNaturalOrder.compare("same", "same"))
        assertTrue(audioNaturalOrder.compare("2", "a") < 0)
        assertTrue(audioNaturalOrder.compare("a", "a2") < 0)
        assertTrue(audioNaturalOrder.compare("A02", "a2") < 0)
        val line = AudioTimeline(listOf(AudioTrack("track", "000.wav", "a".repeat(64), 10000,
            listOf(AudioChapter("start",0), AudioChapter("bad",-1)))))
        assertEquals("start", line.chapter(-1).title)
        assertEquals(0L, line.global(99,-1))
        assertTrue(runCatching { AudioTimeline(List(MAX_AUDIO_TRACKS+1) { AudioTrack("t","000.wav","a".repeat(64),1) }) }.isFailure)
    }
    @Test fun boundedCopyRejectsEmptyOverflowAndZeroReads() = runBlocking {
        val f = File.createTempFile("audio-copy", ".wav")
        try {
            val payload = byteArrayOf(1,2,3)
            assertEquals(3L, copyAudioInput(AudioInput("test.wav") { ByteArrayInputStream(payload) },f,3).first)
            assertTrue(f.readBytes().contentEquals(payload))
            assertTrue(runCatching { copyAudioInput(AudioInput("empty.wav") { ByteArrayInputStream(byteArrayOf()) },f,3) }.isFailure)
            assertTrue(runCatching { copyAudioInput(AudioInput("large.wav") { ByteArrayInputStream(payload) },f,2) }.isFailure)
            assertTrue(runCatching { copyAudioInput(AudioInput("zero.wav") { object: InputStream() {
                override fun read() = 0
                override fun read(b: ByteArray, off: Int, len: Int) = 0
            } },f,3) }.isFailure)
        } finally { f.delete() }
    }
    @Test fun cancelledCopyDoesNotContinueReading() = runBlocking {
        val f = File.createTempFile("audio-cancel", ".wav")
        var reads = 0
        try {
            val job = launch {
                coroutineScope {
                    val scope = this
                    try {
                        copyAudioInput(AudioInput("a.wav") { object : InputStream() {
                            override fun read() = -1
                            override fun read(b: ByteArray, off: Int, len: Int): Int {
                                reads++; b[off] = 1; scope.cancel(); return 1
                            }
                        } },f,100)
                        fail("Cancelled import continued")
                    } catch (expected: CancellationException) { throw expected }
                }
            }
            job.join()
            assertTrue(job.isCancelled)
            assertEquals(1, reads)
        } finally { f.delete() }
    }
    @Test fun chapterParserHandlesExtendedSizesAndMalformedPayloads() {
        fun bytes(write: (DataOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().also { b -> DataOutputStream(b).use(write) }.toByteArray()
        fun box(label: String, body: ByteArray, size: Int = body.size + 8) = bytes { out -> out.writeInt(size); out.writeBytes(label); out.write(body) }
        fun chpl(version: Int, units: Long, label: ByteArray, labelSize: Int = label.size) = bytes { out ->
            out.writeInt(version shl 24); if (version==1) out.writeInt(0); out.writeByte(1); out.writeLong(units); out.writeByte(labelSize); out.write(label)
        }
        val f=File.createTempFile("chapter-edges",".m4b")
        try {
            fun parse(body:ByteArray):List<AudioChapter> { f.writeBytes(body);return AudioChapters.read(f,10000) }
            fun wrap(chapters:ByteArray)=box("moov",box("udta",box("chpl",chapters)))
            assertEquals(listOf(AudioChapter("Chapter 1",0)),parse(wrap(chpl(0,0,byteArrayOf()))))
            assertTrue(parse(wrap(chpl(2,0,byteArrayOf()))).isEmpty())
            assertTrue(parse(wrap(chpl(0,-1,byteArrayOf()))).isEmpty())
            assertTrue(parse(wrap(chpl(0,0,byteArrayOf(1),5))).isEmpty())
            assertTrue(parse(wrap(byteArrayOf(0,0,0,0,1))).isEmpty())
            assertTrue(parse(wrap(byteArrayOf(0,0))).isEmpty())
            assertTrue(parse(box("moov",box("junk",byteArrayOf()))).isEmpty())
            assertTrue(parse(box("moov",box("udta",byteArrayOf()))).isEmpty())
            val body=box("udta",box("chpl",chpl(0,0,"A".toByteArray())))
            val extended=bytes { out->out.writeInt(1);out.writeBytes("moov");out.writeLong(body.size.toLong()+16);out.write(body) }
            assertEquals(listOf(AudioChapter("A",0)),parse(extended))
            assertEquals(listOf(AudioChapter("A",0)),parse(box("moov",body,0)))
        } finally { f.delete() }
    }
}
