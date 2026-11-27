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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import org.vaachak.reader.core.data.repository.GatewayDiagnosticReport
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import org.vaachak.reader.leisure.navigation.Screen
import org.vaachak.reader.core.data.repository.AiManagementAction
import org.vaachak.reader.core.data.repository.AiManagedSlot
import org.vaachak.reader.core.data.repository.AiCredentialStatus
import kotlinx.coroutines.launch
import org.vaachak.reader.core.domain.model.ThemeMode
import org.vaachak.reader.core.domain.model.AiTextProvider
import org.vaachak.reader.core.data.remote.PrivateGatewayEndpoint
import org.vaachak.reader.leisure.ui.reader.components.VaachakHeader
import org.vaachak.reader.leisure.ui.testability.Tid
import org.vaachak.reader.leisure.ui.testability.TidScreen
import org.vaachak.reader.leisure.ui.testability.tid

/** Register the two actual destinations; tests can supply a non-network form host. */
internal fun NavGraphBuilder.aiProviderDestinations(
    navController: NavController,
    content: @Composable (AiTextProvider, () -> Unit) -> Unit = { provider, back ->
        AiConfigScreen(provider = provider, onBack = back)
    }
) {
    composable(Screen.AiConfig.route) { content(AiTextProvider.GEMINI) { navController.popBackStack() } }
    composable(Screen.InternalAiConfig.route) { content(AiTextProvider.PRIVATE_GATEWAY) { navController.popBackStack() } }
}

private fun aiProviderTitle(provider: AiTextProvider): String =
    if (provider == AiTextProvider.GEMINI) "External Providers" else "Internal — AI-G732"

@Composable
fun AiConfigScreen(provider: AiTextProvider, onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val vaultId by viewModel.activeVaultId.collectAsState()
    val diagnostics: GatewayDiagnosticsViewModel = hiltViewModel()
    androidx.compose.runtime.key(vaultId, provider) {
        val snapshotFlow = remember(vaultId) { viewModel.aiEditorSnapshot(vaultId) }
        val snapshot by snapshotFlow.collectAsState(initial = null)
        val editor = snapshot
        if (editor == null) {
            Scaffold(topBar = { VaachakHeader(title = aiProviderTitle(provider), onBack = onBack, isEink = state.themeMode == ThemeMode.E_INK) }) { padding ->
                Text("Loading vault AI settings…", modifier = Modifier.padding(padding))
            }
        } else {
            val statusFlow = remember(vaultId) { viewModel.credentialStatuses(vaultId) }
            val statuses by statusFlow.collectAsState(initial = emptyMap())
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            var saving by remember { mutableStateOf(false) }
            var message by remember { mutableStateOf("") }
            AiConfigForm(state = state.copy(aiConfig = editor.configuration, isOfflineMode = editor.offline), onBack = onBack, statuses = statuses,
                savedGeminiModel = editor.geminiModel, provider = provider,
                onManage = { slot, action, value -> viewModel.manageCredential(vaultId, slot, action, value) },
                onSave = { request ->
                    if (!saving) {
                        saving = true
                        scope.launch {
                            try { viewModel.saveConfiguration(vaultId, request); onBack() }
                            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (_: Exception) { message = "Could not save AI configuration. Check the vault and retry." }
                            finally { saving = false }
                        }
                    }
                },
                onTestGateway = { diagnostics.check(vaultId) }, saving = saving, saveMessage = message,
                geminiModelPanel = { enabled, model -> GeminiModelPanel(vaultId, enabled, model) },
                cloudflareDiagnosticPanel = { enabled -> CloudflareDiagnosticPanel(vaultId, enabled) })
        }
    }
}

/** Same form for production and synthetic callback-based instrumentation. No saved plaintext inputs. */
@Composable
internal fun AiConfigForm(
    state: SettingsUiState,
    onBack: () -> Unit,
    statuses: Map<AiManagedSlot, AiCredentialStatus>,
    onManage: suspend (AiManagedSlot, AiManagementAction, String) -> Unit,
    onSave: (AiConfigSaveRequest) -> Unit,
    onTestGateway: (suspend () -> GatewayDiagnosticReport)? = null,
    saving: Boolean = false,
    saveMessage: String = "",
    geminiModelPanel: (@Composable (Boolean, GeminiModelEditor) -> Unit)? = null,
    cloudflareDiagnosticPanel: (@Composable (Boolean) -> Unit)? = null,
    savedGeminiModel: String = "",
    provider: AiTextProvider = state.aiConfig.textProvider
) {
    val isEink = state.themeMode == ThemeMode.E_INK
    var url by remember(state.aiConfig.cloudflareUrl) { mutableStateOf(state.aiConfig.cloudflareUrl) }
    var autoSave by remember(state.aiConfig.autoSaveRecaps) { mutableStateOf(state.aiConfig.autoSaveRecaps) }
    var gatewayUrl by remember(state.aiConfig.gatewayUrl) { mutableStateOf(state.aiConfig.gatewayUrl) }
    var gatewayModel by remember(state.aiConfig.gatewayModel) { mutableStateOf(state.aiConfig.gatewayModel) }
    var geminiModel by remember(savedGeminiModel) { mutableStateOf(savedGeminiModel) }
    var modelBusy by remember { mutableStateOf(false) }
    val modelChanged = geminiModel != savedGeminiModel
    val modelValid = provider != AiTextProvider.GEMINI || !modelChanged || org.vaachak.reader.core.data.remote.GeminiTextModels.isCandidate(geminiModel)
    var gatewayError by remember { mutableStateOf("") }
    var diagnosticRevision by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var dirtySlots by remember { mutableStateOf(emptySet<AiManagedSlot>()) }
    val blocked = busy || saving || modelBusy
    val savedGatewayUnchanged = provider == state.aiConfig.textProvider &&
        gatewayUrl == state.aiConfig.gatewayUrl && gatewayModel == state.aiConfig.gatewayModel &&
        AiManagedSlot.GATEWAY !in dirtySlots
    val ready: (AiManagedSlot) -> Boolean = { statuses[it] == AiCredentialStatus.SAVED && it !in dirtySlots }
    val updateBusy: (Boolean) -> Unit = { active ->
        busy = active
        if (active) diagnosticRevision++
    }
    val updateDirty: (AiManagedSlot, Boolean) -> Unit = { slot, dirty ->
        dirtySlots = if (dirty) dirtySlots + slot else dirtySlots - slot
        diagnosticRevision++
    }
    TidScreen(Tid.Screen.aiConfig) {
        Scaffold(topBar = { VaachakHeader(title = aiProviderTitle(provider), onBack = onBack, isEink = isEink) }) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
                Text(if (provider == state.aiConfig.textProvider) "Active text provider" else "Not active for text. Save Changes to activate.",
                    modifier = Modifier.tid("ai_config_active_provider"), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                if (provider == AiTextProvider.PRIVATE_GATEWAY) {
                    OutlinedCard(Modifier.fillMaxWidth().tid("ai_config_internal_panel")) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Internal AI-G732", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Explain · Who · Recap · Recall", style = MaterialTheme.typography.bodySmall)
                            Text("Existing R7.1 text API. Requires Tailscale and a separate Vaachak key.", style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(value = gatewayUrl, onValueChange = { gatewayUrl = it; diagnosticRevision++ }, enabled = !blocked,
                                label = { Text("Gateway HTTPS API base (/v1)") }, modifier = Modifier.fillMaxWidth().tid("ai_config_gateway_url"), singleLine = true)
                            OutlinedTextField(value = gatewayModel, onValueChange = { gatewayModel = it; diagnosticRevision++ }, enabled = !blocked,
                                label = { Text("Model alias") }, modifier = Modifier.fillMaxWidth().tid("ai_config_gateway_model"), singleLine = true)
                            AiCredentialPanel(AiManagedSlot.GATEWAY, statuses[AiManagedSlot.GATEWAY], blocked, updateBusy,
                                onDraftChanged = { updateDirty(AiManagedSlot.GATEWAY, it) }) { action, value -> onManage(AiManagedSlot.GATEWAY, action, value) }
                            if (onTestGateway != null) androidx.compose.runtime.key(diagnosticRevision, state.aiConfig.gatewayUrl, state.aiConfig.gatewayModel) {
                                GatewayDiagnosticsPanel(enabled = !blocked && savedGatewayUnchanged && ready(AiManagedSlot.GATEWAY) && !state.isOfflineMode,
                                    onTest = onTestGateway)
                            }
                        }
                    }
                } else {
                    OutlinedCard(Modifier.fillMaxWidth().tid("ai_config_gemini_panel")) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Google Gemini", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Explain · Who · Recap · Recall", style = MaterialTheme.typography.bodySmall)
                            AiCredentialPanel(AiManagedSlot.GEMINI, statuses[AiManagedSlot.GEMINI], blocked, updateBusy,
                                onDraftChanged = { updateDirty(AiManagedSlot.GEMINI, it) }) { action, value -> onManage(AiManagedSlot.GEMINI, action, value) }
                            androidx.compose.runtime.key(diagnosticRevision) {
                                geminiModelPanel?.invoke(!busy && !saving && ready(AiManagedSlot.GEMINI) && !state.isOfflineMode &&
                                    provider == AiTextProvider.GEMINI && state.aiConfig.textProvider == AiTextProvider.GEMINI,
                                    GeminiModelEditor(savedGeminiModel, geminiModel, !busy && !saving,
                                        onChange = { geminiModel = it }, onBusy = { modelBusy = it }))
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {
                                contentDescription = "Auto-Save Recaps, ${if (autoSave) "On" else "Off"}"
                            }) {
                                Column(Modifier.weight(1f)) {
                                    Text("Auto-Save Recaps", style = MaterialTheme.typography.bodyLarge)
                                    Text("Save generated summaries to history", style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(checked = autoSave, enabled = !blocked, onCheckedChange = { autoSave = it }, modifier = Modifier.tid("ai_config_auto_save"))
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    OutlinedCard(Modifier.fillMaxWidth().tid("ai_config_cloudflare_panel")) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Cloudflare Workers AI", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Visualize", style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(value = url, onValueChange = { url = it; diagnosticRevision++ }, enabled = !blocked,
                                label = { Text("Cloudflare AI URL") }, modifier = Modifier.fillMaxWidth().tid("ai_config_cloudflare_url"), singleLine = true)
                            AiCredentialPanel(AiManagedSlot.CLOUDFLARE, statuses[AiManagedSlot.CLOUDFLARE], blocked, updateBusy,
                                onDraftChanged = { updateDirty(AiManagedSlot.CLOUDFLARE, it) }) { action, value -> onManage(AiManagedSlot.CLOUDFLARE, action, value) }
                            androidx.compose.runtime.key(diagnosticRevision) {
                                cloudflareDiagnosticPanel?.invoke(!blocked && ready(AiManagedSlot.CLOUDFLARE) && !state.isOfflineMode &&
                                    url.isNotBlank() && url == state.aiConfig.cloudflareUrl)
                            }
                        }
                    }
                }
                if (gatewayError.isNotBlank()) Text(gatewayError, color = MaterialTheme.colorScheme.error)
                state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(24.dp))
                if (saveMessage.isNotBlank()) Text(saveMessage, color = MaterialTheme.colorScheme.error)
                if (dirtySlots.isNotEmpty()) Text("Save or clear the replacement draft before saving other settings.")
                if (!modelValid) Text("Select a valid Gemini text model or restore the saved model.", color = MaterialTheme.colorScheme.error)
                Button(enabled = !blocked && dirtySlots.isEmpty() && modelValid, onClick = {
                    val failure = if (provider == AiTextProvider.PRIVATE_GATEWAY) {
                        runCatching {
                            PrivateGatewayEndpoint.normalizedBase(gatewayUrl)
                            PrivateGatewayEndpoint.validatedModel(gatewayModel)
                        }.exceptionOrNull()
                    } else null
                    if (failure != null) {
                        gatewayError = failure.message ?: "Invalid gateway settings"
                    } else {
                        diagnosticRevision++
                        val external = provider == AiTextProvider.GEMINI
                        onSave(AiConfigSaveRequest("", if (external) url else state.aiConfig.cloudflareUrl, "",
                            if (external) autoSave else state.aiConfig.autoSaveRecaps,
                            AiGatewayUpdate(provider, if (external) state.aiConfig.gatewayUrl else gatewayUrl,
                                if (external) state.aiConfig.gatewayModel else gatewayModel, ""),
                            geminiModel = geminiModel.takeIf { external && modelChanged }))
                    }
                }, modifier = Modifier.fillMaxWidth().height(50.dp).tid("ai_config_save")) { Text("Save Changes", fontWeight = FontWeight.Bold) }
            }
        }
    }
}
