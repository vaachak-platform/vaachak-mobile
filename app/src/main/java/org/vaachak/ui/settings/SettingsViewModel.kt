/*
 * Copyright (c) 2026 Piyush Daiya
 * ... (License Header)
 */

package org.vaachak.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import org.vaachak.data.repository.SettingsRepository
import org.vaachak.data.repository.SyncRepository
import org.vaachak.ui.theme.ThemeMode
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * ViewModel for the Settings screen.
 * Manages application-wide settings including AI keys, theme, dictionary,
 * and the newly implemented secure sync profile.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val syncRepo: SyncRepository
) : ViewModel() {

    // --- SYNC & AUTH STATE ---

    val syncUsername = settingsRepo.syncUsername
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val syncPassword = settingsRepo.syncPassword
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val deviceName = settingsRepo.deviceName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val syncCloudUrl = settingsRepo.syncCloudUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val localServerUrl = settingsRepo.localServerUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val useLocalServer = settingsRepo.useLocalServer
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val deviceId = settingsRepo.deviceId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    // --- AI / INTELLIGENCE STATE ---

    private val _geminiKey = MutableStateFlow("")
    val geminiKey = _geminiKey.asStateFlow()

    private val _cfUrl = MutableStateFlow("")
    val cfUrl = _cfUrl.asStateFlow()

    private val _cfToken = MutableStateFlow("")
    val cfToken = _cfToken.asStateFlow()

    // --- APP & DISPLAY STATE ---

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
        // Ensure a device ID exists when settings are initialized
        viewModelScope.launch { settingsRepo.ensureDeviceId() }
    }

    fun loginUser(user: String, pass: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            // We use the same RegisterRequest DTO since it's just username/password
            val result = syncRepo.login(user, pass)
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { onResult("✅ Login successful!") },
                    onFailure = { onResult("❌ Login failed: ${it.localizedMessage}") }
                )
            }
        }
    }
    private fun loadSettings() = viewModelScope.launch {
        _geminiKey.value = settingsRepo.geminiKey.first()
        _cfUrl.value = settingsRepo.cfUrl.first()
        _cfToken.value = settingsRepo.cfToken.first()
        _isEinkEnabled.value = settingsRepo.isEinkEnabled.first()
        _isAutoSaveRecapsEnabled.value = settingsRepo.isAutoSaveRecapsEnabled.first()
        _isOfflineModeEnabled.value = settingsRepo.isOfflineModeEnabled.first()
    }

    // --- ACTIONS ---

    /**
     * Persists the user's sync credentials and device name locally.
     */
    fun saveSyncProfile(user: String, pass: String, name: String) = viewModelScope.launch {
        settingsRepo.updateSyncProfile(user, pass, name)
    }

    /**
     * Attempts to register a new user on the remote sync server.
     */
    fun registerUser(user: String, pass: String, onResult: (String) -> Unit) = viewModelScope.launch {
        if (user.isBlank() || pass.isBlank()) {
            onResult("❌ Username and Password cannot be empty.")
            return@launch
        }

        syncRepo.register(user, pass).fold(
            onSuccess = { onResult("✅ Account created! You can now Save & Sync.") },
            onFailure = { onResult("❌ Registration failed: ${it.message ?: "Unknown error"}") }
        )
    }

    fun updateGemini(valText: String) { _geminiKey.value = valText }
    fun updateCfUrl(valText: String) { _cfUrl.value = valText }
    fun updateCfToken(valText: String) { _cfToken.value = valText }

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

    /**
     * Saves AI/Intelligence settings with validation.
     */
    suspend fun saveSettings() {
        val isDictEnabled = useEmbeddedDictionary.value
        val dictPath = dictionaryFolder.value

        if (isDictEnabled) {
            if (dictPath.isBlank()) throw Exception("Please select a folder for the External Dictionary.")
            if (!settingsRepo.validateStarDictFolder(dictPath)) {
                throw Exception("Invalid Dictionary Folder: No StarDict files (.idx) found.")
            }
        }

        settingsRepo.saveSettings(
            gemini = _geminiKey.value,
            cfUrl = _cfUrl.value,
            cfToken = _cfToken.value,
            isEnk = _isEinkEnabled.value
        )
    }

    fun saveSyncSettings(syncCloud: String, localUrl: String, useLocal: Boolean) {
        viewModelScope.launch {
            settingsRepo.saveSyncSettings(syncCloud, localUrl, useLocal)
        }
    }

    fun resetSettings() = viewModelScope.launch {
        settingsRepo.clearSettings()
        _geminiKey.value = ""
        _cfUrl.value = ""
        _cfToken.value = ""
        _isEinkEnabled.value = false
        _isAutoSaveRecapsEnabled.value = true
        _isOfflineModeEnabled.value = false
        settingsRepo.ensureDeviceId()
    }
    fun testConnection(url: String, onResult: (String) -> Unit) = viewModelScope.launch {
        try {
            Log.d("SyncDebug", "ViewModel: Initiating test for $url")
            val result = syncRepo.testConnection(url)

            // Switch back to Main thread to trigger the UI callback
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { onResult("✅ Connection successful!") },
                    onFailure = { onResult("❌ Connection failed: ${it.localizedMessage}") }
                )
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onResult("❌ Error: ${e.localizedMessage}")
            }
        }
    }
}