package org.vaachak.reader.leisure.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vaachak.reader.core.data.repository.AiRepository
import org.vaachak.reader.core.data.repository.AiVaultScope
import org.vaachak.reader.core.data.repository.SettingsRepository
import org.vaachak.reader.core.data.remote.GeminiTextModels
import org.vaachak.reader.leisure.ui.testability.tid
import javax.inject.Inject

@HiltViewModel
class GeminiModelViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val ai: AiRepository
) : ViewModel() {
    val savedModel = settings.geminiModel
    suspend fun save(vault: String, model: String) = settings.saveGeminiModel(vault, model)
    suspend fun discover(vault: String) = withContext(AiVaultScope(vault)) { ai.discoverSavedGeminiModels() }
    suspend fun test(vault: String) = withContext(AiVaultScope(vault)) { ai.testSavedGemini() }
    suspend fun testImage(vault: String) = withContext(AiVaultScope(vault)) { ai.testSavedCloudflare() }
}

/** The form owns the draft; both save buttons use the same selection. */
internal class GeminiModelEditor(
    val saved: String,
    val draft: String,
    val editingEnabled: Boolean,
    val onChange: (String) -> Unit,
    val onBusy: (Boolean) -> Unit
)

/** Independent immediate model save and diagnostics; never receives raw credentials. */
@Composable
internal fun GeminiModelPanel(vault: String, enabled: Boolean, editor: GeminiModelEditor, viewModel: GeminiModelViewModel = hiltViewModel()) {
    val saved = editor.saved
    val draft = editor.draft
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf(emptyList<String>()) }
    var menu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    LaunchedEffect(saved) { message = "" }
    LaunchedEffect(enabled) { if (!enabled) { models = emptyList(); menu = false; message = "" } }
    Text("Saved Gemini model: ${saved.ifBlank { "Not selected" }}")
    if (draft != saved) Text("Unsaved model selection — Save Changes or Save Gemini model.")
    OutlinedTextField(value = draft, onValueChange = editor.onChange, enabled = editor.editingEnabled && !busy,
        label = { Text("Gemini text model ID") },
        singleLine = true, modifier = Modifier.fillMaxWidth().tid("ai_config_gemini_model"))
    Button(enabled = editor.editingEnabled && !busy && GeminiTextModels.isCandidate(draft), onClick = {
        busy = true
        editor.onBusy(true)
        scope.launch {
            try { viewModel.save(vault, draft); message = "Model saved. Inference not yet verified." }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "Could not save model. Check vault and model ID." }
            finally { busy = false; editor.onBusy(false) }
        }
    }) { Text("Save Gemini model") }
    Button(enabled = enabled && !busy, onClick = {
        scope.launch {
            busy = true
            models = emptyList()
            try {
                val result = viewModel.discover(vault)
                models = result.models
                message = result.message
                menu = models.isNotEmpty()
            } finally { busy = false }
        }
    }) { Text("Refresh text model candidates") }
    Button(enabled = editor.editingEnabled && models.isNotEmpty() && !busy, onClick = { menu = true }) { Text("Choose discovered model") }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        models.forEach { model -> DropdownMenuItem(text = { Text(model) }, enabled = editor.editingEnabled, onClick = { editor.onChange(model); menu = false }) }
    }
    Text("Catalog candidates are not proof of text inference or billing entitlement. Save your key and select Gemini before testing.")
    Button(enabled = enabled && !busy && saved.isNotBlank() && draft == saved, onClick = {
        scope.launch {
            busy = true
            try { message = viewModel.test(vault) }
            finally { busy = false }
        }
    }) { Text("Test saved Gemini (uses quota)") }
    if (busy) Text("Checking Gemini…")
    if (message.isNotBlank()) Text(message)
}

/** Explicit quota-bearing synthetic image check. No reader content or provider bytes are shown. */
@Composable
internal fun CloudflareDiagnosticPanel(vault: String, enabled: Boolean, viewModel: GeminiModelViewModel = hiltViewModel()) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    LaunchedEffect(enabled) { if (!enabled) message = "" }
    Text("Test sends a fixed synthetic prompt to the saved image endpoint and may use quota.")
    Button(enabled = enabled && !busy, onClick = {
        scope.launch {
            busy = true
            try { message = viewModel.testImage(vault) }
            finally { busy = false }
        }
    }) { Text("Test saved Cloudflare (uses quota)") }
    if (busy) Text("Checking Cloudflare…")
    if (message.isNotBlank()) Text(message)
}
