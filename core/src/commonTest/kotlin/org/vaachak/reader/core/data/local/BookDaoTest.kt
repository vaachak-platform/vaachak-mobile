package org.vaachak.reader.core.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.vaachak.reader.core.domain.model.BookEntity

// --- Dummy Database for Testing ---
@Database(entities = [BookEntity::class], version = 1, exportSchema = false)
abstract class TestDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
}

@RunWith(AndroidJUnit4::class)
class BookDaoTest {

    private lateinit var database: TestDatabase
    private lateinit var dao: BookDao

    // --- Test Data Constants ---
    private val profile1 = "profile_piyush"
    private val profile2 = "profile_guest"

    private fun createDummyBook(
        hash: String,
        profileId: String,
        title: String = "Test Book",
        uri: String = "content://dummy/$hash",
        progress: Double = 0.0,
        updatedAt: Long = 1000L,
        isDirty: Boolean = false
    ) = BookEntity(
        bookHash = hash,
        profileId = profileId,
        title = title,
        localUri = uri,
        progress = progress,
        lastCfiLocation = "",
        lastRead = updatedAt,
        updatedAt = updatedAt,
        isDirty = isDirty,
        // Add any other required default fields from your actual BookEntity here
        coverPath = null,
        author = "Author"
    )

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, TestDatabase::class.java)
            // Allowing main thread queries just for isolated unit testing speed
            .allowMainThreadQueries()
            .build()
        dao = database.bookDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun `insertBook and getAllBooks validates multi-tenant isolation`() = runTest {
        // Given
        val book1 = createDummyBook(hash = "hash1", profileId = profile1)
        val book2 = createDummyBook(hash = "hash2", profileId = profile2) // Different profile

        // When
        dao.insertBook(book1)
        dao.insertBook(book2)

        // Then: Profile 1 should only see Book 1 (Testing Flow emission via Turbine)
        dao.getAllBooks(profile1).test {
            val items = awaitItem()
            assertEquals(1, items.size)
            assertEquals("hash1", items.first().bookHash)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `isBookExists returns true only for exact title and profile match`() = runTest {
        // Given
        val book = createDummyBook(hash = "hash1", profileId = profile1, title = "The Martian")
        dao.insertBook(book)

        // When & Then
        assertTrue(dao.isBookExists("The Martian", profile1))
        assertFalse(dao.isBookExists("The Martian", profile2)) // Wrong profile
        assertFalse(dao.isBookExists("Dune", profile1)) // Wrong title
    }

    @Test
    fun `updateBookProgressByUri updates progress and flags as dirty`() = runTest {
        // Given
        val uri = "content://book1"
        val book = createDummyBook(hash = "hash1", profileId = profile1, uri = uri, progress = 0.1, isDirty = false)
        dao.insertBook(book)

        // When
        val newTimestamp = 5000L
        dao.updateBookProgressByUri(
            localUri = uri,
            profileId = profile1,
            progress = 0.5,
            cfiLocation = "/4/2",
            timestamp = newTimestamp
        )

        // Then
        val updatedBook = dao.getBookByUri(uri, profile1)
        assertNotNull(updatedBook)
        assertEquals(0.5, updatedBook!!.progress, 0.0)
        assertEquals("/4/2", updatedBook.lastCfiLocation)
        assertEquals(newTimestamp, updatedBook.updatedAt)
        assertTrue(updatedBook.isDirty) // Crucial for offline-first sync logic!
    }

    @Test
    fun `updateBookMetadataFromSync applies incoming cloud data and clears dirty flag`() = runTest {
        // Given: A local book that hasn't been updated recently (updatedAt = 1000L)
        val book = createDummyBook(hash = "hash1", profileId = profile1, updatedAt = 1000L, isDirty = true)
        dao.insertBook(book)

        // When: A newer sync payload comes in from Cloudflare (timestamp = 2000L)
        val newCloudTimestamp = 2000L
        dao.updateBookMetadataFromSync(
            bookHash = "hash1",
            profileId = profile1,
            cfi = "/new/cfi",
            progress = 0.99,
            timestamp = newCloudTimestamp
        )

        // Then: The update is applied and the dirty flag is cleared
        val syncedBook = dao.getBookByHash("hash1", profile1)
        assertEquals(0.99, syncedBook!!.progress, 0.0)
        assertEquals("/new/cfi", syncedBook.lastCfiLocation)
        assertFalse(syncedBook.isDirty) // Flag cleared because cloud is source of truth
    }

    @Test
    fun `updateBookMetadataFromSync ignores stale cloud data`() = runTest {
        // Given: A local book that was updated VERY recently (updatedAt = 5000L)
        val book = createDummyBook(hash = "hash1", profileId = profile1, progress = 0.5, updatedAt = 5000L)
        dao.insertBook(book)

        // When: An OLDER sync payload comes in from Cloudflare (timestamp = 2000L)
        dao.updateBookMetadataFromSync(
            bookHash = "hash1",
            profileId = profile1,
            cfi = "/old/cfi",
            progress = 0.1,
            timestamp = 2000L
        )

        // Then: The update is IGNORED because local is newer
        val unchangedBook = dao.getBookByHash("hash1", profile1)
        assertEquals(0.5, unchangedBook!!.progress, 0.0) // Kept the local 50% progress
    }

    @Test
    fun `deleteBook strictly removes only the targeted profile's book`() = runTest {
        // Given
        dao.insertBook(createDummyBook(hash = "hash1", profileId = profile1))
        dao.insertBook(createDummyBook(hash = "hash1", profileId = profile2)) // Same book, diff user

        // When
        dao.deleteBook(bookHash = "hash1", profileId = profile1)

        // Then
        assertNull(dao.getBookByHash("hash1", profile1)) // Profile 1 deleted
        assertNotNull(dao.getBookByHash("hash1", profile2)) // Profile 2 intact
    }
}