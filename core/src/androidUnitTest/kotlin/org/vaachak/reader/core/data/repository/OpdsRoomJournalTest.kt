package org.vaachak.reader.core.data.repository

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.SQLiteMode
import org.vaachak.reader.core.data.local.AppDatabase
import org.vaachak.reader.core.data.local.OPDS_DRIVER_MIGRATION_2_3
import org.vaachak.reader.core.data.local.OPDS_MIGRATION_3_4
import org.vaachak.reader.core.domain.model.OpdsEntity
import org.vaachak.reader.core.domain.model.ProfileEntity
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class OpdsRoomJournalTest {
    private val catalog = OpdsEntity(id = 42, title = "Synthetic", url = "https://example.org/opds",
        username = "fixture-user", password = "fixture-password")

    private fun open(file: File): AppDatabase = Room.databaseBuilder<AppDatabase>(
        context = ApplicationProvider.getApplicationContext<Context>(), name = file.absolutePath
    ).setDriver(AndroidSQLiteDriver())
        .addMigrations(OPDS_DRIVER_MIGRATION_2_3, OPDS_MIGRATION_3_4).build()

    private suspend fun withFile(block: suspend (File) -> Unit) {
        val directory = Files.createTempDirectory("opds-room-fixture-").toFile()
        try { block(File(directory, "fixture.db")) } finally { directory.deleteRecursively() }
    }

    private fun sql(file: File, vararg statements: String) {
        val connection = AndroidSQLiteDriver().open(file.absolutePath)
        try { statements.forEach { connection.execSQL(it) } } finally { connection.close() }
    }

    @Test
    fun upgradeV3KeepsLegacyAndReaderProfileUntilVerifiedMigration() = runBlocking {
        withFile { file ->
            val initial = open(file)
            try {
                initial.opdsDao().insertFeed(catalog)
                initial.profileDao().insertProfile(ProfileEntity(profileId = "fixture-profile", name = "Fixture"))
            } finally { initial.close() }
            // Recreate the v3 OPDS table in the disposable test database. Older
            // Android SQLite versions do not support ALTER TABLE DROP COLUMN.
            // Keep every legacy catalog field and leave unrelated tables intact.
            sql(
                file,
                "DROP TABLE opds_credential_journal",
                """CREATE TABLE opds_feeds_v3 (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    title TEXT NOT NULL,
                    url TEXT NOT NULL,
                    username TEXT,
                    password TEXT,
                    isPredefined INTEGER NOT NULL
                )""".trimIndent(),
                """INSERT INTO opds_feeds_v3 (id, title, url, username, password, isPredefined)
                    SELECT id, title, url, username, password, isPredefined FROM opds_feeds""".trimIndent(),
                "DROP TABLE opds_feeds",
                "ALTER TABLE opds_feeds_v3 RENAME TO opds_feeds",
                "PRAGMA user_version = 3"
            )
            val upgraded = open(file)
            try {
                val dao = upgraded.opdsCredentialJournalDao()
                assertEquals(catalog, dao.load(catalog.id))
                assertEquals(listOf(catalog.id), dao.legacyIds())
                assertEquals("Fixture", upgraded.profileDao().getProfileById("fixture-profile")?.name)
                val journal = RoomOpdsCredentialJournal(upgraded)
                val backend = FakeBackend()
                val storage = OpdsCredentialStorage(backend)
                assertEquals(OpdsMigrationOutcome.MIGRATED, OpdsCredentialMigration(journal, storage).migrate(catalog.id))
                val row = requireNotNull(journal.load(catalog.id))
                assertFalse(row.hasLegacy)
                val ref = requireNotNull(row.activeRef)
                assertEquals("ACTIVE", dao.entry(catalog.id, ref.generation)?.state)
                assertEquals("fixture-password", storage.readForRequest(catalog.id, ref, catalog.url, catalog.url)?.password)
                assertTrue(dao.legacyIds().isEmpty())
            } finally { upgraded.close() }
        }
    }

    @Test
    fun staleSnapshotAndAbandonedReservationCannotPublish() = runBlocking {
        withFile { file ->
            val db = open(file)
            try {
                db.opdsDao().insertFeed(catalog)
                val journal = RoomOpdsCredentialJournal(db)
                val old = requireNotNull(journal.load(catalog.id))
                val ref = OpdsCredentialRef.create(catalog.id)
                assertTrue(journal.reserve(old, ref))
                assertFalse(journal.reserve(old, ref))
                db.opdsDao().updateFeed(catalog.copy(url = "https://other.example/opds"))
                assertFalse(journal.publish(old, ref))
                journal.abandon(ref)
                val new = requireNotNull(journal.load(catalog.id))
                assertFalse(journal.publish(new, ref))
                assertTrue(new.hasLegacy)
                assertEquals("RETIRED", db.opdsCredentialJournalDao().entry(catalog.id, ref.generation)?.state)
            } finally { db.close() }
        }
    }

    @Test
    fun delayedAbandonCannotRetireActiveAndReplacementRetiresOld() = runBlocking {
        withFile { file ->
            val db = open(file)
            try {
                db.opdsDao().insertFeed(catalog)
                val journal = RoomOpdsCredentialJournal(db)
                val dao = db.opdsCredentialJournalDao()
                val first = requireNotNull(journal.load(catalog.id))
                val ref1 = OpdsCredentialRef.create(catalog.id)
                assertTrue(journal.reserve(first, ref1))
                assertTrue(journal.publish(first, ref1))
                journal.abandon(ref1)
                assertEquals("ACTIVE", dao.entry(catalog.id, ref1.generation)?.state)
                val current = requireNotNull(journal.load(catalog.id))
                val ref2 = OpdsCredentialRef.create(catalog.id)
                assertTrue(journal.reserve(current, ref2))
                assertTrue(journal.publish(current, ref2))
                assertEquals(ref2, journal.load(catalog.id)?.activeRef)
                assertEquals("ACTIVE", dao.entry(catalog.id, ref2.generation)?.state)
                assertEquals(listOf(ref1.generation), dao.retiredCandidates().map { it.generation })
            } finally { db.close() }
        }
    }

    @Test
    fun journalTransitionFailureRollsBackPlaintextRemoval() = runBlocking {
        withFile { file ->
            val initial = open(file)
            try { initial.opdsDao().insertFeed(catalog) } finally { initial.close() }
            sql(file, """CREATE TRIGGER fixture_reject_activation BEFORE UPDATE OF state ON opds_credential_journal
                WHEN NEW.state = 'ACTIVE' BEGIN SELECT RAISE(ABORT, 'fixture failure'); END""".trimIndent())
            val db = open(file)
            try {
                val journal = RoomOpdsCredentialJournal(db)
                val expected = requireNotNull(journal.load(catalog.id))
                val ref = OpdsCredentialRef.create(catalog.id)
                assertTrue(journal.reserve(expected, ref))
                var failed = false
                try { journal.publish(expected, ref) } catch (_: Exception) { failed = true }
                assertTrue(failed)
                val after = requireNotNull(journal.load(catalog.id))
                assertTrue(after.hasLegacy)
                assertNull(after.activeRef)
                assertEquals("RESERVED", db.opdsCredentialJournalDao().entry(catalog.id, ref.generation)?.state)
            } finally { db.close() }
        }
    }

    private class FakeBackend : OpdsSecretBackend {
        private val values = mutableMapOf<String, String>()
        override fun read(id: String): String = values[id].orEmpty()
        override fun write(id: String, value: String) { values[id] = value }
        override fun ensureDurable(id: String) { check(id in values) }
        override fun clear(id: String) { values.remove(id) }
    }
}
