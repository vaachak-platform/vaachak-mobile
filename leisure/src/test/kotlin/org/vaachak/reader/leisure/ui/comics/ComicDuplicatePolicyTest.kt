package org.vaachak.reader.leisure.ui.comics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComicDuplicatePolicyTest {
    private val existing = listOf(
        ComicDuplicatePolicy.Item("old-uri-id", "content://provider/original", "Skyforge Vol 1", "abcdef"),
        ComicDuplicatePolicy.Item("other", "content://provider/other", "Starfall", null)
    )

    @Test fun duplicateUriIsRejected() {
        assertEquals("Skyforge Vol 1", ComicDuplicatePolicy.findDuplicate(
            existing, "content://provider/original", "new-id", "Changed", "changed")?.title)
    }

    @Test fun sameContentUnderDifferentNameAndUriIsRejected() {
        assertEquals("Skyforge Vol 1", ComicDuplicatePolicy.findDuplicate(
            existing, "content://provider/renamed", "new-id", "Totally new filename", "abcdef")?.title)
    }

    @Test fun identicalTitleIsRejectedCaseInsensitively() {
        assertEquals("Starfall", ComicDuplicatePolicy.findDuplicate(
            existing, "content://provider/new", "new-id", "  STARFALL ", "new-content")?.title)
    }

    @Test fun legacyUriIdentityStillMatches() {
        assertEquals("Skyforge Vol 1", ComicDuplicatePolicy.findDuplicate(
            existing, "content://provider/reselected", "old-uri-id", "Changed", "new-content")?.title)
    }

    @Test fun differentComicCanImportAndAnotherVaultIsIndependent() {
        assertNull(ComicDuplicatePolicy.findDuplicate(
            existing, "content://provider/new", "new-id", "Unique Vol 2", "123456"))
        assertNull(ComicDuplicatePolicy.findDuplicate(
            emptyList(), "content://provider/original", "old-uri-id", "Skyforge Vol 1", "abcdef"))
    }
}
