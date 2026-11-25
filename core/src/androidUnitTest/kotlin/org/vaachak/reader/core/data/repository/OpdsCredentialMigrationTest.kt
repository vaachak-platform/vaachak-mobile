package org.vaachak.reader.core.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.Test

/** In-memory model of the required Room transaction and journal semantics, not a Room test. */
private class FakeJournal(var row: OpdsCredentialSnapshot?) : OpdsCredentialJournal {
    val reservations = mutableSetOf<OpdsCredentialRef>()
    val retired = mutableSetOf<OpdsCredentialRef>()
    var rejectReserve = false
    var rejectPublish = false
    var throwBeforePublish = false
    var throwAfterPublish = false

    override suspend fun load(catalogId: Long): OpdsCredentialSnapshot? = row?.takeIf { it.catalogId == catalogId }

    override suspend fun reserve(expected: OpdsCredentialSnapshot, ref: OpdsCredentialRef): Boolean =
        !rejectReserve && row === expected && reservations.add(ref)

    override suspend fun publish(expected: OpdsCredentialSnapshot, ref: OpdsCredentialRef): Boolean {
        if (throwBeforePublish) error("simulated transaction interruption")
        if (rejectPublish || row !== expected || ref !in reservations) return false
        row = OpdsCredentialSnapshot(expected.catalogId, expected.catalogUrl, null, null, ref)
        reservations.remove(ref)
        expected.activeRef?.let { retired.add(it) }
        if (throwAfterPublish) error("simulated ambiguous commit acknowledgement")
        return true
    }

    override suspend fun abandon(ref: OpdsCredentialRef) {
        if (row?.activeRef != ref && reservations.remove(ref)) retired.add(ref)
    }
}

class OpdsCredentialMigrationTest {
    private val url = "https://reader.example/opds"
    private val original = OpdsCredentialSnapshot(42, url, "synthetic-reader", "synthetic-sensitive-password", null)
    private val journal = FakeJournal(original)
    private val backend = FakeOpdsSecretBackend()
    private val storage = OpdsCredentialStorage(backend)
    private val migration = OpdsCredentialMigration(journal, storage)

    @Test fun verifiedCiphertextPrecedesAtomicPublish() = runBlocking {
        check(migration.migrate(42) == OpdsMigrationOutcome.MIGRATED)
        val row = checkNotNull(journal.row)
        check(!row.hasLegacy)
        val ref = checkNotNull(row.activeRef)
        check(storage.readForRequest(42, ref, url, url)?.password == original.legacyPassword)
        check(journal.reservations.isEmpty())
        check(migration.migrate(42) == OpdsMigrationOutcome.ALREADY_REFERENCED)
        check(backend.writes == 1)
    }

    @Test fun noLockOrWriteFailurePreservesLegacy() = runBlocking {
        backend.rejectWrite = true
        check(migration.migrate(42) == OpdsMigrationOutcome.RETRY_REQUIRED)
        check(journal.row === original)
        check(journal.retired.size == 1)
        backend.rejectWrite = false
        check(migration.migrate(42) == OpdsMigrationOutcome.MIGRATED)
    }

    @Test fun exceptionAfterEncryptedWriteDoesNotClearLegacy() = runBlocking {
        backend.failAfterWrite = true
        check(migration.migrate(42) == OpdsMigrationOutcome.RETRY_REQUIRED)
        check(journal.row === original)
        check(journal.retired.size == 1 && backend.values.size == 1)
        check(backend.clears == 0)
    }

    @Test fun failedReadBackCannotPublish() = runBlocking {
        backend.corruptWrite = true
        check(migration.migrate(42) == OpdsMigrationOutcome.RETRY_REQUIRED)
        check(journal.row === original)
        check(journal.retired.size == 1)
    }

    @Test fun conflictBeforeReservationDoesNotWrite() = runBlocking {
        journal.rejectReserve = true
        check(migration.migrate(42) == OpdsMigrationOutcome.CONFLICT)
        check(backend.writes == 0 && journal.row === original)
    }

    @Test fun compareAndSetFailureRetiresOnlyUnpublishedGeneration() = runBlocking {
        journal.rejectPublish = true
        check(migration.migrate(42) == OpdsMigrationOutcome.CONFLICT)
        check(journal.row === original && journal.retired.size == 1)
        check(backend.clears == 0)
    }

    @Test fun interruptedPublishLeavesDurableReservation() = runBlocking {
        journal.throwBeforePublish = true
        check(runCatching { migration.migrate(42) }.isFailure)
        check(journal.row === original && journal.reservations.size == 1)
        check(journal.retired.isEmpty() && backend.clears == 0)
    }

    @Test fun commitAcknowledgementFailureNeverDeletesActiveCiphertext() = runBlocking {
        journal.throwAfterPublish = true
        check(runCatching { migration.migrate(42) }.isFailure)
        val active = checkNotNull(journal.row?.activeRef)
        check(storage.readForRequest(42, active, url, url)?.password == original.legacyPassword)
        check(journal.retired.isEmpty() && backend.clears == 0)
        check(migration.migrate(42) == OpdsMigrationOutcome.ALREADY_REFERENCED)
    }

    @Test fun cancellationAfterWriteLeavesReservationForReconciliation() = runBlocking {
        backend.cancelAfterWrite = true
        val failure = runCatching { migration.migrate(42) }.exceptionOrNull()
        check(failure is java.util.concurrent.CancellationException)
        check(journal.row === original && journal.reservations.size == 1)
        check(journal.retired.isEmpty() && backend.clears == 0)
    }

    @Test fun mismatchedActiveReferenceRequiresRecoveryWithoutReading() = runBlocking {
        journal.row = OpdsCredentialSnapshot(42, url, null, null, OpdsCredentialRef.create(43))
        check(migration.migrate(42) == OpdsMigrationOutcome.RECOVERY_REQUIRED)
        check(backend.reads == 0)
    }

    @Test fun partialLegacyPairRequiresRecoveryWithoutWriting() = runBlocking {
        journal.row = OpdsCredentialSnapshot(42, url, "reader", null, null)
        check(migration.migrate(42) == OpdsMigrationOutcome.RECOVERY_REQUIRED)
        check(backend.writes == 0)
    }

    @Test fun absentAndAnonymousCatalogsAreDistinct() = runBlocking {
        check(migration.migrate(43) == OpdsMigrationOutcome.CATALOG_NOT_FOUND)
        journal.row = OpdsCredentialSnapshot(42, url, null, null, null)
        check(migration.migrate(42) == OpdsMigrationOutcome.NONE)
        check(backend.writes == 0)
    }

    @Test fun contradictoryLegacyAndReferenceRequiresRecovery() = runBlocking {
        journal.row = OpdsCredentialSnapshot(42, url, "reader", "secret", OpdsCredentialRef.create(42))
        check(migration.migrate(42) == OpdsMigrationOutcome.RECOVERY_REQUIRED)
        check(backend.writes == 0)
    }
}
