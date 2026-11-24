package org.vaachak.reader.leisure.ui.comics

import com.github.junrar.Archive
import com.github.junrar.ArchiveOptions
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Bounded RAR4 / RAR5 comic reader using Junrar 8.1.2.
 *
 * Inspecting an archive checks metadata without unpacking every page. Only the requested
 * image is decompressed, with a separate real-byte bound regardless of declared sizes.
 * This is important for large and solid RAR archives on mobile devices.
 * No archive paths are ever extracted to the filesystem.
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

    /** Detect RAR4 or RAR5 from the signature, never from the filename alone. */
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
        if (!file.isFile || file.length() <= 0L ||
            file.length() > SafeCbzArchive.MAX_ARCHIVE_BYTES || !isRarFile(file)
        ) throw SafeCbzArchive.UnsafeArchive("Not a supported RAR comic, or file exceeds the 2 GiB limit")

        val pages = ArrayList<String>()
        val names = HashSet<String>()
        var declaredTotalBytes = 0L
        var imageBytes = 0L
        var selected: ByteArray? = null
        // A 256 MiB dictionary budget supports ordinary RAR5 archives without allowing the
        // decoder to allocate the library's multi-gigabyte default dictionary size.
        val options = ArchiveOptions.builder().maxDictionarySize(MAX_DICTIONARY_BYTES).build()
        try {
            Archive(file, options).use { archive ->
                if (archive.isPasswordProtected || archive.isEncrypted) {
                    throw SafeCbzArchive.UnsafeArchive("Password-protected comics are not supported")
                }
                val headers = archive.fileHeaders
                if (headers.size > MAX_ENTRIES) {
                    throw SafeCbzArchive.UnsafeArchive("Too many archive entries")
                }
                for (header in headers) {
                    if (header.isSplitBefore || header.isSplitAfter) {
                        throw SafeCbzArchive.UnsafeArchive("Multi-volume comics are not supported")
                    }
                    if (header.isEncrypted) {
                        throw SafeCbzArchive.UnsafeArchive("Encrypted comic entries are not supported")
                    }
                    // RAR entry names may legitimately use Windows backslashes as separators.
                    val name = SafeCbzArchive.validateEntryName(header.fileName.replace('\\', '/'))
                    if (!names.add(name.lowercase(Locale.ROOT))) {
                        throw SafeCbzArchive.UnsafeArchive("Duplicate archive path")
                    }
                    if (header.isDirectory) continue
                    val isPage = SafeCbzArchive.isImage(name) &&
                        !name.startsWith("__MACOSX/", ignoreCase = true) &&
                        !name.substringAfterLast('/').startsWith(".")
                    if (isPage) {
                        pages += name
                        if (pages.size > MAX_PAGES) throw SafeCbzArchive.UnsafeArchive("Too many comic pages")
                    }
                    val declared = header.fullUnpackSize
                    val packed = header.fullPackSize
                    // Some RAR5 headers use an unknown decoded size (-1). Junrar 8.1.2
                    // supports them; reserve the full page budget until actual decoding.
                    val budgeted = if (declared >= 0L) declared else if (isPage) MAX_IMAGE_BYTES else 0L
                    if (budgeted > MAX_TOTAL_BYTES - declaredTotalBytes) {
                        throw SafeCbzArchive.UnsafeArchive("Comic exceeds safe decompression limits")
                    }
                    declaredTotalBytes += budgeted
                    if (isPage) {
                        if (declared == 0L || declared > MAX_IMAGE_BYTES) {
                            throw SafeCbzArchive.UnsafeArchive("Comic page exceeds the 64 MiB limit")
                        }
                        // Ratios rely on known sizes; real extracted bytes are always
                        // capped separately, including for RAR5's unknown-size headers.
                        if (declared > RATIO_FLOOR_BYTES && packed >= 0L &&
                            (packed == 0L || declared / packed > MAX_RATIO)
                        ) throw SafeCbzArchive.UnsafeArchive("Suspiciously compressed comic page")
                        imageBytes += budgeted
                        if (name == wantedPage) {
                            // Junrar reports decoded bytes as a stream; enforce the actual limit too.
                            val out = ByteArrayOutputStream()
                            archive.getInputStream(header).use { stream ->
                                val buffer = ByteArray(32 * 1024)
                                var count = 0L
                                while (true) {
                                    val read = stream.read(buffer)
                                    if (read < 0) break
                                    if (read == 0) continue
                                    count += read
                                    if (count > MAX_IMAGE_BYTES) {
                                        throw SafeCbzArchive.UnsafeArchive("Comic page exceeds the 64 MiB limit")
                                    }
                                    out.write(buffer, 0, read)
                                }
                            }
                            if (out.size() == 0) throw SafeCbzArchive.UnsafeArchive("Empty comic image")
                            selected = out.toByteArray()
                        }
                    }
                }
            }
        } catch (e: SafeCbzArchive.UnsafeArchive) {
            throw e
        } catch (e: Exception) {
            throw SafeCbzArchive.UnsafeArchive("RAR comic cannot be opened: ${e.javaClass.simpleName}")
        }
        if (pages.isEmpty()) throw SafeCbzArchive.UnsafeArchive("No supported comic images were found")
        pages.sortWith(SafeCbzArchive::compareNatural)
        return Index(pages, imageBytes) to selected
    }

    private fun isRarFile(file: File): Boolean = file.inputStream().use { input ->
        val magic = ByteArray(8)
        var size = 0
        while (size < magic.size) {
            val n = input.read(magic, size, magic.size - size)
            if (n < 0) break
            if (n == 0) continue
            size += n
        }
        isRarSignature(magic.copyOf(size))
    }

    private fun ByteArray.startsWithBytes(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
