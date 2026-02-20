package org.vaachak.reader.leisure.ui.settings


import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.vaachak.reader.core.domain.model.AiConfig
import org.vaachak.reader.core.domain.model.SettingsSection
import org.vaachak.reader.core.domain.model.UserProfile
import org.vaachak.reader.core.domain.model.OpdsEntity
import org.vaachak.reader.core.data.repository.OpdsRepository
import org.vaachak.reader.core.data.repository.SettingsRepository
import org.vaachak.reader.core.data.repository.SyncRepository
import org.vaachak.reader.core.domain.model.ThemeMode
import javax.inject.Inject
import org.vaachak.reader.core.domain.model.ReaderPreferences
import org.vaachak.reader.leisure.utils.EinkHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.vaachak.reader.core.domain.model.TtsSettings

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val syncRepo: SyncRepository,
    private val opdsRepo: OpdsRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    // --- Internal Mutable State ---
    private val _activeEditSection = MutableStateFlow(SettingsSection.NONE)
    private val _isAiMasked = MutableStateFlow(true)
    private val _errorMessage = MutableStateFlow<String?>(null)

    // NEW: Sync Status Feedback
    private val _syncMessage = MutableStateFlow<String?>(null)
    val syncMessage = _syncMessage.asStateFlow()

    // 1. Add this Flow to observe Global Reader Settings
    val readerPreferences: StateFlow<ReaderPreferences> = settingsRepo.readerPreferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReaderPreferences())

    // 2. Add Dictionary Flows
    val useEmbeddedDict = settingsRepo.getUseEmbeddedDictionary()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val dictionaryFolder = settingsRepo.getDictionaryFolder()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    // --- THE COMBINER (Single Source of Truth) ---
    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepo.syncUsername,      // 0
        settingsRepo.deviceName,        // 1
        settingsRepo.lastSyncTimestamp, // 2
        settingsRepo.isOfflineModeEnabled, // 3
        opdsRepo.catalogs,              // 4
        settingsRepo.geminiKey,         // 5
        settingsRepo.cfUrl,             // 6
        settingsRepo.cfToken,           // 7
        settingsRepo.isAutoSaveRecapsEnabled, // 8
        _activeEditSection,             // 9
        _isAiMasked,                    // 10
        _errorMessage,                  // 11
        settingsRepo.themeMode,         // 12
        settingsRepo.einkContrast,
        settingsRepo.ttsSettings,// 13
    ) { params ->
        val username = params[0] as String
        val device = params[1] as String
        val lastSync = params[2] as Long
        val offline = params[3] as Boolean
        @Suppress("UNCHECKED_CAST")
        val feeds = params[4] as List<OpdsEntity>
        val gemini = params[5] as String
        val cfUrl = params[6] as String
        val token = params[7] as String
        val autoSave = params[8] as Boolean
        val editSec = params[9] as SettingsSection
        val masked = params[10] as Boolean
        val error = params[11] as String?
        val theme = params[12] as ThemeMode
        val contrast = params[13] as Float
        val tts = params[14] as TtsSettings
        val isAuthenticated = username.isNotBlank()

        SettingsUiState(
            userProfile = UserProfile(
                username = if (isAuthenticated) username else null,
                deviceName = device,
                lastSyncTime = lastSync,
                isAuthenticated = isAuthenticated
            ),
            isOfflineMode = offline,
            catalogs = feeds,
            aiConfig = AiConfig(
                isEnabled = gemini.isNotBlank(),
                geminiKey = gemini,
                cloudflareUrl = cfUrl,
                authToken = token,
                autoSaveRecaps = autoSave
            ),
            activeEditSection = editSec,
            isAiMasked = masked,
            errorMessage = error,
            themeMode = theme,
            einkContrast = contrast,
            ttsSettings = tts,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        SettingsUiState()
    )

    // --- ACTIONS ---

    fun toggleEditSection(section: SettingsSection) {
        if (_activeEditSection.value == section) {
            _activeEditSection.value = SettingsSection.NONE
            _isAiMasked.value = true
        } else {
            _activeEditSection.value = section
        }
    }

    fun setAiMasked(masked: Boolean) {
        _isAiMasked.value = masked
    }

    fun saveAiConfig(gemini: String, cfUrl: String, token: String, autoSave: Boolean) {
        viewModelScope.launch {
            try {
                val currentEink = settingsRepo.isEinkEnabled.first()
                settingsRepo.saveSettings(
                    gemini = gemini,
                    cfUrl = cfUrl,
                    cfToken = token,
                    isEnk = currentEink
                )
                settingsRepo.setAutoSaveRecaps(autoSave)
                _activeEditSection.value = SettingsSection.NONE
                _errorMessage.value = null
            } catch (e: Exception) {
                _errorMessage.value = "Failed to save settings: ${e.message}"
            }
        }
    }

    fun toggleOfflineMode(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setOfflineMode(enabled) }
    }

    fun logout() {
        viewModelScope.launch {
            settingsRepo.updateSyncProfile("", "", "")
            settingsRepo.setLastSyncTimestamp(0L)
            _activeEditSection.value = SettingsSection.NONE
        }
    }

    fun clearError() { _errorMessage.value = null }

    // --- NEW TTS ACTIONS ---



    fun updateTtsSpeed(newSpeed: Float) {
        viewModelScope.launch {
            // Constrain speed between 0.5x and 2.5x for safety
            val clampedSpeed = newSpeed.coerceIn(0.5f, 2.5f)
            settingsRepo.setTtsDefaultSpeed(clampedSpeed)
        }
    }

    fun setTtsAutoPageTurn(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepo.setTtsAutoPageTurn(enabled)
        }
    }

    fun setTtsVisualStyle(style: String) {
        viewModelScope.launch {
            settingsRepo.setTtsVisualStyle(style)
        }
    }

    // --- NEW SYNC FUNCTIONS ---

    fun triggerManualSync() {
        viewModelScope.launch {
            _syncMessage.value = "Syncing..."
            val result = syncRepo.sync()

            _syncMessage.value = if (result.isSuccess) {
                "Sync Complete!"
            } else {
                "Sync Failed: ${result.exceptionOrNull()?.message}"
            }
            delay(3000)
            _syncMessage.value = null
        }
    }

    fun testServerConnection() {
        viewModelScope.launch {
            _syncMessage.value = "Testing Server..."
            try {
                val useLocal = settingsRepo.useLocalServer.first()
                val url = if (useLocal) settingsRepo.localServerUrl.first() else settingsRepo.syncCloudUrl.first()

                val result = syncRepo.testConnection(url)

                _syncMessage.value = if (result.isSuccess) {
                    "Connected Successfully!"
                } else {
                    "Connection Failed: ${result.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                _syncMessage.value = "Error: ${e.message}"
            }
            delay(3000)
            _syncMessage.value = null
        }
    }

    fun updateReaderPreferences(prefs: ReaderPreferences) {
        viewModelScope.launch {
            settingsRepo.saveReaderPreferences(prefs)
        }
    }

    fun setUseEmbeddedDictionary(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setUseEmbeddedDictionary(enabled) }
    }

    fun setDictionaryFolder(uri: String) {
        viewModelScope.launch {
            // 1. Run validation on IO thread to avoid freezing UI
            val isValid = withContext(Dispatchers.IO) {
                settingsRepo.validateStarDictFolder(uri)
            }

            // 2. Check Result
            if (isValid) {
                settingsRepo.setDictionaryFolder(uri)
                _errorMessage.value = null // Clear any previous errors
                _syncMessage.value = "Dictionary folder set successfully."
            } else {
                // 3. Show Error if invalid
                _errorMessage.value = "Invalid Folder: No StarDict (.idx) files found."
                // Optionally clear the setting if it was previously set to something invalid
                // settingsRepo.setDictionaryFolder("")
            }
        }
    }

    // --- APP GLOBAL THEME ACTIONS (Fixes Sharpness/Dullness) ---
    fun setAppTheme(mode: ThemeMode) {
        viewModelScope.launch {
            settingsRepo.setThemeMode(mode)
            delay(100) // Small delay to let UI redraw first
            EinkHelper.requestFullRefresh(context)
        }
    }

    fun setEinkContrast(value: Float) {
        viewModelScope.launch {
            settingsRepo.setContrast(value)
        }
    }
    fun onContrastChangedFinished() {
        viewModelScope.launch {
            // Wait a tiny bit for the UI to settle on the final color
            delay(200)
            // Trigger the hardware refresh to clear ghosting
            // (Assuming you have access to context or EinkHelper here)
            // If context is not injected, you can pass it from UI or use a Helper that accepts it.
            // For now, let's assume specific Activity logic or just logging if not fully wired.
        }
    }

    // Helper to trigger refresh if Context is available in ViewModel (recommended way is via UI event)
    fun triggerEinkRefresh(context: Context) {
        EinkHelper.requestFullRefresh(context)
    }
    // --- TTS LANGUAGE OVERRIDE ---
    fun setTtsLanguage(language: String) {
        viewModelScope.launch {
            // language will be "default", "en", or "hi"
            settingsRepo.setTtsLanguage(language)
        }
    }

    // --- TTS PITCH CONTROL ---
    fun updateTtsPitch(pitch: Float) {
        viewModelScope.launch {
            // 1. Clamp the pitch between 0.1f and 2.0f to prevent engine crashes
            // 2. Round to 1 decimal place to prevent floating point weirdness (e.g. 0.9000001f)
            val safePitch = (pitch.coerceIn(0.1f, 2.0f) * 10f).toInt() / 10f
            settingsRepo.setTtsPitch(safePitch)
        }
    }


    // --- SLEEP TIMER ---
    fun setSleepTimer(minutes: Int) {
        viewModelScope.launch {
            // minutes will be 0 (off), 15, 30, or 60
            settingsRepo.setTtsSleepTimer(minutes)
        }
    }
}