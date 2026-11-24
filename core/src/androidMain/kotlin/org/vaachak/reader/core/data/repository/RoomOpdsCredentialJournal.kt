package org.vaachak.reader.core.data.repository

import org.vaachak.reader.core.data.local.AppDatabase
import javax.inject.Inject
import javax.inject.Singleton

/** Android adapter for P0-12B's durable journal interface. Not invoked by the catalog UI yet. */
@Singleton
internal class RoomOpdsCredentialJournal @Inject constructor(database: AppDatabase) : OpdsCredentialJournal {
    private val dao = database.opdsCredentialJournalDao()

    override suspend fun load(catalogId: Long): OpdsCredentialSnapshot? = dao.load(catalogId)?.let { row ->
        OpdsCredentialSnapshot(
            row.id, row.url, row.username, row.password,
            row.credentialGeneration?.let { OpdsCredentialRef(row.id, it) }
        )
    }

    override suspend fun reserve(expected: OpdsCredentialSnapshot, ref: OpdsCredentialRef): Boolean {
        if (expected.catalogId != ref.catalogId || !validPrevious(expected)) return false
        return dao.reserve(expected.catalogId, expected.catalogUrl, expected.legacyUsername,
            expected.legacyPassword, expected.activeRef?.generation, ref.generation)
    }

    override suspend fun publish(expected: OpdsCredentialSnapshot, ref: OpdsCredentialRef): Boolean {
        if (expected.catalogId != ref.catalogId || !validPrevious(expected)) return false
        return dao.publish(expected.catalogId, expected.catalogUrl, expected.legacyUsername,
            expected.legacyPassword, expected.activeRef?.generation, ref.generation)
    }

    override suspend fun abandon(ref: OpdsCredentialRef) {
        dao.abandon(ref.catalogId, ref.generation)
    }

    private fun validPrevious(expected: OpdsCredentialSnapshot): Boolean =
        expected.activeRef?.catalogId?.let { it == expected.catalogId } ?: true
}
