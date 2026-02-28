/*
 * Copyright (c) 2026 Piyush Daiya
 * *
 * * Permission is hereby granted, free of charge, to any person obtaining a copy
 * * of this software and associated documentation files (the "Software"), to deal
 * * in the Software without restriction, including without limitation the rights
 * * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * * copies of the Software, and to permit persons to whom the Software is
 * * furnished to do so, subject to the following conditions:
 * *
 * * The above copyright notice and this permission notice shall be included in all
 * * copies or substantial portions of the Software.
 * *
 * * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * * SOFTWARE.
 */

package org.vaachak.reader.leisure.ui.settings

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import org.vaachak.reader.leisure.ui.reader.components.VaachakHeader
import org.vaachak.reader.core.domain.model.ThemeMode

@Composable
fun SyncSettingsScreen(
    navController: androidx.navigation.NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val syncMessage by viewModel.syncMessage.collectAsState()
    val context = LocalContext.current
    val isEink = state.themeMode == ThemeMode.E_INK

    // TEMPORARY UI STATE (To be wired to DataStore in Phase 2)
    var isSyncEnabled by remember { mutableStateOf(false) }
    var tier1Url by remember { mutableStateOf("") }
    var tier2Url by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            VaachakHeader(
                title = "Sync Configuration",
                onBack = { navController.popBackStack() },
                isEink = isEink
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // FEEDBACK MESSAGE
            if (syncMessage != null) {
                Text(
                    text = syncMessage!!,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (syncMessage!!.contains("Failed") || syncMessage!!.contains("Error")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            // STATE 1: GLOBAL OFFLINE MODE IS ACTIVE
            if (state.isOfflineMode) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isEink) Color.White else MaterialTheme.colorScheme.errorContainer,
                    ),
                    border = if (isEink) BorderStroke(1.dp, Color.Black) else null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.CloudOff,
                            contentDescription = null,
                            tint = if(isEink) Color.Black else MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Sync features are disabled while Offline Mode is active.",
                            color = if(isEink) Color.Black else MaterialTheme.colorScheme.onErrorContainer,
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "To configure cloud synchronization, please turn off Offline Mode in your global settings.",
                            color = if(isEink) Color.Black else MaterialTheme.colorScheme.onErrorContainer,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            } else {
                // STATE 2: OFFLINE MODE IS OFF (Show Sync Controls)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Enable Sync Across Devices", fontWeight = FontWeight.Bold)
                        Text("Securely backup progress to the cloud.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    Switch(
                        checked = isSyncEnabled,
                        onCheckedChange = { isSyncEnabled = it }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp), color = if (isEink) Color.Black else MaterialTheme.colorScheme.outlineVariant)

                // STATE 3: SYNC IS TOGGLED ON
                if (isSyncEnabled) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CloudSync, contentDescription = null, tint = if(isEink) Color.Black else MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Server Configuration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Your Active Reader Profile credentials are used automatically to authenticate.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        Spacer(modifier = Modifier.height(24.dp))

                        OutlinedTextField(
                            value = tier1Url,
                            onValueChange = { tier1Url = it },
                            label = { Text("Tier 1 Server (Cloudflare Worker)") },
                            placeholder = { Text("https://sync.yourworker.workers.dev") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = tier2Url,
                            onValueChange = { tier2Url = it },
                            label = { Text("Tier 2 Server (Custom/Self-Hosted)") },
                            placeholder = { Text("http://192.168.1.100:8080") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(32.dp))

                        Button(
                            onClick = { viewModel.triggerManualSync() },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            colors = if (isEink) ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White) else ButtonDefaults.buttonColors()
                        ) {
                            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Sync Now")
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedButton(
                            onClick = { viewModel.testServerConnection() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = if (isEink) ButtonDefaults.outlinedButtonColors(contentColor = Color.Black) else ButtonDefaults.outlinedButtonColors()
                        ) {
                            Text("Test Connection")
                        }
                    }
                }
            }
        }
    }
}