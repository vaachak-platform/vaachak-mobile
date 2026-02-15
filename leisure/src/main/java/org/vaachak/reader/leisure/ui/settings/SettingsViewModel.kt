package org.vaachak.reader.leisure.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.vaachak.reader.leisure.data.local.OpdsEntity
import org.vaachak.reader.leisure.data.repository.AiRepository
import org.vaachak.reader.leisure.data.repository.OpdsRepository
import org.vaachak.reader.leisure.data.repository.SettingsRepository
import org.vaachak.reader.leisure.data.repository.SyncRepository
import org.vaachak.reader.leisure.domain.models.*
import org.vaachak.reader.leisure.ui.theme.ThemeMode
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val syncRepo: SyncRepository,
    private val opdsRepo: OpdsRepository,
    private val aiRepo: AiRepository
) : ViewModel() {

    // --- Internal Mutable State ---
    private val _activeEditSection = MutableStateFlow(SettingsSection.NONE)
    private val _isAiMasked = MutableStateFlow(true)
    private val _errorMessage = MutableStateFlow<String?>(null)

    // NEW: Sync Status Feedback
    private val _syncMessage = MutableStateFlow<String?>(null)
    val syncMessage = _syncMessage.asStateFlow()

    // --- THE COMBINER (Single Source of Truth) ---
    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepo.syncUsername,      // 0
        settingsRepo.deviceName,        // 1
        settingsRepo.lastSyncTimestamp, // 2
        settingsRepo.isOfflineModeEnabled, // 3
        opdsRepo.allFeeds,              // 4
        settingsRepo.geminiKey,         // 5
        settingsRepo.cfUrl,             // 6
        settingsRepo.cfToken,           // 7
        settingsRepo.isAutoSaveRecapsEnabled, // 8
        _activeEditSection,             // 9
        _isAiMasked,                    // 10
        _errorMessage,                  // 11
        settingsRepo.themeMode,         // 12
        settingsRepo.einkContrast       // 13
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
            einkContrast = contrast
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

    fun deleteCatalog(feedId: Long) {
        viewModelScope.launch { opdsRepo.deleteFeed(feedId) }
    }

    fun clearError() { _errorMessage.value = null }

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
}