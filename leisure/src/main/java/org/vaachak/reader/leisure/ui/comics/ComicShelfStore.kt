package org.vaachak.reader.leisure.ui.comics

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Vault-isolated local comic shelf. Does not alter the Books repository or database.
 * Legacy CBZ records from the prior P0-11 test build remain readable.
 */
class ComicShelfStore(private val context: Context) {
    private companion object {
        val mutationLock = Any()
        val readerWrites = java.util.concurrent.Executors.newSingleThreadExecutor()
    }
    private val resolver = context.applicationContext.contentResolver
    private val preferences = context.applicationContext.getSharedPreferences(
        "vaachak_comics_v1", Context.MODE_PRIVATE
    )

    data class Comic(
        val id: String,
        val title: String,
        val uri: String,
        val pageCount: Int,
        val lastPage: Int,
        val format: String = "CBZ",
        val sha256: String? = null,
        val lastOpenedAt: Long = 0L,
        val viewOverride: ComicViewOverride? = null,
        internal val ownerKey: String? = null
    )

    fun list(vaultId: String): List<Comic> {
        val serialized = preferences.getString(key(vaultId), null) ?: return emptyList()
        val records = try {
            JSONArray(serialized)
        } catch (e: Exception) {
            throw IOException("Comic shelf metadata is unreadable", e)
        }
        return (0 until records.length()).map { index ->
            val item = records.getJSONObject(index)
            Comic(
                id = item.getString("id"),
                title = item.getString("title"),
                uri = item.getString("uri"),
                pageCount = item.getInt("pageCount"),
                lastPage = item.getInt("lastPage"),
                format = item.optString("format", "CBZ").ifBlank { "CBZ" },
                sha256 = item.optString("sha256").takeIf { it.isNotBlank() },
                lastOpenedAt = item.optLong("lastOpenedAt", 0L),
                viewOverride = ComicViewOverride.decode(item.optJSONObject("viewOverride")),
                ownerKey = digest(vaultId)
            )
        }
    }

    /** Support both formats, rejecting duplicate URI, content, and same-title imports per vault. */
    fun importComic(vaultId: String, uri: Uri): Comic = synchronized(mutationLock) {
        if (uri.scheme != "content") throw IOException("Choose a comic through the document picker")
        val (displayName, size) = documentInfo(uri)
        if (!ComicDocumentTypes.isComicName(displayName)) {
            throw IOException("Choose a .cbz or .cbr comic archive")
        }
        val header = resolver.openInputStream(uri)?.use { input ->
            val first = ByteArray(8)
            var count = 0
            while (count < first.size) {
                val n = input.read(first, count, first.size - count)
                if (n < 0) break
                if (n == 0) continue
                count += n
            }
            first.copyOf(count)
        } ?: throw IOException("Could not open selected comic")
        val format = ComicDocumentTypes.signatureFormat(header)
            ?: throw IOException("File is not a supported ZIP or RAR comic archive")
        if (size != null && (size <= 0L || size > ComicFileFingerprint.MAX_ARCHIVE_BYTES)) {
            throw IOException("Comic archive exceeds the 2 GiB size limit")
        }
        val title = displayName.substringBeforeLast('.').trim().take(180).ifBlank { "Untitled comic" }
        val existing = list(vaultId)
        checkDuplicate(existing, uri.toString(), null, title)

        return@synchronized if (format == "CBR") {
            importCbr(vaultId, uri, title, existing)
        } else {
            importCbzInternal(vaultId, uri, title, existing)
        }
    }

    /** UI events enqueue in selection order, independently of the reader composition lifetime. */
    internal fun queueViewOverride(vaultId: String, comicId: String, value: ComicViewOverride?,
        onFailure: () -> Unit = {}): java.util.concurrent.Future<*> =
        queueReaderWrite(onFailure) { setViewOverride(vaultId, comicId, value) }

    internal fun queuePage(vaultId: String, comicId: String, page: Int,
        onFailure: () -> Unit = {}): java.util.concurrent.Future<*> =
        queueReaderWrite(onFailure) { setPage(vaultId, comicId, page) }

    private fun queueReaderWrite(onFailure: () -> Unit, action: () -> Unit): java.util.concurrent.Future<*> =
        readerWrites.submit {
            try { action() }
            catch (error: Exception) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { onFailure() }
                throw error
            }
        }

    fun setViewOverride(vaultId: String, comicId: String, value: ComicViewOverride?) = synchronized(mutationLock) {
        val current = list(vaultId)
        if (current.any { it.id == comicId }) {
            save(vaultId, current.map { if (it.id == comicId) it.copy(viewOverride = value) else it })
        }
    }

    /** Select the original archive. The restored private copy is verified before shelf publication. */
    fun relinkArchive(vaultId: String, comicId: String, uri: Uri): Comic = synchronized(mutationLock) {
        require(uri.scheme == "content")
        val record = list(vaultId).firstOrNull { it.id == comicId } ?: throw IOException("Comic no longer exists")
        require(record.format in setOf("CBZ", "CBR"))
        withStagedComic(vaultId) { staged ->
            val hash = resolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { output -> ComicFileFingerprint.calculate(input, output).also { output.fd.sync() } }
            } ?: throw IOException("Cannot open selected archive")
            restoreStaged(vaultId, record, staged, hash)
        }
    }

    fun importImageFolder(vaultId: String, uri: Uri, restoreId: String? = null): Comic = synchronized(mutationLock) {
        require(uri.scheme == "content")
        val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri) ?: throw IOException("Folder unavailable")
        val title = tree.name.orEmpty().trim().take(180).ifBlank { "Image folder" }
        withStagedComic(vaultId) { staged ->
            ComicFolderArchive.write(tree, resolver, staged)
            val hash = staged.inputStream().use { ComicFileFingerprint.calculate(it) }
            val existing = list(vaultId)
            if (restoreId != null) {
                val old = existing.firstOrNull { it.id == restoreId && it.format == "FOLDER" }
                    ?: throw IOException("Comic folder no longer exists")
                restoreStaged(vaultId, old, staged, hash)
            } else {
                checkDuplicate(existing, uri.toString(), hash, title)
                val pages = staged.inputStream().use { SafeCbzArchive.inspect(it).pages }
                val target = publishStaged(vaultId, staged, hash, "cbz", replace = false)
                val record = Comic(digest(uri.toString()), title, Uri.fromFile(target).toString(), pages.size, 0, "FOLDER", hash, ownerKey = digest(vaultId))
                save(vaultId, existing + record)
                record
            }
        }
    }

    private fun restoreStaged(vaultId: String, record: Comic, staged: File, hash: String): Comic {
        check(record.sha256 != null && record.sha256 == hash) { "Original comic fingerprint unavailable or mismatched" }
        val pages = if (record.format == "CBR") SafeCbrArchive.inspect(staged).pages
            else staged.inputStream().use { SafeCbzArchive.inspect(it).pages }
        check(pages.size == record.pageCount) { "Original comic page count changed" }
        val current = list(vaultId)
        check(current.none { it.id != record.id && it.sha256 == hash }) { "Comic belongs to another shelf entry" }
        val target = publishStaged(vaultId, staged, hash, if (record.format == "CBR") "cbr" else "cbz", replace = true)
        val updated = record.copy(uri = Uri.fromFile(target).toString())
        save(vaultId, current.map { if (it.id == record.id) updated else it })
        return updated
    }

    private fun <T> withStagedComic(vaultId: String, action: (File) -> T): T {
        val dir = ownedDirectory(vaultId)
        check(dir.isDirectory || dir.mkdirs())
        val staged = File.createTempFile("recovery-", ".part", dir)
        try { return action(staged) } finally {
            if (staged.exists() && !staged.delete()) android.util.Log.w("ComicShelfStore", "Private staging cleanup incomplete")
        }
    }

    private fun publishStaged(vaultId: String, staged: File, hash: String, extension: String, replace: Boolean): File {
        val target = File(ownedDirectory(vaultId), "$hash.$extension")
        check(target.canonicalFile.parentFile == ownedDirectory(vaultId).canonicalFile)
        if (!replace) check(!target.exists()) { "Comic already stored" }
        if (replace) java.nio.file.Files.move(staged.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        else check(staged.renameTo(target)) { "Cannot publish comic" }
        return target
    }

    /** Compatibility for the original P0-11 API; this is now format-aware. */
    fun importCbz(vaultId: String, uri: Uri): Comic = importComic(vaultId, uri)

    private fun importCbzInternal(vaultId: String, uri: Uri, title: String, existing: List<Comic>): Comic {
        val fingerprint = resolver.openInputStream(uri)?.use { ComicFileFingerprint.calculate(it) }
            ?: throw IOException("Could not read comic document")
        checkDuplicate(existing, uri.toString(), fingerprint, title)
        val pages = resolver.openInputStream(uri)?.use { SafeCbzArchive.inspect(it).pages }
            ?: throw IOException("Could not open CBZ comic")
        // Only save if Android can retain access after a process restart.
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val entry = Comic(digest(uri.toString()), title, uri.toString(), pages.size, 0, "CBZ", fingerprint, ownerKey = digest(vaultId))
        save(vaultId, existing + entry)
        return entry
    }

    private fun importCbr(vaultId: String, uri: Uri, title: String, existing: List<Comic>): Comic {
        // Junrar needs random access. Stage the selected SAF document under noBackupFilesDir,
        // preserving the original and avoiding cache eviction during reading.
        val directory = ownedDirectory(vaultId).apply { mkdirs() }
        val staged = File(directory, ".${UUID.randomUUID()}.part")
        try {
            val hash = resolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { output -> ComicFileFingerprint.calculate(input, output) }
            } ?: throw IOException("Could not read CBR comic")
            checkDuplicate(existing, uri.toString(), hash, title)
            val pages = SafeCbrArchive.inspect(staged).pages
            // Reject archives whose first page cannot actually be decompressed.
            SafeCbrArchive.readPage(staged, pages.first())
            val owned = File(directory, "$hash.cbr")
            if (owned.exists()) throw IOException("This comic is already stored in this profile")
            if (!staged.renameTo(owned)) throw IOException("Could not save CBR to private storage")
            val entry = Comic(digest(uri.toString()), title, Uri.fromFile(owned).toString(),
                pages.size, 0, "CBR", hash, ownerKey = digest(vaultId))
            try {
                save(vaultId, existing + entry)
            } catch (e: Exception) {
                if (owned.exists() && !owned.delete()) {
                    e.addSuppressed(IOException("Could not clean up imported comic file"))
                }
                throw e
            }
            return entry
        } finally {
            if (staged.exists() && !staged.delete()) {
                staged.deleteOnExit() // Android process lifetime fallback; surface cleanup problems in logs.
            }
        }
    }

    private fun checkDuplicate(comics: List<Comic>, uri: String, sha256: String?, title: String) {
        val existing = ComicDuplicatePolicy.findDuplicate(
            existing = comics.map { ComicDuplicatePolicy.Item(it.id, it.uri, it.title, it.sha256) },
            uri = uri, legacyUriId = digest(uri), title = title, sha256 = sha256
        )
        if (existing != null) throw IOException("Duplicate: '${existing.title}' is already in this profile's comics library")
    }

    /** Read using the matching container type; original CBZ records default to CBZ. */
    fun pageNames(comic: Comic): List<String> = if (comic.format == "CBR") {
        SafeCbrArchive.inspect(ownedFile(comic.uri, comic.ownerKey)).pages
    } else {
        openComicZip(comic).use { SafeCbzArchive.inspect(it).pages }
    }

    fun readPage(comic: Comic, name: String): ByteArray = if (comic.format == "CBR") {
        SafeCbrArchive.readPage(ownedFile(comic.uri, comic.ownerKey), name)
    } else {
        openComicZip(comic).use { SafeCbzArchive.readPage(it, name) }
    }

    /** Opening page 1 is a reading session too, even if the page index is zero. */
    fun markOpened(vaultId: String, comicId: String): Unit = synchronized(mutationLock) {
        val current = list(vaultId)
        if (current.none { it.id == comicId }) return@synchronized
        val openedAt = System.currentTimeMillis()
        save(vaultId, current.map { comic ->
            if (comic.id == comicId) comic.copy(lastOpenedAt = openedAt) else comic
        })
    }

    fun setPage(vaultId: String, comicId: String, page: Int): Unit = synchronized(mutationLock) {
        val current = list(vaultId)
        if (current.none { it.id == comicId }) return@synchronized
        save(vaultId, current.map { item ->
            if (item.id == comicId) item.copy(
                lastPage = page.coerceIn(0, item.pageCount - 1),
                lastOpenedAt = System.currentTimeMillis()
            ) else item
        })
    }

    fun remove(vaultId: String, comicId: String): Unit = synchronized(mutationLock) {
        val current = list(vaultId)
        val removed = current.firstOrNull { it.id == comicId } ?: return@synchronized
        val others = current.filterNot { it.id == comicId }
        // Persist the removal first; a failed preferences write must not leave a dangling
        // shelf entry pointing to a deleted private file.
        save(vaultId, others)
        if (removed.uri.toUri().scheme == "file") {
            // Only delete files under this vault's private directory. Never touch external files.
            val file = runCatching { ownedFile(removed.uri, digest(vaultId)) }.getOrNull()
            if (file != null &&
                file.canonicalFile.parentFile == ownedDirectory(vaultId).canonicalFile &&
                file.exists() && !file.delete()
            ) {
                throw IOException("Could not remove the private comic archive")
            }
        }
    }

    private fun openComicZip(comic: Comic): java.io.InputStream =
        if (comic.uri.toUri().scheme == "file") ownedFile(comic.uri, comic.ownerKey).inputStream() else open(comic.uri)

    fun open(uri: String) = resolver.openInputStream(uri.toUri())
        ?: throw IOException("Comic document permission is unavailable; import it again")

    private fun ownedFile(uri: String, ownerKey: String?): File {
        val parsed = uri.toUri()
        if (parsed.scheme != "file") throw IOException("Expected private comic file")
        val file = File(parsed.path ?: "")
        val root = File(context.applicationContext.noBackupFilesDir.canonicalFile, "comics")
        val directory = ownerKey?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }?.let { File(root, it) }
        if (directory == null || directory.canonicalFile != directory.absoluteFile ||
            file.canonicalFile.parentFile != directory.absoluteFile) {
            throw IOException("Comic location is not owned by this vault")
        }
        return file.canonicalFile
    }

    private fun ownedDirectory(vaultId: String): File {
        val directory = File(context.applicationContext.noBackupFilesDir.canonicalFile, "comics/${digest(vaultId)}")
        if (directory.canonicalFile != directory.absoluteFile) throw IOException("Invalid private comic directory")
        return directory
    }

    private fun save(vaultId: String, comics: List<Comic>) {
        val serialized = JSONArray()
        comics.forEach { item ->
            serialized.put(JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("uri", item.uri)
                put("pageCount", item.pageCount)
                put("lastPage", item.lastPage)
                put("format", item.format)
                if (item.sha256 != null) put("sha256", item.sha256)
                put("lastOpenedAt", item.lastOpenedAt)
                item.viewOverride?.let { put("viewOverride", it.encode()) }
            })
        }
        if (!preferences.edit().putString(key(vaultId), serialized.toString()).commit()) {
            throw IOException("Could not save comic shelf")
        }
    }

    private fun documentInfo(uri: Uri): Pair<String, Long?> {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val n = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val name = if (n >= 0 && !cursor.isNull(n)) cursor.getString(n) else ""
                    val size = if (s >= 0 && !cursor.isNull(s)) cursor.getLong(s) else null
                    return name to size
                }
            }
        throw IOException("Comic document details are unavailable")
    }

    private fun key(vaultId: String): String = "vault_" + digest(vaultId)

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
