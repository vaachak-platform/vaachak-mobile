package org.vaachak.reader.leisure.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.vaachak.reader.core.data.repository.AiCredentialStatus
import org.vaachak.reader.core.data.repository.AiManagedSlot
import org.vaachak.reader.core.data.repository.AiManagementAction
import org.vaachak.reader.leisure.ui.testability.tid

/** Only a newly typed replacement enters UI state; saved plaintext never does. */
@Composable
internal fun AiCredentialPanel(slot: AiManagedSlot, status: AiCredentialStatus?, busy: Boolean,
    onBusyChange: (Boolean) -> Unit, onDraftChanged: (Boolean) -> Unit,
    onManage: suspend (AiManagementAction, String) -> Unit) {
    val scope = rememberCoroutineScope()
    var draft by remember(slot) { mutableStateOf("") }
    var confirmation by remember(slot) { mutableStateOf<AiManagementAction?>(null) }
    var masked by remember(slot) { mutableStateOf(true) }
    var ownBusy by remember(slot) { mutableStateOf(false) }
    var message by remember(slot) { mutableStateOf("") }
    val tag = "ai_credential_${slot.name.lowercase()}"
    fun execute(action: AiManagementAction, value: String = "") {
        if (busy || ownBusy) return
        ownBusy = true
        onBusyChange(true)
        draft = ""
        masked = true
        onDraftChanged(false) // never retain a submitted draft across operation or navigation
        scope.launch {
            try {
                onManage(action, value)
                message = when (action) {
                    AiManagementAction.REMOVE -> "Credential removed."
                    AiManagementAction.REPLACE -> "Credential saved."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = "Operation not completed. Check status; unlock the device or retry recovery."
            } finally { ownBusy = false; onBusyChange(false) }
        }
    }
    Column {
        Text(when (status) {
            null -> "Checking saved credential…"
            AiCredentialStatus.MISSING -> "Not configured"
            AiCredentialStatus.SAVED -> "Saved (not yet verified with provider)"
            AiCredentialStatus.UNAVAILABLE -> "Saved but unavailable. Unlock your secure device."
            AiCredentialStatus.RECOVERY_REQUIRED -> "Recovery required. Replace or remove explicitly."
            AiCredentialStatus.REMOVAL_PENDING -> "Removal incomplete. Confirm removal again to finish cleanup."
        }, modifier = Modifier.tid("${tag}_status"))
        OutlinedTextField(value = draft, onValueChange = { draft = it; onDraftChanged(it.isNotBlank()) },
            label = { Text("New credential (blank keeps saved value)") },
            enabled = !busy, singleLine = true,
            visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
            trailingIcon = { IconButton(onClick = { masked = !masked }, enabled = !busy,
                modifier = Modifier.tid("${tag}_mask")) {
                Icon(if (masked) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    contentDescription = if (masked) "Show new draft" else "Hide new draft")
            } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().tid("${tag}_draft"))
        Button(enabled = !busy && status != null && draft.isNotBlank(),
            onClick = {
                if (status == AiCredentialStatus.MISSING) execute(AiManagementAction.REPLACE, draft)
                else confirmation = AiManagementAction.REPLACE
            }, modifier = Modifier.tid("${tag}_replace")) {
            Text(if (status == AiCredentialStatus.MISSING) "Save credential" else "Replace credential")
        }
        if (status != null && status != AiCredentialStatus.MISSING) {
            TextButton(enabled = !busy, onClick = { confirmation = AiManagementAction.REMOVE }, modifier = Modifier.tid("${tag}_remove")) {
                Text("Remove saved credential")
            }
        }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
    }
    confirmation?.let { action ->
        CredentialConfirmation(action, busy || ownBusy, draft,
            onDismiss = { confirmation = null },
            onConfirm = { value ->
                confirmation = null
                execute(action, value)
            })
    }
}

@Composable
private fun CredentialConfirmation(action: AiManagementAction, busy: Boolean, draft: String,
    onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val replacing = action == AiManagementAction.REPLACE
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (replacing) "Replace saved credential?" else "Remove saved credential?") },
        text = { Text(if (replacing) "Replace the saved credential for this provider in this reader profile."
            else "AI features requiring this credential will be unavailable until a new credential is saved.") },
        confirmButton = { TextButton(enabled = !busy && (!replacing || draft.isNotBlank()),
            onClick = { onConfirm(if (replacing) draft else "") }) { Text(if (replacing) "Replace" else "Remove") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep") } })
}
