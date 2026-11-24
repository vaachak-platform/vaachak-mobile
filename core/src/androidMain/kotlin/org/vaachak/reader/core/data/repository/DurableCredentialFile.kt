package org.vaachak.reader.core.data.repository

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Ciphertext only. Publish success after data sync, atomic rename, and directory sync.
 * No non-atomic fallback; after a post-rename failure the new file may exist, but the
 * caller must not commit a database reference until a successful durability barrier.
 */
internal class DurableCredentialFile(private val syncDirectory: (File) -> Unit) {
    fun replace(destination: File, ciphertext: ByteArray) {
        val directory = requireNotNull(destination.absoluteFile.parentFile)
        val temporary = File.createTempFile(".private_ai_", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(ciphertext)
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(), destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
            )
            syncDirectory(directory)
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    /** Never create a missing file; retry the durability barrier without rewriting ciphertext. */
    fun ensureDurable(destination: File) {
        FileChannel.open(destination.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
        syncDirectory(requireNotNull(destination.absoluteFile.parentFile))
    }
}
