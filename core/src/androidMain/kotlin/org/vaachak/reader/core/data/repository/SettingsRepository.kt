package org.vaachak.reader.core.data.repository

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.vaachak.reader.core.common.AppCoroutineConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.vaachak.reader.core.domain.model.AiTextProvider
import org.vaachak.reader.core.domain.model.ComicPreferences
import org.vaachak.reader.core.domain.model.ComicReadingDirection
import org.vaachak.reader.core.domain.model.ComicReadingMode
import org.vaachak.reader.core.domain.model.ComicPageFit
import org.vaachak.reader.core.domain.model.BookshelfPreferences
import org.vaachak.reader.core.domain.model.CoverAspectRatio
import org.vaachak.reader.core.domain.model.DitheringMode
import org.vaachak.reader.core.domain.model.ReaderPreferences
import org.vaachak.reader.core.domain.model.ThemeMode
import org.vaachak.reader.core.domain.model.TtsSettings
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vaultRepository: VaultRepository,
    private val privateGatewaySecrets: PrivateGatewaySecrets
) {

    companion object {
        // --- APP GLOBAL SETTINGS ---
        val IS_AI_ENABLED = booleanPreferencesKey("is_ai_enabled")
        val GEMINI_KEY = stringPreferencesKey("gemini_api_key")
        val CF_URL = stringPreferencesKey("cloudflare_url")
        val CF_TOKEN = stringPreferencesKey("cloudflare_token")
        // Non-secret revision used to notify consumers when encrypted files change.
        private val CREDENTIAL_REVISION = intPreferencesKey("encrypted_credential_revision")

        // AI provider routing (per-vault). These keys must coexist with the
        // existing Gemini/Cloudflare settings and the comic preferences.
        val AI_TEXT_PROVIDER = stringPreferencesKey("ai_text_provider")
        val PRIVATE_GATEWAY_URL = stringPreferencesKey("private_gateway_url")
        val PRIVATE_GATEWAY_MODEL = stringPreferencesKey("private_gateway_model")
        val PRIVATE_GATEWAY_TOKEN = stringPreferencesKey("private_gateway_token")
        val IS_EINK_ENABLED = booleanPreferencesKey("is_eink_enabled")
        val AUTO_SAVE_RECAPS_KEY = booleanPreferencesKey("auto_save_recaps")
        val THEME_KEY = stringPreferencesKey("theme_mode")
        val CONTRAST_KEY = floatPreferencesKey("eink_contrast")
        val DICTIONARY_FOLDER_KEY = stringPreferencesKey("dictionary_folder")
        val USE_EMBEDDED_DICT = booleanPreferencesKey("use_embedded_dict")
        val OFFLINE_MODE_KEY = booleanPreferencesKey("offline_mode")

        // --- TTS SETTINGS ---
        val TTS_DEFAULT_SPEED = floatPreferencesKey("tts_default_speed")
        val TTS_AUTO_PAGE_TURN = booleanPreferencesKey("tts_auto_page_turn")
        val TTS_VISUAL_STYLE = stringPreferencesKey("tts_visual_style")
        val TTS_LANGUAGE = stringPreferencesKey("tts_language")
        val TTS_PITCH = floatPreferencesKey("tts_pitch")
        val TTS_BACKGROUND_PLAYBACK = booleanPreferencesKey("tts_background_playback")
        val TTS_SLEEP_TIMER = intPreferencesKey("tts_sleep_timer")
        val TTS_VOICE = stringPreferencesKey("tts_voice")

        // --- COMIC READER SETTINGS (PER-VAULT) ---
        val COMIC_READING_DIRECTION = stringPreferencesKey("comic_reading_direction")
        val COMIC_READING_MODE = stringPreferencesKey("comic_reading_mode")
        val COMIC_PAGE_FIT = stringPreferencesKey("comic_page_fit")
        val COMIC_TAP_TO_TURN = booleanPreferencesKey("comic_tap_to_turn")
        val COMIC_VOLUME_KEYS = booleanPreferencesKey("comic_volume_keys")
        val COMIC_PRELOAD_NEXT = booleanPreferencesKey("comic_preload_next")
        val COMIC_REDUCE_MOTION = booleanPreferencesKey("comic_reduce_motion")

        // --- SYNC SETTINGS ---
        val IS_SYNC_ENABLED = booleanPreferencesKey("is_sync_enabled")
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

        val READER_WORD_SPACING = doublePreferencesKey("reader_word_spacing")
        val READER_PARAGRAPH_INDENT = doublePreferencesKey("reader_paragraph_indent")
        val READER_HYPHENS = booleanPreferencesKey("reader_hyphens")
        val READER_LIGATURES = booleanPreferencesKey("reader_ligatures")

        // --- LIBRARY & COVER STYLE SETTINGS ---
        val DITHERING_MODE_KEY = stringPreferencesKey("dithering_mode")
        val GROUP_BY_SERIES_KEY = booleanPreferencesKey("group_by_series")
        val COVER_ASPECT_RATIO_KEY = stringPreferencesKey("cover_aspect_ratio")
        val SHOW_FORMAT_BADGE_KEY = booleanPreferencesKey("show_format_badge")
        val SHOW_FAVORITE_ICON_KEY = booleanPreferencesKey("show_favorite_icon")
        val SHOW_PROGRESS_BADGE_KEY = booleanPreferencesKey("show_progress_badge")
        val SHOW_SYNC_STATUS_KEY = booleanPreferencesKey("show_sync_status")
    }

    // --- 1. THE THREAD-SAFE VAULT CACHE ---
    private val activeVaults = mutableMapOf<String, DataStore<Preferences>>()
    private val vaultMutex = Mutex()
    private val gatewayMigrationMutex = Mutex()
    private val credentialMutex = Mutex()

    private enum class CredentialSlot { GEMINI, CLOUDFLARE, SYNC_PASSWORD }

    /** Reserved namespace prevents collisions with existing Gateway vault files. */
    private fun credentialId(vaultId: String, slot: CredentialSlot): String =
        "vaachak_credential_v1:${slot.name}:\u0000$vaultId"

    /** Secure slot used by existing repository mocks, without changing public APIs. */
    private fun credentialFlow(legacyKey: Preferences.Key<String>, slot: CredentialSlot): Flow<String> =
        vaultRepository.activeVaultId.flatMapLatest { vaultId ->
            getVaultDataStore(vaultId).data.map {
                readAndMigrateCredential(vaultId, legacyKey, slot)
            }
        }

    private suspend fun readAndMigrateCredential(
        vaultId: String,
        legacyKey: Preferences.Key<String>,
        slot: CredentialSlot
    ): String = withContext(AppCoroutineConfig.io) {
        credentialMutex.withLock { readAndMigrateCredentialLocked(vaultId, legacyKey, slot) }
    }

    private suspend fun readAndMigrateCredentialLocked(
        vaultId: String,
        legacyKey: Preferences.Key<String>,
        slot: CredentialSlot
    ): String = try {
        val store = getVaultDataStore(vaultId)
        val key = credentialId(vaultId, slot)
        val legacy = store.data.first()[legacyKey]
        if (legacy != null) {
            if (legacy.isNotEmpty()) {
                privateGatewaySecrets.write(key, legacy)
                check(privateGatewaySecrets.read(key) == legacy) {
                    "Encrypted credential verification failed"
                }
            }
            // Only remove plaintext once ciphertext has been verified.
            store.edit { prefs ->
                prefs.remove(legacyKey)
                prefs[CREDENTIAL_REVISION] = (prefs[CREDENTIAL_REVISION] ?: 0) + 1
            }
        }
        privateGatewaySecrets.read(key)
    } catch (_: GatewayCredentialUnavailableException) {
        // No plaintext network fallback. Leave legacy preference for retry after unlock.
        ""
    }

    private suspend fun getVaultDataStore(vaultId: String): DataStore<Preferences> {
        vaultMutex.withLock {
            return activeVaults.getOrPut(vaultId) {
                PreferenceDataStoreFactory.create(
                    produceFile = { context.preferencesDataStoreFile("vault_$vaultId") }
                )
            }
        }
    }

    // Helper function for the edit/save methods
    private suspend fun editCurrentVault(transform: suspend (MutablePreferences) -> Unit) {
        val currentVaultId = vaultRepository.activeVaultId.first()
        val dataStore = getVaultDataStore(currentVaultId)
        dataStore.edit { transform(it) }
    }

    // --- 2. THE MASTER DYNAMIC FLOW ---
    private val vaultPreferencesFlow: Flow<Preferences> = vaultRepository.activeVaultId
        .flatMapLatest { vaultId -> getVaultDataStore(vaultId).data }

    // --- APP FLOWS ---
    val isAiEnabled: Flow<Boolean> = vaultPreferencesFlow.map { it[IS_AI_ENABLED] ?: false }
    val geminiKey: Flow<String> = credentialFlow(GEMINI_KEY, CredentialSlot.GEMINI)
    val cfUrl: Flow<String> = vaultPreferencesFlow.map { it[CF_URL] ?: "" }
    val cfToken: Flow<String> = credentialFlow(CF_TOKEN, CredentialSlot.CLOUDFLARE)

    // Interpret unknown/obsolete provider IDs as Gemini instead of crashing
    // when a user's saved preferences are from another application version.
    val aiTextProvider: Flow<AiTextProvider> = vaultPreferencesFlow.map { prefs ->
        when (prefs[AI_TEXT_PROVIDER]) {
            "PRIVATE_GATEWAY" -> AiTextProvider.PRIVATE_GATEWAY
            else -> AiTextProvider.GEMINI
        }
    }
    val privateGatewayUrl: Flow<String> = vaultPreferencesFlow.map {
        it[PRIVATE_GATEWAY_URL] ?: ""
    }
    val privateGatewayModel: Flow<String> = vaultPreferencesFlow.map {
        it[PRIVATE_GATEWAY_MODEL] ?: ""
    }

    /**
     * Migrate pre-existing plaintext DataStore credentials into no-backup encrypted storage.
     * If encryption fails the old value is left intact, so a failed upgrade never loses
     * the credential. The vault ID is captured once to avoid crossing profiles mid-call.
     */
    suspend fun loadPrivateGatewayToken(): String = withContext(AppCoroutineConfig.io) {
        gatewayMigrationMutex.withLock { loadGatewayTokenLocked(vaultRepository.activeVaultId.first()) }
    }

    private suspend fun loadGatewayTokenLocked(vaultId: String): String {
        val dataStore = getVaultDataStore(vaultId)
        val legacy = dataStore.data.first()[PRIVATE_GATEWAY_TOKEN]
        if (legacy != null) {
            if (legacy.isNotEmpty()) privateGatewaySecrets.write(vaultId, legacy)
            dataStore.edit { it.remove(PRIVATE_GATEWAY_TOKEN) }
            if (legacy.isEmpty()) return ""
        }
        return privateGatewaySecrets.read(vaultId)
    }
    /** A fixed-vault network snapshot. Existing key storage and migration paths are reused. */
    internal suspend fun loadAiRequest(kind: AiRequestKind, expectedVault: String?): AiRequestConfig =
        withContext(AppCoroutineConfig.io) {
            // Existing writers take one lock only; the network snapshot takes both in this fixed order.
            credentialMutex.withLock {
                gatewayMigrationMutex.withLock { loadAiRequestLocked(kind, expectedVault) }
            }
        }

    private suspend fun loadAiRequestLocked(kind: AiRequestKind, expectedVault: String?): AiRequestConfig {
        val vaultId = vaultRepository.persistedVaultIdentity.first()
        if (expectedVault != null && expectedVault != vaultId) {
            throw CancellationException("AI vault changed")
        }
        val store = getVaultDataStore(vaultId)
        val before = store.data.first()
        if (before[OFFLINE_MODE_KEY] ?: true) throw AiOfflineException()
        val provider = if (before[AI_TEXT_PROVIDER] == "PRIVATE_GATEWAY") {
            AiTextProvider.PRIVATE_GATEWAY
        } else AiTextProvider.GEMINI
        val credential = when {
            kind == AiRequestKind.IMAGE -> readAndMigrateCredentialLocked(vaultId, CF_TOKEN, CredentialSlot.CLOUDFLARE)
            provider == AiTextProvider.PRIVATE_GATEWAY -> loadGatewayTokenLocked(vaultId)
            else -> readAndMigrateCredentialLocked(vaultId, GEMINI_KEY, CredentialSlot.GEMINI)
        }
        val after = store.data.first()
        // Allow the existing legacy migration to update credential revision, but never mix endpoints.
        if (!sameAiRouting(before, after) || vaultRepository.persistedVaultIdentity.first() != vaultId) {
            throw CancellationException("AI configuration changed")
        }
        if (after[OFFLINE_MODE_KEY] ?: true) throw AiOfflineException()
        val url = if (kind == AiRequestKind.IMAGE) after[CF_URL].orEmpty() else after[PRIVATE_GATEWAY_URL].orEmpty()
        val invalidated = combine(vaultRepository.persistedVaultIdentity, store.data) { active, prefs ->
            active != vaultId || !sameAiRouting(after, prefs) ||
                after[CREDENTIAL_REVISION] != prefs[CREDENTIAL_REVISION]
        }
        return AiRequestConfig(provider, url, after[PRIVATE_GATEWAY_MODEL].orEmpty(), credential, invalidated)
    }

    private fun sameAiRouting(left: Preferences, right: Preferences): Boolean =
        left[OFFLINE_MODE_KEY] == right[OFFLINE_MODE_KEY] &&
            left[AI_TEXT_PROVIDER] == right[AI_TEXT_PROVIDER] &&
            left[CF_URL] == right[CF_URL] &&
            left[PRIVATE_GATEWAY_URL] == right[PRIVATE_GATEWAY_URL] &&
            left[PRIVATE_GATEWAY_MODEL] == right[PRIVATE_GATEWAY_MODEL]

    val isEinkEnabled: Flow<Boolean> = vaultPreferencesFlow.map { it[IS_EINK_ENABLED] ?: false }
    val isAutoSaveRecapsEnabled: Flow<Boolean> = vaultPreferencesFlow.map { it[AUTO_SAVE_RECAPS_KEY] ?: true }
    val einkContrast: Flow<Float> = vaultPreferencesFlow.map { it[CONTRAST_KEY] ?: 0.5f }
    val isOfflineModeEnabled: Flow<Boolean> = vaultPreferencesFlow.map { it[OFFLINE_MODE_KEY] ?: true }

    val themeMode: Flow<ThemeMode> = vaultPreferencesFlow.map { prefs ->
        val name = prefs[THEME_KEY] ?: ThemeMode.E_INK.name
        try { ThemeMode.valueOf(name) } catch (_: Exception) { ThemeMode.E_INK }
    }

    // --- TTS FLOWS ---
    val ttsSettings: Flow<TtsSettings> = vaultPreferencesFlow.map { preferences ->
        TtsSettings(
            defaultSpeed = preferences[TTS_DEFAULT_SPEED] ?: 1.0f,
            pitch = preferences[TTS_PITCH] ?: 1.0f,
            visualStyle = preferences[TTS_VISUAL_STYLE] ?: "underline",
            isAutoPageTurnEnabled = preferences[TTS_AUTO_PAGE_TURN] ?: true,
            language = preferences[TTS_LANGUAGE] ?: "default",
            isBackgroundPlaybackEnabled = preferences[TTS_BACKGROUND_PLAYBACK] ?: false,
            sleepTimerMinutes = preferences[TTS_SLEEP_TIMER] ?: 0,
            voice = preferences[TTS_VOICE] ?: "default"
        )
    }

    // --- SYNC FLOWS ---
    val isSyncEnabled: Flow<Boolean> = vaultPreferencesFlow.map { it[IS_SYNC_ENABLED] ?: false }
    val syncCloudUrl: Flow<String> = vaultPreferencesFlow.map { it[SYNC_CLOUD_URL] ?: "" }
    val useLocalServer: Flow<Boolean> = vaultPreferencesFlow.map { it[USE_LOCAL_SERVER] ?: false }
    val localServerUrl: Flow<String> = vaultPreferencesFlow.map { it[LOCAL_SERVER_URL] ?: "" }
    val deviceId: Flow<String> = vaultPreferencesFlow.map { it[SYNC_DEVICE_ID] ?: "" }
    val lastSyncTimestamp: Flow<Long> = vaultPreferencesFlow.map { it[LAST_SYNC_TIMESTAMP] ?: 0L }
    val syncUsername: Flow<String> = vaultPreferencesFlow.map { it[SYNC_USERNAME] ?: "" }
    val syncPassword: Flow<String> = credentialFlow(SYNC_PASSWORD, CredentialSlot.SYNC_PASSWORD)
    val deviceName: Flow<String> = vaultPreferencesFlow.map { preferences ->
        val savedName = preferences[DEVICE_NAME]
        if (savedName.isNullOrBlank()) {
            val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
            val model = Build.MODEL
            if (model.startsWith(manufacturer)) model else "$manufacturer $model"
        } else {
            savedName
        }
    }

    // --- READER PREF FLOWS ---
    val readerPreferences: Flow<ReaderPreferences> = vaultPreferencesFlow.map { prefs ->
        ReaderPreferences(
            theme = prefs[READER_THEME] ?: "light",
            fontSize = prefs[READER_FONT_SIZE] ?: 1.0,
            publisherStyles = prefs[READER_PUBLISHER_STYLES] ?: true,
            fontFamily = prefs[READER_FONT_FAMILY],
            textAlign = prefs[READER_TEXT_ALIGN],
            lineHeight = prefs[READER_LINE_HEIGHT],
            letterSpacing = prefs[READER_LETTER_SPACING],
            paragraphSpacing = prefs[READER_PARAGRAPH_SPACING],
            pageMargins = prefs[READER_MARGIN_SIDE],
            wordSpacing = prefs[READER_WORD_SPACING],
            paragraphIndent = prefs[READER_PARAGRAPH_INDENT],
            hyphens = prefs[READER_HYPHENS],
            ligatures = prefs[READER_LIGATURES],
            marginTop = prefs[READER_MARGIN_TOP],
            marginBottom = prefs[READER_MARGIN_BOTTOM]
        )
    }

    // Dictionary Flows
    fun getUseEmbeddedDictionary(): Flow<Boolean> = vaultPreferencesFlow.map { it[USE_EMBEDDED_DICT] ?: false }
    fun getDictionaryFolder(): Flow<String> = vaultPreferencesFlow.map { it[DICTIONARY_FOLDER_KEY] ?: "" }

    // --- TTS ACTIONS ---
    suspend fun setTtsDefaultSpeed(speed: Float) { editCurrentVault { it[TTS_DEFAULT_SPEED] = speed } }
    suspend fun setTtsAutoPageTurn(enabled: Boolean) { editCurrentVault { it[TTS_AUTO_PAGE_TURN] = enabled } }
    suspend fun setTtsVisualStyle(style: String) { editCurrentVault { it[TTS_VISUAL_STYLE] = style } }
    suspend fun setTtsLanguage(language: String) { editCurrentVault { it[TTS_LANGUAGE] = language } }
    suspend fun setTtsPitch(pitch: Float) { editCurrentVault { it[TTS_PITCH] = pitch } }
    suspend fun setTtsSleepTimer(minutes: Int) { editCurrentVault { it[TTS_SLEEP_TIMER] = minutes } }

    // Comic preferences are scoped to the active local vault, like book settings.
    val comicPreferences: Flow<ComicPreferences> = vaultPreferencesFlow.map { p ->
        fun <T : Enum<T>> decode(raw: String?, values: Array<T>, fallback: T): T =
            values.firstOrNull { it.name == raw } ?: fallback

        ComicPreferences(
            readingDirection = decode(p[COMIC_READING_DIRECTION], ComicReadingDirection.entries.toTypedArray(), ComicReadingDirection.LEFT_TO_RIGHT),
            readingMode = decode(p[COMIC_READING_MODE], ComicReadingMode.entries.toTypedArray(), ComicReadingMode.SINGLE_PAGE),
            pageFit = decode(p[COMIC_PAGE_FIT], ComicPageFit.entries.toTypedArray(), ComicPageFit.FIT_PAGE),
            tapToTurn = p[COMIC_TAP_TO_TURN] ?: true,
            useVolumeKeys = p[COMIC_VOLUME_KEYS] ?: false,
            preloadNextPage = p[COMIC_PRELOAD_NEXT] ?: true,
            reduceMotion = p[COMIC_REDUCE_MOTION] ?: true
        )
    }

    suspend fun saveComicPreferences(settings: ComicPreferences) {
        editCurrentVault { p ->
            p[COMIC_READING_DIRECTION] = settings.readingDirection.name
            p[COMIC_READING_MODE] = settings.readingMode.name
            p[COMIC_PAGE_FIT] = settings.pageFit.name
            p[COMIC_TAP_TO_TURN] = settings.tapToTurn
            p[COMIC_VOLUME_KEYS] = settings.useVolumeKeys
            p[COMIC_PRELOAD_NEXT] = settings.preloadNextPage
            p[COMIC_REDUCE_MOTION] = settings.reduceMotion
        }
    }

    // --- SYNC ACTIONS ---
    suspend fun updateSyncProfile(user: String, pass: String, name: String): Unit =
        withContext(AppCoroutineConfig.io) {
            credentialMutex.withLock {
                val vaultId = vaultRepository.activeVaultId.first()
                val store = getVaultDataStore(vaultId)
                val key = credentialId(vaultId, CredentialSlot.SYNC_PASSWORD)
                if (pass.isEmpty()) {
                    privateGatewaySecrets.clear(key)
                } else {
                    privateGatewaySecrets.write(key, pass)
                    check(privateGatewaySecrets.read(key) == pass) {
                        "Encrypted sync credential verification failed"
                    }
                }
                store.edit { prefs ->
                    prefs.remove(SYNC_PASSWORD)
                    prefs[SYNC_USERNAME] = user.trim()
                    prefs[DEVICE_NAME] = name.trim()
                    prefs[CREDENTIAL_REVISION] = (prefs[CREDENTIAL_REVISION] ?: 0) + 1
                }
                Unit
            }
        }

    suspend fun setSyncEnabled(enabled: Boolean) { editCurrentVault { it[IS_SYNC_ENABLED] = enabled } }
    suspend fun setSyncCloudUrl(url: String) { editCurrentVault { it[SYNC_CLOUD_URL] = url } }
    suspend fun setLocalServerUrl(url: String) { editCurrentVault { it[LOCAL_SERVER_URL] = url } }
    suspend fun setUseLocalServer(useLocal: Boolean) { editCurrentVault { it[USE_LOCAL_SERVER] = useLocal } }

    suspend fun saveSyncSettings(
        syncCloudUrl: String,
        localUrl: String,
        useLocal: Boolean,
        deviceName: String? = null
    ) {
        editCurrentVault { prefs ->
            prefs[SYNC_CLOUD_URL] = syncCloudUrl.trim().removeSuffix("/")
            prefs[LOCAL_SERVER_URL] = localUrl.trim().removeSuffix("/")
            prefs[USE_LOCAL_SERVER] = useLocal

            if (!deviceName.isNullOrBlank()) {
                prefs[DEVICE_NAME] = deviceName.trim()
            }
        }
    }

    suspend fun getLastSyncTimestamp(): Long = vaultPreferencesFlow.first()[LAST_SYNC_TIMESTAMP] ?: 0L

    suspend fun setLastSyncTimestamp(ts: Long) { editCurrentVault { it[LAST_SYNC_TIMESTAMP] = ts } }

    suspend fun ensureDeviceId(): String {
        val existing = vaultPreferencesFlow.first()[SYNC_DEVICE_ID]
        if (!existing.isNullOrBlank()) return existing

        val newId = "android-${UUID.randomUUID().toString().take(8)}"
        editCurrentVault { it[SYNC_DEVICE_ID] = newId }
        return newId
    }

    // --- APP SETTINGS ACTIONS ---
    suspend fun setAiEnabled(enabled: Boolean) { editCurrentVault { it[IS_AI_ENABLED] = enabled } }

    suspend fun setAiTextProvider(provider: AiTextProvider) {
        editCurrentVault { prefs ->
            prefs[AI_TEXT_PROVIDER] = if (provider == AiTextProvider.PRIVATE_GATEWAY) {
                "PRIVATE_GATEWAY"
            } else {
                "GEMINI"
            }
        }
    }

    suspend fun savePrivateGatewaySettings(url: String, model: String, token: String) =
        withContext(AppCoroutineConfig.io) {
            gatewayMigrationMutex.withLock {
                val vaultId = vaultRepository.activeVaultId.first()
                val dataStore = getVaultDataStore(vaultId)
                // Never persist the gateway credential as plaintext in a preferences file.
                if (token.isBlank()) {
                    dataStore.edit { it.remove(PRIVATE_GATEWAY_TOKEN) }
                    privateGatewaySecrets.clear(vaultId)
                } else {
                    privateGatewaySecrets.write(vaultId, token.trim())
                }
                dataStore.edit { prefs ->
                    prefs[PRIVATE_GATEWAY_URL] = url.trim()
                    prefs[PRIVATE_GATEWAY_MODEL] = model.trim()
                    prefs.remove(PRIVATE_GATEWAY_TOKEN)
                    prefs[CREDENTIAL_REVISION] = (prefs[CREDENTIAL_REVISION] ?: 0) + 1
                }
            }
        }

    suspend fun savePrivateGatewayToken(token: String) = withContext(AppCoroutineConfig.io) {
        gatewayMigrationMutex.withLock {
            val vaultId = vaultRepository.activeVaultId.first()
            val dataStore = getVaultDataStore(vaultId)
            if (token.isBlank()) {
                // Remove the legacy plaintext copy first so it cannot be re-migrated.
                dataStore.edit {
                    it.remove(PRIVATE_GATEWAY_TOKEN)
                    it[CREDENTIAL_REVISION] = (it[CREDENTIAL_REVISION] ?: 0) + 1
                }
                privateGatewaySecrets.clear(vaultId)
            } else {
                privateGatewaySecrets.write(vaultId, token.trim())
                dataStore.edit {
                    it.remove(PRIVATE_GATEWAY_TOKEN)
                    it[CREDENTIAL_REVISION] = (it[CREDENTIAL_REVISION] ?: 0) + 1
                }
            }
        }
    }

    suspend fun saveSettings(
        gemini: String, cfUrl: String, cfToken: String, isEnk: Boolean, isAiEnabled: Boolean? = null
    ): Unit = withContext(AppCoroutineConfig.io) {
        credentialMutex.withLock {
            val vaultId = vaultRepository.activeVaultId.first()
            val store = getVaultDataStore(vaultId)
            val prefs = store.data.first()
            val geminiValue = gemini.trim()
            val cloudflareValue = cfToken.trim()
            // A locked migration can leave a legacy secret hidden from the UI;
            // refuse an accidental empty overwrite rather than deleting user data.
            if ((prefs[GEMINI_KEY]?.isNotEmpty() == true && geminiValue.isEmpty()) ||
                (prefs[CF_TOKEN]?.isNotEmpty() == true && cloudflareValue.isEmpty())) {
                throw GatewayCredentialUnavailableException(
                    "Unlock your device and retry migrating the existing AI credentials."
                )
            }
            val geminiId = credentialId(vaultId, CredentialSlot.GEMINI)
            val cloudflareId = credentialId(vaultId, CredentialSlot.CLOUDFLARE)
            // Authentication/invalidated-key check before clearing any existing ciphertext.
            privateGatewaySecrets.read(geminiId)
            privateGatewaySecrets.read(cloudflareId)
            if (geminiValue.isEmpty()) privateGatewaySecrets.clear(geminiId)
            else privateGatewaySecrets.write(geminiId, geminiValue)
            if (cloudflareValue.isEmpty()) privateGatewaySecrets.clear(cloudflareId)
            else privateGatewaySecrets.write(cloudflareId, cloudflareValue)
            check(privateGatewaySecrets.read(geminiId) == geminiValue) {
                "Encrypted credential verification failed"
            }
            check(privateGatewaySecrets.read(cloudflareId) == cloudflareValue) {
                "Encrypted credential verification failed"
            }
            store.edit { updated ->
                updated.remove(GEMINI_KEY)
                updated.remove(CF_TOKEN)
                updated[CF_URL] = cfUrl
                updated[IS_EINK_ENABLED] = isEnk
                if (isAiEnabled != null) updated[IS_AI_ENABLED] = isAiEnabled
                updated[CREDENTIAL_REVISION] = (updated[CREDENTIAL_REVISION] ?: 0) + 1
            }
            Unit
        }
    }

    suspend fun setAutoSaveRecaps(enabled: Boolean) { editCurrentVault { it[AUTO_SAVE_RECAPS_KEY] = enabled } }
    suspend fun setThemeMode(mode: ThemeMode) { editCurrentVault { it[THEME_KEY] = mode.name } }
    suspend fun setContrast(value: Float) { editCurrentVault { it[CONTRAST_KEY] = value } }
    suspend fun setUseEmbeddedDictionary(enabled: Boolean) { editCurrentVault { it[USE_EMBEDDED_DICT] = enabled } }
    suspend fun setDictionaryFolder(uri: String) { editCurrentVault { it[DICTIONARY_FOLDER_KEY] = uri } }
    suspend fun setOfflineMode(enabled: Boolean) { editCurrentVault { it[OFFLINE_MODE_KEY] = enabled } }

    fun validateStarDictFolder(uriString: String): Boolean {
        return try {
            val uri = Uri.parse(uriString)
            val dir = DocumentFile.fromTreeUri(context, uri)
            dir?.let { it.isDirectory && it.canRead() && it.listFiles().any { f -> f.name?.endsWith(".idx", true) == true } } ?: false
        } catch (e: Exception) { false }
    }

    suspend fun saveReaderPreferences(prefs: ReaderPreferences) {
        editCurrentVault { p ->
            p[READER_THEME] = prefs.theme
            p[READER_FONT_SIZE] = prefs.fontSize
            p[READER_PUBLISHER_STYLES] = prefs.publisherStyles

            if (prefs.publisherStyles) {
                p.remove(READER_FONT_FAMILY)
                p.remove(READER_TEXT_ALIGN)
                p.remove(READER_LINE_HEIGHT)
                p.remove(READER_LETTER_SPACING)
                p.remove(READER_PARAGRAPH_SPACING)
                p.remove(READER_MARGIN_SIDE)
                p.remove(READER_MARGIN_TOP)
                p.remove(READER_MARGIN_BOTTOM)
                p.remove(READER_WORD_SPACING)
                p.remove(READER_PARAGRAPH_INDENT)
                p.remove(READER_HYPHENS)
                p.remove(READER_LIGATURES)
            } else {
                prefs.fontFamily?.let { p[READER_FONT_FAMILY] = it } ?: p.remove(READER_FONT_FAMILY)
                prefs.textAlign?.let { p[READER_TEXT_ALIGN] = it } ?: p.remove(READER_TEXT_ALIGN)
                prefs.lineHeight?.let { p[READER_LINE_HEIGHT] = it } ?: p.remove(READER_LINE_HEIGHT)
                prefs.letterSpacing?.let { p[READER_LETTER_SPACING] = it } ?: p.remove(READER_LETTER_SPACING)
                prefs.paragraphSpacing?.let { p[READER_PARAGRAPH_SPACING] = it } ?: p.remove(READER_PARAGRAPH_SPACING)
                prefs.pageMargins?.let { p[READER_MARGIN_SIDE] = it } ?: p.remove(READER_MARGIN_SIDE)
                prefs.marginTop?.let { p[READER_MARGIN_TOP] = it } ?: p.remove(READER_MARGIN_TOP)
                prefs.marginBottom?.let { p[READER_MARGIN_BOTTOM] = it } ?: p.remove(READER_MARGIN_BOTTOM)
                prefs.wordSpacing?.let { p[READER_WORD_SPACING] = it } ?: p.remove(READER_WORD_SPACING)
                prefs.paragraphIndent?.let { p[READER_PARAGRAPH_INDENT] = it } ?: p.remove(READER_PARAGRAPH_INDENT)
                prefs.hyphens?.let { p[READER_HYPHENS] = it } ?: p.remove(READER_HYPHENS)
                prefs.ligatures?.let { p[READER_LIGATURES] = it } ?: p.remove(READER_LIGATURES)
            }
        }
    }

    suspend fun resetLayoutPreferences() {
        editCurrentVault { p ->
            p[READER_PUBLISHER_STYLES] = false
            p.remove(READER_LINE_HEIGHT)
            p.remove(READER_TEXT_ALIGN)
            p.remove(READER_PARAGRAPH_SPACING)
            p.remove(READER_MARGIN_SIDE)
            p.remove(READER_MARGIN_TOP)
            p.remove(READER_MARGIN_BOTTOM)
            p.remove(READER_LETTER_SPACING)
            p.remove(READER_WORD_SPACING)
            p.remove(READER_PARAGRAPH_INDENT)
            p.remove(READER_HYPHENS)
            p.remove(READER_LIGATURES)
        }
    }

    val bookshelfPreferences: Flow<BookshelfPreferences> = vaultPreferencesFlow.map { prefs ->
        val ditheringName = prefs[DITHERING_MODE_KEY] ?: DitheringMode.AUTO.name
        val ditheringMode = try { DitheringMode.valueOf(ditheringName) } catch (_: Exception) { DitheringMode.AUTO }

        val ratioName = prefs[COVER_ASPECT_RATIO_KEY] ?: CoverAspectRatio.UNIFORM.name
        val coverAspectRatio = try { CoverAspectRatio.valueOf(ratioName) } catch (_: Exception) { CoverAspectRatio.UNIFORM }

        BookshelfPreferences(
            ditheringMode = ditheringMode,
            groupBySeries = prefs[GROUP_BY_SERIES_KEY] ?: true,
            coverAspectRatio = coverAspectRatio,
            showFormatBadge = prefs[SHOW_FORMAT_BADGE_KEY] ?: true,
            showFavoriteIcon = prefs[SHOW_FAVORITE_ICON_KEY] ?: true,
            showProgressBadge = prefs[SHOW_PROGRESS_BADGE_KEY] ?: true,
            showSyncStatus = prefs[SHOW_SYNC_STATUS_KEY] ?: true
        )
    }

    suspend fun setDitheringMode(mode: DitheringMode) { editCurrentVault { it[DITHERING_MODE_KEY] = mode.name } }
    suspend fun setGroupBySeries(enabled: Boolean) { editCurrentVault { it[GROUP_BY_SERIES_KEY] = enabled } }
    suspend fun setCoverAspectRatio(ratio: CoverAspectRatio) { editCurrentVault { it[COVER_ASPECT_RATIO_KEY] = ratio.name } }

    suspend fun setCoverStyleElements(
        format: Boolean, favorite: Boolean, progress: Boolean, sync: Boolean
    ) {
        editCurrentVault { prefs ->
            prefs[SHOW_FORMAT_BADGE_KEY] = format
            prefs[SHOW_FAVORITE_ICON_KEY] = favorite
            prefs[SHOW_PROGRESS_BADGE_KEY] = progress
            prefs[SHOW_SYNC_STATUS_KEY] = sync
        }
    }
}
