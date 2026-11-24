package org.vaachak.reader.leisure.ui.comics

/** Pure duplicate detection used by the vault-local comics store. */
object ComicDuplicatePolicy {
    data class Item(val id: String, val uri: String, val title: String, val sha256: String?)

    fun findDuplicate(
        existing: List<Item>,
        uri: String,
        legacyUriId: String,
        title: String,
        sha256: String?
    ): Item? = existing.firstOrNull { item ->
        item.uri == uri || item.id == legacyUriId ||
            (sha256 != null && item.sha256 == sha256) ||
            item.title.trim().equals(title.trim(), ignoreCase = true)
    }
}
