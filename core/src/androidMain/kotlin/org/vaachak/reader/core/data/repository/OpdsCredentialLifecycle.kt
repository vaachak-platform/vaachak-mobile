package org.vaachak.reader.core.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.vaachak.reader.core.data.local.OpdsCredentialJournalDao
import org.vaachak.reader.core.domain.model.OpdsEntity
import java.util.UUID

/** No generated string representation containing the supplied pair. */
internal sealed interface OpdsCredentialEdit {
    data object Keep : OpdsCredentialEdit
    data object Remove : OpdsCredentialEdit
    class Replace(val pair: OpdsCredentialPair) : OpdsCredentialEdit {
        override fun toString(): String = "OpdsCredentialEdit.Replace(REDACTED)"
    }
}

internal class OpdsLifecycleException : IllegalStateException(
    "Catalog credentials could not be saved or accessed. Refresh the catalog and retry after device authentication."
)

/**
 * Single app-process owner of catalog mutations, migration, recovery and request dispatch. Retired ciphertext is intentionally
 * retained until database commit durability and durable deletion have been validated.
 */
internal class OpdsCredentialLifecycle(
    private val dao: OpdsCredentialJournalDao,
    private val storage: OpdsCredentialStorage,
    journal: OpdsCredentialJournal
) {
    private val mutex = Mutex()
    private val migration = OpdsCredentialMigration(journal, storage, mutex)

    suspend fun prepareCatalogs() = locked {
        recoverInterruptedLocked()
        dao.seedDefaultsIfEmpty()
    }

    suspend fun create(title: String, url: String, edit: OpdsCredentialEdit): Long = locked {
        httpsOrigin(url)
        val row = OpdsEntity(title = title, url = url)
        when (edit) {
            OpdsCredentialEdit.Keep -> throw OpdsLifecycleException()
            OpdsCredentialEdit.Remove -> dao.createAnonymous(row)
            is OpdsCredentialEdit.Replace -> createAuthenticated(row, edit.pair)
        }
    }

    private suspend fun createAuthenticated(row: OpdsEntity, pair: OpdsCredentialPair): Long {
        val generation = UUID.randomUUID().toString()
        val id = dao.reserveCreate(row, generation)
        val ref = OpdsCredentialRef(id, generation)
        val pending = row.copy(id = id, credentialGeneration = generation)
        // A failure/cancellation can occur after a durable file write or database commit.
        // Do not guess whether publication committed. Recovery checks the journal state.
        try {
            storage.writeVerified(ref, row.url, pair)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: OpdsCredentialStorageException) {
            dao.discardPending(id, generation)
            throw failure
        }
        val published = try {
            dao.publishCreate(pending)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // An exception after a successful commit is not a failed save.
            if (dao.load(id) == pending && dao.entry(id, generation)?.state == "ACTIVE") true
            else throw OpdsLifecycleException()
        }
        if (!published) {
            dao.discardPending(id, generation)
            throw OpdsLifecycleException()
        }
        return id
    }

    suspend fun update(session: OpdsCatalogSession, title: String, url: String, edit: OpdsCredentialEdit) = locked {
        val row = current(session, editor = true)
        val newOrigin = httpsOrigin(url)
        requireActiveOrLegacy(row)
        val changed = when (edit) {
            OpdsCredentialEdit.Keep -> {
                if (hasCredentials(row) && httpsOrigin(row.url) != newOrigin) throw OpdsLifecycleException()
                dao.keepMetadata(row, title, url)
            }
            OpdsCredentialEdit.Remove -> dao.removeCredentials(row, title, url)
            is OpdsCredentialEdit.Replace -> replace(row, title, url, edit.pair)
        }
        if (!changed) throw OpdsLifecycleException()
    }

    private suspend fun replace(row: OpdsEntity, title: String, url: String, pair: OpdsCredentialPair): Boolean {
        val ref = OpdsCredentialRef.create(row.id)
        if (!dao.reserve(row.id, row.url, row.username, row.password,
                row.credentialGeneration, ref.generation)) return false
        try {
            storage.writeVerified(ref, url, pair)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: OpdsCredentialStorageException) {
            dao.abandon(row.id, ref.generation)
            throw failure
        }
        val published = try {
            dao.publishReplacement(row, title, url, ref.generation)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            val after = row.copy(title = title, url = url, username = null,
                password = null, credentialGeneration = ref.generation)
            val previousRetired = row.credentialGeneration?.let { dao.entry(row.id, it)?.state == "RETIRED" } ?: true
            if (dao.load(row.id) == after && dao.entry(row.id, ref.generation)?.state == "ACTIVE" && previousRetired) true
            else throw OpdsLifecycleException()
        }
        if (!published) dao.abandon(row.id, ref.generation)
        return published
    }

    suspend fun delete(session: OpdsCatalogSession) = locked {
        val row = current(session, editor = true)
        requireActiveOrLegacy(row)
        if (!dao.deleteCatalog(row)) throw OpdsLifecycleException()
    }

    /** Request-time read: never send legacy plaintext or downgrade a same-origin locked account. */
    suspend fun readForRequest(session: OpdsCatalogSession, requestedUrl: String): OpdsCredentialPair? = locked {
        readLocked(session, requestedUrl)
    }

    /** Hold mutation lock through request construction and response headers. Already-issued body
     * transfers may finish with the original selection; no later request can reuse a stale row. */
    suspend fun <T> dispatch(session: OpdsCatalogSession, requestedUrl: String, send: (OpdsCredentialPair?) -> T): T = locked {
        send(readLocked(session, requestedUrl))
    }

    private suspend fun readLocked(session: OpdsCatalogSession, requestedUrl: String): OpdsCredentialPair? {
        val origin = httpsOrigin(session.catalogUrl)
        val destination = httpsOrigin(requestedUrl)
        val row = current(session)
        requireActiveOrLegacy(row)
        if (origin != destination) return null
        if (row.username != null || row.password != null) throw OpdsLifecycleException()
        val generation = row.credentialGeneration ?: return null
        return storage.readForRequest(row.id, OpdsCredentialRef(row.id, generation), row.url, requestedUrl)
    }

    /** Explicit migration only; do not call while legacy CRUD or the old editor is active. */
    suspend fun migrate(id: Long): OpdsMigrationOutcome = sanitized { migration.migrate(id) }

    suspend fun migrateAll(): OpdsMigrationReport {
        val ids = locked {
            recoverInterruptedLocked()
            dao.legacyIds()
        }
        var migrated = 0
        // Each migration shares the same mutation mutex. Concurrent edits recheck the row.
        for (id in ids) {
            val outcome = try { migrate(id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { OpdsMigrationOutcome.RETRY_REQUIRED }
            if (outcome == OpdsMigrationOutcome.MIGRATED) migrated++
        }
        return OpdsMigrationReport(migrated, locked { dao.legacyIds().size })
    }

    /** Safe to call only under the single-writer invariant. No ciphertext is deleted here. */
    suspend fun recoverInterrupted(): Int = locked { recoverInterruptedLocked() }

    private suspend fun recoverInterruptedLocked(): Int {
        var retired = 0
        for (entry in dao.interruptedCandidates()) {
            val done = when (entry.state) {
                "PENDING_CREATE" -> dao.discardPending(entry.catalogId, entry.generation)
                "RESERVED" -> dao.abandon(entry.catalogId, entry.generation) == 1
                else -> false
            }
            if (!done) throw OpdsLifecycleException()
            retired++
        }
        return retired
    }

    private suspend fun current(session: OpdsCatalogSession, editor: Boolean = false): OpdsEntity {
        if (session.catalogId <= 0) throw OpdsLifecycleException()
        val row = dao.load(session.catalogId)
        if (row == null || !(if (editor) session.matchesEditor(row) else session.matches(row))) throw OpdsLifecycleException()
        return row
    }

    private suspend fun requireActiveOrLegacy(row: OpdsEntity) {
        val generation = row.credentialGeneration ?: return
        OpdsCredentialRef(row.id, generation) // validate the pointer before use or retirement
        if (dao.entry(row.id, generation)?.state != "ACTIVE" || row.username != null || row.password != null) {
            throw OpdsLifecycleException()
        }
    }

    private fun hasCredentials(row: OpdsEntity): Boolean =
        row.username != null || row.password != null || row.credentialGeneration != null

    private suspend fun <T> locked(block: suspend () -> T): T = sanitized { mutex.withLock { block() } }

    private suspend fun <T> sanitized(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: OpdsCredentialStorageException) {
        throw failure
    } catch (failure: OpdsLifecycleException) {
        throw failure
    } catch (_: Exception) {
        // DB and backend exceptions may include bound values. No raw cause escapes this API.
        throw OpdsLifecycleException()
    }
}
