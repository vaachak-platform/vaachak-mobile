package org.vaachak.reader.core.data.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Internal snapshot only: never expose this object through UI catalog flows or logs. */
internal class OpdsCredentialSnapshot(
    val catalogId: Long,
    val catalogUrl: String,
    val legacyUsername: String?,
    val legacyPassword: String?,
    val activeRef: OpdsCredentialRef?
) {
    override fun toString(): String = "OpdsCredentialSnapshot(REDACTED)"

    val hasLegacy: Boolean get() = legacyUsername != null || legacyPassword != null
}

/**
 * Persistence contract for the next Room adapter. Implement ALL writes transactionally.
 *
 * reserve: compare the entire expected row (ID, URL, legacy pair, active generation),
 * then record an unpublished generation in a durable journal. Never overwrite an existing ref.
 * publish: compare that same row and the reservation, atomically publish the new ref,
 * clear both legacy columns, and move the previous generation (if any) to retired state.
 * abandon: mark only a still-unpublished reservation retired; if publish actually committed
 * before throwing, the active generation must remain untouched.
 *
 * A crash can leave reservations/retired generations. Reconciliation must verify references
 * transactionally before clearing files. No process-local marker is sufficient for production.
 */
internal interface OpdsCredentialJournal {
    suspend fun load(catalogId: Long): OpdsCredentialSnapshot?
    suspend fun reserve(expected: OpdsCredentialSnapshot, ref: OpdsCredentialRef): Boolean
    suspend fun publish(expected: OpdsCredentialSnapshot, ref: OpdsCredentialRef): Boolean
    suspend fun abandon(ref: OpdsCredentialRef)
}

internal enum class OpdsMigrationOutcome {
    NONE, CATALOG_NOT_FOUND, ALREADY_REFERENCED, MIGRATED, RETRY_REQUIRED, CONFLICT, RECOVERY_REQUIRED
}

/**
 * First migration slice: pure orchestration over a durable journal and authenticated storage.
 * Serialize with future CRUD operations through a shared repository mutex. The Room adapter,
 * startup traversal and UI/network integration are deliberately separate from this slice.
 */
internal class OpdsCredentialMigration(
    private val journal: OpdsCredentialJournal,
    private val storage: OpdsCredentialStorage,
    private val mutationMutex: Mutex = Mutex()
) {
    suspend fun migrate(catalogId: Long): OpdsMigrationOutcome = mutationMutex.withLock {
        val row = journal.load(catalogId) ?: return@withLock OpdsMigrationOutcome.CATALOG_NOT_FOUND
        if (row.catalogId != catalogId || row.activeRef?.catalogId?.let { it != catalogId } == true) {
            return@withLock OpdsMigrationOutcome.RECOVERY_REQUIRED
        }
        if (row.activeRef != null) {
            return@withLock if (row.hasLegacy) OpdsMigrationOutcome.RECOVERY_REQUIRED
            else OpdsMigrationOutcome.ALREADY_REFERENCED
        }
        if (!row.hasLegacy) return@withLock OpdsMigrationOutcome.NONE
        val username = row.legacyUsername ?: return@withLock OpdsMigrationOutcome.RECOVERY_REQUIRED
        val password = row.legacyPassword ?: return@withLock OpdsMigrationOutcome.RECOVERY_REQUIRED
        val ref = OpdsCredentialRef.create(catalogId)
        if (!journal.reserve(row, ref)) return@withLock OpdsMigrationOutcome.CONFLICT
        try {
            storage.writeVerified(ref, row.catalogUrl, OpdsCredentialPair(username, password))
        } catch (cancelled: java.util.concurrent.CancellationException) {
            // Reservation stays durable; reconcile after restart. Never delete ciphertext here.
            throw cancelled
        } catch (_: OpdsCredentialStorageException) {
            journal.abandon(ref)
            return@withLock OpdsMigrationOutcome.RETRY_REQUIRED
        }
        // Database exceptions deliberately propagate. A publish may have committed before
        // the error surfaced. Do not delete ciphertext or guess the transaction outcome.
        if (journal.publish(row, ref)) {
            OpdsMigrationOutcome.MIGRATED
        } else {
            journal.abandon(ref)
            OpdsMigrationOutcome.CONFLICT
        }
    }
}
