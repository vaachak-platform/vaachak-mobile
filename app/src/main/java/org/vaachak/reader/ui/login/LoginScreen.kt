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

package org.vaachak.reader.ui.login

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import org.vaachak.reader.ui.reader.components.VaachakHeader

@Composable
fun LoginScreen(
    navController: NavController,
    viewModel: LoginViewModel = hiltViewModel()
) {
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    val username by viewModel.username.collectAsState()
    val password by viewModel.password.collectAsState()
    val serverUrl by viewModel.serverUrl.collectAsState()
    val useCustomServer by viewModel.useCustomServer.collectAsState()

    // Device Config State
    val deviceName by viewModel.deviceName.collectAsState()
    val deviceId by viewModel.deviceId.collectAsState()
    val testStatus by viewModel.testStatus.collectAsState()

    var isRegisterMode by remember { mutableStateOf(false) }
    var isPasswordVisible by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            VaachakHeader(
                title = if (isRegisterMode) "Create Account" else "Sign In",
                onBack = { navController.popBackStack() }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()) // FIX: Prevents layout squishing
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // --- TABS ---
            TabRow(selectedTabIndex = if (isRegisterMode) 1 else 0) {
                Tab(selected = !isRegisterMode, onClick = { isRegisterMode = false }, text = { Text("Sign In") })
                Tab(selected = isRegisterMode, onClick = { isRegisterMode = true }, text = { Text("Register") })
            }

            Spacer(modifier = Modifier.height(32.dp))

            // --- CREDENTIALS ---
            OutlinedTextField(
                value = username,
                onValueChange = { viewModel.username.value = it },
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { viewModel.password.value = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                trailingIcon = {
                    IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                        Icon(if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Toggle")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            // --- SERVER CONFIG ---
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.useCustomServer.value = !useCustomServer }
            ) {
                Checkbox(checked = useCustomServer, onCheckedChange = { viewModel.useCustomServer.value = it })
                Text("Use Custom Server / Self-Hosted")
            }

            AnimatedVisibility(visible = useCustomServer) {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { viewModel.serverUrl.value = it },
                        label = { Text("Server URL") },
                        placeholder = { Text("https://your-worker.workers.dev") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Connection Test
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (testStatus != null) {
                            Text(
                                text = testStatus!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f).padding(end = 8.dp)
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        TextButton(onClick = { viewModel.testUrlConnection() }) {
                            Text("Test Connection")
                        }
                    }
                }
            }

            // --- DEVICE CONFIG ---
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            OutlinedTextField(
                value = deviceName,
                onValueChange = { viewModel.deviceName.value = it },
                label = { Text("Device Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Device ID: $deviceId",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Start)
            )

            // --- ERROR MESSAGE ---
            if (error != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // --- MAIN BUTTON ---
            Button(
                onClick = {
                    viewModel.submit(isRegister = isRegisterMode) {
                        navController.popBackStack()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                } else {
                    Text(if (isRegisterMode) "Create Account" else "Sign In")
                }
            }
        }
    }
}

