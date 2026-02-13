/*
 * Copyright (c) 2026 Piyush Daiya
 * ... (License Header)
 */

package org.vaachak.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import org.vaachak.ui.theme.ThemeMode
import kotlinx.coroutines.launch
import androidx.compose.ui.text.input.KeyboardType
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    // --- VIEWMODEL STATE ---
    val vmGeminiKey by viewModel.geminiKey.collectAsState()
    val vmCfUrl by viewModel.cfUrl.collectAsState()
    val vmCfToken by viewModel.cfToken.collectAsState()
    val vmIsAutoSave by viewModel.isAutoSaveRecapsEnabled.collectAsState()

    // Sync States
    val vmSyncCloudUrl by viewModel.syncCloudUrl.collectAsState()
    val vmUseLocalServer by viewModel.useLocalServer.collectAsState()
    val vmLocalServerUrl by viewModel.localServerUrl.collectAsState()
    val deviceId by viewModel.deviceId.collectAsState()
    val syncUsername by viewModel.syncUsername.collectAsState()
    val syncPassword by viewModel.syncPassword.collectAsState()
    val deviceName by viewModel.deviceName.collectAsState()

    // General States
    val currentTheme by viewModel.themeMode.collectAsState()
    val contrast by viewModel.einkContrast.collectAsState()
    val useEmbeddedDictionary by viewModel.useEmbeddedDictionary.collectAsState()
    val dictionaryFolder by viewModel.dictionaryFolder.collectAsState()
    val isOfflineMode by viewModel.isOfflineModeEnabled.collectAsState()

    // --- LOCAL FORM STATE ---
    var inputGemini by remember(vmGeminiKey) { mutableStateOf(vmGeminiKey) }
    var inputCfUrl by remember(vmCfUrl) { mutableStateOf(vmCfUrl) }
    var inputCfToken by remember(vmCfToken) { mutableStateOf(vmCfToken) }

    var inputSyncCloudUrl by remember(vmSyncCloudUrl) { mutableStateOf(vmSyncCloudUrl) }
    var inputLocalUrl by remember(vmLocalServerUrl) { mutableStateOf(vmLocalServerUrl) }
    var inputUseLocal by remember(vmUseLocalServer) { mutableStateOf(vmUseLocalServer) }

    // Sync Profile Local State
    var tempUser by remember(syncUsername) { mutableStateOf(syncUsername) }
    var tempPass by remember(syncPassword) { mutableStateOf(syncPassword) }
    var tempName by remember(deviceName) { mutableStateOf(deviceName) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    var showResetDialog by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            viewModel.updateDictionaryFolder(it.toString())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 1. HEADER & SAVE ACTION
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Preferences", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Customize your reading engine", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                FilledTonalButton(
                    onClick = {
                        keyboardController?.hide()
                        scope.launch {
                            try {
                                viewModel.updateGemini(inputGemini)
                                viewModel.updateCfUrl(inputCfUrl)
                                viewModel.updateCfToken(inputCfToken)
                                viewModel.saveSyncSettings(inputSyncCloudUrl, inputLocalUrl, inputUseLocal)
                                viewModel.saveSyncProfile(tempUser, tempPass, tempName)
                                snackbarHostState.showSnackbar("✅ All settings saved")
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar("❌ Error: ${e.localizedMessage}")
                            }
                        }
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Save, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save All")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // 2. CONNECTIVITY
            SettingsSection(title = "Connectivity", icon = Icons.Default.WifiOff) {
                SettingsToggleRow(
                    label = "Offline Mode",
                    description = "Hide AI features for distraction-free reading",
                    checked = isOfflineMode,
                    onCheckedChange = { viewModel.toggleOfflineMode(it) }
                )
            }

            // 3. DISPLAY
            SettingsSection(title = "Display", icon = Icons.Default.Face) {
                Text("Theme Selection", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(12.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = currentTheme == mode,
                            onClick = { viewModel.updateTheme(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                            label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) }
                        )
                    }
                }
                if (currentTheme == ThemeMode.E_INK) {
                    var sliderPosition by remember(contrast) { mutableFloatStateOf(contrast) }
                    Text("E-ink Sharpness", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 16.dp))
                    Slider(
                        value = sliderPosition,
                        onValueChange = { sliderPosition = it; viewModel.updateContrast(it) },
                        steps = 3,
                        colors = SliderDefaults.colors(thumbColor = Color.Black, activeTrackColor = Color.Black)
                    )
                }
            }

            // 4. SYNC & BACKUP
            SettingsSection(title = "Sync & Backup", icon = Icons.Default.Sync) {
                // Device ID Display (Read-Only)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
                    Icon(Icons.Default.Smartphone, null, modifier = Modifier.size(18.dp), tint = Color.Gray)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Device ID: ", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Text(deviceId, style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                }

                SettingsToggleRow(
                    label = "Use Local Server",
                    description = if (inputUseLocal) "Syncing via Home WiFi" else "Syncing via Cloud",
                    checked = inputUseLocal,
                    onCheckedChange = { inputUseLocal = it }
                )

                Spacer(modifier = Modifier.height(12.dp))

                AnimatedVisibility(visible = inputUseLocal) {
                    SettingsTextField(value = inputLocalUrl, onValueChange = { inputLocalUrl = it }, label = "Local Server URL", icon = Icons.Default.Computer, isUrl = true)
                }
                AnimatedVisibility(visible = !inputUseLocal) {
                    SettingsTextField(value = inputSyncCloudUrl, onValueChange = { inputSyncCloudUrl = it }, label = "Sync Server URL", icon = Icons.Default.CloudSync, isUrl = true)
                }

                // --- URL CONFIG BUTTONS ---
                Spacer(modifier = Modifier.height(12.dp))
                val isUrlEmpty = if (inputUseLocal) inputLocalUrl.isBlank() else inputSyncCloudUrl.isBlank()

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                viewModel.saveSyncSettings(inputSyncCloudUrl, inputLocalUrl, inputUseLocal)
                                snackbarHostState.showSnackbar("✅ Server URL updated")
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(if (isUrlEmpty) "Add Url" else "Update Url")
                    }

                    OutlinedButton(
                        onClick = {
                            android.util.Log.d("SyncDebug", "UI: Test Button Tapped")
                            val currentUrl = if (inputUseLocal) inputLocalUrl else inputSyncCloudUrl
                            if (currentUrl.isBlank()) {
                                scope.launch { snackbarHostState.showSnackbar("❌ Enter a URL first") }
                            } else {
                                viewModel.testConnection(currentUrl) { message ->
                                    scope.launch {
                                        snackbarHostState.showSnackbar(message)
                                    }
                                }
                            }
                        },
                        modifier = Modifier.weight(0.6f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Link, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Test")
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                // --- USER PROFILE SUB-SECTION ---
                Text("User Profile", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))

                SettingsTextField(value = tempUser, onValueChange = { tempUser = it }, label = "Username", icon = Icons.Default.Person)
                Spacer(modifier = Modifier.height(8.dp))
                SettingsTextField(value = tempPass, onValueChange = { tempPass = it }, label = "Password", icon = Icons.Default.Lock, isSensitive = true)
                Spacer(modifier = Modifier.height(8.dp))
                SettingsTextField(value = tempName, onValueChange = { tempName = it }, label = "Device Friendly Name", icon = Icons.Default.Badge)

                Spacer(modifier = Modifier.height(12.dp))

                // --- AUTH ACTIONS (Login & Register) ---
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // LOGIN BUTTON (For existing users)
                    Button(
                        onClick = {
                            viewModel.loginUser(tempUser, tempPass) { msg ->
                                scope.launch {
                                    if (msg.contains("✅")) viewModel.saveSyncProfile(tempUser, tempPass, tempName)
                                    snackbarHostState.showSnackbar(msg)
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Login, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Login")
                    }

                    // REGISTER BUTTON (For new users)
                    OutlinedButton(
                        onClick = {
                            viewModel.registerUser(tempUser, tempPass) { msg ->
                                scope.launch {
                                    if (msg.contains("✅")) viewModel.saveSyncProfile(tempUser, tempPass, tempName)
                                    snackbarHostState.showSnackbar(msg)
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Register")
                    }
                }
            }

            // 5. READING
            SettingsSection(title = "Reading", icon = Icons.AutoMirrored.Filled.MenuBook) {
                SettingsToggleRow(
                    label = "Use External Dictionary",
                    description = "StarDict support",
                    checked = useEmbeddedDictionary,
                    onCheckedChange = { viewModel.toggleEmbeddedDictionary(it) }
                )
                if (useEmbeddedDictionary) {
                    OutlinedButton(onClick = { launcher.launch(null) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text(if (dictionaryFolder.isEmpty()) "Select Dictionary Folder" else "Change Folder")
                    }
                }

                if (!isOfflineMode) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    SettingsToggleRow(label = "Auto-Save Recaps", description = "AI summaries to highlights", checked = vmIsAutoSave, onCheckedChange = { viewModel.toggleAutoSaveRecaps(it) })
                }
            }

            // 6. INTELLIGENCE
            AnimatedVisibility(visible = !isOfflineMode) {
                SettingsSection(title = "Intelligence", icon = Icons.Default.Psychology) {
                    SettingsTextField(value = inputGemini, onValueChange = { inputGemini = it }, label = "Gemini API Key", icon = Icons.Default.Key, isSensitive = true)
                    Spacer(modifier = Modifier.height(12.dp))
                    SettingsTextField(value = inputCfUrl, onValueChange = { inputCfUrl = it }, label = "Cloudflare AI URL", icon = Icons.Default.Cloud, isUrl = true)
                    Spacer(modifier = Modifier.height(12.dp))
                    SettingsTextField(value = inputCfToken, onValueChange = { inputCfToken = it }, label = "Auth Token", icon = Icons.Default.Security, isSensitive = true)
                }
            }

            // 7. DANGER ZONE
            SettingsSection(title = "Danger Zone", icon = Icons.Default.Warning, titleColor = MaterialTheme.colorScheme.error) {
                OutlinedButton(
                    onClick = { showResetDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                ) { Text("Reset All Settings") }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }

        if (showResetDialog) {
            AlertDialog(
                onDismissRequest = { showResetDialog = false },
                title = { Text("Factory Reset?") },
                text = { Text("Erase all local credentials and keys?") },
                confirmButton = { TextButton(onClick = { viewModel.resetSettings(); showResetDialog = false }) { Text("Reset", color = Color.Red) } },
                dismissButton = { TextButton(onClick = { showResetDialog = false }) { Text("Cancel") } }
            )
        }
    }
}

// (Helper composables SettingsSection, SettingsToggleRow, SettingsTextField remain same as provided previously)

// --- HELPER COMPOSABLES ---

@Composable
fun SettingsSection(title: String, icon: ImageVector, borderColor: Color = MaterialTheme.colorScheme.outlineVariant, titleColor: Color = MaterialTheme.colorScheme.onSurface, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().border(1.dp, borderColor, RoundedCornerShape(12.dp)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
            Icon(imageVector = icon, contentDescription = null, tint = titleColor, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = titleColor)
        }
        content()
    }
}

@Composable
fun SettingsToggleRow(label: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.scale(0.8f))
    }
}

@Composable
fun SettingsTextField(value: String, onValueChange: (String) -> Unit, label: String, icon: ImageVector, placeholder: String = "", isSensitive: Boolean = false, isUrl: Boolean = false) {
    var passwordVisible by remember { mutableStateOf(false) }
    // Basic sanitizer to prevent newlines
    val sanitize: (String) -> String = { input -> input.replace("\n", "").take(512) }

    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(sanitize(it)) },
        label = { Text(label) },
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
        leadingIcon = { Icon(icon, null, modifier = Modifier.size(18.dp)) },
        visualTransformation = if (isSensitive && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (isUrl) KeyboardType.Uri else if (isSensitive) KeyboardType.Password else KeyboardType.Text),
        trailingIcon = if (isSensitive) { { IconButton(onClick = { passwordVisible = !passwordVisible }) { Icon(if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, contentDescription = null) } } } else null,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(8.dp),
        textStyle = MaterialTheme.typography.bodyMedium
    )
}