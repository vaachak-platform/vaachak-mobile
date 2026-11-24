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
        val lastOpenedAt: Long = 0L
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
                lastOpenedAt = item.optLong("lastOpenedAt", 0L)
            )
        }
    }

    /** Support both formats, rejecting duplicate URI, content, and same-title imports per vault. */
    fun importComic(vaultId: String, uri: Uri): Comic {
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

        return if (format == "CBR") {
            importCbr(vaultId, uri, title, existing)
        } else {
            importCbzInternal(vaultId, uri, title, existing)
        }
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
        val entry = Comic(digest(uri.toString()), title, uri.toString(), pages.size, 0, "CBZ", fingerprint)
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
                pages.size, 0, "CBR", hash)
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
        SafeCbrArchive.inspect(ownedFile(comic.uri)).pages
    } else {
        open(comic.uri).use { SafeCbzArchive.inspect(it).pages }
    }

    fun readPage(comic: Comic, name: String): ByteArray = if (comic.format == "CBR") {
        SafeCbrArchive.readPage(ownedFile(comic.uri), name)
    } else {
        open(comic.uri).use { SafeCbzArchive.readPage(it, name) }
    }

    /** Opening page 1 is a reading session too, even if the page index is zero. */
    fun markOpened(vaultId: String, comicId: String) {
        val current = list(vaultId)
        if (current.none { it.id == comicId }) return
        val openedAt = System.currentTimeMillis()
        save(vaultId, current.map { comic ->
            if (comic.id == comicId) comic.copy(lastOpenedAt = openedAt) else comic
        })
    }

    fun setPage(vaultId: String, comicId: String, page: Int) {
        val current = list(vaultId)
        if (current.none { it.id == comicId }) return
        save(vaultId, current.map { item ->
            if (item.id == comicId) item.copy(
                lastPage = page.coerceIn(0, item.pageCount - 1),
                lastOpenedAt = System.currentTimeMillis()
            ) else item
        })
    }

    fun remove(vaultId: String, comicId: String) {
        val current = list(vaultId)
        val removed = current.firstOrNull { it.id == comicId } ?: return
        val others = current.filterNot { it.id == comicId }
        // Persist the removal first; a failed preferences write must not leave a dangling
        // shelf entry pointing to a deleted private file.
        save(vaultId, others)
        if (removed.format == "CBR") {
            // Only delete files under this vault's private directory. Never touch external files.
            val file = runCatching { ownedFile(removed.uri) }.getOrNull()
            if (file != null && file.canonicalFile.parentFile == ownedDirectory(vaultId).canonicalFile) {
                if (file.exists() && !file.delete()) {
                    throw IOException("Could not remove the private comic archive")
                }
            }
        }
    }

    fun open(uri: String) = resolver.openInputStream(uri.toUri())
        ?: throw IOException("Comic document permission is unavailable; import it again")

    private fun ownedFile(uri: String): File {
        val parsed = uri.toUri()
        if (parsed.scheme != "file") throw IOException("Expected private comic file")
        val file = File(parsed.path ?: "")
        val root = File(context.applicationContext.noBackupFilesDir, "comics")
        if (!file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) {
            throw IOException("Comic location is not private")
        }
        return file
    }

    private fun ownedDirectory(vaultId: String): File =
        File(context.applicationContext.noBackupFilesDir, "comics/${digest(vaultId)}")

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
