package org.vaachak.reader.leisure.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import org.vaachak.reader.leisure.navigation.Screen
import androidx.compose.runtime.saveable.rememberSaveable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController, // NEW: Takes a custom back action
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    val settingsTabs = listOf("Global", "Book", "Content", "Intelligence")
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
    ){ paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            // SETTINGS SUB-TABS
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                settingsTabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal) }
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when (selectedTab) {
                    0 -> GlobalSettingsTab(state, viewModel, navController)
                    1 -> BookSettingsTab(navController)
                    2 -> ContentSettingsTab(navController)
                    3 -> IntelligenceSettingsTab(state, navController)
                }
            }
        }
    }
}

@Composable
fun GlobalSettingsTab(state: SettingsUiState, viewModel: SettingsViewModel, navController: NavController) {
    SettingsGroup(title = "Account & Sync") {
        SettingsTile(
            icon = Icons.Default.Sync, title = "Sync Configuration", subtitle = "Manage end-to-end encrypted vaults",
            onClick = { navController.navigate(Screen.SyncSettings.route) }
        )
        SettingsTile(
            icon = Icons.Default.Person, title = "Account", subtitle = "Manage profile and devices",
            onClick = { navController.navigate("login") }
        )
    }

    SettingsGroup(title = "Device & Network") {
        SettingsTile(
            icon = if (state.isOfflineMode) Icons.Outlined.CloudOff else Icons.Default.CloudQueue,
            title = "Offline Mode", subtitle = "Default: On. Cloud features disabled.",
            onClick = { viewModel.toggleOfflineMode(!state.isOfflineMode) },
            trailing = { Switch(checked = state.isOfflineMode, onCheckedChange = { viewModel.toggleOfflineMode(it) }) }
        )
        SettingsTile(
            icon = Icons.Default.Contrast, title = "App-wide Theme", subtitle = "E-ink vs LCD, Sharpness",
            onClick = { navController.navigate("settings/app_appearance") }
        )
    }
}

@Composable
fun BookSettingsTab(navController: NavController) {
    SettingsGroup(title = "Library & Visuals") {
        SettingsTile(
            icon = Icons.Default.LibraryBooks, title = "Library Settings (Visuals)", subtitle = "E-ink optimization, Cover Styles, Smart Stacks",
            onClick = { navController.navigate(Screen.Bookshelf.route) } // Points to the LibrarySettingsScreen we just built!
        )
    }

    SettingsGroup(title = "Reading Experience") {
        SettingsTile(
            icon = Icons.Default.FormatSize, title = "Default Book Appearance", subtitle = "Fonts, margins, publisher styles, page color",
            onClick = { navController.navigate("settings/appearance") }
        )
        SettingsTile(
            icon = Icons.Default.RecordVoiceOver, title = "Speech & Narration", subtitle = "Language, Speed, Pitch, Sleep-Timer",
            onClick = { navController.navigate("settings/tts") }
        )
        SettingsTile(
            icon = Icons.Default.Book, title = "Dictionary Settings", subtitle = "Manage local and custom dictionaries",
            onClick = { navController.navigate("settings/dictionary") }
        )
    }
}

@Composable
fun ContentSettingsTab(navController: NavController) {
    SettingsGroup(title = "Sources") {
        SettingsTile(
            icon = Icons.Default.List, title = "Manage Catalogs", subtitle = "Add or remove OPDS feeds",
            onClick = { navController.navigate("catalog_manage") }
        )
    }
}

@Composable
fun IntelligenceSettingsTab(state: SettingsUiState, navController: NavController) {
    if (state.isOfflineMode) {
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text("Intelligence features are disabled while Offline Mode is active.", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
        }
    } else {
        SettingsGroup(title = "AI Configuration") {
            SettingsTile(
                icon = Icons.Default.AutoAwesome, title = "Gemini Integration", subtitle = "Manage API keys for Global Recall and Smart Summaries",
                onClick = { navController.navigate(Screen.AiConfig.route) }
            )
            SettingsTile(
                icon = Icons.Default.Image, title = "Cloudflare Works", subtitle = "Image generation settings",
                onClick = { /* Navigate to Cloudflare Config */ }
            )
        }
    }
}

// --- DUMMY HELPERS TO PREVENT COMPILER ERRORS (Replace with your actual UI components if they differ) ---
@Composable fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(8.dp)) { content() }
        }
    }
}

@Composable fun SettingsTile(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) trailing() else Icon(Icons.Default.ChevronRight, null, tint = Color.Gray)
    }
}