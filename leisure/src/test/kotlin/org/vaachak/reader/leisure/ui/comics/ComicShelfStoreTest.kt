package org.vaachak.reader.leisure.ui.comics

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ContentProviderController
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises the real vault-local persistence and SAF import paths, without the EPUB library. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ComicShelfStoreTest {
    private lateinit var context: Context
    private lateinit var store: ComicShelfStore
    private lateinit var provider: FixtureComicProvider
    private lateinit var providerController: ContentProviderController<FixtureComicProvider>
    private lateinit var source: File
    private val vault = "comics-coverage-${UUID.randomUUID()}"
    private val otherVault = "unrelated-${UUID.randomUUID()}"

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        store = ComicShelfStore(context)
        source = File.createTempFile("vaachak-comics-test", ".bin", context.cacheDir)
        provider = FixtureComicProvider(source)
        // Initialize and register a real fixture provider instead of using Robolectric's
        // internal-only provider registry. Without attachInfo, Android may deny queries
        // and stream reads before the import validator can examine the document.
        providerController = ContentProviderController.of(provider).create(
            ProviderInfo().apply {
                authority = AUTHORITY
                name = FixtureComicProvider::class.java.name
                packageName = context.packageName
                exported = true
                grantUriPermissions = true
            }
        )
    }

    @After fun tearDown() {
        providerController.shutdown()
        source.delete()
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .remove(storageKey(vault)).remove(storageKey(otherVault)).commit()
        File(context.noBackupFilesDir, "comics/${hash(vault)}").deleteRecursively()
        File(context.noBackupFilesDir, "comics/${hash(otherVault)}").deleteRecursively()
    }

    @Test fun emptyVaultAndLegacyRecordsHaveSafeDefaults() {
        assertTrue(store.list(vault).isEmpty())
        seed(vault, JSONObject().put("id", "old").put("title", "Old Issue")
            .put("uri", "content://legacy/old.cbz").put("pageCount", 11).put("lastPage", 4))
        val comic = store.list(vault).single()
        assertEquals("CBZ", comic.format)
        assertEquals(4, comic.lastPage)
        assertEquals(0L, comic.lastOpenedAt)
        assertEquals(null, comic.sha256)
    }

    @Test fun brokenShelfMetadataFailsExplicitlyInsteadOfLosingTheLibrary() {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putString(storageKey(vault), "broken-json").commit()
        assertThrows(IOException::class.java) { store.list(vault) }
    }

    @Test fun firstPageVisitCountsAsRecentReadingAndDoesNotAffectOtherVaults() {
        val a = record("a", "Alpha", "content://archive/a.cbz", 10, 0)
        val b = record("b", "Beta", "content://archive/b.cbz", 4, 0)
        seed(vault, a, b)
        seed(otherVault, record("a", "Private", "content://archive/private.cbz", 3, 0))

        store.markOpened(vault, "missing")
        assertEquals(0L, store.list(vault).first().lastOpenedAt)
        store.markOpened(vault, "a")
        val after = store.list(vault)
        assertTrue(after[0].lastOpenedAt > 0L)
        assertEquals(0, after[0].lastPage)
        assertEquals(0L, after[1].lastOpenedAt)
        assertEquals(0L, store.list(otherVault).single().lastOpenedAt)
    }

    @Test fun progressIsClampedAndIsolatedToTheMatchingComic() {
        seed(vault, record("a", "Alpha", "content://archive/a.cbz", 4, 0),
            record("b", "Beta", "content://archive/b.cbz", 2, 0))
        store.setPage(vault, "does-not-exist", 22)
        store.setPage(vault, "a", 100)
        assertEquals(3, store.list(vault)[0].lastPage)
        assertTrue(store.list(vault)[0].lastOpenedAt > 0L)
        assertEquals(0, store.list(vault)[1].lastPage)
        store.setPage(vault, "a", -50)
        assertEquals(0, store.list(vault)[0].lastPage)
    }

    @Test fun removeNeverDeletesAnotherVaultFileOrAnExternalComic() {
        val otherDir = File(context.noBackupFilesDir, "comics/${hash(otherVault)}")
        otherDir.mkdirs()
        val otherFile = File(otherDir, "another-profile.cbr").apply { writeText("keep me") }
        val external = File.createTempFile("vaachak-original", ".cbr", context.cacheDir)
        try {
            seed(vault, record("a", "A", Uri.fromFile(otherFile).toString(), 3, 0, "CBR"),
                record("b", "B", Uri.fromFile(external).toString(), 2, 0, "CBR"))
            store.remove(vault, "not-present")
            assertEquals(2, store.list(vault).size)
            store.remove(vault, "a")
            store.remove(vault, "b")
            assertTrue(store.list(vault).isEmpty())
            assertTrue(otherFile.isFile)
            assertTrue(external.isFile)
        } finally {
            external.delete()
        }
    }

    @Test fun removeDeletesOnlyTheOwnedCbrCopy() {
        val directory = File(context.noBackupFilesDir, "comics/${hash(vault)}").apply { mkdirs() }
        val owned = File(directory, "owned.cbr").apply { writeText("owned content") }
        seed(vault, record("a", "Issue", Uri.fromFile(owned).toString(), 3, 1, "CBR"))
        store.remove(vault, "a")
        assertTrue(store.list(vault).isEmpty())
        assertFalse(owned.exists())
    }

    @Test fun untrustedLocationsAndUnknownDocumentsFailBeforeImport() {
        assertThrows(IOException::class.java) { store.importComic(vault, Uri.fromFile(source)) }
        assertThrows(IOException::class.java) { store.importComic(vault, Uri.parse("content://missing/some.cbz")) }
        provider.displayName = "not-a-comic.pdf"
        source.writeBytes(byteArrayOf(1, 2, 3))
        assertThrows(IOException::class.java) { store.importComic(vault, uri("bad.pdf")) }
        provider.displayName = "not-actually-zip.cbz"
        assertThrows(IOException::class.java) { store.importComic(vault, uri("bad.cbz")) }
        assertTrue(store.list(vault).isEmpty())
    }

    @Test fun archiveSizeAndPreExistingTitleAreRejectedWithoutChangingTheShelf() {
        provider.displayName = "Large.cbr"
        provider.reportedSize = ComicFileFingerprint.MAX_ARCHIVE_BYTES + 1
        source.writeBytes(RAR4_HEADER)
        assertThrows(IOException::class.java) { store.importComic(vault, uri("large.cbr")) }

        provider.reportedSize = null
        provider.displayName = "Already There.cbr"
        seed(vault, record("x", "Already There", "content://old/comic.cbr", 5, 2, "CBR"))
        val error = assertThrows(IOException::class.java) {
            store.importComic(vault, uri("another.cbr"))
        }
        assertTrue(error.message.orEmpty().contains("Duplicate"))
        assertEquals(1, store.list(vault).size)
    }

    @Test fun cbrCorruptionCleansUpStagingWithoutSavingARecord() {
        provider.displayName = "Broken.cbr"
        source.writeBytes(RAR4_HEADER + ByteArray(20))
        assertThrows(Exception::class.java) { store.importComic(vault, uri("broken.cbr")) }
        assertTrue(store.list(vault).isEmpty())
        val staged = File(context.noBackupFilesDir, "comics/${hash(vault)}")
        assertTrue(staged.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test fun validCbzSupportsReadResumeAndRejectsRenamedDuplicateContent() {
        val first = byteArrayOf(5, 6, 7)
        source.writeBytes(cbz("10.jpg" to byteArrayOf(10), "02.png" to first))
        provider.displayName = "Original.cbz"
        provider.reportedSize = null
        val added = store.importComic(vault, uri("original.cbz"))
        assertEquals("CBZ", added.format)
        assertEquals(2, added.pageCount)
        assertNotNull(added.sha256)
        assertEquals(listOf("02.png", "10.jpg"), store.pageNames(added))
        assertArrayEquals(first, store.readPage(added, "02.png"))
        assertEquals(1, store.list(vault).size)

        assertThrows(IOException::class.java) { store.importComic(vault, uri("original.cbz")) }
        provider.displayName = "Renamed.cbz"
        val duplicate = assertThrows(IOException::class.java) {
            store.importComic(vault, uri("renamed.cbz"))
        }
        assertTrue(duplicate.message.orEmpty().contains("Duplicate"))
        assertEquals(1, store.list(vault).size)
    }

    private fun record(id: String, title: String, uri: String, count: Int, page: Int, format: String = "CBZ") =
        JSONObject().put("id", id).put("title", title).put("uri", uri)
            .put("pageCount", count).put("lastPage", page).put("format", format)

    private fun seed(vaultId: String, vararg records: JSONObject) {
        val rows = JSONArray()
        records.forEach { rows.put(it) }
        assertTrue(context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(storageKey(vaultId), rows.toString()).commit())
    }

    private fun uri(name: String) = Uri.parse("content://$AUTHORITY/$name")
    private fun storageKey(vaultId: String) = "vault_${hash(vaultId)}"
    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun cbz(vararg pages: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { stream ->
            pages.forEach { (name, bytes) ->
                stream.putNextEntry(ZipEntry(name))
                stream.write(bytes)
                stream.closeEntry()
            }
        }
        return output.toByteArray()
    }

    companion object {
        private const val AUTHORITY = "vaachak-comics-robolectric-tests"
        private const val PREF_NAME = "vaachak_comics_v1"
        private val RAR4_HEADER = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0)
    }

    /** Real ContentResolver metadata and repeatable file reads, without relying on device storage. */
    private class FixtureComicProvider(private val source: File) : ContentProvider() {
        var displayName = "Example.cbz"
        var reportedSize: Long? = null

        override fun onCreate(): Boolean = true
        override fun getType(uri: Uri): String = "application/octet-stream"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor = MatrixCursor(
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        ).apply { addRow(arrayOf<Any?>(displayName, reportedSize)) }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?,
            selectionArgs: Array<out String>?): Int = 0
    }
}
