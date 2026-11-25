package org.vaachak.reader.leisure.ui.comics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicDocumentTypesTest {
    @Test fun pickerRequestsOnlyArchiveMimeTypes() {
        val allowed = ComicDocumentTypes.pickerMimeTypes.toList()
        assertFalse(allowed.contains("*/*"))
        assertFalse(allowed.contains("application/octet-stream"))
        assertTrue(allowed.contains("application/x-cbr"))
        assertTrue(allowed.contains("application/x-cbz"))
    }

    @Test fun filenamesStillRequireComicExtension() {
        assertTrue(ComicDocumentTypes.isComicName("issue.CBR"))
        assertTrue(ComicDocumentTypes.isComicName("issue.cbz"))
        assertFalse(ComicDocumentTypes.isComicName("notes.rar"))
        assertFalse(ComicDocumentTypes.isComicName("photos.zip"))
    }

    @Test fun archiveSignatureOverridesExtension() {
        assertEquals("CBR", ComicDocumentTypes.signatureFormat(
            byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)
        ))
        assertEquals("CBZ", ComicDocumentTypes.signatureFormat(
            byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        ))
        assertNull(ComicDocumentTypes.signatureFormat("garbage".toByteArray()))
    }
}
