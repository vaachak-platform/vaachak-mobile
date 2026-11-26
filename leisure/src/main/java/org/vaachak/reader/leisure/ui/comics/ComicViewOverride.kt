package org.vaachak.reader.leisure.ui.comics

import org.json.JSONObject
import org.vaachak.reader.core.domain.model.ComicPreferences
import org.vaachak.reader.core.domain.model.ComicReadingMode
import org.vaachak.reader.core.domain.model.ComicReadingDirection
import org.vaachak.reader.core.domain.model.ComicPageFit

enum class ComicViewMode { PAGED_LTR, PAGED_RTL, VERTICAL_PAGER, CONTINUOUS_VERTICAL, WEBTOON }

data class ComicViewOverride(val mode: ComicViewMode, val fit: ComicZoomFit) {
    internal fun encode() = JSONObject().put("mode", mode.name).put("fit", fit.name)
    companion object {
        internal fun decode(value: JSONObject?): ComicViewOverride? {
            if (value == null) return null
            val mode = ComicViewMode.entries.firstOrNull { it.name == value.optString("mode") } ?: return null
            val fit = ComicZoomFit.entries.firstOrNull { it.name == value.optString("fit") } ?: return null
            return ComicViewOverride(mode, fit)
        }
        internal fun from(preferences: ComicPreferences) = ComicViewOverride(
            when (preferences.readingMode) {
                ComicReadingMode.SINGLE_PAGE -> if (preferences.readingDirection == ComicReadingDirection.RIGHT_TO_LEFT)
                    ComicViewMode.PAGED_RTL else ComicViewMode.PAGED_LTR
                ComicReadingMode.VERTICAL_SCROLL -> ComicViewMode.CONTINUOUS_VERTICAL
                ComicReadingMode.VERTICAL_PAGER -> ComicViewMode.VERTICAL_PAGER
                ComicReadingMode.WEBTOON -> ComicViewMode.WEBTOON
            },
            if (preferences.pageFit == ComicPageFit.FIT_WIDTH) ComicZoomFit.WIDTH else ComicZoomFit.PAGE
        )
    }
}
