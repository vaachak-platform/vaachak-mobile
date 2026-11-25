package org.vaachak.reader.leisure.ui.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

internal data class AudioInput(val name: String, val open: () -> InputStream)
internal class AudioImporter(private val context: Context, private val store: AudioStore = AudioStore.get(context)) {
    suspend fun documents(vault: String, uris: List<Uri>): AudioBook = withContext(Dispatchers.IO) {
        require(uris.size in 1..MAX_AUDIO_TRACKS && uris.distinct().size == uris.size)
        val sources = uris.map { uri ->
            require(uri.scheme == "content")
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: error("File name unavailable")
            source(uri, name)
        }
        importSources(vault, sources, null)
    }
    suspend fun folder(vault: String, uri: Uri): AudioBook = withContext(Dispatchers.IO) {
        require(uri.scheme == "content")
        val tree = DocumentFile.fromTreeUri(context, uri) ?: error("Folder unavailable")
        val files = mutableListOf<AudioInput>()
        var visited = 0
        val seen = mutableSetOf<Uri>()
        suspend fun walk(node: DocumentFile, depth: Int, prefix: String) {
            currentCoroutineContext().ensureActive()
            check(depth <= 8 && ++visited <= 1024 && seen.add(node.uri)) { "Folder exceeds import limits" }
            val name = node.name ?: "Audio"
            val path = if (prefix.isEmpty()) name else "$prefix/$name"
            if (node.isDirectory) {
                node.listFiles().forEach { walk(it, depth + 1, path) }
            } else if (node.isFile && extension(name) in AUDIO_EXTENSIONS) {
                check(files.size < MAX_AUDIO_TRACKS) { "Too many audio tracks" }
                files += source(node.uri, path)
            }
        }
        walk(tree, 0, "")
        importSources(vault, files, tree.name)
    }
    private fun source(uri: Uri, name: String): AudioInput = AudioInput(name.take(1024)) {
        require(uri.scheme == "content")
        context.contentResolver.openInputStream(uri) ?: error("Cannot open document")
    }
    suspend fun importSources(vault: String, inputs: List<AudioInput>, title: String?): AudioBook = withContext(Dispatchers.IO) {
        require(inputs.size in 1..MAX_AUDIO_TRACKS)
        require(inputs.all { extension(it.name) in AUDIO_EXTENSIONS }) { "Unsupported audio file" }
        val stage = store.staging(vault)
        try {
            val ordered = inputs.sortedWith { a, b -> audioNaturalOrder.compare(a.name, b.name) }
            var remaining = MAX_BOOK_BYTES
            val metadata = mutableListOf<AudioMetadata>()
            val tracks = ordered.mapIndexed { index, input ->
                val filename = String.format(Locale.ROOT, "%03d.%s", index, extension(input.name))
                val local = File(stage, filename)
                val copied = copy(input, local, minOf(remaining, MAX_FILE_BYTES))
                remaining -= copied.first
                val info = readMetadata(local, if (index == 0) File(stage, "cover.jpg") else null)
                metadata += info
                val label = info.title ?: input.name.substringAfterLast('/').substringBeforeLast('.').take(256)
                AudioTrack(label, filename, copied.second, info.duration, AudioChapters.read(local, info.duration))
            }
            AudioTimeline(tracks)
            val first = metadata.first()
            val label = (title ?: first.album ?: if (tracks.size == 1) tracks.first().name else "${tracks.first().name} (${tracks.size} tracks)")
                .ifBlank { "Audiobook" }.take(256)
            currentCoroutineContext().ensureActive()
            store.publish(vault, stage, AudioBook(UUID.randomUUID().toString(), label, first.artist.orEmpty(), tracks))
        } finally {
            if (stage.exists()) stage.deleteRecursively()
        }
    }
    private suspend fun copy(source: AudioInput, file: File, limit: Long): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        source.open().use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count == -1) break
                    check(count > 0 && total <= limit - count) { "Audio file exceeds import size limit" }
                    total += count
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
        }
        require(total > 0) { "Empty audio file" }
        return total to digest.digest().joinToString("") { "%02x".format(it) }
    }
    private data class AudioMetadata(val duration: Long, val title: String?, val album: String?, val artist: String?)
    private fun readMetadata(file: File, cover: File?): AudioMetadata {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            require(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes") { "No audio track" }
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            require(duration in 1..MAX_AUDIO_DURATION) { "Audio duration unavailable or too long" }
            if (cover != null && file.length() <= MAX_ART_SOURCE_BYTES) saveCover(retriever, cover)
            fun get(key: Int) = retriever.extractMetadata(key)?.trim()?.take(256)?.takeIf { it.isNotBlank() }
            return AudioMetadata(duration, get(MediaMetadataRetriever.METADATA_KEY_TITLE),
                get(MediaMetadataRetriever.METADATA_KEY_ALBUM), get(MediaMetadataRetriever.METADATA_KEY_ARTIST))
        } finally { retriever.release() }
    }
    /** Limit the source before asking the platform for an embedded picture. Art is optional. */
    private fun saveCover(retriever: MediaMetadataRetriever, target: File) {
        try {
            val bytes = retriever.embeddedPicture ?: return
            if (bytes.size !in 1..MAX_ART_BYTES) return
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096) return
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return
            try {
                target.outputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)); output.fd.sync() }
            } finally { bitmap.recycle() }
        } catch (_: Exception) { target.delete() }
    }
    private fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    private companion object {
        const val MAX_ART_SOURCE_BYTES = 32L * 1024 * 1024
        const val MAX_ART_BYTES = 2 * 1024 * 1024
        const val MAX_FILE_BYTES = 8L * 1024 * 1024 * 1024
        const val MAX_BOOK_BYTES = 16L * 1024 * 1024 * 1024
    }
}
