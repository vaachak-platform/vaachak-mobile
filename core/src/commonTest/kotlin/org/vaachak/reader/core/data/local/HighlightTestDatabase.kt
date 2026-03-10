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
import org.vaachak.reader.core.domain.model.HighlightEntity
import java.util.UUID

@Database(entities = [HighlightEntity::class], version = 1, exportSchema = false)
abstract class HighlightTestDatabase : RoomDatabase() {
    abstract fun highlightDao(): HighlightDao
}

@RunWith(AndroidJUnit4::class)
class HighlightDaoTest {

    private lateinit var database: HighlightTestDatabase
    private lateinit var dao: HighlightDao

    private val profile1 = "profile_piyush"
    private val profile2 = "profile_guest"

    private fun createDummyHighlight(
        profileId: String,
        bookHashId: String = "book_1",
        tag: String = "note",
        id: String = UUID.randomUUID().toString()
    ) = HighlightEntity(
        id = id,
        bookHashId = bookHashId,
        profileId = profileId,
        locatorJson = "{}",
        text = "Sample text",
        color = 0,
        tag = tag,
        created = 1000L
    )

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, HighlightTestDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.highlightDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun `getHighlightsForBook strictly isolates by profileId`() = runTest {
        dao.insertHighlight(createDummyHighlight(profileId = profile1, bookHashId = "book_1"))
        dao.insertHighlight(createDummyHighlight(profileId = profile2, bookHashId = "book_1")) // Same book, diff user

        dao.getHighlightsForBook("book_1", profile1).test {
            val items = awaitItem()
            assertEquals(1, items.size)
            assertEquals(profile1, items.first().profileId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `getBooksWithBookmarks retrieves only books marked with BOOKMARK tag for specific profile`() = runTest {
        dao.insertHighlight(createDummyHighlight(profileId = profile1, bookHashId = "book_1", tag = "BOOKMARK"))
        dao.insertHighlight(createDummyHighlight(profileId = profile1, bookHashId = "book_2", tag = "note")) // Wrong tag
        dao.insertHighlight(createDummyHighlight(profileId = profile2, bookHashId = "book_3", tag = "BOOKMARK")) // Wrong profile

        dao.getBooksWithBookmarks(profile1).test {
            val bookHashes = awaitItem()
            assertEquals(1, bookHashes.size)
            assertEquals("book_1", bookHashes.first())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deleteHighlightById removes specific record`() = runTest {
        val id = UUID.randomUUID().toString()
        dao.insertHighlight(createDummyHighlight(profileId = profile1, id = id))

        dao.deleteHighlightById(id)

        dao.getAllHighlights(profile1).test {
            assertTrue(awaitItem().isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }
}