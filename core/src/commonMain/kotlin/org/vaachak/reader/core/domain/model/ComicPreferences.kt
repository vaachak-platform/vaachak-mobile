package org.vaachak.reader.core.domain.model

/**
 * Reader-independent comic preferences. These values are kept in each local vault
 * so that a future comics engine can consume them without changing the settings API.
 */
enum class ComicReadingDirection { LEFT_TO_RIGHT, RIGHT_TO_LEFT }
enum class ComicReadingMode { SINGLE_PAGE, VERTICAL_SCROLL }
enum class ComicPageFit { FIT_PAGE, FIT_WIDTH }

data class ComicPreferences(
    val readingDirection: ComicReadingDirection = ComicReadingDirection.LEFT_TO_RIGHT,
    val readingMode: ComicReadingMode = ComicReadingMode.SINGLE_PAGE,
    val pageFit: ComicPageFit = ComicPageFit.FIT_PAGE,
    val tapToTurn: Boolean = true,
    val useVolumeKeys: Boolean = false,
    val preloadNextPage: Boolean = true,
    val reduceMotion: Boolean = true
)
