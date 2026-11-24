package org.vaachak.reader.leisure.ui.comics

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicFileFingerprintTest {
    @Test fun identicalContentHasSameIdentityWhenFilenameOrUriChanges() {
        val bytes = "renamed comic page content".toByteArray()
        val original = ComicFileFingerprint.calculate(ByteArrayInputStream(bytes))
        val renamed = ComicFileFingerprint.calculate(ByteArrayInputStream(bytes))
        assertEquals(original, renamed)
        assertEquals(64, original.length)
    }

    @Test fun differentContentHasDifferentIdentity() {
        val a = ComicFileFingerprint.calculate(ByteArrayInputStream("page1".toByteArray()))
        val b = ComicFileFingerprint.calculate(ByteArrayInputStream("page2".toByteArray()))
        assertTrue(a != b)
    }

    @Test fun streamCopyIsByteExact() {
        val bytes = byteArrayOf(0, 2, 4, 6, 8)
        val output = ByteArrayOutputStream()
        ComicFileFingerprint.calculate(ByteArrayInputStream(bytes), output)
        assertTrue(bytes.contentEquals(output.toByteArray()))
    }

    @Test fun emptyInputFailsClosed() {
        assertThrows(IOException::class.java) {
            ComicFileFingerprint.calculate(ByteArrayInputStream(byteArrayOf()))
        }
    }
}
