package org.vaachak.reader.core.data.repository

import android.app.KeyguardManager
import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeNoException
import org.junit.Test
import org.junit.runner.RunWith
import org.vaachak.reader.core.data.local.AppDatabase
import org.vaachak.reader.core.domain.model.OpdsEntity
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Disposable fixture database and test-owned generation UUIDs only. Never opens the app database. */
@RunWith(AndroidJUnit4::class)
class OpdsCredentialLifecycleInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val strictCi get() = InstrumentationRegistry.getArguments().getString("vaachakAuthCi") == "true"
    private val expectNoLock get() = InstrumentationRegistry.getArguments().getString("vaachakExpectNoLock") == "true"
    private val secureLock get() = (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure
    private val url = "https://catalog.example/opds"

    @Test fun realRoomAndAuthenticatedStorageMigrateAndKeepOrRemoveGeneration() = runBlocking {
        if (expectNoLock) check(!secureLock) { "No-lock acceptance requires a disposable device without secure lock" }
        val dir = File(context.cacheDir, "opds-fixture-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val file = File(dir, "fixture.db")
        val secrets = PrivateGatewaySecrets(context)
        val storage = AndroidOpdsCredentialStorage(secrets).storage
        val db = Room.databaseBuilder<AppDatabase>(context, file.absolutePath).setDriver(BundledSQLiteDriver()).build()
        val dao = db.opdsCredentialJournalDao()
        val lifecycle = OpdsCredentialLifecycle(dao, storage, RoomOpdsCredentialJournal(db))
        val legacy = OpdsEntity(id = 42, title = "Synthetic", url = url, username = "fixture-user", password = "fixture-password")
        try {
            // No production database is touched; only the historical fixture uses this raw DAO.
            db.opdsDao().insertFeed(legacy)
            check(runCatching { lifecycle.readForRequest(OpdsCatalogSession(legacy), url) }.isFailure)
            val result = lifecycle.migrateAll()
            if (!secureLock) {
                check(!strictCi) { "CI must provision and unlock the disposable PIN device" }
                check(result == OpdsMigrationReport(0, 1) && dao.load(42) == legacy)
                val public = lifecycle.create("Public", url, OpdsCredentialEdit.Remove)
                check(lifecycle.readForRequest(OpdsCatalogSession(checkNotNull(dao.load(public))), url) == null)
                check(runCatching { lifecycle.create("Locked", url, OpdsCredentialEdit.Replace(OpdsCredentialPair("new-user", "new-password"))) }.isFailure)
                check(db.opdsDao().getFeedsCount() == 2)
                return@runBlocking
            }
            if (result != OpdsMigrationReport(1, 0)) {
                check(!strictCi) { "Authenticated migration must succeed in strict CI" }
                // Not counted as positive device evidence when authorization is unavailable.
                assumeNoException("Authenticate with device PIN before positive test", IllegalStateException("Migration unavailable"))
            }
            check(dao.legacyIds().isEmpty())
            val active = checkNotNull(dao.load(42))
            val ref = OpdsCredentialRef(active.id, checkNotNull(active.credentialGeneration))
            check(active.username == null && active.password == null && dao.entry(active.id, ref.generation)?.state == "ACTIVE")
            check(lifecycle.readForRequest(OpdsCatalogSession(active), url)?.password == legacy.password)
            check(lifecycle.readForRequest(OpdsCatalogSession(active), "https://other.example/book") == null)
            val hash = MessageDigest.getInstance("SHA-256").digest(ref.storageId().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            val ciphertext = File(context.noBackupFilesDir, "private_ai_$hash.enc")
            check(ciphertext.isFile && ciphertext.readBytes().take(4).toByteArray().contentEquals("VAG2".toByteArray(Charsets.US_ASCII)))
            check(!ciphertext.readText(Charsets.ISO_8859_1).contains("fixture-password"))
            lifecycle.update(OpdsCatalogSession(active), "Renamed", url, OpdsCredentialEdit.Keep)
            val kept = checkNotNull(dao.load(42))
            check(kept.credentialGeneration == ref.generation)
            lifecycle.update(OpdsCatalogSession(kept), "Anonymous", url, OpdsCredentialEdit.Remove)
            check(dao.entry(42, ref.generation)?.state == "RETIRED")
            check(lifecycle.readForRequest(OpdsCatalogSession(checkNotNull(dao.load(42))), url) == null)
            check(ciphertext.isFile) // Production intentionally defers physical retirement.
        } finally {
            // All these generation references belong exclusively to the disposable test database.
            try {
                val refs = dao.retiredCandidates() + dao.interruptedCandidates()
                for (entry in refs) storage.retire(OpdsCredentialRef(entry.catalogId, entry.generation))
                dao.load(42)?.credentialGeneration?.let { storage.retire(OpdsCredentialRef(42, it)) }
            } finally { db.close(); dir.deleteRecursively() }
        }
    }
}
