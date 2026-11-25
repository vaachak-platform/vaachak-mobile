package org.vaachak.reader.leisure.ui.comics

import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/** Stream CBZ archives without extracting paths to disk; enforce limits on actual decompressed bytes. */
object SafeCbzArchive {
    const val MAX_ARCHIVE_BYTES: Long = 2L * 1024L * 1024L * 1024L
    private const val MAX_ENTRIES = 20_000
    private const val MAX_PAGES = 5_000
    private const val MAX_IMAGE_BYTES: Long = 64L * 1024L * 1024L
    private const val MAX_METADATA_BYTES: Long = 4L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES: Long = 8L * 1024L * 1024L * 1024L

    data class Index(val pages: List<String>, val totalImageBytes: Long)

    class UnsafeArchive(message: String) : IOException(message)

    fun inspect(input: InputStream): Index = visit(input, wantedPage = null).first

    /** Revalidate the archive each time a page is requested; never trust saved metadata. */
    fun readPage(input: InputStream, name: String): ByteArray {
        val (index, bytes) = visit(input, wantedPage = name)
        if (name !in index.pages || bytes == null) throw UnsafeArchive("Requested comic page is unavailable")
        return bytes
    }

    private class Inspection(private val wantedPage: String?) {
        val pageNames = ArrayList<String>()
        val seenNames = HashSet<String>()
        var entryCount = 0
        var decompressedBytes = 0L
        var imageBytes = 0L
        var selected: ByteArray? = null

        fun process(zip: ZipInputStream, entry: ZipEntry) {
            entryCount++
            if (entryCount > MAX_ENTRIES) reject("Too many archive entries")
            val name = validateEntryName(entry.name)
            if (!seenNames.add(name.lowercase(Locale.ROOT))) reject("Duplicate archive path")
            val image = isReadableImage(entry, name)
            if (image) {
                pageNames += name
                if (pageNames.size > MAX_PAGES) reject("Too many comic pages")
            }
            val bytes = drainEntry(zip, if (image) MAX_IMAGE_BYTES else MAX_METADATA_BYTES,
                if (image && name == wantedPage) ByteArrayOutputStream() else null)
            if (image) {
                validateImage(entry, bytes)
                imageBytes += bytes
            }
            zip.closeEntry()
        }

        private fun isReadableImage(entry: ZipEntry, name: String): Boolean =
            !entry.isDirectory && isImage(name) &&
                !name.startsWith("__MACOSX/", ignoreCase = true) &&
                !name.substringAfterLast('/').startsWith(".")

        private fun drainEntry(zip: ZipInputStream, limit: Long, output: ByteArrayOutputStream?): Long {
            val buffer = ByteArray(8192)
            var bytes = 0L
            while (true) {
                val count = zip.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                bytes += count
                decompressedBytes += count
                if (bytes > limit || decompressedBytes > MAX_TOTAL_BYTES) {
                    reject("Archive exceeds safe decompression limits")
                }
                output?.write(buffer, 0, count)
            }
            if (output != null) selected = output.toByteArray()
            return bytes
        }

        private fun validateImage(entry: ZipEntry, bytes: Long) {
            if (bytes <= 0L) reject("Empty comic image")
            val compressed = entry.compressedSize
            if (bytes > 1024L * 1024L && compressed >= 0L &&
                (compressed == 0L || bytes / compressed > 500L)
            ) reject("Suspiciously compressed comic image")
        }
    }

    private fun visit(input: InputStream, wantedPage: String?): Pair<Index, ByteArray?> {
        val inspection = Inspection(wantedPage)
        ZipInputStream(BoundedInputStream(input, MAX_ARCHIVE_BYTES)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                inspection.process(zip, entry)
            }
        }
        if (inspection.pageNames.isEmpty()) reject("No supported comic images were found")
        return Index(inspection.pageNames.sortedWith(::compareNatural), inspection.imageBytes) to inspection.selected
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
        ) reject("Unsafe archive entry path")
        if (name.trimEnd('/').split('/').any { it.isEmpty() }) reject("Unsafe archive entry path")
        return name
    }

    /** Natural ordering: page 2 comes before page 10, without overflow on enormous numbers. */
    internal fun compareNatural(a: String, b: String): Int {
        var left = 0
        var right = 0
        while (left < a.length && right < b.length) {
            val c1 = a[left]
            val c2 = b[right]
            if (c1.isDigit() && c2.isDigit()) {
                val end1 = a.indexOfFirstFrom(left) { !it.isDigit() }
                val end2 = b.indexOfFirstFrom(right) { !it.isDigit() }
                val d1 = a.substring(left, end1).trimStart('0').ifEmpty { "0" }
                val d2 = b.substring(right, end2).trimStart('0').ifEmpty { "0" }
                val numeric = d1.length.compareTo(d2.length).takeIf { it != 0 }
                    ?: d1.compareTo(d2).takeIf { it != 0 }
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

    private class BoundedInputStream(delegate: InputStream, private val limit: Long) : FilterInputStream(delegate) {
        private var consumed = 0L
        override fun read(): Int {
            if (consumed >= limit) {
                if (super.read() != -1) reject("Archive is too large")
                return -1
            }
            val result = super.read()
            if (result != -1) consumed++
            return result
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (consumed >= limit) {
                if (super.read() != -1) reject("Archive is too large")
                return -1
            }
            val allowed = minOf(len.toLong(), limit - consumed).toInt()
            val result = super.read(b, off, allowed)
            if (result > 0) consumed += result
            return result
        }
    }

    private fun reject(message: String): Nothing = throw UnsafeArchive(message)
}
