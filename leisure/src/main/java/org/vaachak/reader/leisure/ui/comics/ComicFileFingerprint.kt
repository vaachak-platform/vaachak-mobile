package org.vaachak.reader.leisure.ui.comics

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** SHA-256 content identity; never uses the filename, title, or provider URI. */
object ComicFileFingerprint {
    const val MAX_ARCHIVE_BYTES = SafeCbzArchive.MAX_ARCHIVE_BYTES

    fun calculate(source: InputStream, copyTo: OutputStream? = null): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val read = source.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read.toLong()
            if (total > MAX_ARCHIVE_BYTES) throw IOException("Comic archive exceeds 2 GiB")
            digest.update(buffer, 0, read)
            copyTo?.write(buffer, 0, read)
        }
        if (total == 0L) throw IOException("Comic archive is empty")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
