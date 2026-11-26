package org.vaachak.reader.leisure.ui.comics

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ContentProviderController
import org.robolectric.annotation.Config

/** Exercises real SAF queries, byte copies, deterministic archives and vault persistence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ComicFolderRecoveryTest {
    private lateinit var context: Context
    private lateinit var store: ComicShelfStore
    private lateinit var root: File
    private lateinit var provider: FolderFixtureProvider
    private lateinit var controller: ContentProviderController<FolderFixtureProvider>
    private val vault = "folder-${UUID.randomUUID()}"
    private val other = "folder-other-${UUID.randomUUID()}"

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        store = ComicShelfStore(context)
        root = File(context.cacheDir, "comic-folder-${UUID.randomUUID()}").apply { mkdirs() }
        provider = FolderFixtureProvider(root)
        controller = ContentProviderController.of(provider).create(ProviderInfo().apply {
            authority = AUTHORITY
            name = FolderFixtureProvider::class.java.name
            packageName = context.packageName
            exported = true
            grantUriPermissions = true
        })
    }

    @After fun cleanup() {
        controller.shutdown()
        for (key in listOf(vault, other)) {
            store.list(key).forEach { store.remove(key, it.id) }
        }
        root.deleteRecursively()
    }

    @Test fun nestedImportAndRestoreRetainIdentityProgressAndOriginalBytes() {
        val comic = store.importImageFolder(vault, tree("root"))
        assertEquals("FOLDER", comic.format)
        assertEquals(3, comic.pageCount)
        assertEquals(listOf("00000.png", "00001.jpg", "00002.webp"), store.pageNames(comic))
        assertArrayEquals(byteArrayOf(2), store.readPage(comic, "00000.png"))
        assertArrayEquals(byteArrayOf(10), store.readPage(comic, "00001.jpg"))
        assertArrayEquals(byteArrayOf(20), store.readPage(comic, "00002.webp"))
        store.setPage(vault, comic.id, 2)
        val override = ComicViewOverride(ComicViewMode.VERTICAL_PAGER, ComicZoomFit.PAGE)
        store.setViewOverride(vault, comic.id, override)
        val before = store.list(vault).single()
        val archive = File(Uri.parse(comic.uri).path!!)
        val bytes = archive.readBytes()
        assertTrue(archive.delete())
        provider.children["root"] = provider.children.getValue("root").reversed()
        assertEquals(before, store.importImageFolder(vault, tree("root"), comic.id))
        assertArrayEquals(bytes, archive.readBytes())
        assertEquals(before, store.list(vault).single())
        assertTrue(runCatching { store.importImageFolder(vault, tree("root")) }.isFailure)
        provider.payloads["two"] = byteArrayOf(99)
        assertTrue(runCatching { store.importImageFolder(vault, tree("root"), comic.id) }.isFailure)
        assertArrayEquals(bytes, archive.readBytes())
        assertEquals(before, store.list(vault).single())
        assertTrue(runCatching { store.importImageFolder(other, tree("root"), comic.id) }.isFailure)
        assertTrue(store.list(other).isEmpty())
    }

    @Test fun malformedTreesAndEmptyImagesNeverPublishShelfEntries() {
        for (id in listOf("empty", "cycle", "duplicate", "caseCollision", "unsafe", "zero", "denied", "depth0", "two")) {
            assertTrue("Expected rejection of $id", runCatching { store.importImageFolder(vault, tree(id)) }.isFailure)
            assertTrue(store.list(vault).isEmpty())
        }
        assertTrue(runCatching { store.importImageFolder(vault, Uri.fromFile(root)) }.isFailure)
        assertTrue(store.list(vault).isEmpty())
    }

    @Test fun flatFolderUsesNaturalOrderAndRejectsForeignRestoreId() {
        val comic = store.importImageFolder(vault, tree("flat"))
        assertEquals(listOf("00000.png", "00001.jpg"), store.pageNames(comic))
        assertTrue(runCatching { store.importImageFolder(vault, tree("flat"), "unknown") }.isFailure)
        assertEquals(comic, store.list(vault).single())
        val owned = File(Uri.parse(comic.uri).path!!)
        store.remove(vault, comic.id)
        assertFalse(owned.exists())
        assertTrue(store.list(vault).isEmpty())
    }

    @Test fun failedDescendantListingCannotPublishPartialArchive() {
        for (failure in listOf("throw", "null", "loading", "error", "columns")) {
            provider.listingFailure = failure
            assertTrue("Expected listing rejection: $failure", runCatching {
                store.importImageFolder(vault, tree("root"))
            }.isFailure)
            assertTrue(store.list(vault).isEmpty())
        }
        provider.listingFailure = null
        assertEquals(3, store.importImageFolder(vault, tree("root")).pageCount)
    }

    @Test fun excessiveProviderRowsFailBeforePublication() {
        provider.children["flat"] = List(5001) { "ignored-$it" }
        assertTrue(runCatching { store.importImageFolder(vault, tree("flat")) }.isFailure)
        assertTrue(store.list(vault).isEmpty())
    }

    private fun tree(id: String): Uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, id)
    private companion object { const val AUTHORITY = "vaachak.comic.folder.fixture" }
}

private class FolderFixtureProvider(private val root: File) : ContentProvider() {
    var listingFailure: String? = null
    val children = mutableMapOf(
        "root" to listOf("ten", "sub", "hidden", "two", "text"),
        "sub" to listOf("twenty"), "flat" to listOf("ten", "two"),
        "empty" to emptyList(), "cycle" to listOf("cycle"),
        "duplicate" to listOf("two", "two"), "caseCollision" to listOf("two", "caseTwo"),
        "unsafe" to listOf("escape"), "zero" to listOf("emptyImage"), "denied" to listOf("blocked")
    ).apply { for (i in 0..9) put("depth$i", listOf(if (i == 9) "two" else "depth${i+1}")) }
    private val names = mapOf("two" to "2.png", "ten" to "10.jpg", "twenty" to "20.webp",
        "sub" to "Sub", "hidden" to ".hidden.png", "text" to "readme.txt", "caseTwo" to "2.PNG",
        "escape" to "../escape.png", "emptyImage" to "empty.png", "blocked" to "denied.png")
    val payloads = mutableMapOf("two" to byteArrayOf(2), "ten" to byteArrayOf(10),
        "twenty" to byteArrayOf(20), "emptyImage" to byteArrayOf())
    override fun onCreate() = true
    override fun getType(uri: Uri): String = "image/png"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val columns = projection?.map { it }?.toTypedArray() ?: arrayOf(OpenableColumns.DISPLAY_NAME)
        val cursor = MatrixCursor(columns)
        val parts = uri.pathSegments
        val listing = parts.last() == "children"
        val id = if (listing) parts[parts.size - 2] else parts.last()
        if (listing && id == "sub") {
            when (listingFailure) {
                "throw" -> throw SecurityException("fixture listing denied")
                "null" -> return null
                "columns" -> return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME))
                "loading", "error" -> cursor.setExtras(android.os.Bundle().apply {
                    if (listingFailure == "loading") putBoolean(DocumentsContract.EXTRA_LOADING, true)
                    else putString(DocumentsContract.EXTRA_ERROR, "fixture provider error")
                })
            }
        }
        val ids = if (listing) children[id].orEmpty() else listOf(id)
        ids.forEach { item -> cursor.addRow(columns.map { column -> when (column) {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> item
            OpenableColumns.DISPLAY_NAME -> names[item] ?: item
            DocumentsContract.Document.COLUMN_MIME_TYPE -> if (item in children) DocumentsContract.Document.MIME_TYPE_DIR else "image/png"
            else -> 0
        } }.toTypedArray<Any?>()) }
        return cursor
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val id = uri.pathSegments.last()
        if (id == "blocked") throw SecurityException("fixture denied")
        val bytes = checkNotNull(payloads[id]) { "Unexpected fixture read" }
        val file = File(root, "source-$id").apply { writeBytes(bytes) }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
