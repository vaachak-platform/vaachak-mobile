package org.vaachak.reader.leisure.ui.comics

/** Pure shelf selection, extracted from Compose so sorting and vault-visible state can be tested. */
internal object ComicShelfFiltering {
    fun recent(comics: List<ComicShelfStore.Comic>): List<ComicShelfStore.Comic> =
        comics.filter { it.lastOpenedAt > 0L || it.lastPage > 0 }
            .asReversed()
            .sortedByDescending { it.lastOpenedAt }

    fun visible(
        comics: List<ComicShelfStore.Comic>,
        selectedTab: Int,
        query: String,
        sort: String
    ): List<ComicShelfStore.Comic> {
        if (selectedTab == 0) return recent(comics)
        val matches = comics.filter { it.title.contains(query, ignoreCase = true) }
        return when (sort) {
            "Title" -> matches.sortedBy { it.title.lowercase() }
            "Progress" -> matches.sortedByDescending {
                (it.lastPage + 1).toFloat() / it.pageCount.coerceAtLeast(1)
            }
            else -> matches.asReversed().sortedByDescending { it.lastOpenedAt }
        }
    }
}
