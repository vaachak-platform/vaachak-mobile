package org.vaachak.reader.core.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.vaachak.reader.core.data.local.OpdsCredentialJournalDao
import org.vaachak.reader.core.data.local.OpdsCredentialJournalEntity
import org.vaachak.reader.core.domain.model.OpdsEntity

/** Real DAO method bodies; in-memory row operations are not Room transaction tests. */
internal class MemoryLifecycleDao : OpdsCredentialJournalDao() {
    val rows = linkedMapOf<Long, OpdsEntity>()
    val entries = linkedMapOf<Pair<Long, String>, OpdsCredentialJournalEntity>()
    private var nextId = 1L
    var failAfterCreateCommit = false
    var failAfterReplacementCommit = false
    var loadFailure: Exception? = null
    override suspend fun rowCount() = rows.size

    override suspend fun load(id: Long): OpdsEntity? {
        loadFailure?.let { throw it }
        return rows[id]
    }
    override suspend fun legacyIds() = rows.values.filter { it.username != null || it.password != null }.map { it.id }
    override suspend fun entry(id: Long, generation: String) = entries[id to generation]
    override suspend fun insert(entry: OpdsCredentialJournalEntity) {
        check(entries.put(entry.catalogId to entry.generation, entry) == null)
    }
    override suspend fun compareAndPublish(id: Long, url: String, username: String?, password: String?,
        previous: String?, generation: String): Int {
        val row = rows[id] ?: return 0
        if (row.url != url || row.username != username || row.password != password ||
            row.credentialGeneration != previous) return 0
        rows[id] = row.copy(username = null, password = null, credentialGeneration = generation)
        return 1
    }
    override suspend fun transition(id: Long, generation: String, previous: String, next: String): Int {
        val row = entry(id, generation) ?: return 0
        if (row.state != previous) return 0
        entries[id to generation] = row.copy(state = next)
        return 1
    }
    override suspend fun abandon(id: Long, generation: String): Int {
        if (rows[id]?.credentialGeneration == generation) return 0
        return transition(id, generation, "RESERVED", "RETIRED")
    }
    override suspend fun retiredCandidates() = entries.values.filter {
        it.state == "RETIRED" && rows[it.catalogId]?.credentialGeneration != it.generation
    }
    override suspend fun insertCatalogRow(row: OpdsEntity): Long {
        val id = nextId++
        rows[id] = row.copy(id = id)
        return id
    }
    override suspend fun updateCatalogRow(row: OpdsEntity): Int {
        if (row.id !in rows) return 0
        rows[row.id] = row
        return 1
    }
    override suspend fun deleteCatalogRow(row: OpdsEntity) = if (rows.remove(row.id) != null) 1 else 0
    override suspend fun updateMetadata(id: Long, title: String, url: String): Int {
        val row = rows[id] ?: return 0
        rows[id] = row.copy(title = title, url = url)
        return 1
    }
    override suspend fun interruptedCandidates() = entries.values.filter { it.state in setOf("RESERVED", "PENDING_CREATE") }

    override suspend fun publishCreate(expected: OpdsEntity): Boolean {
        val result = super.publishCreate(expected)
        if (result && failAfterCreateCommit) error("synthetic-sensitive-db-error")
        return result
    }
    override suspend fun publishReplacement(expected: OpdsEntity, title: String, url: String, generation: String): Boolean {
        val result = super.publishReplacement(expected, title, url, generation)
        if (result && failAfterReplacementCommit) error("synthetic-sensitive-db-error")
        return result
    }
}

internal class MemoryMigrationJournal(private val dao: MemoryLifecycleDao) : OpdsCredentialJournal {
    override suspend fun load(catalogId: Long) = dao.load(catalogId)?.let {
        OpdsCredentialSnapshot(it.id, it.url, it.username, it.password,
            it.credentialGeneration?.let { generation -> OpdsCredentialRef(catalogId, generation) })
    }
    override suspend fun reserve(expected: OpdsCredentialSnapshot, ref: OpdsCredentialRef) =
        dao.reserve(expected.catalogId, expected.catalogUrl, expected.legacyUsername, expected.legacyPassword,
            expected.activeRef?.generation, ref.generation)
    override suspend fun publish(expected: OpdsCredentialSnapshot, ref: OpdsCredentialRef) =
        dao.publish(expected.catalogId, expected.catalogUrl, expected.legacyUsername, expected.legacyPassword,
            expected.activeRef?.generation, ref.generation)
    override suspend fun abandon(ref: OpdsCredentialRef) { dao.abandon(ref.catalogId, ref.generation) }
}

class OpdsCredentialLifecycleTest {
    private val dao = MemoryLifecycleDao()
    private val backend = FakeOpdsSecretBackend()
    private val storage = OpdsCredentialStorage(backend)
    private val lifecycle = OpdsCredentialLifecycle(dao, storage, MemoryMigrationJournal(dao))
    private val url = "https://catalog.example/opds"
    private val foreign = "https://other.example/opds"
    private val pair = OpdsCredentialPair("fixture-user", "fixture-password")
    private val replacement = OpdsCredentialEdit.Replace(pair)

    private suspend fun create() = lifecycle.create("Fixture", url, replacement)
    private fun row(id: Long) = checkNotNull(dao.rows[id])
    private fun session(id: Long) = OpdsCatalogSession(row(id))
    private fun state(row: OpdsEntity) = dao.entries[row.id to row.credentialGeneration]?.state

    @Test fun createAuthenticatedPublishesOnlyGenerationAndNoLegacyPair() = runBlocking {
        val id = create()
        val row = row(id)
        check(row.username == null && row.password == null && state(row) == "ACTIVE")
        check(lifecycle.readForRequest(session(id), url)?.password == pair.password)
        check(backend.values.size == 1 && backend.clears == 0)
    }

    @Test fun anonymousCreateNeedsNoDeviceAuthentication() = runBlocking {
        backend.rejectRead = true
        val id = lifecycle.create("Public", url, OpdsCredentialEdit.Remove)
        check(lifecycle.readForRequest(session(id), url) == null && backend.reads == 0)
        check(runCatching { lifecycle.create("Invalid", url, OpdsCredentialEdit.Keep) }.isFailure)
        check(dao.rows.size == 1 && dao.entries.isEmpty())
    }

    @Test fun failedCreateRetiresPendingAndPreservesOtherCatalog() = runBlocking {
        val id = create()
        backend.failAfterWrite = true
        check(runCatching { create() }.isFailure)
        check(dao.rows.keys == setOf(id) && state(row(id)) == "ACTIVE")
        check(dao.retiredCandidates().size == 1 && backend.clears == 0)
    }

    @Test fun cancelledCreateIsRecoveredWithoutPublishingOrDeletingCiphertext() = runBlocking {
        backend.cancelAfterWrite = true
        check(runCatching { create() }.exceptionOrNull() is CancellationException)
        val pending = dao.rows.values.single()
        check(state(pending) == "PENDING_CREATE")
        check(runCatching { lifecycle.readForRequest(OpdsCatalogSession(pending), url) }.isFailure)
        check(lifecycle.recoverInterrupted() == 1 && dao.rows.isEmpty())
        check(dao.retiredCandidates().size == 1 && backend.values.size == 1 && backend.clears == 0)
        check(lifecycle.recoverInterrupted() == 0)
    }

    @Test fun ambiguousCreateCommitIsReconciledFromActiveGeneration() = runBlocking {
        dao.failAfterCreateCommit = true
        val id = create()
        val row = dao.rows.values.single()
        check(id == row.id)
        check(state(row) == "ACTIVE" && lifecycle.recoverInterrupted() == 0)
        check(lifecycle.readForRequest(session(row.id), url)?.password == pair.password)
        check(backend.clears == 0)
    }

    @Test fun keepWhileLockedPreservesCredentialAndRequiresSameOrigin() = runBlocking {
        val id = create()
        val before = row(id)
        backend.rejectRead = true
        lifecycle.update(session(id), "Renamed", "$url/folder", OpdsCredentialEdit.Keep)
        check(row(id).credentialGeneration == before.credentialGeneration && state(row(id)) == "ACTIVE")
        check(row(id).title == "Renamed" && backend.writes == 1)
        check(runCatching { lifecycle.update(session(id), "Foreign", foreign, OpdsCredentialEdit.Keep) }.isFailure)
        check(row(id).url == "$url/folder")
    }

    @Test fun replacementOriginChangeIsAtomicAndOldSessionCannotRead() = runBlocking {
        val id = create()
        val old = row(id)
        val selected = session(id)
        lifecycle.update(selected, "Changed", foreign, OpdsCredentialEdit.Replace(OpdsCredentialPair("other", "new-pass")))
        val after = row(id)
        check(after.url == foreign && after.credentialGeneration != old.credentialGeneration)
        check(state(old) == "RETIRED" && state(after) == "ACTIVE")
        check(runCatching { lifecycle.readForRequest(selected, url) }.isFailure)
        check(lifecycle.readForRequest(session(id), foreign)?.password == "new-pass")
        check(lifecycle.readForRequest(session(id), url) == null && backend.clears == 0)
    }

    @Test fun failedReplacementDoesNotChangeActiveMetadata() = runBlocking {
        val id = create()
        val old = row(id)
        backend.rejectWrite = true
        check(runCatching { lifecycle.update(session(id), "New", foreign, replacement) }.isFailure)
        check(row(id) == old && state(old) == "ACTIVE" && dao.retiredCandidates().size == 1)
        check(lifecycle.readForRequest(session(id), url)?.password == pair.password)
    }

    @Test fun cancelledReplacementRecoveryRetiresOnlyUnpublishedGeneration() = runBlocking {
        val id = create()
        val old = row(id)
        backend.cancelAfterWrite = true
        check(runCatching { lifecycle.update(session(id), "New", foreign, replacement) }.exceptionOrNull() is CancellationException)
        check(row(id) == old && lifecycle.recoverInterrupted() == 1)
        check(state(old) == "ACTIVE" && backend.clears == 0 && dao.retiredCandidates().size == 1)
    }

    @Test fun ambiguousReplacementCommitIsReconciledFromActiveGeneration() = runBlocking {
        val id = create()
        val old = row(id)
        dao.failAfterReplacementCommit = true
        lifecycle.update(session(id), "New", foreign, replacement)
        check(lifecycle.recoverInterrupted() == 0)
        check(state(old) == "RETIRED" && state(row(id)) == "ACTIVE")
        check(lifecycle.readForRequest(session(id), foreign)?.password == pair.password)
    }

    @Test fun removeAndDeleteWhileLockedRetireOnlySelectedGeneration() = runBlocking {
        val first = create()
        val second = create()
        val oldFirst = row(first)
        val oldSecond = row(second)
        backend.rejectRead = true
        lifecycle.update(session(first), "Public", foreign, OpdsCredentialEdit.Remove)
        check(row(first).credentialGeneration == null && state(oldFirst) == "RETIRED")
        check(lifecycle.readForRequest(session(first), foreign) == null && state(oldSecond) == "ACTIVE")
        lifecycle.delete(session(second))
        check(dao.rows.keys == setOf(first) && state(oldSecond) == "RETIRED" && backend.clears == 0)
    }

    @Test fun missingLockedCorruptAndForeignReadsHaveDistinctBehavior() = runBlocking {
        val id = create()
        val selected = session(id)
        val ref = OpdsCredentialRef(id, checkNotNull(row(id).credentialGeneration))
        backend.rejectRead = true
        val reads = backend.reads
        check(lifecycle.readForRequest(selected, foreign) == null && backend.reads == reads)
        check(runCatching { lifecycle.readForRequest(selected, url) }.exceptionOrNull() is OpdsCredentialStorageException)
        backend.rejectRead = false
        backend.values.remove(ref.storageId())
        check(runCatching { lifecycle.readForRequest(selected, url) }.exceptionOrNull() is OpdsCredentialStorageException)
        backend.values[ref.storageId()] = "corrupt"
        check(runCatching { lifecycle.readForRequest(selected, url) }.exceptionOrNull() is OpdsCredentialStorageException)
    }

    @Test fun legacyPairCannotBeSentAndMigrationFailurePreservesIt() = runBlocking {
        val id = lifecycle.create("Legacy", url, OpdsCredentialEdit.Remove)
        val legacy = row(id).copy(username = pair.username, password = pair.password)
        dao.rows[id] = legacy
        check(runCatching { lifecycle.readForRequest(session(id), url) }.isFailure)
        backend.rejectWrite = true
        check(lifecycle.migrate(id) == OpdsMigrationOutcome.RETRY_REQUIRED && row(id) == legacy)
        backend.rejectWrite = false
        val selected = session(id)
        check(lifecycle.migrate(id) == OpdsMigrationOutcome.MIGRATED)
        check(row(id).username == null && row(id).password == null && state(row(id)) == "ACTIVE")
        check(runCatching { lifecycle.readForRequest(selected, url) }.isFailure)
        check(lifecycle.readForRequest(session(id), url)?.password == pair.password)
    }

    @Test fun keepLegacyPreservesIncompletePairAndStaleMutationCannotOverwrite() = runBlocking {
        val id = lifecycle.create("Legacy", url, OpdsCredentialEdit.Remove)
        val legacy = row(id).copy(username = pair.username)
        dao.rows[id] = legacy
        lifecycle.update(session(id), "Renamed", url, OpdsCredentialEdit.Keep)
        check(row(id) == legacy.copy(title = "Renamed") && backend.writes == 0)
        val old = session(id)
        dao.rows[id] = row(id).copy(password = "changed")
        check(runCatching { lifecycle.update(old, "Stale", url, OpdsCredentialEdit.Remove) }.isFailure)
        check(runCatching { lifecycle.delete(old) }.isFailure && row(id).password == "changed")
    }

    @Test fun prepareCatalogsRecoversAndSeedsDefaultsOnlyOnce() = runBlocking {
        backend.cancelAfterWrite = true
        check(runCatching { create() }.exceptionOrNull() is CancellationException)
        lifecycle.prepareCatalogs()
        lifecycle.prepareCatalogs()
        check(dao.rows.values.map { it.title } == listOf("Project Gutenberg", "ManyBooks"))
        check(dao.rows.values.all { it.credentialGeneration == null && it.username == null && it.password == null })
        check(dao.retiredCandidates().size == 1)
    }

    @Test fun bulkMigrationPreservesIncompleteAndLockedRowsAndReportsRemaining() = runBlocking {
        val first = lifecycle.create("Legacy 1", url, OpdsCredentialEdit.Remove)
        val second = lifecycle.create("Legacy 2", url, OpdsCredentialEdit.Remove)
        dao.rows[first] = row(first).copy(username = pair.username, password = pair.password)
        dao.rows[second] = row(second).copy(username = pair.username)
        backend.rejectWrite = true
        check(lifecycle.migrateAll() == OpdsMigrationReport(0, 2))
        check(row(first).password == pair.password && row(second).username == pair.username)
        backend.rejectWrite = false
        check(lifecycle.migrateAll() == OpdsMigrationReport(1, 1))
        check(row(first).username == null && row(first).password == null && state(row(first)) == "ACTIVE")
        check(row(second).username == pair.username && row(second).credentialGeneration == null)
    }

    @Test fun staleEditorMetadataCannotReplaceOrDeleteButBrowserCanStillRead() = runBlocking {
        val id = create()
        val old = session(id)
        lifecycle.update(old, "Renamed", url, OpdsCredentialEdit.Keep)
        check(lifecycle.readForRequest(old, url)?.password == pair.password)
        check(runCatching { lifecycle.update(old, "Stale", url, OpdsCredentialEdit.Remove) }.isFailure)
        check(runCatching { lifecycle.delete(old) }.isFailure)
        check(row(id).title == "Renamed" && state(row(id)) == "ACTIVE")
    }

    @Test fun dispatchDoesNotCallSenderWhenLockedOrStale() = runBlocking {
        val id = create()
        val selected = session(id)
        var sent = 0
        check(lifecycle.dispatch(selected, url) { sent++; it?.username } == pair.username)
        backend.rejectRead = true
        check(runCatching { lifecycle.dispatch(selected, url) { sent++ } }.isFailure)
        lifecycle.update(selected, "Public", url, OpdsCredentialEdit.Remove)
        check(runCatching { lifecycle.dispatch(selected, url) { sent++ } }.isFailure)
        check(sent == 1)
    }
}
