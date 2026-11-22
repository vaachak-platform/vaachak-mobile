package org.vaachak.reader.leisure.ui.settings

/** Based on the space available to Settings, not screen pixels, device model or orientation. */
internal object SettingsLayoutPolicy {
    fun useTwoPane(availableWidthDp: Float, fontScale: Float): Boolean {
        // The navigation pane must retain enough width for non-truncated labels.
        val minimumWidth = if (fontScale >= 1.3f) 920f else 720f
        return availableWidthDp >= minimumWidth
    }
}
