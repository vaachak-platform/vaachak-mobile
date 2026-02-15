/*
 * Copyright (c) 2026 Piyush Daiya
 * ... (License Header)
 */

package org.vaachak.reader.leisure.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import org.vaachak.reader.leisure.domain.models.SettingsSection
import org.vaachak.reader.leisure.ui.reader.components.VaachakHeader
import org.vaachak.reader.leisure.ui.theme.ThemeMode
import org.vaachak.reader.leisure.navigation.Screen

@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val syncMessage by viewModel.syncMessage.collectAsState() // NEW

    val scrollState = rememberScrollState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            VaachakHeader(
                title = "Settings",
                onBack = { navController.popBackStack() },
                showBackButton = true,
                isEink = state.isOfflineMode
            )
        }
    ) { paddingValues ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
        ) {
            // SECTION 1: PROFILE HUB
            UserProfileCard(
                profile = state.userProfile,
                onLoginClick = { navController.navigate("login") },
                onManageClick = { navController.navigate(Screen.SyncSettings.route) }
            )

            // SECTION 2: GENERAL
            SettingsGroup(title = "General") {
                // Offline Mode
                SettingsTile(
                    icon = if (state.isOfflineMode) Icons.Outlined.CloudOff else Icons.Default.CloudQueue,
                    title = "Offline Mode",
                    subtitle = if (state.isOfflineMode) "Cloud features disabled" else "Sync active",
                    onClick = { viewModel.toggleOfflineMode(!state.isOfflineMode) },
                    trailing = {
                        Switch(
                            checked = state.isOfflineMode,
                            onCheckedChange = { viewModel.toggleOfflineMode(it) }
                        )
                    }
                )

                // Dictionary
                SettingsTile(
                    icon = Icons.Default.Book,
                    title = "Dictionary Settings",
                    subtitle = "Manage external dictionaries",
                    onClick = {
                        Toast.makeText(context, "Dictionary management coming in v1.3", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            // SECTION 3: CONTENT
            SettingsGroup(title = "Content & Sources") {
                SettingsTile(
                    icon = Icons.AutoMirrored.Filled.List,
                    title = "Manage Catalogs",
                    subtitle = "${state.catalogs.size} active feeds",
                    onClick = { navController.navigate("catalog_manage") }
                )
            }

            // SECTION 4: INTELLIGENCE
            if (!state.isOfflineMode) {
                SettingsGroup(title = "Intelligence") {
                    SettingsTile(
                        icon = Icons.Default.AutoAwesome,
                        title = "AI Configuration",
                        subtitle = if (state.aiConfig.isEnabled) "Enabled" else "Configure AI keys",
                        onClick = { navController.navigate(Screen.AiConfig.route) } // Navigates to dedicated screen
                    )
                }
            }

            // SECTION 5: APP INFO
            SettingsGroup(title = "App Info") {
                SettingsTile(
                    icon = Icons.Default.Info,
                    title = "About Vaachak",
                    subtitle = "Version 1.2.0 (Beta)",
                    onClick = { /* Show Dialog */ }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }

    }
}