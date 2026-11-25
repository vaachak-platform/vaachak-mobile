package org.vaachak.reader.leisure.ui.comics

import java.util.Locale

/** SAF provides MIME filtering (not a portable filename-extension picker filter). */
object ComicDocumentTypes {
    // Intentionally omit */* and application/octet-stream: those made unrelated files
    // visible in the old import picker. Some providers may not report specialist MIME types.
    val pickerMimeTypes: Array<String> = arrayOf(
        "application/vnd.comicbook+zip", "application/x-cbz",
        "application/vnd.comicbook-rar", "application/x-cbr",
        "application/vnd.rar", "application/x-rar", "application/x-rar-compressed",
        "application/zip"
    )

    fun isComicName(filename: String): Boolean = when (filename.substringAfterLast('.', "")
        .lowercase(Locale.ROOT)) {
        "cbz", "cbr" -> true
        else -> false
    }

    /** Actual archive signature wins over a mismatched .cbz/.cbr extension. */
    fun signatureFormat(firstBytes: ByteArray): String? = when {
        SafeCbrArchive.isRarSignature(firstBytes) -> "CBR"
        firstBytes.size >= 4 && firstBytes[0] == 0x50.toByte() &&
            firstBytes[1] == 0x4B.toByte() && firstBytes[2] == 0x03.toByte() &&
            firstBytes[3] == 0x04.toByte() -> "CBZ"
        else -> null
    }
}
