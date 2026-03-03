package org.vaachak.reader.leisure.ui.testability

object Tid {
    object Screen {
        const val bookshelf = "screen_bookshelf"
        const val reader = "screen_reader"
        const val settings = "screen_settings"
        const val catalog = "screen_catalog"
        const val highlights = "screen_highlights"
        const val login = "screen_login"
        const val vault = "screen_vault"
        const val session = "screen_session"
    }

    object Tab {
        const val books = "tab_books"
        const val comics = "tab_comics"
        const val audio = "tab_audio"
        const val settings = "tab_settings"
    }

    object Library {
        const val add = "library_add"
        const val search = "library_search"
        const val sync = "library_sync"
        const val sort = "library_sort"
        const val filter = "library_filter"
        const val language = "library_language"

        fun bookByHash(bookHash: String): String = "library_book_${bookHash.asTidSegment()}"
        fun deleteByHash(bookHash: String): String =
            "library_delete_${bookHash.asTidSegment()}"
    }

    object Reader {
        const val back = "reader_back"
        const val tts = "reader_tts"
        const val bookmarkToggle = "reader_bookmark_toggle"
        const val toc = "reader_toc"
        const val bookmarksList = "reader_bookmarks_list"
        const val highlights = "reader_highlights"
        const val overflow = "reader_overflow"
        const val menuSearch = "reader_menu_search"
        const val menuRecap = "reader_menu_recap"
        const val menuSettings = "reader_menu_settings"
    }

    object ReaderSettings {
        const val close = "reader_settings_close"
        const val toggleAi = "reader_settings_toggle_ai"
        const val togglePublisherStyles = "reader_settings_toggle_publisher_styles"
        const val themeLight = "reader_settings_theme_light"
        const val themeDark = "reader_settings_theme_dark"
        const val themeSepia = "reader_settings_theme_sepia"
        const val fontSizeSlider = "reader_settings_font_size_slider"

        fun fontOption(fontName: String): String =
            "reader_settings_font_${fontName.asTidSegment()}"
    }
}

private fun String.asTidSegment(): String {
    val normalized = lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
    return normalized.ifEmpty { "item" }
}
