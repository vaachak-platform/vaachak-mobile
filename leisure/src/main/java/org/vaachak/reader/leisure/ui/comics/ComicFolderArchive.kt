package org.vaachak.reader.leisure.ui.comics

import android.content.ContentResolver
import androidx.documentfile.provider.DocumentFile
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.IOException
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Copy a bounded SAF image tree to a deterministic private CBZ, with no external writes. */
internal object ComicFolderArchive {
    private const val MAX_NODES = 5000
    private const val MAX_DEPTH = 8
    private data class Page(val path: String, val uri: android.net.Uri)
    private const val MAX_PAGE_BYTES = 64L * 1024 * 1024
    // Reserve space for ZIP headers and central directory within the existing archive cap.
    private const val MAX_CONTENT = SafeCbzArchive.MAX_ARCHIVE_BYTES - 4L * 1024 * 1024

    fun write(tree: DocumentFile, resolver: ContentResolver, target: File) {
        val root = tree.uri
        val rootId = DocumentsContract.getDocumentId(root)
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        val rootCursor = resolver.query(root, columns, null, null, null)
            ?: throw IOException("Folder metadata unavailable")
        rootCursor.use { cursor ->
            check(cursor.moveToFirst() && cursor.getString(cursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)) == rootId &&
                cursor.getString(cursor.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)) == Document.MIME_TYPE_DIR &&
                !cursor.moveToNext()) { "Choose an image folder" }
            check(!cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) { "Folder metadata is incomplete" }
            check(cursor.extras.getString(DocumentsContract.EXTRA_ERROR).isNullOrBlank()) { "Folder metadata failed" }
        }
        val images = mutableListOf<Page>()
        val visited = mutableSetOf(rootId)
        val paths = mutableSetOf<String>()
        var nodes = 0
        fun walk(id: String, prefix: String, depth: Int) {
            check(depth <= MAX_DEPTH) { "Image folder nesting exceeds limit" }
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(root, id)
            val cursor = resolver.query(children, columns, null, null, null)
                ?: throw IOException("Folder listing unavailable")
            // Collect only bounded directory IDs; close the cursor before opening descendants.
            val directories = mutableListOf<Pair<String, String>>()
            cursor.use { rows ->
                val idColumn = rows.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
                val nameColumn = rows.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
                val mimeColumn = rows.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)
                while (rows.moveToNext()) {
                    check(++nodes <= MAX_NODES) { "Too many folder entries" }
                    val childId = checkNotNull(rows.getString(idColumn)) { "Missing document identity" }
                    check(childId.isNotBlank() && visited.add(childId)) { "Repeated folder document" }
                    val name = checkNotNull(rows.getString(nameColumn)) { "Unnamed folder document" }
                    val mime = checkNotNull(rows.getString(mimeColumn)) { "Missing document type" }
                    check(name.isNotBlank() && name != "." && name != ".." &&
                        '/' !in name && '\\' !in name && '\u0000' !in name) { "Invalid folder document name" }
                    check(mime.isNotBlank()) { "Missing document type" }
                    if (!name.startsWith('.')) {
                        val path = prefix + name
                        if (mime == Document.MIME_TYPE_DIR) {
                            directories += childId to "$path/"
                        } else if (SafeCbzArchive.isImage(name)) {
                            check(paths.add(path.lowercase(Locale.ROOT))) { "Duplicate image path" }
                            images += Page(path, DocumentsContract.buildDocumentUriUsingTree(root, childId))
                        }
                    }
                }
                // A provider can advertise an incomplete directory while loading.
                check(!rows.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) { "Folder listing is incomplete" }
                check(rows.extras.getString(DocumentsContract.EXTRA_ERROR).isNullOrBlank()) { "Folder listing failed" }
            }
            directories.forEach { (childId, path) -> walk(childId, path, depth + 1) }
        }
        walk(rootId, "", 0)
        check(images.isNotEmpty()) { "No supported comic images found" }
        val ordered = images.sortedWith { a, b ->
            SafeCbzArchive.compareNatural(a.path, b.path).takeIf { it != 0 } ?: a.path.compareTo(b.path)
        }
        var total = 0L
        target.outputStream().use { file ->
            ZipOutputStream(file).use { zip ->
                zip.setLevel(0)
                ordered.forEachIndexed { index, child ->
                    val suffix = child.path.substringAfterLast('.').lowercase(Locale.ROOT)
                    val name = String.format(Locale.ROOT, "%05d.%s", index, suffix)
                    zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                    val source = checkNotNull(resolver.openInputStream(child.uri)) { "Cannot read image" }
                    source.use { input ->
                        var count = 0L
                        val buffer = ByteArray(65536)
                        while (true) {
                            val n = input.read(buffer)
                            if (n == -1) break
                            check(n > 0 && count <= MAX_PAGE_BYTES - n && total <= MAX_CONTENT - n) { "Image folder exceeds size limits" }
                            count += n; total += n
                            zip.write(buffer, 0, n)
                        }
                        check(count > 0) { "Empty comic image" }
                    }
                    zip.closeEntry()
                }
                zip.finish()
                zip.flush()
                file.fd.sync()
            }
        }
        check(target.length() in 1L..SafeCbzArchive.MAX_ARCHIVE_BYTES)
    }
}
