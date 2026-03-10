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
import org.vaachak.reader.core.domain.model.OpdsEntity

@Database(entities = [OpdsEntity::class], version = 1, exportSchema = false)
abstract class OpdsTestDatabase : RoomDatabase() {
    abstract fun opdsDao(): OpdsDao
}

@RunWith(AndroidJUnit4::class)
class OpdsDaoTest {

    private lateinit var database: OpdsTestDatabase
    private lateinit var dao: OpdsDao

    private fun createDummyFeed(
        id: Long = 0L,
        title: String,
        url: String,
        isPredefined: Boolean = false
    ) = OpdsEntity(id = id, title = title, url = url, isPredefined = isPredefined)

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, OpdsTestDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.opdsDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun `getAllFeeds sorts predefined feeds first, then alphabetically`() = runTest {
        dao.insertFeed(createDummyFeed(id = 1, title = "Zebra Feed", url = "url1", isPredefined = false))
        dao.insertFeed(createDummyFeed(id = 2, title = "Project Gutenberg", url = "url2", isPredefined = true)) // Should be 1st
        dao.insertFeed(createDummyFeed(id = 3, title = "Apple Feed", url = "url3", isPredefined = false)) // Should be 2nd

        dao.getAllFeeds().test {
            val feeds = awaitItem()
            assertEquals(3, feeds.size)
            assertEquals("Project Gutenberg", feeds[0].title) // Predefined wins
            assertEquals("Apple Feed", feeds[1].title)        // Alphabetical second
            assertEquals("Zebra Feed", feeds[2].title)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `getFeedsCount returns accurate total`() = runTest {
        assertEquals(0, dao.getFeedsCount())
        dao.insertFeed(createDummyFeed(title = "Test", url = "url1"))
        assertEquals(1, dao.getFeedsCount())
    }
}