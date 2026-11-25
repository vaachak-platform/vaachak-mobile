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

package org.vaachak.reader.leisure.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.vaachak.reader.core.domain.model.OpdsEntity
import org.vaachak.reader.core.data.repository.CatalogCredentialAction
import kotlinx.coroutines.launch
import kotlinx.coroutines.Deferred
import org.vaachak.reader.core.data.repository.OpdsMigrationReport
import org.vaachak.reader.leisure.ui.testability.Tid
import org.vaachak.reader.leisure.ui.testability.TidScreen
import org.vaachak.reader.leisure.ui.testability.tid

@Composable
fun CatalogManagerScreen(
    onBack: () -> Unit,
    viewModel: CatalogViewModel = hiltViewModel()
) {
    val catalogs by viewModel.catalogs.collectAsStateWithLifecycle()
    val isEink by viewModel.isEinkEnabled.collectAsStateWithLifecycle()
    CatalogManagerContent(catalogs, isEink, onBack,
        onSave = { existing, title, url, action, user, pass ->
            viewModel.saveCatalog(existing, title, url, action, user, pass).awaitCatalogOperation()
        },
        onDelete = { viewModel.deleteCatalog(it).awaitCatalogOperation() },
        onMigrate = { viewModel.migrateLegacyCredentials().awaitCatalogOperation() })
}

// The ViewModel owns the job; disposing its requesting screen also cancels the operation.
private suspend fun <T> Deferred<T>.awaitCatalogOperation(): T = try { await() } finally { cancel() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CatalogManagerContent(
    catalogs: List<OpdsEntity>,
    isEink: Boolean,
    onBack: () -> Unit,
    onSave: suspend (OpdsEntity?, String, String, CatalogCredentialAction, String?, String?) -> Result<String>,
    onDelete: suspend (OpdsEntity) -> Result<String>,
    onMigrate: suspend () -> Result<OpdsMigrationReport>
) {
    var showDialog by remember { mutableStateOf(false) }
    var editingCatalog by remember { mutableStateOf<OpdsEntity?>(null) }
    var deletingCatalog by remember { mutableStateOf<OpdsEntity?>(null) }
    var operationBusy by remember { mutableStateOf(false) }
    var operationMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun migrate() {
        if (operationBusy) return
        operationBusy = true
        operationMessage = null
        scope.launch {
            try {
                operationMessage = onMigrate().fold(
                    onSuccess = { "Migrated ${it.migrated}; ${it.remaining} catalog(s) still need migration." },
                    onFailure = { "Migration unavailable. Unlock your device and retry." }
                )
            } finally { operationBusy = false }
        }
    }

    fun delete(feed: OpdsEntity) {
        if (operationBusy) return
        operationBusy = true
        scope.launch {
            try {
                val result = onDelete(feed)
                operationMessage = if (result.isSuccess) "Catalog deleted." else "Delete unavailable. Refresh and retry."
                deletingCatalog = null
            } finally { operationBusy = false }
        }
    }

    val containerColor = if (isEink) Color.White else MaterialTheme.colorScheme.background
    val contentColor = if (isEink) Color.Black else MaterialTheme.colorScheme.onBackground
    TidScreen(Tid.Screen.catalogManager) {
        Scaffold(
            topBar = { CatalogManagerTopBar(containerColor, contentColor, operationBusy, onBack) },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = {
                        if (!operationBusy) {
                            editingCatalog = null
                            showDialog = true
                        }
                    },
                    containerColor = if (isEink) Color.Black else MaterialTheme.colorScheme.primary,
                    contentColor = if (isEink) Color.White else MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.tid(Tid.CatalogManager.add)
                ) { Icon(Icons.Default.Add, "Add Catalog") }
            },
            containerColor = containerColor,
            contentColor = contentColor
        ) { padding ->
            CatalogManagerList(catalogs, isEink, operationBusy, operationMessage,
                onMigrate = ::migrate,
                onEdit = { feed -> editingCatalog = feed; showDialog = true },
                onDelete = { deletingCatalog = it },
                modifier = Modifier.padding(padding).fillMaxSize())
        }
        deletingCatalog?.let { feed ->
            CatalogDeleteConfirmation(operationBusy, onDismiss = { deletingCatalog = null }, onConfirm = { delete(feed) })
        }
        if (showDialog) {
            AddCatalogDialog(
                existingCatalog = editingCatalog,
                onDismiss = { showDialog = false; editingCatalog = null },
                onSave = { title, url, action, user, pass -> onSave(editingCatalog, title, url, action, user, pass) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatalogManagerTopBar(containerColor: Color, contentColor: Color, busy: Boolean, onBack: () -> Unit) {
    TopAppBar(
        title = { Text("Manage Catalogs") },
        navigationIcon = {
            IconButton(onClick = { if (!busy) onBack() }, enabled = !busy,
                modifier = Modifier.tid(Tid.CatalogManager.back)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = containerColor,
            titleContentColor = contentColor, navigationIconContentColor = contentColor)
    )
}

@Composable
private fun CatalogManagerList(
    catalogs: List<OpdsEntity>, isEink: Boolean, busy: Boolean, message: String?,
    onMigrate: () -> Unit, onEdit: (OpdsEntity) -> Unit, onDelete: (OpdsEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    if (catalogs.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No catalogs added yet.", color = Color.Gray)
                message?.let { Text(it) }
            }
        }
        return
    }
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(bottom = 80.dp)) {
        item { CatalogMigrationHeader(catalogs.count { it.username != null || it.password != null }, busy, message, onMigrate) }
        items(items = catalogs, key = { it.id }) { feed ->
            CatalogManagerItem(feed, isEink,
                onEdit = { if (!busy) onEdit(feed) },
                onDelete = { if (!busy) onDelete(feed) },
                modifier = if (catalogs.first() == feed) Modifier.tid(Tid.CatalogManager.first) else Modifier)
            HorizontalDivider(thickness = 0.5.dp, color = Color.LightGray)
        }
    }
}

@Composable
private fun CatalogMigrationHeader(legacyCount: Int, busy: Boolean, message: String?, onMigrate: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (legacyCount > 0) {
            Text("$legacyCount catalog(s) need credential migration. Unlock your device with its PIN, then migrate. Existing values are preserved if migration cannot finish.")
            Button(enabled = !busy, onClick = onMigrate) { Text("Migrate saved credentials") }
        }
        message?.let { Text(it) }
    }
}

@Composable
private fun CatalogDeleteConfirmation(busy: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Delete catalog?") },
        text = { Text("Remove this catalog and its saved credential reference. Downloaded books are not deleted.") },
        properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy),
        confirmButton = { Button(enabled = !busy, onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun CatalogManagerItem(
    feed: OpdsEntity,
    isEink: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    ListItem(
        modifier = modifier.tid(Tid.CatalogManager.item(feed.id.toString())),
        headlineContent = { Text(feed.title, fontWeight = FontWeight.Bold) },
        supportingContent = { Text(safeCatalogDisplayUrl(feed.url), style = MaterialTheme.typography.bodySmall) },
        colors = ListItemDefaults.colors(
            containerColor = if (isEink) Color.White else MaterialTheme.colorScheme.surface,
            headlineColor = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
        ),
        trailingContent = {
            Row {
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.tid(Tid.CatalogManager.edit(feed.id.toString()))
                ) {
                    Icon(Icons.Default.Edit, "Edit Catalog", tint = Color.Gray)
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.tid(Tid.CatalogManager.delete(feed.id.toString()))
                ) {
                    Icon(Icons.Default.Delete, "Delete Catalog", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    )
}

// Intentionally not rememberSaveable: neither replacement input nor credential-bearing rows enter saved state.
private class CatalogEditorState(existing: OpdsEntity?) {
    var title by mutableStateOf(existing?.title ?: "")
    var url by mutableStateOf(existing?.url?.let(::safeCatalogDisplayUrl) ?: "")
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var action by mutableStateOf(if (existing == null) CatalogCredentialAction.REMOVE else CatalogCredentialAction.KEEP)
        private set
    var confirmRemove by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    val hasLegacy = existing?.let { it.username != null || it.password != null } == true
    val hasCredentials = hasLegacy || existing?.credentialGeneration != null
    val options = if (existing == null) listOf(CatalogCredentialAction.REMOVE, CatalogCredentialAction.REPLACE)
        else CatalogCredentialAction.entries
    val canSave: Boolean get() = !busy && title.isNotBlank() && url.isNotBlank() &&
        (action != CatalogCredentialAction.REPLACE || (username.isNotBlank() && password.isNotBlank()))
    fun clearPair() { username = ""; password = "" }
    fun select(option: CatalogCredentialAction) {
        action = option
        if (option != CatalogCredentialAction.REPLACE) clearPair()
        errorMessage = null
    }
}

@Composable
fun AddCatalogDialog(
    existingCatalog: OpdsEntity?,
    onDismiss: () -> Unit,
    onSave: suspend (String, String, CatalogCredentialAction, String?, String?) -> Result<String>
) {
    val state = remember { CatalogEditorState(existingCatalog) }
    val scope = rememberCoroutineScope()
    fun submit() {
        if (!state.canSave) return
        state.busy = true
        state.errorMessage = null
        scope.launch {
            try {
                val result = onSave(state.title, state.url, state.action,
                    state.username.takeIf { state.action == CatalogCredentialAction.REPLACE },
                    state.password.takeIf { state.action == CatalogCredentialAction.REPLACE })
                if (result.isSuccess) {
                    state.clearPair()
                    onDismiss()
                } else {
                    state.errorMessage = "Save unavailable. Check HTTPS URL and credentials, configure or unlock your device PIN, or refresh and retry. Saved credentials were not silently removed."
                }
            } finally { state.busy = false }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !state.busy, dismissOnClickOutside = !state.busy),
        title = { Text(if (existingCatalog == null) "Add Catalog" else "Edit Catalog") },
        text = { CatalogEditorFields(state) },
        confirmButton = {
            Button(onClick = {
                if (state.action == CatalogCredentialAction.REMOVE && state.hasCredentials) state.confirmRemove = true else submit()
            }, enabled = state.canSave,
                modifier = Modifier.tid(Tid.CatalogManager.dialogSave)) { Text(if (state.busy) "Saving…" else "Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.busy,
                modifier = Modifier.tid(Tid.CatalogManager.dialogCancel)) { Text("Cancel") }
        }
    )
    if (state.confirmRemove) {
        RemoveCredentialConfirmation(onDismiss = { state.confirmRemove = false }, onConfirm = { state.confirmRemove = false; submit() })
    }
}

@Composable
private fun CatalogEditorFields(state: CatalogEditorState) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = state.title, onValueChange = { state.title = it }, enabled = !state.busy,
            label = { Text("Name") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().tid(Tid.CatalogManager.dialogTitle))
        OutlinedTextField(value = state.url, onValueChange = { state.url = it }, enabled = !state.busy,
            label = { Text("URL (OPDS)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().tid(Tid.CatalogManager.dialogUrl))
        CatalogCredentialOptions(state)
        if (state.action == CatalogCredentialAction.REPLACE) CatalogReplacementFields(state)
        if (state.action == CatalogCredentialAction.KEEP && state.hasCredentials) {
            Text("Changing the server origin requires Replace or Remove.")
        }
        state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun CatalogCredentialOptions(state: CatalogEditorState) {
    Text(when {
        state.hasLegacy -> "Legacy credentials saved; migrate or replace after device authentication."
        state.hasCredentials -> "Encrypted credentials saved; they may be locked or unavailable."
        else -> "No credentials saved."
    })
    state.options.forEach { option ->
        val label = when (option) {
            CatalogCredentialAction.KEEP -> "Keep saved credentials"
            CatalogCredentialAction.REPLACE -> "Replace credentials"
            CatalogCredentialAction.REMOVE -> if (state.hasCredentials) "Remove saved credentials" else "No authentication"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = state.action == option, enabled = !state.busy,
                modifier = Modifier.tid("catalog_credential_${option.name.lowercase()}"), onClick = { state.select(option) })
            Text(label)
        }
    }
}

@Composable
private fun CatalogReplacementFields(state: CatalogEditorState) {
    OutlinedTextField(value = state.username, onValueChange = { state.username = it }, enabled = !state.busy,
        label = { Text("Username") }, singleLine = true,
        modifier = Modifier.fillMaxWidth().tid(Tid.CatalogManager.dialogUser))
    OutlinedTextField(value = state.password, onValueChange = { state.password = it }, enabled = !state.busy,
        label = { Text("Password") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().tid(Tid.CatalogManager.dialogPass))
}

@Composable
private fun RemoveCredentialConfirmation(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Remove credentials?") },
        text = { Text("The catalog will no longer use its saved credentials. You can enter a replacement later.") },
        confirmButton = { Button(onClick = onConfirm) { Text("Remove") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.tid("catalog_remove_confirm_cancel")) { Text("Cancel") } })
}

/** Historical URLs might contain user-info. Never prefill or display those credentials. */
internal fun safeCatalogDisplayUrl(url: String): String {
    val scheme = url.indexOf("://")
    if (scheme < 0) return url
    val start = scheme + 3
    val end = url.indexOfAny(charArrayOf('/', '?', '#'), start).let { if (it < 0) url.length else it }
    val at = url.lastIndexOf('@', end - 1)
    return if (at >= start) url.substring(0, start) + url.substring(at + 1) else url
}
