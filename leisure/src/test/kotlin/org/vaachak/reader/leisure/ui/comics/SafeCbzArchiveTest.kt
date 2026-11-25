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

    @Test fun directoriesAndHiddenEntriesDoNotBecomePages() {
        val archive = cbz(
            "images/" to byteArrayOf(),
            "__MACOSX/cover.jpg" to byteArrayOf(9),
            "images/.hidden.png" to byteArrayOf(8),
            "images/page.gif" to byteArrayOf(7),
            "notes.txt" to byteArrayOf(6),
            "images/0001.jpeg" to byteArrayOf(1, 2),
            "images/0002.PNG" to byteArrayOf(3)
        )
        val indexed = SafeCbzArchive.inspect(ByteArrayInputStream(archive))
        assertEquals(listOf("images/0001.jpeg", "images/0002.PNG"), indexed.pages)
        assertEquals(3L, indexed.totalImageBytes)
        assertArrayEquals(byteArrayOf(3), SafeCbzArchive.readPage(ByteArrayInputStream(archive), "images/0002.PNG"))
    }

    @Test fun rejectEmptyImageAndPathValidationBoundaries() {
        assertThrows(SafeCbzArchive.UnsafeArchive::class.java) {
            SafeCbzArchive.inspect(ByteArrayInputStream(cbz("page.jpg" to byteArrayOf())))
        }
        for (bad in listOf(
            "", "/", "a/../escape.jpg", "a/./image.jpg", "a//image.jpg",
            "folder/image:1.png", "a/\\image.jpg", "a/\u0001.jpg", 
            "x".repeat(513), "x/".repeat(25) + "page.jpg"
        )) {
            assertThrows("Expected unsafe path: $bad", SafeCbzArchive.UnsafeArchive::class.java) {
                SafeCbzArchive.validateEntryName(bad)
            }
        }
        assertEquals("valid/path/", SafeCbzArchive.validateEntryName("valid/path/"))
    }

    @Test fun orderingHandlesLongNumericRunsWithoutOverflow() {
        assertTrue(SafeCbzArchive.compareNatural("issue2.jpg", "issue10.jpg") < 0)
        assertTrue(SafeCbzArchive.compareNatural(
            "page00000000000000000000002.jpg", "page00000000000000000000010.jpg") < 0)
        assertEquals(0, SafeCbzArchive.compareNatural("a.JPG", "A.jpg"))
        assertTrue(SafeCbzArchive.compareNatural("page.jpg", "page2.jpg") < 0)
    }

    @Test fun incompressiblePageCanBeReadWithoutTriggeringBombDetection() {
        val page = kotlin.random.Random(72).nextBytes(ByteArray(1_250_000))
        val archive = cbz("chapter/0001.webp" to page)
        assertTrue(archive.size > 1_000_000)
        assertEquals(page.size.toLong(), SafeCbzArchive.inspect(ByteArrayInputStream(archive)).totalImageBytes)
        assertArrayEquals(page, SafeCbzArchive.readPage(ByteArrayInputStream(archive), "chapter/0001.webp"))
    }

    @Test fun rejectMoreThanFiveThousandImagePages() {
        val stream = ByteArrayOutputStream()
        ZipOutputStream(stream).use { zip ->
            repeat(5_001) { i ->
                zip.putNextEntry(ZipEntry("pages/$i.jpg"))
                zip.write(byteArrayOf(1))
                zip.closeEntry()
            }
        }
        assertThrows(SafeCbzArchive.UnsafeArchive::class.java) {
            SafeCbzArchive.inspect(ByteArrayInputStream(stream.toByteArray()))
        }
    }
}
