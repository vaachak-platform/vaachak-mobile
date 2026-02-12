/*
 * Copyright (c) 2026 Piyush Daiya
 * ... (License Header)
 */

package org.vaachak.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import org.vaachak.data.repository.SettingsRepository
import org.vaachak.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Settings screen.
 * Manages application-wide settings such as API keys, theme preferences, sync configs, and dictionary configuration.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository
) : ViewModel() {

    // --- AI / INTELLIGENCE STATE ---
    private val _geminiKey = MutableStateFlow("")
    val geminiKey = _geminiKey.asStateFlow()

    private val _cfUrl = MutableStateFlow("")
    /** AI/Image Gen Cloudflare URL */
    val cfUrl = _cfUrl.asStateFlow()

    private val _cfToken = MutableStateFlow("")
    val cfToken = _cfToken.asStateFlow()

    // --- SYNC STATE ---
    val syncCloudUrl = settingsRepo.syncCloudUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val localServerUrl = settingsRepo.localServerUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val useLocalServer = settingsRepo.useLocalServer
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val deviceId = settingsRepo.deviceId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    // --- APP STATE ---
    private val _isEinkEnabled = MutableStateFlow(false)
    val isEinkEnabled = _isEinkEnabled.asStateFlow()

    private val _isAutoSaveRecapsEnabled = MutableStateFlow(true)
    val isAutoSaveRecapsEnabled = _isAutoSaveRecapsEnabled.asStateFlow()

    private val _isOfflineModeEnabled = MutableStateFlow(false)
    val isOfflineModeEnabled = _isOfflineModeEnabled.asStateFlow()

    val themeMode: StateFlow<ThemeMode> = settingsRepo.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeMode.E_INK)

    val einkContrast: StateFlow<Float> = settingsRepo.einkContrast
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.5f)

    val useEmbeddedDictionary: StateFlow<Boolean> = settingsRepo.getUseEmbeddedDictionary()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val dictionaryFolder: StateFlow<String> = settingsRepo.getDictionaryFolder()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    init {
        loadSettings()
        // Ensure a device ID exists when settings are opened
        viewModelScope.launch { settingsRepo.ensureDeviceId() }
    }

    private fun loadSettings() = viewModelScope.launch {
        _geminiKey.value = settingsRepo.geminiKey.first()
        _cfUrl.value = settingsRepo.cfUrl.first()
        _cfToken.value = settingsRepo.cfToken.first()
        _isEinkEnabled.value = settingsRepo.isEinkEnabled.first()
        _isAutoSaveRecapsEnabled.value = settingsRepo.isAutoSaveRecapsEnabled.first()
        _isOfflineModeEnabled.value = settingsRepo.isOfflineModeEnabled.first()
    }

    // --- UI UPDATERS (Local Input State) ---
    // These update the ViewModel's local state, but don't persist until saveSettings() is called

    fun updateGemini(valText: String) { _geminiKey.value = valText }
    fun updateCfUrl(valText: String) { _cfUrl.value = valText }
    fun updateCfToken(valText: String) { _cfToken.value = valText }

    // --- INSTANT UPDATERS (Persist Immediately) ---

    fun updateTheme(mode: ThemeMode) = viewModelScope.launch {
        settingsRepo.setThemeMode(mode)
        _isEinkEnabled.value = (mode == ThemeMode.E_INK)
    }

    fun updateContrast(newContrast: Float) = viewModelScope.launch {
        settingsRepo.setContrast(newContrast)
    }

    fun toggleAutoSaveRecaps(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setAutoSaveRecaps(enabled)
        _isAutoSaveRecapsEnabled.value = enabled
    }

    fun toggleEmbeddedDictionary(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setUseEmbeddedDictionary(enabled)
    }

    fun updateDictionaryFolder(uri: String) = viewModelScope.launch {
        settingsRepo.setDictionaryFolder(uri)
    }

    fun toggleOfflineMode(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setOfflineMode(enabled)
        _isOfflineModeEnabled.value = enabled
    }

    // --- SAVE LOGIC ---

    /**
     * Saves AI/Intelligence settings.
     * Validates dictionary path if necessary.
     */
    suspend fun saveSettings() {
        val isDictEnabled = useEmbeddedDictionary.value
        val dictPath = dictionaryFolder.value

        if (isDictEnabled) {
            if (dictPath.isBlank()) {
                throw Exception("Please select a folder for the External Dictionary.")
            }
            val isValid = settingsRepo.validateStarDictFolder(dictPath)
            if (!isValid) {
                throw Exception("Invalid Dictionary Folder: No StarDict files (.idx/.dict/.ifo) found.")
            }
        } else {
            if (dictPath.isNotEmpty()) {
                settingsRepo.setDictionaryFolder("")
            }
        }

        settingsRepo.saveSettings(
            gemini = _geminiKey.value,
            cfUrl = _cfUrl.value,
            cfToken = _cfToken.value,
            isEnk = _isEinkEnabled.value
        )
    }

    /**
     * Saves Sync Settings.
     */
    fun saveSyncSettings(syncCloud: String, localUrl: String, useLocal: Boolean) {
        viewModelScope.launch {
            settingsRepo.saveSyncSettings(syncCloud, localUrl, useLocal)
        }
    }

    fun resetSettings() = viewModelScope.launch {
        settingsRepo.clearSettings()
        // Reset Local State
        _geminiKey.value = ""
        _cfUrl.value = ""
        _cfToken.value = ""
        _isEinkEnabled.value = false
        _isAutoSaveRecapsEnabled.value = true
        _isOfflineModeEnabled.value = false

        // Re-generate ID after reset
        settingsRepo.ensureDeviceId()
    }
}