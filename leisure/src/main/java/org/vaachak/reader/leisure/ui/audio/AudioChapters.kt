package org.vaachak.reader.leisure.ui.audio

import java.io.RandomAccessFile
import java.io.File

/** Bounded ISO BMFF Nero chpl reader. Unknown chapter formats fall back to one track marker. */
internal object AudioChapters {
    private const val MAX_BOXES = 4096
    private const val MAX_CHPL = 65536L
    private data class Box(val type: String, val payload: Long, val end: Long)
    fun read(file: File, duration: Long): List<AudioChapter> = runCatching {
        RandomAccessFile(file, "r").use { input ->
            val moov = find(input, 0, input.length(), "moov") ?: return@use emptyList()
            val udta = find(input, moov.payload, moov.end, "udta") ?: return@use emptyList()
            val chpl = find(input, udta.payload, udta.end, "chpl") ?: return@use emptyList()
            decode(input, chpl, duration)
        }
    }.getOrDefault(emptyList())

    private fun find(input: RandomAccessFile, start: Long, end: Long, type: String): Box? {
        var pos = start
        repeat(MAX_BOXES) {
            if (end - pos < 8) return null
            input.seek(pos)
            val size32 = input.readInt().toLong() and 0xffffffffL
            val label = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
            val size = when (size32) {
                0L -> end - pos
                1L -> { if (end - pos < 16) return null; input.readLong() }
                else -> size32
            }
            val header = if (size32 == 1L) 16 else 8
            if (size < header || size > end - pos) return null
            val box = Box(label, pos + header, pos + size)
            if (label == type) return box
            pos = box.end
        }
        return null
    }
    private fun decode(input: RandomAccessFile, box: Box, duration: Long): List<AudioChapter> {
        if (box.end - box.payload !in 5..MAX_CHPL) return emptyList()
        input.seek(box.payload)
        val version = input.readUnsignedByte()
        if (version !in 0..1) return emptyList()
        input.skipBytes(3)
        if (version == 1) input.skipBytes(4)
        if (input.filePointer >= box.end) return emptyList()
        val count = input.readUnsignedByte()
        val result = mutableListOf<AudioChapter>()
        repeat(count) { index ->
            if (box.end - input.filePointer < 9) return emptyList()
            val units = input.readLong()
            val length = input.readUnsignedByte()
            if (units < 0 || length > box.end - input.filePointer) return emptyList()
            val label = ByteArray(length).also(input::readFully).toString(Charsets.UTF_8).trim()
            val start = units / 10000
            if (start in 0 until duration) result += AudioChapter(label.ifBlank { "Chapter ${index + 1}" }, start)
        }
        return result.distinctBy { it.start }.sortedBy { it.start }
    }
}
