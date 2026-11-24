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
import org.vaachak.reader.leisure.ui.testability.Tid
import org.vaachak.reader.leisure.ui.testability.TidScreen
import org.vaachak.reader.leisure.ui.testability.tid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogManagerScreen(
    onBack: () -> Unit,
    viewModel: CatalogViewModel = hiltViewModel()
) {
    val catalogs by viewModel.catalogs.collectAsStateWithLifecycle()
    val isEink by viewModel.isEinkEnabled.collectAsStateWithLifecycle()

    // Dialog State
    var showDialog by remember { mutableStateOf(false) }
    var editingCatalog by remember { mutableStateOf<OpdsEntity?>(null) }
    var deletingCatalog by remember { mutableStateOf<OpdsEntity?>(null) }
    var operationBusy by remember { mutableStateOf(false) }
    var operationMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val legacyCount = catalogs.count { it.username != null || it.password != null }

    val containerColor = if (isEink) Color.White else MaterialTheme.colorScheme.background
    val contentColor = if (isEink) Color.Black else MaterialTheme.colorScheme.onBackground

    TidScreen(Tid.Screen.catalogManager) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage Catalogs") },
                navigationIcon = {
                    IconButton(
                        onClick = { if (!operationBusy) onBack() },
                        enabled = !operationBusy,
                        modifier = Modifier.tid(Tid.CatalogManager.back)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = containerColor,
                    titleContentColor = contentColor,
                    navigationIconContentColor = contentColor
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (operationBusy) return@FloatingActionButton
                    editingCatalog = null
                    showDialog = true
                },
                containerColor = if(isEink) Color.Black else MaterialTheme.colorScheme.primary,
                contentColor = if(isEink) Color.White else MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.tid(Tid.CatalogManager.add)
            ) {
                Icon(Icons.Default.Add, "Add Catalog")
            }
        },
        containerColor = containerColor,
        contentColor = contentColor
    ) { padding ->
        if (catalogs.isEmpty()) {
            Box(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No catalogs added yet.", color = Color.Gray)
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                // Fix: explicit 'items' call resolving to OpdsEntity
                item {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (legacyCount > 0) {
                            Text("$legacyCount catalog(s) need credential migration. Unlock your device with its PIN, then migrate. Existing values are preserved if migration cannot finish.")
                            Button(enabled = !operationBusy, onClick = {
                                operationBusy = true
                                operationMessage = null
                                scope.launch {
                                    try {
                                        val result = viewModel.migrateLegacyCredentials()
                                        operationMessage = result.fold(
                                            onSuccess = { "Migrated ${it.migrated}; ${it.remaining} catalog(s) still need migration." },
                                            onFailure = { "Migration unavailable. Unlock your device and retry." }
                                        )
                                    } finally { operationBusy = false }
                                }
                            }) { Text("Migrate saved credentials") }
                        }
                        operationMessage?.let { Text(it) }
                    }
                }
                items(items = catalogs, key = { it.id }) { feed ->
                    CatalogManagerItem(
                        feed = feed,
                        isEink = isEink,
                        onEdit = {
                            if (operationBusy) return@CatalogManagerItem
                            editingCatalog = feed
                            showDialog = true
                        },
                        onDelete = { if (!operationBusy) deletingCatalog = feed },
                        modifier = if (catalogs.first() == feed) Modifier.tid(Tid.CatalogManager.first) else Modifier
                    )
                    HorizontalDivider(thickness = 0.5.dp, color = Color.LightGray)
                }
            }
        }

        deletingCatalog?.let { feed ->
            AlertDialog(
                onDismissRequest = { if (!operationBusy) deletingCatalog = null },
                title = { Text("Delete catalog?") },
                text = { Text("Remove this catalog and its saved credential reference. Downloaded books are not deleted.") },
                properties = DialogProperties(dismissOnBackPress = !operationBusy, dismissOnClickOutside = !operationBusy),
                confirmButton = {
                    Button(enabled = !operationBusy, onClick = {
                        operationBusy = true
                        scope.launch {
                            try {
                                val result = viewModel.deleteCatalog(feed)
                                operationMessage = if (result.isSuccess) "Catalog deleted." else "Delete unavailable. Refresh and retry."
                                deletingCatalog = null
                            } finally { operationBusy = false }
                        }
                    }) { Text("Delete") }
                },
                dismissButton = { TextButton(enabled = !operationBusy, onClick = { deletingCatalog = null }) { Text("Cancel") } }
            )
        }
        if (showDialog) {
            AddCatalogDialog(
                existingCatalog = editingCatalog,
                onDismiss = { showDialog = false; editingCatalog = null },
                onSave = { title, url, action, user, pass ->
                    viewModel.saveCatalog(editingCatalog, title, url, action, user, pass)
                }
            )
        }
    }
    }
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

@Composable
fun AddCatalogDialog(
    existingCatalog: OpdsEntity?,
    onDismiss: () -> Unit,
    onSave: suspend (String, String, CatalogCredentialAction, String?, String?) -> Result<String>
) {
    var title by remember { mutableStateOf(existingCatalog?.title ?: "") }
    var url by remember { mutableStateOf(existingCatalog?.url?.let(::safeCatalogDisplayUrl) ?: "") }
    // Never prefill or rememberSaveable a stored username/password.
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var action by remember { mutableStateOf(if (existingCatalog == null) CatalogCredentialAction.REMOVE else CatalogCredentialAction.KEEP) }
    var confirmRemove by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val hasLegacy = existingCatalog?.let { it.username != null || it.password != null } == true
    val hasCredentials = hasLegacy || existingCatalog?.credentialGeneration != null
    val options = if (existingCatalog == null) listOf(CatalogCredentialAction.REMOVE, CatalogCredentialAction.REPLACE)
        else CatalogCredentialAction.entries

    fun submit() {
        if (busy) return
        busy = true
        errorMessage = null
        scope.launch {
            try {
                val result = onSave(title, url, action,
                    username.takeIf { action == CatalogCredentialAction.REPLACE },
                    password.takeIf { action == CatalogCredentialAction.REPLACE })
                if (result.isSuccess) {
                    username = ""
                    password = ""
                    onDismiss()
                } else {
                    errorMessage = "Save unavailable. Check HTTPS URL and credentials, configure or unlock your device PIN, or refresh and retry. Saved credentials were not silently removed."
                }
            } finally { busy = false }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy),
        title = { Text(if (existingCatalog == null) "Add Catalog" else "Edit Catalog") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, enabled = !busy,
                    label = { Text("Name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().tid(Tid.CatalogManager.dialogTitle))
                OutlinedTextField(value = url, onValueChange = { url = it }, enabled = !busy,
                    label = { Text("URL (OPDS)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().tid(Tid.CatalogManager.dialogUrl))
                Text(when {
                    hasLegacy -> "Legacy credentials saved; migrate or replace after device authentication."
                    hasCredentials -> "Encrypted credentials saved; they may be locked or unavailable."
                    else -> "No credentials saved."
                })
                options.forEach { option ->
                    val label = when (option) {
                        CatalogCredentialAction.KEEP -> "Keep saved credentials"
                        CatalogCredentialAction.REPLACE -> "Replace credentials"
                        CatalogCredentialAction.REMOVE -> if (hasCredentials) "Remove saved credentials" else "No authentication"
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = action == option, enabled = !busy, modifier = Modifier.tid("catalog_credential_${option.name.lowercase()}"), onClick = {
                            action = option
                            if (option != CatalogCredentialAction.REPLACE) { username = ""; password = "" }
                            errorMessage = null
                        })
                        Text(label)
                    }
                }
                if (action == CatalogCredentialAction.REPLACE) {
                    OutlinedTextField(value = username, onValueChange = { username = it }, enabled = !busy,
                        label = { Text("Username") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().tid(Tid.CatalogManager.dialogUser))
                    OutlinedTextField(value = password, onValueChange = { password = it }, enabled = !busy,
                        label = { Text("Password") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().tid(Tid.CatalogManager.dialogPass))
                }
                if (action == CatalogCredentialAction.KEEP && hasCredentials) Text("Changing the server origin requires Replace or Remove.")
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (action == CatalogCredentialAction.REMOVE && hasCredentials) confirmRemove = true else submit()
            }, enabled = !busy && title.isNotBlank() && url.isNotBlank() &&
                (action != CatalogCredentialAction.REPLACE || (username.isNotBlank() && password.isNotBlank())),
                modifier = Modifier.tid(Tid.CatalogManager.dialogSave)) { Text(if (busy) "Saving…" else "Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy,
                modifier = Modifier.tid(Tid.CatalogManager.dialogCancel)) { Text("Cancel") }
        }
    )
    if (confirmRemove) {
        AlertDialog(onDismissRequest = { confirmRemove = false }, title = { Text("Remove credentials?") },
            text = { Text("The catalog will no longer use its saved credentials. You can enter a replacement later.") },
            confirmButton = { Button(onClick = { confirmRemove = false; submit() }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } })
    }
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
