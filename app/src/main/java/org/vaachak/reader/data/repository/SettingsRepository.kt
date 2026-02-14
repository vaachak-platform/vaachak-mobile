package org.vaachak.reader.data.repository

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.vaachak.reader.ui.theme.ThemeMode
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
//UI rewrite
import kotlinx.coroutines.flow.combine
import org.vaachak.reader.domain.models.AiConfig


@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @ApplicationContext private val context: Context
) {

    companion object {
        // --- APP GLOBAL SETTINGS ---
        val GEMINI_KEY = stringPreferencesKey("gemini_api_key")
        val CF_URL = stringPreferencesKey("cloudflare_url")
        val CF_TOKEN = stringPreferencesKey("cloudflare_token")
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

        // Auth & Personalization
        val SYNC_USERNAME = stringPreferencesKey("sync_username")
        val SYNC_PASSWORD = stringPreferencesKey("sync_password")
        val DEVICE_NAME = stringPreferencesKey("device_name")

        // --- READER PREFERENCES ---
        val READER_FONT_FAMILY = stringPreferencesKey("reader_font_family")
        val READER_FONT_SIZE = doublePreferencesKey("reader_font_size")
        val READER_TEXT_ALIGN = stringPreferencesKey("reader_text_align")
        val READER_THEME = stringPreferencesKey("reader_theme")
        val READER_PUBLISHER_STYLES = booleanPreferencesKey("reader_publisher_styles")
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
    val syncUsername: Flow<String> = dataStore.data.map { it[SYNC_USERNAME] ?: "" }
    val syncPassword: Flow<String> = dataStore.data.map { it[SYNC_PASSWORD] ?: "" }
    val deviceName: Flow<String> = dataStore.data.map { preferences ->
        val savedName = preferences[DEVICE_NAME]

        // Check for NULL or EMPTY. If either, use the hardware model.
        if (savedName.isNullOrBlank()) {
            val manufacturer = android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
            val model = android.os.Build.MODEL
            if (model.startsWith(manufacturer)) model else "$manufacturer $model"
        } else {
            savedName
        }
    }

    // --- READER PREF FLOWS ---
    val readerFontFamily: Flow<String?> = dataStore.data.map { it[READER_FONT_FAMILY] }
    val readerFontSize: Flow<Double> = dataStore.data.map { it[READER_FONT_SIZE] ?: 1.0 }
    val readerTextAlign: Flow<String> = dataStore.data.map { it[READER_TEXT_ALIGN] ?: "justify" }
    val readerTheme: Flow<String> = dataStore.data.map { it[READER_THEME] ?: "light" }
    val readerPublisherStyles: Flow<Boolean> = dataStore.data.map { it[READER_PUBLISHER_STYLES] ?: true }
    val readerLetterSpacing: Flow<Double> = dataStore.data.map { it[READER_LETTER_SPACING] ?: 1.0 }
    val readerLineHeight: Flow<Double> = dataStore.data.map { it[READER_LINE_HEIGHT] ?: 1.0}
    val readerParaSpacing: Flow<Double> = dataStore.data.map { it[READER_PARAGRAPH_SPACING]  ?: 1.0 }
    val readerMarginSide: Flow<Double> = dataStore.data.map { it[READER_MARGIN_SIDE] ?: 1.0 }

    // Dictionary Flows
    fun getUseEmbeddedDictionary(): Flow<Boolean> = dataStore.data.map { it[USE_EMBEDDED_DICT] ?: false }
    fun getDictionaryFolder(): Flow<String> = dataStore.data.map { it[DICTIONARY_FOLDER_KEY] ?: "" }

    // --- SYNC ACTIONS ---

    suspend fun saveDeviceName(name: String) {
        dataStore.edit { preferences ->
            preferences[DEVICE_NAME] = name
        }
    }

    suspend fun updateSyncProfile(user: String, pass: String, name: String) {
        dataStore.edit { prefs ->
            prefs[SYNC_USERNAME] = user.trim()
            prefs[SYNC_PASSWORD] = pass
            prefs[DEVICE_NAME] = name.trim()
        }
    }

    /**
     * Updates sync configuration.
     * Now optionally saves the Device Name to ensure accurate identification during login.
     */
    suspend fun saveSyncSettings(
        syncCloudUrl: String,
        localUrl: String,
        useLocal: Boolean,
        deviceName: String? = null // <--- ADD THIS PARAMETER
    ) {
        dataStore.edit { prefs ->
            prefs[SYNC_CLOUD_URL] = syncCloudUrl.trim().removeSuffix("/")
            prefs[LOCAL_SERVER_URL] = localUrl.trim().removeSuffix("/")
            prefs[USE_LOCAL_SERVER] = useLocal

            // If provided, save the device name too
            if (!deviceName.isNullOrBlank()) {
                prefs[DEVICE_NAME] = deviceName.trim()
            }
        }
    }

    suspend fun getLastSyncTimestamp(): Long = dataStore.data.first()[LAST_SYNC_TIMESTAMP] ?: 0L

    suspend fun setLastSyncTimestamp(ts: Long) {
        dataStore.edit { it[LAST_SYNC_TIMESTAMP] = ts }
    }

    suspend fun ensureDeviceId(): String {
        val existing = dataStore.data.first()[SYNC_DEVICE_ID]
        if (!existing.isNullOrBlank()) return existing

        val newId = "android-${UUID.randomUUID().toString().take(8)}"
        dataStore.edit { it[SYNC_DEVICE_ID] = newId }
        return newId
    }

    // --- APP SETTINGS ACTIONS ---

    suspend fun saveSettings(gemini: String, cfUrl: String, cfToken: String, isEnk: Boolean) {
        dataStore.edit { prefs ->
            prefs[GEMINI_KEY] = gemini.trim()
            prefs[CF_URL] = cfUrl.trim().removeSuffix("/")
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

    fun validateStarDictFolder(uriString: String): Boolean {
        return try {
            val uri = Uri.parse(uriString)
            val dir = DocumentFile.fromTreeUri(context, uri)
            dir?.let { it.isDirectory && it.canRead() && it.listFiles().any { f -> f.name?.endsWith(".idx", true) == true } } ?: false
        } catch (e: Exception) { false }
    }

    //UI rewrite code
    // --- NEW: Combined Flows for UI State ---

    // 1. Unified AI Config Flow
    // This allows the ViewModel to get a single "AiConfig" object
    val aiConfig: Flow<AiConfig> = combine(
        geminiKey,
        cfUrl,
        cfToken,
        isAutoSaveRecapsEnabled
    ) { key, url, token, autoSave ->
        AiConfig(
            // If key is present, we assume AI is "Enabled" for now.
            // You can add a specific IS_AI_ENABLED preference later if you want a master switch.
            isEnabled = key.isNotBlank(),
            geminiKey = key,
            cloudflareUrl = url,
            authToken = token,
            autoSaveRecaps = autoSave
        )
    }

    // 2. Unified AI Update Function
    suspend fun updateAiConfig(
        enabled: Boolean,
        geminiKey: String,
        cfUrl: String,
        token: String,
        autoSave: Boolean
    ) {
        dataStore.edit { prefs ->
            // If we add a master switch later, save 'enabled' here
            prefs[GEMINI_KEY] = geminiKey.trim()
            prefs[CF_URL] = cfUrl.trim().removeSuffix("/")
            prefs[CF_TOKEN] = token.trim()
            prefs[AUTO_SAVE_RECAPS_KEY] = autoSave
        }
    }

}