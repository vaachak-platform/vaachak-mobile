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
import org.vaachak.reader.core.domain.model.ProfileEntity

@Database(entities = [ProfileEntity::class], version = 1, exportSchema = false)
abstract class ProfileTestDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
}

@RunWith(AndroidJUnit4::class)
class ProfileDaoTest {

    private lateinit var database: ProfileTestDatabase
    private lateinit var dao: ProfileDao

    private fun createDummyProfile(
        id: String,
        name: String = "Test User",
        timestamp: Long = 1000L,
        pinHash: String? = null
    ) = ProfileEntity(
        profileId = id,
        name = name,
        isGuest = false,
        pinHash = pinHash,
        avatarColorHex = "#FFFFFF",
        lastActiveTimestamp = timestamp
    )

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ProfileTestDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.profileDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun `insertProfile and getAllProfiles sorts by lastActiveTimestamp descending`() = runTest {
        val olderProfile = createDummyProfile(id = "user1", timestamp = 1000L)
        val newerProfile = createDummyProfile(id = "user2", timestamp = 5000L)

        dao.insertProfile(olderProfile)
        dao.insertProfile(newerProfile)

        dao.getAllProfiles().test {
            val profiles = awaitItem()
            assertEquals(2, profiles.size)
            // The newest profile should be first in the list
            assertEquals("user2", profiles[0].profileId)
            assertEquals("user1", profiles[1].profileId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `updateLastActive modifies timestamp successfully`() = runTest {
        dao.insertProfile(createDummyProfile(id = "user1", timestamp = 1000L))

        // When
        val newTime = 9999L
        dao.updateLastActive("user1", newTime)

        // Then
        val updatedProfile = dao.getProfileById("user1")
        assertEquals(newTime, updatedProfile?.lastActiveTimestamp)
    }

    @Test
    fun `updatePin successfully changes or removes pinHash`() = runTest {
        dao.insertProfile(createDummyProfile(id = "user1", pinHash = "oldHash"))

        // Update to new hash
        dao.updatePin("user1", "newHash")
        assertEquals("newHash", dao.getProfileById("user1")?.pinHash)

        // Remove PIN (set to null)
        dao.updatePin("user1", null)
        assertNull(dao.getProfileById("user1")?.pinHash)
    }
}