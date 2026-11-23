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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import org.vaachak.reader.core.domain.model.ThemeMode
import org.vaachak.reader.core.domain.model.AiTextProvider
import org.vaachak.reader.core.data.remote.PrivateGatewayEndpoint
import org.vaachak.reader.leisure.ui.reader.components.VaachakHeader
import org.vaachak.reader.leisure.ui.testability.Tid
import org.vaachak.reader.leisure.ui.testability.TidScreen
import org.vaachak.reader.leisure.ui.testability.tid

@Composable
fun AiConfigScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    AiConfigForm(
        state = state,
        onBack = onBack,
        onMaskChange = viewModel::setAiMasked,
        onClearGatewayKey = viewModel::clearPrivateGatewayToken,
        onSave = { request -> viewModel.saveAiConfig(request, onSaved = onBack) }
    )
}

/** Production AI form accepts callbacks to make credential validation testable on an emulator. */
@Composable
internal fun AiConfigForm(
    state: SettingsUiState,
    onBack: () -> Unit,
    onMaskChange: (Boolean) -> Unit,
    onClearGatewayKey: () -> Unit,
    onSave: (AiConfigSaveRequest) -> Unit
) {
    val isEink = state.themeMode == ThemeMode.E_INK
    // Local state for the form
    var key by remember { mutableStateOf(state.aiConfig.geminiKey) }
    var url by remember { mutableStateOf(state.aiConfig.cloudflareUrl) }
    var token by remember { mutableStateOf(state.aiConfig.authToken) }
    var autoSave by remember { mutableStateOf(state.aiConfig.autoSaveRecaps) }
    var provider by remember { mutableStateOf(state.aiConfig.textProvider) }
    var gatewayUrl by remember { mutableStateOf(state.aiConfig.gatewayUrl) }
    var gatewayModel by remember { mutableStateOf(state.aiConfig.gatewayModel) }
    var gatewayToken by remember { mutableStateOf("") }
    var gatewayError by remember { mutableStateOf("") }
    LaunchedEffect(state.aiConfig) {
        key = state.aiConfig.geminiKey
        url = state.aiConfig.cloudflareUrl
        token = state.aiConfig.authToken
        autoSave = state.aiConfig.autoSaveRecaps
        provider = state.aiConfig.textProvider
        gatewayUrl = state.aiConfig.gatewayUrl
        gatewayModel = state.aiConfig.gatewayModel
    }

    TidScreen(Tid.Screen.aiConfig) {
    Scaffold(
        topBar = {
            VaachakHeader(
                title = "AI Configuration",
                onBack = onBack,
                isEink = isEink
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Text("Text AI Provider", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = provider == AiTextProvider.GEMINI,
                    onClick = { provider = AiTextProvider.GEMINI },
                    modifier = Modifier.tid("ai_config_provider_gemini"))
                Text("Gemini", modifier = Modifier.padding(end = 16.dp))
                RadioButton(selected = provider == AiTextProvider.PRIVATE_GATEWAY,
                    onClick = { provider = AiTextProvider.PRIVATE_GATEWAY },
                    modifier = Modifier.tid("ai_config_provider_gateway"))
                Text("Private ai-g732")
            }
            if (provider == AiTextProvider.PRIVATE_GATEWAY) {
                Text("Text only. Requires Tailscale and a separate Vaachak key.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = gatewayUrl, onValueChange = { gatewayUrl = it },
                    label = { Text("Gateway HTTPS API base (/v1)") },
                    modifier = Modifier.fillMaxWidth().tid("ai_config_gateway_url"), singleLine = true)
                OutlinedTextField(value = gatewayModel, onValueChange = { gatewayModel = it },
                    label = { Text("Model alias") },
                    modifier = Modifier.fillMaxWidth().tid("ai_config_gateway_model"), singleLine = true)
                OutlinedTextField(value = gatewayToken, onValueChange = { gatewayToken = it },
                    label = { Text("Vaachak gateway API key (blank keeps saved key)") },
                    modifier = Modifier.fillMaxWidth().tid("ai_config_gateway_key"), singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
                Button(onClick = { onClearGatewayKey() }) {
                    Text("Remove saved Vaachak gateway key")
                }
            }
            if (gatewayError.isNotBlank()) Text(gatewayError, color = MaterialTheme.colorScheme.error)
            state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(modifier = Modifier.height(24.dp))
            Text("Gemini Integration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("Gemini API Key") },
                modifier = Modifier.fillMaxWidth().tid("ai_config_gemini_key"),
                singleLine = true,
                visualTransformation = if (state.isAiMasked) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false // Extra safety to prevent dictionary caching
                ),
                trailingIcon = {
                    IconButton(
                        onClick = { onMaskChange(!state.isAiMasked) },
                        modifier = Modifier.tid("ai_config_toggle_mask")
                    ) {
                        Icon(
                            imageVector = if (state.isAiMasked) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = if (state.isAiMasked) "Show API Key" else "Hide API Key"
                        )
                    }
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text("Cloudflare Workers (Image Generation)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Cloudflare AI URL") },
                modifier = Modifier.fillMaxWidth().tid("ai_config_cloudflare_url"),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Cloudflare Token") },
                modifier = Modifier.fillMaxWidth().tid("ai_config_cloudflare_token"),
                singleLine = true,
                visualTransformation = if (state.isAiMasked) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false // Extra safety to prevent dictionary caching
                )
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Auto-Save Recaps, ${if (autoSave) "On" else "Off"}"
                    }
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-Save Recaps", style = MaterialTheme.typography.bodyLarge)
                    Text("Save generated summaries to history", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = autoSave,
                    onCheckedChange = { autoSave = it },
                    modifier = Modifier.tid("ai_config_auto_save")
                )
            }

            Spacer(modifier = Modifier.weight(1f)) // Pushes the button to the bottom
            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    if (provider == AiTextProvider.PRIVATE_GATEWAY) {
                        val failure = runCatching {
                            PrivateGatewayEndpoint.normalizedBase(gatewayUrl)
                            PrivateGatewayEndpoint.validatedModel(gatewayModel)
                        }.exceptionOrNull()
                        if (failure != null) {
                            gatewayError = failure.message ?: "Invalid gateway settings"
                            return@Button
                        }
                    }
                    onSave(
                        AiConfigSaveRequest(
                            geminiKey = key,
                            cloudflareUrl = url,
                            cloudflareToken = token,
                            autoSaveRecaps = autoSave,
                            gateway = AiGatewayUpdate(
                                provider = provider,
                                url = gatewayUrl,
                                model = gatewayModel,
                                token = gatewayToken
                            )
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth().height(50.dp).tid("ai_config_save")
            ) {
                Text("Save Changes", fontWeight = FontWeight.Bold)
            }
        }
    }
    }
}
