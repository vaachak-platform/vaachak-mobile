package org.vaachak.reader.leisure.ui.comics

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.vaachak.reader.core.domain.model.ComicPreferences
import org.vaachak.reader.core.domain.model.ComicReadingMode
import org.vaachak.reader.core.domain.model.ComicReadingDirection
import org.vaachak.reader.core.domain.model.ComicPageFit

@RunWith(RobolectricTestRunner::class)
class ComicViewOverrideTest {
    @Test fun legacyAndNewDefaultsResolveAllFiveModes() {
        val defaults = ComicPreferences()
        assertEquals(ComicViewMode.PAGED_LTR, ComicViewOverride.from(defaults).mode)
        assertEquals(ComicViewMode.PAGED_RTL, ComicViewOverride.from(defaults.copy(readingDirection = ComicReadingDirection.RIGHT_TO_LEFT)).mode)
        assertEquals(ComicViewMode.CONTINUOUS_VERTICAL, ComicViewOverride.from(defaults.copy(readingMode = ComicReadingMode.VERTICAL_SCROLL)).mode)
        assertEquals(ComicViewMode.VERTICAL_PAGER, ComicViewOverride.from(defaults.copy(readingMode = ComicReadingMode.VERTICAL_PAGER)).mode)
        assertEquals(ComicViewMode.WEBTOON, ComicViewOverride.from(defaults.copy(readingMode = ComicReadingMode.WEBTOON)).mode)
        assertEquals(ComicZoomFit.WIDTH, ComicViewOverride.from(defaults.copy(pageFit = ComicPageFit.FIT_WIDTH)).fit)
    }
    @Test fun overrideRoundTripsAndUnknownValuesDoNotCrashLegacyShelf() {
        for (mode in ComicViewMode.entries) for (fit in ComicZoomFit.entries) {
            val value = ComicViewOverride(mode, fit)
            assertEquals(value, ComicViewOverride.decode(value.encode()))
        }
        assertNull(ComicViewOverride.decode(null))
        assertNull(ComicViewOverride.decode(JSONObject().put("mode", "unknown")))
        assertNull(ComicViewOverride.decode(JSONObject().put("mode", "PAGED_RTL").put("fit", "unknown")))
    }
}
