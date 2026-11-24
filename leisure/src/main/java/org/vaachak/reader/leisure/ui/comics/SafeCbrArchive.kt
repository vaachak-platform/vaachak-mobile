package org.vaachak.reader.leisure.ui.comics

import com.github.junrar.Archive
import com.github.junrar.ArchiveOptions
import com.github.junrar.rarfile.FileHeader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Read-only RAR4/RAR5 comic engine. Only a requested image is inflated, never written to disk.
 * Metadata and actual decoded bytes are checked separately, including untrusted RAR5 sizes.
 */
object SafeCbrArchive {
    private const val MAX_ENTRIES = 20_000
    private const val MAX_PAGES = 5_000
    private const val MAX_IMAGE_BYTES = 64L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 8L * 1024L * 1024L * 1024L
    private const val MAX_DICTIONARY_BYTES = 256L * 1024L * 1024L
    private const val MAX_RATIO = 500L
    private const val RATIO_FLOOR_BYTES = 1024L * 1024L
    private val rar4 = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)
    private val rar5 = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)

    data class Index(val pages: List<String>, val totalImageBytes: Long)

    fun isRarSignature(header: ByteArray): Boolean =
        header.startsWithBytes(rar4) || header.startsWithBytes(rar5)

    fun inspect(file: File): Index = visit(file, wantedPage = null).first

    fun readPage(file: File, name: String): ByteArray {
        val (index, bytes) = visit(file, wantedPage = name)
        if (name !in index.pages || bytes == null) {
            throw SafeCbzArchive.UnsafeArchive("Comic page is unavailable")
        }
        return bytes
    }

    private fun visit(file: File, wantedPage: String?): Pair<Index, ByteArray?> {
        requireReadable(file)
        val options = ArchiveOptions.builder().maxDictionarySize(MAX_DICTIONARY_BYTES).build()
        return try {
            Archive(file, options).use { archive ->
                checkEncryption(archive)
                val headers = archive.fileHeaders.toList()
                if (headers.size > MAX_ENTRIES) reject("Too many archive entries")
                inspectHeaders(archive, headers, wantedPage)
            }
        } catch (e: SafeCbzArchive.UnsafeArchive) {
            throw e
        } catch (e: Exception) {
            // Do not leak user-controlled archive paths or private device details into the UI.
            throw SafeCbzArchive.UnsafeArchive("RAR comic cannot be opened: ${e.javaClass.simpleName}")
        }
    }

    private fun requireReadable(file: File) {
        if (!file.isFile || file.length() <= 0L ||
            file.length() > SafeCbzArchive.MAX_ARCHIVE_BYTES || !isRarFile(file)
        ) reject("Not a supported RAR comic, or file exceeds the 2 GiB limit")
    }

    private fun checkEncryption(archive: Archive) {
        if (archive.isPasswordProtected || archive.isEncrypted) {
            reject("Password-protected comics are not supported")
        }
    }

    /** Package-visible for adversarial header regression tests without extracting user-controlled files. */
    internal class Inspection {
        val pages = ArrayList<String>()
        val names = HashSet<String>()
        var totalBytes = 0L
        var imageBytes = 0L
        var selected: ByteArray? = null

        fun add(header: FileHeader): String? {
            checkEntrySafety(header)
            val name = SafeCbzArchive.validateEntryName(header.fileName.replace('\\', '/'))
            if (!names.add(name.lowercase(Locale.ROOT))) reject("Duplicate archive path")
            if (header.isDirectory) return null
            val page = isReadableImage(name)
            val size = budgetSize(header.fullUnpackSize, page)
            if (size > MAX_TOTAL_BYTES - totalBytes) reject("Comic exceeds safe decompression limits")
            totalBytes += size
            if (!page) return null
            checkImageSize(header)
            pages += name
            if (pages.size > MAX_PAGES) reject("Too many comic pages")
            imageBytes += size
            return name
        }

        private fun checkEntrySafety(header: FileHeader) {
            if (header.isSplitBefore || header.isSplitAfter) reject("Multi-volume comics are not supported")
            if (header.isEncrypted) reject("Encrypted comic entries are not supported")
        }

        private fun isReadableImage(name: String): Boolean =
            SafeCbzArchive.isImage(name) &&
                !name.startsWith("__MACOSX/", ignoreCase = true) &&
                !name.substringAfterLast('/').startsWith(".")

        private fun budgetSize(declared: Long, image: Boolean): Long =
            if (declared >= 0L) declared else if (image) MAX_IMAGE_BYTES else 0L

        private fun checkImageSize(header: FileHeader) {
            val declared = header.fullUnpackSize
            val packed = header.fullPackSize
            if (declared == 0L || declared > MAX_IMAGE_BYTES) reject("Comic page exceeds the 64 MiB limit")
            if (declared > RATIO_FLOOR_BYTES && packed >= 0L &&
                (packed == 0L || declared / packed > MAX_RATIO)
            ) reject("Suspiciously compressed comic page")
        }
    }

    private fun inspectHeaders(archive: Archive, headers: List<FileHeader>, wantedPage: String?): Pair<Index, ByteArray?> {
        val inspection = Inspection()
        for (header in headers) {
            val pageName = inspection.add(header)
            if (pageName != null && pageName == wantedPage) {
                inspection.selected = readBounded(archive, header)
            }
        }
        if (inspection.pages.isEmpty()) reject("No supported comic images were found")
        return Index(inspection.pages.sortedWith(SafeCbzArchive::compareNatural), inspection.imageBytes) to inspection.selected
    }

    private fun readBounded(archive: Archive, header: FileHeader): ByteArray {
        val output = ByteArrayOutputStream()
        archive.getInputStream(header).use { input ->
            val buffer = ByteArray(32 * 1024)
            var count = 0L
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                if (size == 0) continue
                count += size
                if (count > MAX_IMAGE_BYTES) reject("Comic page exceeds the 64 MiB limit")
                output.write(buffer, 0, size)
            }
        }
        if (output.size() == 0) reject("Empty comic image")
        return output.toByteArray()
    }

    private fun isRarFile(file: File): Boolean = file.inputStream().use { input ->
        val header = ByteArray(8)
        var count = 0
        while (count < header.size) {
            val next = input.read(header, count, header.size - count)
            if (next < 0) break
            if (next == 0) continue
            count += next
        }
        isRarSignature(header.copyOf(count))
    }

    private fun ByteArray.startsWithBytes(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun reject(message: String): Nothing = throw SafeCbzArchive.UnsafeArchive(message)
}
