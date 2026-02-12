package org.vaachak.data.repository

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.vaachak.ui.theme.ThemeMode
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing application settings and user preferences.
 * Uses DataStore to persist settings such as API keys, theme, reader preferences, and sync configs.
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @ApplicationContext private val context: Context
) {

    companion object {
        // --- APP GLOBAL SETTINGS ---
        val GEMINI_KEY = stringPreferencesKey("gemini_api_key")
        val CF_URL = stringPreferencesKey("cloudflare_url") // AI / Image Gen URL
        val CF_TOKEN = stringPreferencesKey("cloudflare_token") // AI Token
        val IS_EINK_ENABLED = booleanPreferencesKey("is_eink_enabled")
        val AUTO_SAVE_RECAPS_KEY = booleanPreferencesKey("auto_save_recaps")
        val THEME_KEY = stringPreferencesKey("theme_mode")
        val CONTRAST_KEY = floatPreferencesKey("eink_contrast")
        val DICTIONARY_FOLDER_KEY = stringPreferencesKey("dictionary_folder")
        val USE_EMBEDDED_DICT = booleanPreferencesKey("use_embedded_dict")
        val OFFLINE_MODE_KEY = booleanPreferencesKey("offline_mode")

        // --- SYNC SETTINGS ---
        val SYNC_CLOUD_URL = stringPreferencesKey("sync_cloud_url")
        val USE_LOCAL_SERVER = booleanPreferencesKey("use_local_server")
        val LOCAL_SERVER_URL = stringPreferencesKey("local_server_url")
        val SYNC_DEVICE_ID = stringPreferencesKey("sync_device_id")
        val LAST_SYNC_TIMESTAMP = longPreferencesKey("last_sync_timestamp")

        // --- READER PREFERENCES ---
        val READER_FONT_FAMILY = stringPreferencesKey("reader_font_family")
        val READER_FONT_SIZE = doublePreferencesKey("reader_font_size")
        val READER_TEXT_ALIGN = stringPreferencesKey("reader_text_align")
        val READER_THEME = stringPreferencesKey("reader_theme")
        val READER_PUBLISHER_STYLES = booleanPreferencesKey("reader_publisher_styles")

        // Layout Sliders
        val READER_LETTER_SPACING = doublePreferencesKey("reader_letter_spacing")
        val READER_LINE_HEIGHT = doublePreferencesKey("reader_line_height")
        val READER_PARAGRAPH_SPACING = doublePreferencesKey("reader_para_spacing")
        val READER_MARGIN_SIDE = doublePreferencesKey("reader_margin_side")
        val READER_MARGIN_TOP = doublePreferencesKey("reader_margin_top")
        val READER_MARGIN_BOTTOM = doublePreferencesKey("reader_margin_bottom")
    }

    // --- APP FLOWS ---
    val geminiKey: Flow<String> = dataStore.data.map { it[GEMINI_KEY] ?: "" }
    val cfUrl: Flow<String> = dataStore.data.map { it[CF_URL] ?: "" }
    val cfToken: Flow<String> = dataStore.data.map { it[CF_TOKEN] ?: "" }
    val isEinkEnabled: Flow<Boolean> = dataStore.data.map { it[IS_EINK_ENABLED] ?: false }
    val isAutoSaveRecapsEnabled: Flow<Boolean> = dataStore.data.map { it[AUTO_SAVE_RECAPS_KEY] ?: true }
    val einkContrast: Flow<Float> = dataStore.data.map { it[CONTRAST_KEY] ?: 0.5f }
    val isOfflineModeEnabled: Flow<Boolean> = dataStore.data.map { it[OFFLINE_MODE_KEY] ?: false }

    val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        val name = prefs[THEME_KEY] ?: ThemeMode.E_INK.name
        try { ThemeMode.valueOf(name) } catch (_: Exception) { ThemeMode.E_INK }
    }

    // --- SYNC FLOWS ---
    val syncCloudUrl: Flow<String> = dataStore.data.map { it[SYNC_CLOUD_URL] ?: "" }
    val useLocalServer: Flow<Boolean> = dataStore.data.map { it[USE_LOCAL_SERVER] ?: false }
    val localServerUrl: Flow<String> = dataStore.data.map { it[LOCAL_SERVER_URL] ?: "" }
    val deviceId: Flow<String> = dataStore.data.map { it[SYNC_DEVICE_ID] ?: "" }
    val lastSyncTimestamp: Flow<Long> = dataStore.data.map { it[LAST_SYNC_TIMESTAMP] ?: 0L }

    // Dictionary Flows
    fun getUseEmbeddedDictionary(): Flow<Boolean> = dataStore.data.map { it[USE_EMBEDDED_DICT] ?: false }
    fun getDictionaryFolder(): Flow<String> = dataStore.data.map { it[DICTIONARY_FOLDER_KEY] ?: "" }

    // --- READER PREF FLOWS ---
    val readerFontFamily: Flow<String?> = dataStore.data.map { it[READER_FONT_FAMILY] }
    val readerFontSize: Flow<Double> = dataStore.data.map { it[READER_FONT_SIZE] ?: 1.0 }
    val readerTextAlign: Flow<String> = dataStore.data.map { it[READER_TEXT_ALIGN] ?: "justify" }
    val readerTheme: Flow<String> = dataStore.data.map { it[READER_THEME] ?: "light" }
    val readerPublisherStyles: Flow<Boolean> = dataStore.data.map { it[READER_PUBLISHER_STYLES] ?: true }

    val readerLetterSpacing: Flow<Double?> = dataStore.data.map { it[READER_LETTER_SPACING] }
    val readerLineHeight: Flow<Double?> = dataStore.data.map { it[READER_LINE_HEIGHT] }
    val readerParaSpacing: Flow<Double?> = dataStore.data.map { it[READER_PARAGRAPH_SPACING] }
    val readerMarginSide: Flow<Double> = dataStore.data.map { it[READER_MARGIN_SIDE] ?: 1.0 }
    val readerMarginTop: Flow<Double> = dataStore.data.map { it[READER_MARGIN_TOP] ?: 1.0 }
    val readerMarginBottom: Flow<Double> = dataStore.data.map { it[READER_MARGIN_BOTTOM] ?: 1.0 }

    // --- SYNC HELPERS ---

    suspend fun getLastSyncTimestamp(): Long {
        return dataStore.data.first()[LAST_SYNC_TIMESTAMP] ?: 0L
    }

    suspend fun setLastSyncTimestamp(ts: Long) {
        dataStore.edit { it[LAST_SYNC_TIMESTAMP] = ts }
    }

    suspend fun isSyncConfigured(): Boolean {
        val prefs = dataStore.data.first()
        val useLocal = prefs[USE_LOCAL_SERVER] ?: false
        val local = prefs[LOCAL_SERVER_URL]
        val cloud = prefs[SYNC_CLOUD_URL]

        return if (useLocal) !local.isNullOrBlank() else !cloud.isNullOrBlank()
    }

    // --- WRITE ACTIONS ---

    suspend fun saveSettings(gemini: String, cfUrl: String, cfToken: String, isEnk: Boolean) {
        dataStore.edit { prefs ->
            prefs[GEMINI_KEY] = gemini.trim()
            var cleanUrl = cfUrl.trim()
            if (cleanUrl.endsWith("/")) cleanUrl = cleanUrl.removeSuffix("/")
            prefs[CF_URL] = cleanUrl
            prefs[CF_TOKEN] = cfToken.trim()
            prefs[IS_EINK_ENABLED] = isEnk
        }
    }

    suspend fun setAutoSaveRecaps(enabled: Boolean) { dataStore.edit { it[AUTO_SAVE_RECAPS_KEY] = enabled } }
    suspend fun setThemeMode(mode: ThemeMode) { dataStore.edit { it[THEME_KEY] = mode.name } }
    suspend fun setContrast(value: Float) { dataStore.edit { it[CONTRAST_KEY] = value } }
    suspend fun setUseEmbeddedDictionary(enabled: Boolean) { dataStore.edit { it[USE_EMBEDDED_DICT] = enabled } }
    suspend fun setDictionaryFolder(uri: String) { dataStore.edit { it[DICTIONARY_FOLDER_KEY] = uri } }
    suspend fun setOfflineMode(enabled: Boolean) { dataStore.edit { it[OFFLINE_MODE_KEY] = enabled } }
    suspend fun clearSettings() { dataStore.edit { it.clear() } }

    suspend fun saveSyncSettings(syncCloudUrl: String, localUrl: String, useLocal: Boolean) {
        dataStore.edit { prefs ->
            var cleanCloud = syncCloudUrl.trim()
            if (cleanCloud.endsWith("/")) cleanCloud = cleanCloud.removeSuffix("/")
            prefs[SYNC_CLOUD_URL] = cleanCloud

            var cleanLocal = localUrl.trim()
            if (cleanLocal.endsWith("/")) cleanLocal = cleanLocal.removeSuffix("/")
            prefs[LOCAL_SERVER_URL] = cleanLocal

            prefs[USE_LOCAL_SERVER] = useLocal
        }
    }

    suspend fun ensureDeviceId(): String {
        val prefs = dataStore.data.first()
        val existing = prefs[SYNC_DEVICE_ID]
        if (!existing.isNullOrBlank()) return existing

        val newId = "android-${UUID.randomUUID().toString().take(8)}"
        // Wait for the edit to complete
        dataStore.edit { it[SYNC_DEVICE_ID] = newId }

        // Return the newId directly to ensure the caller has it immediately
        return newId
    }

    suspend fun updateReaderPreferences(
        fontFamily: String? = null,
        fontSize: Double? = null,
        textAlign: String? = null,
        theme: String? = null,
        publisherStyles: Boolean? = null,
        letterSpacing: Double? = null,
        lineHeight: Double? = null,
        paraSpacing: Double? = null,
        marginSide: Double? = null,
        marginTop: Double? = null,
        marginBottom: Double? = null
    ) {
        dataStore.edit { prefs ->
            fontFamily?.let { prefs[READER_FONT_FAMILY] = it }
            fontSize?.let { prefs[READER_FONT_SIZE] = it }
            textAlign?.let { prefs[READER_TEXT_ALIGN] = it }
            theme?.let { prefs[READER_THEME] = it }
            publisherStyles?.let { prefs[READER_PUBLISHER_STYLES] = it }
            letterSpacing?.let { prefs[READER_LETTER_SPACING] = it }
            lineHeight?.let { prefs[READER_LINE_HEIGHT] = it }
            paraSpacing?.let { prefs[READER_PARAGRAPH_SPACING] = it }
            marginSide?.let { prefs[READER_MARGIN_SIDE] = it }
            marginTop?.let { prefs[READER_MARGIN_TOP] = it }
            marginBottom?.let { prefs[READER_MARGIN_BOTTOM] = it }
        }
    }

    suspend fun resetReaderLayout() {
        dataStore.edit { prefs ->
            prefs.remove(READER_TEXT_ALIGN)
            prefs.remove(READER_PUBLISHER_STYLES)
            prefs.remove(READER_LETTER_SPACING)
            prefs.remove(READER_PARAGRAPH_SPACING)
            prefs.remove(READER_MARGIN_SIDE)
            prefs.remove(READER_MARGIN_TOP)
            prefs.remove(READER_MARGIN_BOTTOM)
        }
    }

    fun validateStarDictFolder(uriString: String): Boolean {
        return try {
            val uri = Uri.parse(uriString)
            val dir = DocumentFile.fromTreeUri(context, uri)
            if (dir == null || !dir.isDirectory || !dir.canRead()) return false
            val files = dir.listFiles()
            files.any { it.name?.endsWith(".idx", ignoreCase = true) == true }
        } catch (e: Exception) { false }
    }
}