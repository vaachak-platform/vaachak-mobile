package org.vaachak.reader.leisure.ui.audio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import org.vaachak.reader.core.common.AppCoroutineConfig
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class LibraryUi(val library: AudioLibrary? = null, val failed: Boolean = false, val covers: Map<String, File> = emptyMap())
@Composable
private fun audioLibrary(store: AudioStore, vault: String): LibraryUi {
    val revision by store.revision.collectAsState()
    var data by remember(vault) { mutableStateOf(LibraryUi()) }
    LaunchedEffect(vault, revision) {
        data = try { withContext(AppCoroutineConfig.io) {
            val library = store.load(vault)
            LibraryUi(library, covers = library.books.mapNotNull { book -> store.coverFile(vault, book.id)?.let { book.id to it } }.toMap())
        } }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { LibraryUi(failed = true) }
    }
    return data
}
internal fun importError(error: Exception): String = when (error) {
    is SecurityException -> "Access to the selected file or folder was denied. Select it again."
    is IllegalArgumentException -> "Select supported, readable audio files within the import limits."
    else -> if (error.message == "This audiobook is already in your library") "This audiobook is already in your library."
        else "Import failed. Check the file format and available storage."
}

/** UI-owned operation: cancellation is never reported as a user-visible failure. */
internal fun <T> launchAudioOperation(scope: CoroutineScope, operation: suspend () -> T,
    onSuccess: (T) -> Unit, onError: (Exception) -> Unit, onFinish: () -> Unit = {}) = scope.launch {
    try { onSuccess(operation()) }
    catch (cancel: CancellationException) { throw cancel }
    catch (error: Exception) { onError(error) }
    finally { onFinish() }
}

@Composable
internal fun AudioShelfScreen(vaultId: String) = key(vaultId) {
    val context = LocalContext.current
    val store = remember { AudioStore.get(context) }
    val importer = remember { AudioImporter(context.applicationContext, store) }
    val scope = rememberCoroutineScope()
    val data = audioLibrary(store, vaultId)
    val playback by AudioPlayback.state.collectAsState()
    val playbackError by AudioPlayback.error.collectAsState()
    val now = playback?.takeIf { it.vault == vaultId }
    var selected by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    var order by remember { mutableStateOf("Recent") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var remove by remember { mutableStateOf<AudioBook?>(null) }
    fun runImport(action: suspend () -> AudioBook) {
        if (busy) return
        busy = true; message = null
        launchAudioOperation(scope, action, onSuccess = { selected = it.id; message = "Audiobook imported." },
            onError = { message = importError(it) }, onFinish = { busy = false })
    }
    var restoring by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var restoreChoice by remember { mutableStateOf<AudioBook?>(null) }
    fun runRestore(id: String, action: suspend () -> AudioBook) {
        if (busy || now?.id == id) return
        busy = true; message = null
        launchAudioOperation(scope, action, onSuccess = { message = "Audiobook restored. Listening position retained." },
            onError = { message = "Restore failed. Select the original tracks in their original order and close playback." },
            onFinish = { busy = false })
    }

    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val target = restoring
        restoring = null
        if (uris.isNotEmpty()) {
            if (target == null) runImport { importer.documents(vaultId, uris) }
            else runRestore(target) { importer.restoreDocuments(vaultId, target, uris) }
        }
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val target = restoring
        restoring = null
        if (uri != null) {
            if (target == null) runImport { importer.folder(vaultId, uri) }
            else runRestore(target) { importer.restoreFolder(vaultId, target, uri) }
        }
    }
    fun send(book: AudioBook, action: String, value: Long = 0L) = AudioPlayback.send(context, vaultId, action, book.id, value)
    fun updatePreferences(book: AudioBook, prefs: AudioPreferences?) {
        launchAudioOperation(scope, { withContext(AppCoroutineConfig.io) { store.setTitlePreferences(vaultId, book.id, prefs) } },
            onSuccess = { if (now?.id == book.id) send(book, "settings") },
            onError = { message = "Playback settings could not be saved." })
    }
    val listState = rememberLazyListState()
    fun openBook(book: AudioBook) {
        selected = book.id; send(book, "open")
        scope.launch { listState.scrollToItem(0) }
    }
    val library = data.library
    val opened = library?.find(selected ?: now?.id.orEmpty())
    LazyColumn(Modifier.fillMaxSize().testTag("audio_shelf").padding(horizontal = 16.dp),
        state = listState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            AudioShelfHeader(busy, library != null, data.failed, message,
                playbackError?.takeIf { it.vault == vaultId },
                onFiles = { files.launch(arrayOf("audio/*", "application/octet-stream", "video/mp4")) },
                onFolder = { folder.launch(null) })
        }
        if (library != null) {
            item { TextButton(enabled = !busy, onClick = {
                launchAudioOperation(scope, { withContext(AppCoroutineConfig.io) { store.recoverySummary(vaultId) } },
                    onSuccess = { message = it }, onError = { message = "Storage check unavailable. No files deleted." })
            }) { Text("Check local storage") } }
            if (opened != null) item {
                AudioPlayerPanel(opened, library.defaults, now?.takeIf { it.id == opened.id },
                    onClose = { selected = null }, onCommand = { action, value -> send(opened, action, value) },
                    onSettings = { updatePreferences(opened, it) })
            }
            audioShelfItems(library, data.covers, now?.id, query, filter, order,
                onQuery = { query = it }, onFilter = { filter = it }, onOrder = { order = it },
                onOpen = { if (!busy) openBook(it) }, onRemove = { if (!busy) remove = it },
                onRestore = { if (!busy && now?.id != it.id) restoreChoice = it })
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    restoreChoice?.let { book ->
        AlertDialog(onDismissRequest = { restoreChoice = null }, title = { Text("Restore original audio") },
            text = { Text("Choose the same original tracks or folder. Listening position and title preferences are kept. Playback must be closed.") },
            confirmButton = { TextButton(onClick = {
                restoreChoice = null; restoring = book.id
                files.launch(arrayOf("audio/*", "application/octet-stream", "video/mp4"))
            }) { Text("Select files") } },
            dismissButton = { Column {
                TextButton(onClick = { restoreChoice = null; restoring = book.id; folder.launch(null) }) { Text("Select folder") }
                TextButton(onClick = { restoreChoice = null }) { Text("Cancel") }
            } })
    }

    remove?.let { book ->
        AudioRemoveDialog(onDismiss = { remove = null }, onRemove = {
            remove = null
            launchAudioOperation(scope, { withContext(AppCoroutineConfig.io) { store.remove(vaultId, book.id) } },
                onSuccess = { if (selected == book.id) selected = null },
                onError = { message = "Could not remove audiobook." })
        })
    }
}

@Composable
internal fun AudioShelfHeader(busy: Boolean, loaded: Boolean, failed: Boolean, message: String?,
    playbackError: AudioPlaybackError?, onFiles: () -> Unit, onFolder: () -> Unit) {
    Text("Audiobooks", style = MaterialTheme.typography.headlineSmall)
    Text("Listen offline. Selected files are copied into this profile's private storage.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !busy && loaded, onClick = { onFiles() },
            modifier = Modifier.weight(1f).testTag("audio_import_files")) { Text("Add files") }
        OutlinedButton(enabled = !busy && loaded, onClick = { onFolder() },
            modifier = Modifier.weight(1f).testTag("audio_import_folder")) { Text("Add folder") }
    }
    Text("Select one file or all tracks of one book. Folder tracks are ordered by filename.", style = MaterialTheme.typography.bodySmall)
    if (busy) Text("Importing audio. Keep this screen open until finished.")
    message?.let { Text(it, modifier = Modifier.testTag("audio_message")) }
    playbackError?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
    if (failed) Text("Audiobook index unavailable. Existing files have not been cleared.", color = MaterialTheme.colorScheme.error)
    else if (!loaded) Text("Loading audiobooks…")
}


private fun LazyListScope.audioShelfItems(library: AudioLibrary, covers: Map<String, File>, activeId: String?,
    query: String, filter: String, order: String, onQuery: (String) -> Unit, onFilter: (String) -> Unit,
    onOrder: (String) -> Unit, onOpen: (AudioBook) -> Unit, onRemove: (AudioBook) -> Unit,
    onRestore: (AudioBook) -> Unit) {
    val recent = library.recent()
    if (recent.isNotEmpty()) {
        item { Text("Continue listening", style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("audio_continue_heading")) }
        items(recent.take(8), key = { "continue-${it.id}" }) { book ->
            AudioShelfCard(book, "Continue", covers[book.id], "continue", onOpen = { onOpen(book) }, onRemove = null)
        }
    }
    item {
        OutlinedTextField(query, onQuery, Modifier.fillMaxWidth().testTag("audio_search"),
            label = { Text("Search title or author") }, singleLine = true)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AudioChoice("Filter", filter, listOf("All", "Continue", "Finished"), { it }, "audio_filter", onSelect = onFilter)
            AudioChoice("Sort", order, listOf("Recent", "Title", "Progress"), { it }, "audio_sort", onSelect = onOrder)
        }
    }
    val sorted = audioShelfSelection(library, query, filter, order)
    if (sorted.isEmpty()) item { Text(if (library.books.isEmpty()) "No audiobooks yet." else "No matching audiobooks.") }
    items(sorted, key = { "all-${it.id}" }) { book ->
        val action = when {
            book.continueListening -> "Continue"
            book.finished -> "Replay"
            else -> "Play"
        }
        AudioShelfCard(book, action, covers[book.id], "all", onOpen = { onOpen(book) },
            onRemove = if (activeId == book.id) null else ({ onRemove(book) }),
            onRestore = if (activeId == book.id) null else ({ onRestore(book) }))
    }
}

@Composable
internal fun AudioRemoveDialog(onDismiss: () -> Unit, onRemove: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Remove audiobook?") },
        text = { Text("Remove this profile's imported copy and its listening progress? Original source files are not changed.") },
        confirmButton = { TextButton(onClick = onRemove, modifier = Modifier.testTag("audio_confirm_remove")) { Text("Remove") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun AudioShelfCard(book: AudioBook, action: String, cover: File?, section: String, onOpen: () -> Unit, onRemove: (() -> Unit)?, onRestore: (() -> Unit)? = null) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (cover != null) AsyncImage(model = cover, contentDescription = null, modifier = Modifier.size(72.dp))
            Text(book.title, style = MaterialTheme.typography.titleMedium)
            if (book.author.isNotBlank()) Text(book.author)
            Text(if (book.finished) "Finished · ${audioClock(book.duration)}" else
                "${audioClock(book.position)} / ${audioClock(book.duration)} · ${(book.fraction * 100).toInt()}%")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpen, modifier = Modifier.testTag("audio_${section}_${action.lowercase()}_${book.id}")) { Text(action) }
                if (onRemove != null) TextButton(onClick = onRemove, modifier = Modifier.testTag("audio_remove_${book.id}")) { Text("Remove") }
            }
            if (onRestore != null) TextButton(onClick = onRestore, modifier = Modifier.testTag("audio_restore_${book.id}")) { Text("Restore original files") }
        }
    }
}

@Composable
internal fun AudioPlayerPanel(book: AudioBook, defaults: AudioPreferences, now: AudioNowPlaying?,
    onClose: () -> Unit, onCommand: (String, Long) -> Unit, onSettings: (AudioPreferences?) -> Unit) {
    val timeline = remember(book.tracks) { AudioTimeline(book.tracks) }
    val pos = now?.position ?: book.position
    val prefs = book.titlePreferences ?: defaults
    var dragging by remember(book.id) { mutableStateOf<Float?>(null) }
    var advanced by remember(book.id) { mutableStateOf(false) }
    var chapters by remember(book.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().testTag("audio_player")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleLarge)
            if (book.author.isNotBlank()) Text(book.author)
            Text(timeline.chapter(pos).title, style = MaterialTheme.typography.titleMedium)
            Slider(value = dragging ?: pos.toFloat(), onValueChange = { dragging = it },
                valueRange = 0f..timeline.duration.toFloat(), enabled = now != null,
                onValueChangeFinished = { dragging?.let { onCommand("seek", it.toLong()) }; dragging = null },
                modifier = Modifier.testTag("audio_seek"))
            Text("${audioClock(dragging?.toLong() ?: pos)} / ${audioClock(timeline.duration)}")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { onCommand("back", 0L) }, enabled = now != null, modifier = Modifier.weight(1f)) { Text("−${prefs.back}s") }
                Button(onClick = { onCommand(if (now == null) "open" else "toggle", 0L) }, modifier = Modifier.weight(1f).testTag("audio_play_pause")) {
                    Text(if (now?.playing == true) "Pause" else "Play")
                }
                OutlinedButton(onClick = { onCommand("forward", 0L) }, enabled = now != null, modifier = Modifier.weight(1f)) { Text("+${prefs.forward}s") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onCommand("previous", 0L) }, enabled = now != null, modifier = Modifier.weight(1f)) { Text("Previous chapter") }
                OutlinedButton(onClick = { onCommand("next", 0L) }, enabled = now != null, modifier = Modifier.weight(1f)) { Text("Next chapter") }
            }
            AudioChoice("Sleep timer", now?.sleep ?: "Off", listOf("Off", "15 min", "30 min", "60 min", "End of chapter"), { it }, "audio_sleep", now != null) {
                if (it == "End of chapter") onCommand("sleepChapter", 0L) else onCommand("sleep", it.substringBefore(' ').toLongOrNull() ?: 0L)
            }
            TextButton(onClick = { chapters = !chapters }, modifier = Modifier.testTag("audio_chapters")) { Text(if (chapters) "Hide chapters" else "Chapters (${timeline.chapters.size})") }
            if (chapters) {
                var selectedChapter by remember(book.id) { mutableIntStateOf(0) }
                AudioChoice("Chapter", selectedChapter, timeline.chapters.indices.toList(),
                    { "${it + 1}. ${timeline.chapters[it].title} · ${audioClock(timeline.chapters[it].start)}" }, "audio_chapter_select", now != null) {
                    selectedChapter = it; onCommand("seek", timeline.chapters[it].start)
                }
            }
            TextButton(onClick = { advanced = !advanced }, modifier = Modifier.testTag("audio_title_settings")) { Text("Playback settings · ${prefs.speed}×") }
            if (advanced) {
                AudioPreferenceFields(prefs, onSettings = { onSettings(it) }, prefix = "audio_title")
                TextButton(onClick = { onSettings(null) }, modifier = Modifier.testTag("audio_reset_title")) { Text("Reset to default") }
                Text(if (book.titlePreferences == null) "Using global defaults" else "This title has its own settings")
            }
            if (now != null) TextButton(onClick = { onCommand("stop", 0L); onClose() }) { Text("Stop playback") }
            else TextButton(onClick = onClose) { Text("Close player") }
        }
    }
}

@Composable
internal fun AudioDefaultsPanel(vaultId: String) = key(vaultId) {
    val context = LocalContext.current
    val store = remember { AudioStore.get(context) }
    val scope = rememberCoroutineScope()
    val data = audioLibrary(store, vaultId)
    var error by remember { mutableStateOf(false) }
    val now by AudioPlayback.state.collectAsState()
    Text("Audiobook defaults are independent of text-to-speech. Title overrides are kept until reset.")
    if (data.failed) Text("Audiobook settings unavailable.", color = MaterialTheme.colorScheme.error)
    data.library?.let { library ->
        AudioPreferenceFields(library.defaults, prefix = "audio_default", onSettings = { prefs ->
            scope.launch {
                try {
                    withContext(AppCoroutineConfig.io) { store.setDefaults(vaultId, prefs) }; error = false
                    now?.takeIf { it.vault == vaultId }?.let { AudioPlayback.send(context, vaultId, "settings", it.id) }
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { error = true }
            }
        })
    }
    if (error) Text("Settings could not be saved.", color = MaterialTheme.colorScheme.error)
}
@Composable
private fun AudioPreferenceFields(prefs: AudioPreferences, prefix: String, onSettings: (AudioPreferences) -> Unit) {
    AudioChoice("Speed", prefs.speed, AUDIO_SPEEDS, { "${it}×" }, "${prefix}_speed") { onSettings(prefs.copy(speed = it)) }
    AudioChoice("Skip back", prefs.back, AUDIO_BACK, { "${it}s" }, "${prefix}_back") { onSettings(prefs.copy(back = it)) }
    AudioChoice("Skip forward", prefs.forward, AUDIO_FORWARD, { "${it}s" }, "${prefix}_forward") { onSettings(prefs.copy(forward = it)) }
}
@Composable
private fun <T> AudioChoice(label: String, value: T, options: List<T>, format: (T) -> String, tag: String,
    enabled: Boolean = true, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.testTag(tag)) { Text("$label: ${format(value)}") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(format(option)) }, onClick = { expanded = false; onSelect(option) }) }
        }
    }
}
