package org.vaachak.reader.leisure.ui.comics

import com.github.junrar.rarfile.FileHeader
import io.mockk.every
import io.mockk.mockk
import java.io.File
import org.junit.Assert.assertEquals
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
            assertTrue(file.delete())
        }
    }

    private fun header(
        name: String,
        unpacked: Long = 64,
        packed: Long = 64,
        encrypted: Boolean = false,
        splitBefore: Boolean = false,
        splitAfter: Boolean = false,
        directory: Boolean = false
    ): FileHeader = mockk {
        every { fileName } returns name
        every { fullUnpackSize } returns unpacked
        every { fullPackSize } returns packed
        every { isEncrypted } returns encrypted
        every { isSplitBefore } returns splitBefore
        every { isSplitAfter } returns splitAfter
        every { isDirectory } returns directory
    }

    @Test fun validHeaderIsIndexedAndMetadataIsIgnored() {
        val catalog = SafeCbrArchive.Inspection()
        assertEquals(null, catalog.add(header("ComicInfo.xml", unpacked = 11)))
        assertEquals("pages/01.JPG", catalog.add(header("pages\\01.JPG")))
        assertEquals("pages/02.png", catalog.add(header("pages/02.png", unpacked = -1)))
        assertEquals(2, catalog.pages.size)
        assertEquals(64L + 64L * 1024 * 1024, catalog.imageBytes)
        assertEquals(null, catalog.add(header("pages/", directory = true)))
    }

    @Test fun duplicateAndTraversalHeadersAreRejected() {
        val catalog = SafeCbrArchive.Inspection()
        catalog.add(header("pages/A.jpg"))
        assertThrows(SafeCbzArchive.UnsafeArchive::class.java) { catalog.add(header("pages/a.JPG")) }
        assertThrows(SafeCbzArchive.UnsafeArchive::class.java) { catalog.add(header("../escape.png")) }
    }

    @Test fun encryptedAndMultivolumeEntriesAreRejected() {
        listOf(
            header("a.jpg", encrypted = true),
            header("a.jpg", splitBefore = true),
            header("a.jpg", splitAfter = true)
        ).forEach { entry ->
            assertThrows(SafeCbzArchive.UnsafeArchive::class.java) {
                SafeCbrArchive.Inspection().add(entry)
            }
        }
    }

    @Test fun hugeAndSuspiciousPageHeadersAreRejected() {
        val invalid = listOf(
            header("a.png", unpacked = 0),
            header("a.png", unpacked = 64L * 1024 * 1024 + 1),
            header("a.png", unpacked = 8L * 1024 * 1024, packed = 0),
            header("a.png", unpacked = 8L * 1024 * 1024, packed = 1)
        )
        invalid.forEach { entry ->
            assertThrows(SafeCbzArchive.UnsafeArchive::class.java) {
                SafeCbrArchive.Inspection().add(entry)
            }
        }
    }

    @Test fun anExcessiveDeclaredTotalIsRejected() {
        val index = SafeCbrArchive.Inspection()
        index.add(header("large.bin", unpacked = 8L * 1024 * 1024 * 1024))
        assertThrows(SafeCbzArchive.UnsafeArchive::class.java) {
            index.add(header("next.jpg", unpacked = 64))
        }
    }

    @Test fun signaturesRejectShortPrefixesAndAcceptRARHeadersWithTrailingBytes() {
        assertFalse(SafeCbrArchive.isRarSignature(byteArrayOf()))
        assertFalse(SafeCbrArchive.isRarSignature(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07)))
        assertFalse(SafeCbrArchive.isRarSignature(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 1)))
        assertTrue(SafeCbrArchive.isRarSignature(
            byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0, 0x12, 0x13)))
        assertTrue(SafeCbrArchive.isRarSignature(
            byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 1, 0, 0x12)))
    }

    @Test fun missingEmptyAndTruncatedArchivesFailWithoutExtraction() {
        val empty = File.createTempFile("empty-comic", ".cbr")
        val missing = File(empty.parentFile, "missing-${System.nanoTime()}.cbr")
        try {
            assertThrows(SafeCbzArchive.UnsafeArchive::class.java) { SafeCbrArchive.inspect(empty) }
            assertThrows(SafeCbzArchive.UnsafeArchive::class.java) { SafeCbrArchive.inspect(missing) }
            assertThrows(SafeCbzArchive.UnsafeArchive::class.java) { SafeCbrArchive.readPage(missing, "1.jpg") }
            empty.writeBytes(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0))
            assertThrows(SafeCbzArchive.UnsafeArchive::class.java) { SafeCbrArchive.inspect(empty) }
        } finally {
            assertTrue(empty.delete())
        }
    }

    @Test fun directoriesHiddenFilesAndNonImageFormatsAreIgnored() {
        val catalog = SafeCbrArchive.Inspection()
        assertEquals(null, catalog.add(header("chapters/", directory = true)))
        assertEquals(null, catalog.add(header("__MACOSX/1.png")))
        assertEquals(null, catalog.add(header("chapters/.thumbnail.jpg")))
        assertEquals(null, catalog.add(header("chapters/cover.gif")))
        assertEquals(null, catalog.add(header("ComicInfo.xml", unpacked = -1)))
        assertEquals("chapters/1.webp", catalog.add(header("chapters\\1.webp", unpacked = -1, packed = -1)))
        assertEquals(1, catalog.pages.size)
        assertEquals(64L * 1024 * 1024, catalog.imageBytes)
    }

    @Test fun safeCompressionRatioBoundariesAreAccepted() {
        val catalog = SafeCbrArchive.Inspection()
        assertEquals("first.png", catalog.add(header("first.png", unpacked = 2L * 1024 * 1024,
            packed = 4_194)))
        assertEquals("second.jpg", catalog.add(header("second.jpg", unpacked = 2L * 1024 * 1024,
            packed = -1)))
        assertEquals(4L * 1024 * 1024, catalog.imageBytes)
    }

    @Test fun invalidHeaderPathsAreRejectedBeforeExtraction() {
        for (path in listOf("/cover.png", "C:/cover.png", "dir/../cover.png", "dir//page.jpg",
            "path/./page.png", "x".repeat(513), "a/\u0001.jpg", "a/".repeat(25) + "1.jpg")) {
            assertThrows("Unexpectedly accepted: $path", SafeCbzArchive.UnsafeArchive::class.java) {
                SafeCbrArchive.Inspection().add(header(path))
            }
        }
    }
}
