package org.vaachak.reader.core.data.repository

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.SQLiteMode
import org.vaachak.reader.core.data.local.AppDatabase
import org.vaachak.reader.core.data.local.OPDS_DRIVER_MIGRATION_2_3
import org.vaachak.reader.core.data.local.OPDS_MIGRATION_3_4
import org.vaachak.reader.core.domain.model.ProfileEntity
import org.vaachak.reader.core.domain.model.OpdsEntity
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Same AndroidSQLiteDriver and native SQLite configuration as the accepted Room journal tests. */
@RunWith(AndroidJUnit4::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class OpdsRoomLifecycleTest {
    private val url = "https://catalog.example/opds"
    private val row = OpdsEntity(title = "Synthetic", url = url)
    private val pair = OpdsCredentialPair("fixture-user", "fixture-password")

    private fun open(file: File): AppDatabase = Room.databaseBuilder<AppDatabase>(
        context = ApplicationProvider.getApplicationContext<Context>(), name = file.absolutePath
    ).setDriver(AndroidSQLiteDriver()).addMigrations(OPDS_DRIVER_MIGRATION_2_3, OPDS_MIGRATION_3_4).build()

    private suspend fun fixture(block: suspend (File) -> Unit) {
        val directory = Files.createTempDirectory("opds-room-lifecycle-").toFile()
        try { block(File(directory, "fixture.db")) } finally { directory.deleteRecursively() }
    }

    @Test fun pendingCreateIsInvisibleUntilAtomicPublication() = runBlocking {
        fixture { file ->
            val db = open(file)
            try {
                val dao = db.opdsCredentialJournalDao()
                val visible = db.opdsDao()
                val generation = UUID.randomUUID().toString()
                val id = dao.reserveCreate(row, generation)
                val pending = checkNotNull(dao.load(id))
                check(pending.username == null && pending.password == null)
                check(dao.entry(id, generation)?.state == "PENDING_CREATE")
                check(visible.getAllFeeds().first().isEmpty() && visible.getFeedsCount() == 0)
                check(visible.getFeedById(id) == null && visible.getFeedByUrl(url) == null)
                val storage = OpdsCredentialStorage(FakeOpdsSecretBackend())
                storage.writeVerified(OpdsCredentialRef(id, generation), url, pair)
                check(dao.publishCreate(pending))
                check(visible.getAllFeeds().first() == listOf(pending) && visible.getFeedsCount() == 1)
                check(visible.getFeedById(id) == pending && visible.getFeedByUrl(url) == pending)
                check(dao.entry(id, generation)?.state == "ACTIVE" && !dao.discardPending(id, generation))
            } finally { db.close() }
        }
    }

    @Test fun interruptedCreateCanBeRecoveredAfterDatabaseReopen() = runBlocking {
        fixture { file ->
            val backend = FakeOpdsSecretBackend()
            val storage = OpdsCredentialStorage(backend)
            val generation = UUID.randomUUID().toString()
            val initial = open(file)
            val id = try {
                val reserved = initial.opdsCredentialJournalDao().reserveCreate(row, generation)
                storage.writeVerified(OpdsCredentialRef(reserved, generation), url, pair)
                reserved
            } finally { initial.close() }
            val reopened = open(file)
            try {
                val dao = reopened.opdsCredentialJournalDao()
                check(reopened.opdsDao().getFeedsCount() == 0)
                val lifecycle = OpdsCredentialLifecycle(dao, storage, RoomOpdsCredentialJournal(reopened))
                check(lifecycle.recoverInterrupted() == 1)
                check(dao.load(id) == null && dao.entry(id, generation)?.state == "RETIRED")
                check(backend.clears == 0 && backend.values.size == 1)
                check(lifecycle.recoverInterrupted() == 0)
                val freshId = lifecycle.create("Public", url, OpdsCredentialEdit.Remove)
                check(freshId > id && reopened.opdsDao().getFeedsCount() == 1)
            } finally { reopened.close() }
        }
    }

    @Test fun replacementRetirementFailureRollsBackMetadataAndBothJournalTransitions() = runBlocking {
        fixture { file ->
            val backend = FakeOpdsSecretBackend()
            val storage = OpdsCredentialStorage(backend)
            val initial = open(file)
            val original = try {
                val dao = initial.opdsCredentialJournalDao()
                val lifecycle = OpdsCredentialLifecycle(dao, storage, RoomOpdsCredentialJournal(initial))
                val id = lifecycle.create(row.title, url, OpdsCredentialEdit.Replace(pair))
                checkNotNull(dao.load(id))
            } finally { initial.close() }
            val connection = AndroidSQLiteDriver().open(file.absolutePath)
            try {
                connection.execSQL("""CREATE TRIGGER fixture_reject_retire BEFORE UPDATE OF state ON opds_credential_journal
                    WHEN NEW.state = 'RETIRED' BEGIN SELECT RAISE(ABORT, 'fixture failure'); END""".trimIndent())
            } finally { connection.close() }
            val reopened = open(file)
            try {
                val dao = reopened.opdsCredentialJournalDao()
                val lifecycle = OpdsCredentialLifecycle(dao, storage, RoomOpdsCredentialJournal(reopened))
                val failure = runCatching {
                    lifecycle.update(OpdsCatalogSession(original), "Changed", "https://other.example/opds",
                        OpdsCredentialEdit.Replace(OpdsCredentialPair("new-user", "new-pass")))
                }.exceptionOrNull()
                check(failure is OpdsLifecycleException && failure.cause == null)
                check(dao.load(original.id) == original)
                check(dao.entry(original.id, checkNotNull(original.credentialGeneration))?.state == "ACTIVE")
                check(dao.interruptedCandidates().single().state == "RESERVED")
                check(lifecycle.readForRequest(OpdsCatalogSession(original), url)?.password == pair.password)
                check(backend.clears == 0)
            } finally { reopened.close() }
        }
    }

    @Test fun removeAndDeleteRetireGenerationsWithoutCrossCatalogEffects() = runBlocking {
        fixture { file ->
            val db = open(file)
            try {
                val dao = db.opdsCredentialJournalDao()
                val backend = FakeOpdsSecretBackend()
                val lifecycle = OpdsCredentialLifecycle(dao, OpdsCredentialStorage(backend), RoomOpdsCredentialJournal(db))
                val first = checkNotNull(dao.load(lifecycle.create("First", url, OpdsCredentialEdit.Replace(pair))))
                val second = checkNotNull(dao.load(lifecycle.create("Second", url, OpdsCredentialEdit.Replace(pair))))
                backend.rejectRead = true
                lifecycle.update(OpdsCatalogSession(first), "Public", url, OpdsCredentialEdit.Remove)
                check(dao.load(first.id)?.credentialGeneration == null)
                check(dao.load(second.id) == second)
                lifecycle.delete(OpdsCatalogSession(second))
                check(dao.load(second.id) == null)
                check(dao.retiredCandidates().map { it.catalogId }.toSet() == setOf(first.id, second.id))
                check(db.opdsDao().getAllFeeds().first().single().id == first.id && backend.clears == 0)
            } finally { db.close() }
        }
    }

    @Test fun syntheticVersionTwoShapeUpgradesAndMigratesWithoutLosingProfile() = runBlocking {
        fixture { file ->
            val legacy = row.copy(id = 42, username = pair.username, password = pair.password)
            val initial = open(file)
            try {
                initial.opdsDao().insertFeed(legacy)
                initial.profileDao().insertProfile(ProfileEntity(profileId = "fixture-profile", name = "Fixture"))
            } finally { initial.close() }
            // Synthetic v2-compatible column shape, not a released-app fixture.
            val connection = AndroidSQLiteDriver().open(file.absolutePath)
            try {
                listOf("DROP TABLE opds_credential_journal", """CREATE TABLE opds_feeds_v2 (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, url TEXT NOT NULL,
                    username TEXT, password TEXT, isPredefined INTEGER NOT NULL DEFAULT 0)""".trimIndent(),
                    """INSERT INTO opds_feeds_v2 (id,title,url,username,password,isPredefined)
                        SELECT id,title,url,username,password,isPredefined FROM opds_feeds""".trimIndent(),
                    "DROP TABLE opds_feeds", "ALTER TABLE opds_feeds_v2 RENAME TO opds_feeds", "PRAGMA user_version = 2"
                ).forEach { connection.execSQL(it) }
            } finally { connection.close() }
            val upgraded = open(file)
            try {
                val dao = upgraded.opdsCredentialJournalDao()
                check(dao.load(42) == legacy)
                check(upgraded.profileDao().getProfileById("fixture-profile")?.name == "Fixture")
                val lifecycle = OpdsCredentialLifecycle(dao, OpdsCredentialStorage(FakeOpdsSecretBackend()), RoomOpdsCredentialJournal(upgraded))
                check(lifecycle.migrateAll() == OpdsMigrationReport(1, 0))
                val active = checkNotNull(dao.load(42))
                check(active.username == null && active.password == null)
                check(lifecycle.readForRequest(OpdsCatalogSession(active), url)?.password == pair.password)
            } finally { upgraded.close() }
        }
    }
}
