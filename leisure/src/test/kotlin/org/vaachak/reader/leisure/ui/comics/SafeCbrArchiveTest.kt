package org.vaachak.reader.leisure.ui.comics

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeCbrArchiveTest {
    @Test fun rar4AndRar5MagicAccepted() {
        assertTrue(SafeCbrArchive.isRarSignature(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0)))
        assertTrue(SafeCbrArchive.isRarSignature(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 1, 0)))
    }

    @Test fun rejectZipRenamedCbrAndTruncatedRar() {
        assertFalse(SafeCbrArchive.isRarSignature(byteArrayOf(0x50, 0x4b, 0x03, 0x04)))
        assertFalse(SafeCbrArchive.isRarSignature(byteArrayOf(0x52, 0x61, 0x72, 0x21)))
        val file = File.createTempFile("notrar", ".cbr")
        try {
            file.writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04, 1))
            assertThrows(SafeCbzArchive.UnsafeArchive::class.java) { SafeCbrArchive.inspect(file) }
        } finally {
            file.delete()
        }
    }
}
