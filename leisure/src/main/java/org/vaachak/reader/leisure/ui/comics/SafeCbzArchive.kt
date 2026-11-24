package org.vaachak.reader.leisure.ui.comics

import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * CBZ stream-based archive inspector. Never extracts ZIP paths to the file system.
 * All limits apply while decompressing, not just to the ZIP header's untrusted sizes.
 */
object SafeCbzArchive {
    const val MAX_ARCHIVE_BYTES: Long = 2L * 1024L * 1024L * 1024L
    private const val MAX_ENTRIES = 20_000
    private const val MAX_PAGES = 5_000
    private const val MAX_IMAGE_BYTES: Long = 64L * 1024L * 1024L
    private const val MAX_METADATA_BYTES: Long = 4L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES: Long = 8L * 1024L * 1024L * 1024L

    data class Index(val pages: List<String>, val totalImageBytes: Long)

    class UnsafeArchive(message: String) : IOException(message)

    /** Inspect once at import time. A reader must revalidate on every subsequent file open. */
    fun inspect(input: InputStream): Index = visit(input, wantedPage = null).first

    /** Revalidate the entire archive before returning one bounded image to the caller. */
    fun readPage(input: InputStream, name: String): ByteArray {
        val (index, bytes) = visit(input, wantedPage = name)
        if (name !in index.pages || bytes == null) {
            throw UnsafeArchive("Requested comic page is unavailable")
        }
        return bytes
    }

    private fun visit(input: InputStream, wantedPage: String?): Pair<Index, ByteArray?> {
        val pageNames = ArrayList<String>()
        val seenNames = HashSet<String>()
        val buffer = ByteArray(8192)
        var archiveEntries = 0
        var totalBytes = 0L
        var totalImageBytes = 0L
        var selected: ByteArray? = null

        ZipInputStream(BoundedInputStream(input, MAX_ARCHIVE_BYTES)).use { zip ->
            while (true) {
                val entry: ZipEntry = zip.nextEntry ?: break
                archiveEntries++
                if (archiveEntries > MAX_ENTRIES) throw UnsafeArchive("Too many archive entries")
                val normalized = validateEntryName(entry.name)
                val folded = normalized.lowercase(Locale.ROOT)
                if (!seenNames.add(folded)) throw UnsafeArchive("Duplicate archive path")
                val image = !entry.isDirectory && isImage(normalized) &&
                    !normalized.startsWith("__MACOSX/", ignoreCase = true) &&
                    !normalized.substringAfterLast('/').startsWith(".")
                if (image) {
                    pageNames += normalized
                    if (pageNames.size > MAX_PAGES) throw UnsafeArchive("Too many comic pages")
                }
                val limit = if (image) MAX_IMAGE_BYTES else MAX_METADATA_BYTES
                val content = if (image && normalized == wantedPage) ByteArrayOutputStream() else null
                var entryBytes = 0L
                while (true) {
                    val count = zip.read(buffer)
                    if (count == -1) break
                    entryBytes += count.toLong()
                    totalBytes += count.toLong()
                    if (entryBytes > limit || totalBytes > MAX_TOTAL_BYTES) {
                        throw UnsafeArchive("Archive exceeds safe decompression limits")
                    }
                    content?.write(buffer, 0, count)
                }
                if (image) {
                    if (entryBytes == 0L) throw UnsafeArchive("Empty comic image")
                    val compressed = entry.compressedSize
                    if (entryBytes > 1024L * 1024 && compressed >= 0 &&
                        (compressed == 0L || entryBytes / compressed > 500L)
                    ) throw UnsafeArchive("Suspiciously compressed comic image")
                    totalImageBytes += entryBytes
                }
                if (content != null) selected = content.toByteArray()
                zip.closeEntry()
            }
        }
        if (pageNames.isEmpty()) throw UnsafeArchive("No supported comic images were found")
        pageNames.sortWith(::compareNatural)
        return Index(pageNames, totalImageBytes) to selected
    }

    internal fun isImage(name: String): Boolean = when (name.substringAfterLast('.').lowercase(Locale.ROOT)) {
        "jpg", "jpeg", "png", "webp" -> true
        else -> false
    }

    internal fun validateEntryName(name: String): String {
        if (name.isEmpty() || name.length > 512 || name.startsWith('/') || name.contains("//") ||
            name.any { it == '\u0000' || it == '\\' || it.code < 32 || it == ':' } ||
            name.split('/').any { it == "." || it == ".." || it.isEmpty() && name.last() != '/' } ||
            name.count { it == '/' } > 24
        ) throw UnsafeArchive("Unsafe archive entry path")
        // Directory markers are accepted but never created on disk.
        val components = name.trimEnd('/').split('/')
        if (components.any { it.isEmpty() }) throw UnsafeArchive("Unsafe archive entry path")
        return name
    }

    /** Natural ordering: 2.jpg sorts before 10.jpg without parsing enormous numbers. */
    internal fun compareNatural(a: String, b: String): Int {
        var left = 0
        var right = 0
        while (left < a.length && right < b.length) {
            val c1 = a[left]
            val c2 = b[right]
            if (c1.isDigit() && c2.isDigit()) {
                val end1 = a.indexOfFirstFrom(left) { !it.isDigit() }
                val end2 = b.indexOfFirstFrom(right) { !it.isDigit() }
                val d1 = a.substring(left, end1)
                val d2 = b.substring(right, end2)
                val n1 = d1.trimStart('0').ifEmpty { "0" }
                val n2 = d2.trimStart('0').ifEmpty { "0" }
                val numeric = n1.length.compareTo(n2.length).takeIf { it != 0 }
                    ?: n1.compareTo(n2).takeIf { it != 0 }
                if (numeric != null) return numeric
                left = end1
                right = end2
            } else {
                val cmp = c1.lowercaseChar().compareTo(c2.lowercaseChar())
                if (cmp != 0) return cmp
                left++
                right++
            }
        }
        return a.length.compareTo(b.length)
    }

    private inline fun String.indexOfFirstFrom(start: Int, predicate: (Char) -> Boolean): Int {
        for (i in start until length) if (predicate(this[i])) return i
        return length
    }

    /** Limit compressed input bytes, including when ZIP headers lie about size. */
    private class BoundedInputStream(delegate: InputStream, private val limit: Long) :
        FilterInputStream(delegate) {
        private var consumed = 0L

        override fun read(): Int {
            if (consumed >= limit) {
                val next = super.read()
                if (next != -1) throw UnsafeArchive("Archive is too large")
                return -1
            }
            val result = super.read()
            if (result != -1) consumed++
            return result
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (consumed >= limit) {
                val next = super.read()
                if (next != -1) throw UnsafeArchive("Archive is too large")
                return -1
            }
            val allowed = minOf(len.toLong(), limit - consumed).toInt()
            val result = super.read(b, off, allowed)
            if (result > 0) consumed += result
            return result
        }
    }
}
