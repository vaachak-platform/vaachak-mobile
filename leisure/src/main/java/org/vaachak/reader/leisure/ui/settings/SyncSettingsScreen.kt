/*
 *  Copyright (c) 2026 Piyush Daiya
 *  *
 *  * Permission is hereby granted, free of charge, to any person obtaining a copy
 *  * of this software and associated documentation files (the "Software"), to deal
 *  * in the Software without restriction, including without limitation the rights
 *  * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  * copies of the Software, and to permit persons to whom the Software is
 *  * furnished to do so, subject to the following conditions:
 *  *
 *  * The above copyright notice and this permission notice shall be included in all
 *  * copies or substantial portions of the Software.
 *  *
 *  * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  * SOFTWARE.
 */

package org.vaachak.reader.leisure.ui.settings

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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

    Scaffold(
        topBar = {
            VaachakHeader(
                title = "Sync Account",
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

            // 1. STATUS CARD
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isEink) Color.White else MaterialTheme.colorScheme.surfaceVariant,
                ),
                border = if (isEink) BorderStroke(1.dp, Color.Black) else null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = if(isEink) Color.Black else MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(state.userProfile.username ?: "Unknown", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(state.userProfile.deviceName?: "Unknown Device", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = if (isEink) Color.Black else MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudSync, contentDescription = null, tint = if(isEink) Color.Black else MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("Last Synced", style = MaterialTheme.typography.labelSmall)
                            val timeText = if (state.userProfile.lastSyncTime > 0) {
                                java.text.SimpleDateFormat("MMM dd, hh:mm a", java.util.Locale.getDefault()).format(state.userProfile.lastSyncTime)
                            } else {
                                "Never"
                            }
                            Text(timeText, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            // 2. FEEDBACK MESSAGE
            if (syncMessage != null) {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = syncMessage!!,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (syncMessage!!.contains("Failed") || syncMessage!!.contains("Error")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // 3. PRIMARY ACTIONS
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

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = { Toast.makeText(context, "Change Password feature coming soon", Toast.LENGTH_SHORT).show() },
                modifier = Modifier.fillMaxWidth(),
                colors = if (isEink) ButtonDefaults.outlinedButtonColors(contentColor = Color.Black) else ButtonDefaults.outlinedButtonColors()
            ) {
                Text("Change Password")
            }

            Spacer(modifier = Modifier.weight(1f)) // Push logout to bottom
            Spacer(modifier = Modifier.height(32.dp))

            // 4. DESTRUCTIVE ACTION
            TextButton(
                onClick = {
                    viewModel.logout()
                    navController.popBackStack() // Go back to settings (or login)
                },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Sign Out / Switch Account")
            }
        }
    }
}

