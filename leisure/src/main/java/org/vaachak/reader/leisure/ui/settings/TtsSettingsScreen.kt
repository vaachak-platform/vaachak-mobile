package org.vaachak.reader.leisure.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val ttsSettings = state.ttsSettings
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Speech & Narration") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // 1. LANGUAGE OVERRIDE (Full BCP-47 Tags)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("LANGUAGE OVERRIDE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TtsFilterOption("Default", ttsSettings.language == "default") { viewModel.setTtsLanguage("default") }
                    TtsFilterOption("English", ttsSettings.language == "en-US") { viewModel.setTtsLanguage("en-US") }
                    TtsFilterOption("Hindi", ttsSettings.language == "hi-IN") { viewModel.setTtsLanguage("hi-IN") }
                    TtsFilterOption("Gujarati", ttsSettings.language == "gu-IN") { viewModel.setTtsLanguage("gu-IN") }
                    TtsFilterOption("Kutchi", ttsSettings.language == "gu-IN-kutchi") { viewModel.setTtsLanguage("gu-IN") }
                }
            }

            HorizontalDivider()

            // 2. NARRATION SPEED
            TtsStepperSetting(
                label = "Reading Speed",
                icon = Icons.Default.Speed,
                value = ttsSettings.defaultSpeed,
                onValueChange = { viewModel.updateTtsSpeed(it) },
                valueFormatter = { "${"%.1f".format(it)}x" }
            )

            HorizontalDivider()

            // 3. VOICE PITCH
            TtsStepperSetting(
                label = "Voice Pitch",
                icon = Icons.Default.GraphicEq,
                value = ttsSettings.pitch,
                onValueChange = { viewModel.updateTtsPitch(it) },
                valueFormatter = { "%.1f".format(it) }
            )

            HorizontalDivider()

            // 4. BEHAVIOR
            Text("BEHAVIOR", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            SettingsToggleTile(
                title = "Auto-Turn Pages",
                subtitle = "Turn pages automatically while reading",
                checked = ttsSettings.isAutoPageTurnEnabled,
                onCheckedChange = { viewModel.setTtsAutoPageTurn(it) }
            )

            HorizontalDivider()

            // 5. SLEEP TIMER
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SLEEP TIMER", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(0, 15, 30, 60).forEach { mins ->
                        TtsFilterOption(if (mins == 0) "Off" else "${mins}m", ttsSettings.sleepTimerMinutes == mins) {
                            viewModel.setSleepTimer(mins)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent("com.android.settings.TTS_SETTINGS").apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                },
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text("Manage System Voices")
            }
        }
    }
}





