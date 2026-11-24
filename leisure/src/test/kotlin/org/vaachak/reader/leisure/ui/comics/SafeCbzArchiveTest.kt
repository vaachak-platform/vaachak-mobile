package org.vaachak.reader.leisure.ui.comics

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeCbzArchiveTest {
    private fun cbz(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test fun naturalPageOrderingAndExactBytes() {
        val third = byteArrayOf(9, 8, 7)
        val archive = cbz(
            "chapter/10.png" to byteArrayOf(10),
            "chapter/2.jpg" to byteArrayOf(2),
            "chapter/1.webp" to third,
            "ComicInfo.xml" to "ignored metadata".toByteArray()
        )
        val index = SafeCbzArchive.inspect(ByteArrayInputStream(archive))
        assertEquals(listOf("chapter/1.webp", "chapter/2.jpg", "chapter/10.png"), index.pages)
        assertEquals(5L, index.totalImageBytes)
        assertArrayEquals(third, SafeCbzArchive.readPage(ByteArrayInputStream(archive), "chapter/1.webp"))
    }

    @Test fun archiveMustContainSupportedPages() {
        val archive = cbz("notes.txt" to byteArrayOf(2))
        assertThrows(SafeCbzArchive.UnsafeArchive::class.java) {
            SafeCbzArchive.inspect(ByteArrayInputStream(archive))
        }
    }

    @Test fun rejectZipSlipAndAbsolutePathsWithoutExtractingFiles() {
        for (path in listOf("../cover.png", "/absolute.jpg", "folder\\page.jpg", "C:/drive.png", "a/./b.webp", "a//b.png")) {
            val archive = cbz(path to byteArrayOf(1))
            assertThrows("Expected unsafe path: $path", SafeCbzArchive.UnsafeArchive::class.java) {
                SafeCbzArchive.inspect(ByteArrayInputStream(archive))
            }
        }
    }

    @Test fun rejectCaseInsensitiveDuplicateNames() {
        val archive = cbz("01.JPG" to byteArrayOf(1), "01.jpg" to byteArrayOf(2))
        assertThrows(SafeCbzArchive.UnsafeArchive::class.java) {
            SafeCbzArchive.inspect(ByteArrayInputStream(archive))
        }
    }

    @Test fun rejectDecompressionBombEvenWhenCompressedSizeIsSmall() {
        val archive = cbz("page.png" to ByteArray(16 * 1024 * 1024 + 1))
        assertTrue(archive.size < 200_000)
        assertThrows(SafeCbzArchive.UnsafeArchive::class.java) {
            SafeCbzArchive.inspect(ByteArrayInputStream(archive))
        }
    }

    @Test fun rejectExcessiveMetadata() {
        // Production now permits up to 4 MiB of non-image metadata, not 2 MiB.
        val metadata = cbz("ComicInfo.xml" to ByteArray(4 * 1024 * 1024 + 1), "1.jpg" to byteArrayOf(1))
        assertThrows(SafeCbzArchive.UnsafeArchive::class.java) {
            SafeCbzArchive.inspect(ByteArrayInputStream(metadata))
        }
    }

    @Test fun rejectTooManyEntries() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            repeat(20_001) { index ->
                zip.putNextEntry(ZipEntry("notes/$index.txt"))
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("1.jpg"))
            zip.write(byteArrayOf(1))
            zip.closeEntry()
        }
        assertThrows(SafeCbzArchive.UnsafeArchive::class.java) {
            SafeCbzArchive.inspect(ByteArrayInputStream(out.toByteArray()))
        }
    }

    @Test fun pageReadFailsIfTargetDoesNotExist() {
        val archive = cbz("001.jpg" to byteArrayOf(42))
        assertThrows(SafeCbzArchive.UnsafeArchive::class.java) {
            SafeCbzArchive.readPage(ByteArrayInputStream(archive), "002.jpg")
        }
    }

    @Test fun ignoresResourceForkImagesButStillImportsNormalPages() {
        val archive = cbz("__MACOSX/._1.jpg" to byteArrayOf(1), "images/1.JPG" to byteArrayOf(2))
        assertEquals(listOf("images/1.JPG"), SafeCbzArchive.inspect(ByteArrayInputStream(archive)).pages)
    }
}
