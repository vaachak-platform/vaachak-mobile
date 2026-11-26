package org.vaachak.reader.leisure.ui.audio

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
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ContentProviderController
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AudioImporterStoreTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var store: AudioStore
    private lateinit var controller: ContentProviderController<AudioFixtureProvider>
    private lateinit var provider: AudioFixtureProvider
    private lateinit var importer: AudioImporter
    private val vault = "fixture"

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        root = File(context.cacheDir, "audio-test-${UUID.randomUUID()}").apply { mkdirs() }
        store = AudioStore(File(root,"store"))
        provider = AudioFixtureProvider(root)
        controller = ContentProviderController.of(provider).create(ProviderInfo().apply {
            authority = AUTHORITY; name = AudioFixtureProvider::class.java.name
            packageName = context.packageName; exported = true; grantUriPermissions = true
        })
        importer = AudioImporter(context, store, AudioMetadataReader { file, _ ->
            AudioMetadata(10000L, null, if (file.readBytes().first() == 9.toByte()) "Album" else null, "Narrator")
        })
    }
    @After fun tearDown() { controller.shutdown(); root.deleteRecursively() }

    @Test fun documentImportUsesProviderNamesAndOrder() = runBlocking {
        val book = importer.documents(vault, listOf(uri("ten"),uri("two")))
        assertEquals(listOf("2","10"),book.tracks.map { it.name })
        assertEquals("2 (2 tracks)",book.title)
        assertEquals("Narrator",book.author)
        assertEquals(book,store.load(vault).find(book.id))
        assertTrue(store.load("different").books.isEmpty())
        assertTrue(runCatching { importer.documents(vault,listOf(uri("ten"),uri("two"))) }.isFailure)
        val album = importer.documents(vault,listOf(uri("album")))
        assertEquals("Album",album.title)
        val single = importer.documents(vault,listOf(uri("single")))
        assertEquals("Solo",single.title)
        assertTrue(runCatching { importer.documents(vault,listOf(uri("missing"))) }.isFailure)
        assertTrue(runCatching { importer.documents(vault,listOf(uri("denied"))) }.isFailure)
        assertTrue(runCatching { importer.documents(vault,listOf(uri("bad"))) }.isFailure)
        assertTrue(runCatching { importer.documents(vault,listOf(Uri.parse("https://invalid.example/a.wav"))) }.isFailure)
        assertTrue(runCatching { importer.documents(vault,listOf(uri("ten"),uri("ten"))) }.isFailure)
        assertTrue(runCatching { importer.documents(vault,emptyList()) }.isFailure)
    }
    @Test fun folderImportWalksNestedChildrenAndRejectsCycleAndEmptyFolder() = runBlocking {
        val book = importer.folder(vault, DocumentsContract.buildTreeDocumentUri(AUTHORITY,"root"))
        assertEquals("Folder",book.title)
        assertEquals(listOf("10","2"),book.tracks.map { it.name }) // Folder/10.wav before Folder/Sub/2.wav
        assertTrue(runCatching { importer.folder(vault, DocumentsContract.buildTreeDocumentUri(AUTHORITY,"empty")) }.isFailure)
        assertTrue(runCatching { importer.folder(vault, DocumentsContract.buildTreeDocumentUri(AUTHORITY,"cycle")) }.isFailure)
        assertTrue(runCatching { importer.folder(vault,Uri.parse("file:///private")) }.isFailure)
    }
    @Test fun invalidInputAndMetadataFailureDoNotPublishPartialBook() = runBlocking {
        val badReader = AudioImporter(context,store,AudioMetadataReader { _,_ -> error("Metadata failed") })
        assertTrue(runCatching { badReader.documents(vault,listOf(uri("ten"))) }.isFailure)
        assertTrue(store.load(vault).books.isEmpty())
        assertFalse(File(root,"store").walkTopDown().any { it.name.startsWith("pending-") })
        assertTrue(runCatching { importer.importSources(vault,emptyList(),null) }.isFailure)
        assertTrue(runCatching { importer.importSources(vault,List(MAX_AUDIO_TRACKS+1) { AudioInput("a.wav") { byteArrayOf(1).inputStream() } },null) }.isFailure)
        assertTrue(runCatching { importer.importSources(vault,listOf(AudioInput("bad.exe") { byteArrayOf(1).inputStream() }),null) }.isFailure)
        val book = importer.importSources(vault,listOf(AudioInput("a.wav") { byteArrayOf(3).inputStream() })," ")
        assertEquals("Audiobook",book.title)
    }
    @Test fun indexValidationAndStaleProgressCannotRecreateTitle() = runBlocking {
        val book = importer.documents(vault,listOf(uri("ten")))
        val dir = File(root,"store/${audioSha256(vault.toByteArray())}")
        assertNull(store.coverFile(vault,book.id))
        val cover = File(store.mediaFile(vault,book.id,book.tracks.first().file).parentFile,"cover.jpg")
        cover.writeBytes(byteArrayOf(1))
        assertEquals(cover,store.coverFile(vault,book.id))
        cover.writeBytes(byteArrayOf())
        assertNull(store.coverFile(vault,book.id))
        assertTrue(runCatching { store.load(" ") }.isFailure)
        assertTrue(runCatching { store.load("a".repeat(513)) }.isFailure)
        assertTrue(runCatching { store.mediaFile(vault,"not-uuid","000.wav") }.isFailure)
        assertTrue(runCatching { store.mediaFile(vault,book.id,"../x") }.isFailure)
        val before = store.revision.value
        store.progress(vault,book.id,0,0,false)
        assertEquals(before,store.revision.value)
        val json = AudioJson.encode(store.load(vault))
        assertEquals(store.load(vault),AudioJson.decode(json))
        fun invalid(change: (JSONObject)->Unit) {
            val modified = JSONObject(json.toString()); change(modified)
            assertTrue(runCatching { AudioJson.decode(modified) }.isFailure)
        }
        invalid { it.put("schema",2) }
        invalid { it.getJSONArray("books").put(it.getJSONArray("books").getJSONObject(0)) }
        invalid { it.getJSONArray("books").getJSONObject(0).put("id","invalid") }
        invalid { it.getJSONArray("books").getJSONObject(0).getJSONArray("tracks").getJSONObject(0).put("hash","bad") }
        invalid { it.getJSONArray("books").getJSONObject(0).getJSONArray("tracks").getJSONObject(0).put("file","../wav") }
        store.remove(vault,book.id); store.remove(vault,book.id)
        store.progress(vault,book.id,1000,10,false)
        assertTrue(store.load(vault).books.isEmpty())
        File(dir,"library.json").writeText("invalid-json")
        assertTrue(runCatching { store.load(vault) }.isFailure)
        assertEquals("invalid-json",File(dir,"library.json").readText())
    }
    @Test fun restoresMissingAudioWithoutChangingIdentityProgressOrPreferences() = runBlocking {
        val book = importer.documents(vault, listOf(uri("ten"), uri("two")))
        store.progress(vault, book.id, 12345L, 123L, false)
        store.setTitlePreferences(vault, book.id, AudioPreferences(1.5f, 10, 60))
        val before = store.load(vault)
        val missing = store.mediaFile(vault, book.id, book.tracks.first().file)
        assertTrue(missing.delete())
        assertTrue(store.recoverySummary(vault).startsWith("1 audiobook"))
        assertTrue(runCatching { importer.restoreDocuments(vault, book.id, listOf(uri("single"), uri("ten"))) }.isFailure)
        assertFalse(missing.exists())
        assertEquals(before, store.load(vault))
        val restored = importer.restoreDocuments(vault, book.id, listOf(uri("ten"), uri("two")))
        assertEquals(before.find(book.id), restored)
        assertEquals(before, store.load(vault))
        assertTrue(missing.isFile)
        assertTrue(store.recoverySummary(vault).startsWith("0 audiobook"))
        assertTrue(runCatching { importer.restoreDocuments("different", book.id, listOf(uri("two"), uri("ten"))) }.isFailure)
    }

    @Test fun folderRestoreRetainsTrackOrderAndMetadata() = runBlocking {
        val folder = DocumentsContract.buildTreeDocumentUri(AUTHORITY,"root")
        val book = importer.folder(vault, folder)
        store.mediaFile(vault, book.id, book.tracks.last().file).delete()
        assertEquals(book, importer.restoreFolder(vault, book.id, folder))
        assertEquals(book, store.load(vault).find(book.id))
    }

    @Test fun invalidRestoreInputsAndStageCannotPublishMedia() = runBlocking {
        val book = importer.documents(vault, listOf(uri("ten")))
        val file = store.mediaFile(vault, book.id, book.tracks.single().file)
        val bytes = file.readBytes()
        val before = store.load(vault)
        assertTrue(runCatching { importer.restoreDocuments(vault, book.id, emptyList()) }.isFailure)
        assertTrue(runCatching { importer.restoreDocuments(vault, book.id, listOf(uri("ten"), uri("ten"))) }.isFailure)
        assertTrue(runCatching { importer.restoreSources(vault, book.id, emptyList()) }.isFailure)
        assertTrue(runCatching { importer.restoreSources(vault, book.id, listOf(AudioInput("bad.exe") { bytes.inputStream() })) }.isFailure)
        assertTrue(runCatching { store.restoreVerified(vault, book, root) }.isFailure)
        val stage = store.staging(vault)
        try {
            assertTrue(runCatching { store.restoreVerified(vault, book, stage) }.isFailure)
            File(stage, book.tracks.single().file).writeBytes(byteArrayOf())
            assertTrue(runCatching { store.restoreVerified(vault, book, stage) }.isFailure)
            assertTrue(runCatching { store.restoreVerified("other", book, stage) }.isFailure)
            assertEquals(before, store.load(vault))
            assertArrayEquals(bytes, file.readBytes())
        } finally { stage.deleteRecursively() }
        assertFalse(File(root, "store").walkTopDown().any { it.name.startsWith("pending-") })
    }

    @Test fun storageSummaryReportsEmptyFilesAndRetainsUnreferencedDirectories() = runBlocking {
        assertTrue(store.recoverySummary("uncreated").startsWith("0 audiobook"))
        val book = importer.documents(vault, listOf(uri("ten")))
        val file = store.mediaFile(vault, book.id, book.tracks.single().file)
        val orphan = store.staging(vault)
        val marker = File(orphan, "retain.txt").apply { writeText("retain") }
        val before = store.load(vault)
        try {
            file.writeBytes(byteArrayOf())
            val summary = store.recoverySummary(vault)
            assertTrue(summary.startsWith("1 audiobook"))
            assertTrue(summary.contains("1 unreferenced or staging folder(s)"))
            assertEquals("retain", marker.readText())
            assertEquals(before, store.load(vault))
            assertTrue(file.isFile)
            assertEquals(0L, file.length())
        } finally { orphan.deleteRecursively() }
    }

    @Test fun restoreCannotPublishWhilePlaybackOpeningOrActiveHoldsTitle() = runBlocking {
        val book = importer.documents(vault, listOf(uri("ten")))
        val file = store.mediaFile(vault, book.id, book.tracks.single().file)
        assertTrue(file.delete())
        val lease = checkNotNull(AudioMediaAccess.acquire(vault, book.id))
        try {
            assertNull(AudioMediaAccess.acquire(vault, book.id))
            assertTrue(runCatching { importer.restoreDocuments(vault, book.id, listOf(uri("ten"))) }.isFailure)
            assertFalse(file.exists())
            assertEquals(book, store.load(vault).find(book.id))
            val other = checkNotNull(AudioMediaAccess.acquire("other", book.id))
            AudioMediaAccess.release(other)
        } finally { AudioMediaAccess.release(lease) }
        val next = checkNotNull(AudioMediaAccess.acquire(vault, book.id))
        try {
            AudioMediaAccess.release(lease) // A stale owner cannot release a later reservation.
            assertNull(AudioMediaAccess.acquire(vault, book.id))
        } finally { AudioMediaAccess.release(next) }
        assertEquals(book, importer.restoreDocuments(vault, book.id, listOf(uri("ten"))))
        assertTrue(file.isFile)
        val after = checkNotNull(AudioMediaAccess.acquire(vault, book.id))
        AudioMediaAccess.release(after)
        AudioMediaAccess.release(null)
    }

    @Test fun removalCannotDeleteReservedTitleAndCanRetryAfterRelease() = runBlocking {
        val book = importer.documents(vault, listOf(uri("ten")))
        val file = store.mediaFile(vault, book.id, book.tracks.single().file)
        val bytes = file.readBytes()
        val before = store.load(vault)
        val lease = checkNotNull(AudioMediaAccess.acquire(vault, book.id))
        try {
            assertTrue(runCatching { store.remove(vault, book.id) }.isFailure)
            assertEquals(before, store.load(vault))
            assertArrayEquals(bytes, file.readBytes())
            store.remove("other", book.id)
            assertEquals(before, store.load(vault))
        } finally { AudioMediaAccess.release(lease) }
        store.remove(vault, book.id)
        assertNull(store.load(vault).find(book.id))
        assertFalse(file.exists())
        val next = checkNotNull(AudioMediaAccess.acquire(vault, book.id))
        AudioMediaAccess.release(next)
        store.remove(vault, book.id)
    }

    private fun uri(id: String): Uri = DocumentsContract.buildDocumentUri(AUTHORITY,id)
    private companion object { const val AUTHORITY = "vaachak.audio.fixture" }
}

private class AudioFixtureProvider(private val root: File) : ContentProvider() {
    private val names = mapOf("root" to "Folder", "sub" to "Sub", "ten" to "10.wav", "two" to "2.wav",
        "album" to "Album.wav", "single" to "Solo.wav", "bad" to "bad.txt", "denied" to "Denied.wav",
        "empty" to "Empty", "cycle" to "Cycle")
    private val folders = setOf("root","sub","empty","cycle")
    override fun onCreate() = true
    override fun getType(uri: Uri): String = "audio/wav"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val columns = projection?.map { it }?.toTypedArray() ?: arrayOf(OpenableColumns.DISPLAY_NAME)
        val cursor = MatrixCursor(columns)
        val segments = uri.pathSegments
        val children = segments.last() == "children"
        val id = if (children) segments[segments.size-2] else segments.last()
        val ids = if (children) when(id) {
            "root" -> listOf("ten","sub","bad"); "sub" -> listOf("two"); "cycle" -> listOf("cycle"); else -> emptyList()
        } else listOf(id).filter { it in names }
        ids.forEach { item -> cursor.addRow(columns.map { col -> when(col) {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> item
            OpenableColumns.DISPLAY_NAME -> names[item]
            DocumentsContract.Document.COLUMN_MIME_TYPE -> if (item in folders) DocumentsContract.Document.MIME_TYPE_DIR else "audio/wav"
            else -> 0
        } }.toTypedArray<Any?>()) }
        return cursor
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val id = uri.pathSegments.last()
        if (id == "denied") throw SecurityException("denied")
        val file = File(root,"source-$id").apply { writeBytes(byteArrayOf((when(id) { "album" -> 9; "ten" -> 1; "two" -> 2; else -> 3 }).toByte())) }
        return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
}
