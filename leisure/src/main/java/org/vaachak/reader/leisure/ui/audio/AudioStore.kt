package org.vaachak.reader.leisure.ui.audio

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Separate, no-backup audio index. Never touches the Room v4 or TTS data. */
internal class AudioStore(private val root: File) {
    companion object {
        @Volatile private var instance: AudioStore? = null
        fun get(context: Context): AudioStore = instance ?: synchronized(this) {
            instance ?: AudioStore(File(context.applicationContext.noBackupFilesDir, "audiobooks_v1"))
                .also { instance = it }
        }
    }
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()
    private fun directory(vault: String): File {
        require(vault.isNotBlank() && vault.length <= 512)
        return File(root, audioSha256(vault.toByteArray(Charsets.UTF_8)))
    }
    private fun index(vault: String) = AtomicFile(File(directory(vault), "library.json"))
    fun staging(vault: String): File = File(directory(vault), "pending-${UUID.randomUUID()}").also {
        check(it.mkdirs()) { "Cannot create audio import directory" }
    }
    fun mediaFile(vault: String, id: String, file: String): File {
        require(UUID.fromString(id).toString() == id)
        require(file.matches(Regex("[0-9]{3}\\.[a-z0-9]{1,5}")))
        val base = File(directory(vault), id).canonicalFile
        val path = File(base, file).canonicalFile
        require(path.parentFile == base)
        return path
    }
    fun coverFile(vault: String, id: String): File? = File(mediaFile(vault, id, "000.mp3").parentFile, "cover.jpg")
        .takeIf { it.isFile && it.length() in 1L..(2L * 1024 * 1024) }
    @Synchronized fun load(vault: String): AudioLibrary {
        val atomic = index(vault)
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return AudioLibrary()
        val json = atomic.openRead().use { input ->
            val bytes = input.readBytesBounded(MAX_INDEX_BYTES)
            JSONObject(bytes.toString(Charsets.UTF_8))
        }
        return AudioJson.decode(json)
    }
    @Synchronized fun publish(vault: String, stage: File, book: AudioBook): AudioBook {
        val current = load(vault)
        check(current.books.size < MAX_AUDIO_BOOKS) { "Audiobook shelf limit reached" }
        check(current.books.none { old -> old.tracks.map { it.hash } == book.tracks.map { it.hash } }) {
            "This audiobook is already in your library"
        }
        require(stage.canonicalFile.parentFile == directory(vault).canonicalFile && stage.name.startsWith("pending-"))
        val target = mediaFile(vault, book.id, book.tracks.first().file).parentFile!!
        check(!target.exists() && stage.renameTo(target)) { "Cannot publish imported audio" }
        // If index publication fails, retain the unreferenced directory, not a dangling index.
        save(vault, current.copy(books = current.books + book))
        return book
    }
    @Synchronized fun setDefaults(vault: String, settings: AudioPreferences) {
        save(vault, load(vault).copy(defaults = settings))
    }
    @Synchronized fun setTitlePreferences(vault: String, id: String, settings: AudioPreferences?) {
        update(vault, id) { it.copy(titlePreferences = settings) }
    }
    @Synchronized fun progress(vault: String, id: String, position: Long, opened: Long, finished: Boolean) {
        update(vault, id) { it.progress(position, opened, finished) }
    }
    @Synchronized fun remove(vault: String, id: String) {
        val current = load(vault)
        val book = current.find(id) ?: return
        save(vault, current.copy(books = current.books.filterNot { it.id == id }))
        mediaFile(vault, id, book.tracks.first().file).parentFile?.deleteRecursively()
    }
    private fun update(vault: String, id: String, transform: (AudioBook) -> AudioBook) {
        val current = load(vault)
        val before = current.find(id) ?: return // a removed title is never recreated by a queued progress write
        val after = transform(before)
        if (after != before) save(vault, current.copy(books = current.books.map { if (it.id == id) after else it }))
    }
    private fun save(vault: String, library: AudioLibrary) {
        val data = AudioJson.encode(library).toString().toByteArray(Charsets.UTF_8)
        require(data.size <= MAX_INDEX_BYTES)
        val atomic = index(vault)
        check(atomic.baseFile.parentFile!!.isDirectory || atomic.baseFile.parentFile!!.mkdirs())
        val stream = atomic.startWrite()
        try {
            stream.write(data)
            atomic.finishWrite(stream)
        } catch (failure: Exception) {
            atomic.failWrite(stream)
            throw failure
        }
        check(load(vault) == library) { "Audio index write verification failed" }
        changes.value += 1
    }
}
private const val MAX_INDEX_BYTES = 16 * 1024 * 1024
private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = read(buffer)
        if (n == -1) break
        check(out.size() <= limit - n) { "Audio index exceeds limit" }
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
internal fun audioSha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

internal object AudioJson {
    private fun prefs(value: AudioPreferences) = JSONObject().put("speed", value.speed.toDouble())
        .put("back", value.back).put("forward", value.forward)
    private fun preferences(value: JSONObject) = AudioPreferences(value.getDouble("speed").toFloat(), value.getInt("back"), value.getInt("forward"))
    fun encode(library: AudioLibrary): JSONObject = JSONObject().put("schema", 1).put("defaults", prefs(library.defaults))
        .put("books", JSONArray(library.books.map(::encodeBook)))
    private fun encodeBook(book: AudioBook): JSONObject = JSONObject().put("id", book.id).put("title", book.title)
        .put("author", book.author).put("position", book.position).put("opened", book.opened).put("finished", book.finished)
        .put("preferences", book.titlePreferences?.let(::prefs) ?: JSONObject.NULL)
        .put("tracks", JSONArray(book.tracks.map(::encodeTrack)))
    private fun encodeTrack(track: AudioTrack): JSONObject = JSONObject().put("name", track.name).put("file", track.file)
        .put("hash", track.hash).put("duration", track.duration)
        .put("chapters", JSONArray(track.chapters.map { JSONObject().put("title", it.title).put("start", it.start) }))
    fun decode(json: JSONObject): AudioLibrary {
        require(json.getInt("schema") == 1)
        val books = json.getJSONArray("books")
        require(books.length() <= MAX_AUDIO_BOOKS)
        val decoded = List(books.length()) { decodeBook(books.getJSONObject(it)) }
        require(decoded.map { it.id }.distinct().size == decoded.size)
        return AudioLibrary(preferences(json.getJSONObject("defaults")), decoded)
    }
    private fun decodeBook(json: JSONObject): AudioBook {
        val tracks = json.getJSONArray("tracks")
        require(tracks.length() in 1..MAX_AUDIO_TRACKS)
        val decoded = List(tracks.length()) { decodeTrack(tracks.getJSONObject(it)) }
        val timeline = AudioTimeline(decoded)
        val id = json.getString("id")
        require(UUID.fromString(id).toString() == id)
        require(decoded.map { it.file }.distinct().size == decoded.size)
        return AudioBook(id, json.getString("title").take(256), json.getString("author").take(256), decoded,
            json.getLong("position").coerceIn(0L, timeline.duration), json.getLong("opened").coerceAtLeast(0L),
            json.getBoolean("finished"), json.optJSONObject("preferences")?.let(::preferences))
    }
    private fun decodeTrack(json: JSONObject): AudioTrack {
        val file = json.getString("file")
        val hash = json.getString("hash")
        require(file.matches(Regex("[0-9]{3}\\.[a-z0-9]{1,5}")) && hash.matches(Regex("[0-9a-f]{64}")))
        val chapters = json.getJSONArray("chapters")
        require(chapters.length() <= 255)
        return AudioTrack(json.getString("name").take(256), file, hash, json.getLong("duration"), List(chapters.length()) {
            val item = chapters.getJSONObject(it)
            AudioChapter(item.getString("title").take(256), item.getLong("start"))
        })
    }
}
