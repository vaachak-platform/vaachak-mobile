package org.vaachak.reader.leisure.ui.comics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicShelfFilteringTest {
    private val comics = listOf(
        ComicShelfStore.Comic("a", "Vol 10", "content://test/a", 10, 4, lastOpenedAt = 200L),
        ComicShelfStore.Comic("b", "vol 2", "content://test/b", 10, 0, lastOpenedAt = 0L),
        ComicShelfStore.Comic("c", "Beta", "content://test/c", 10, 8, lastOpenedAt = 100L),
        ComicShelfStore.Comic("d", "Fresh", "content://test/d", 3, 0, lastOpenedAt = 0L)
    )

    @Test fun `recent includes opened at first page and progress without timestamp`() {
        val openedAtFirstPage = comics[1].copy(lastOpenedAt = 300L)
        val progressOnly = comics[3].copy(lastPage = 1)
        val recent = ComicShelfFiltering.recent(listOf(comics[0], openedAtFirstPage, comics[2], progressOnly))
        assertEquals(listOf("b", "a", "c", "d"), recent.map { it.id })
    }

    @Test fun `recent excludes unread comics`() {
        assertTrue(ComicShelfFiltering.recent(listOf(comics[1], comics[3])).isEmpty())
    }

    @Test fun `reading tab ignores search and sort`() {
        val selected = ComicShelfFiltering.visible(comics, 0, "not a match", "Title")
        assertEquals(listOf("a", "c"), selected.map { it.id })
    }

    @Test fun `title sort is case insensitive`() {
        val selected = ComicShelfFiltering.visible(comics, 1, "", "Title")
        assertEquals(listOf("c", "d", "a", "b"), selected.map { it.id })
    }

    @Test fun `progress sort includes first page and zero page count safely`() {
        val brokenCount = comics[1].copy(pageCount = 0)
        val selected = ComicShelfFiltering.visible(listOf(comics[0], brokenCount, comics[2]), 1, "", "Progress")
        assertEquals(listOf("b", "c", "a"), selected.map { it.id })
    }

    @Test fun `recent sort preserves last opened priority`() {
        val selected = ComicShelfFiltering.visible(comics, 1, "", "Recent")
        assertEquals(listOf("a", "c", "d", "b"), selected.map { it.id })
    }

    @Test fun `search handles case and empty matches`() {
        assertEquals(listOf("a", "b"), ComicShelfFiltering.visible(comics, 1, "VOL", "Recent").map { it.id })
        assertTrue(ComicShelfFiltering.visible(comics, 1, "Missing", "Title").isEmpty())
    }
}
