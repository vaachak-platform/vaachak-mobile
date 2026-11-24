package org.vaachak.reader.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import org.vaachak.reader.core.domain.model.OpdsEntity

/** Transactions only mutate metadata; never call Keystore or filesystem inside them. */
@Dao
abstract class OpdsCredentialJournalDao {
    @Query("SELECT * FROM opds_feeds WHERE id = :id")
    abstract suspend fun load(id: Long): OpdsEntity?

    @Query("SELECT id FROM opds_feeds WHERE username IS NOT NULL OR password IS NOT NULL ORDER BY id")
    abstract suspend fun legacyIds(): List<Long>

    @Query("SELECT * FROM opds_credential_journal WHERE catalogId = :id AND generation = :generation")
    abstract suspend fun entry(id: Long, generation: String): OpdsCredentialJournalEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insert(entry: OpdsCredentialJournalEntity)

    @Query("""
        UPDATE opds_feeds SET credentialGeneration = :generation, username = NULL, password = NULL
        WHERE id = :id AND url = :url AND username IS :username AND password IS :password
          AND credentialGeneration IS :previous
    """)
    protected abstract suspend fun compareAndPublish(
        id: Long, url: String, username: String?, password: String?, previous: String?, generation: String
    ): Int

    @Query("""
        UPDATE opds_credential_journal SET state = :next
        WHERE catalogId = :id AND generation = :generation AND state = :previous
    """)
    protected abstract suspend fun transition(id: Long, generation: String, previous: String, next: String): Int

    @Query("""
        UPDATE opds_credential_journal SET state = 'RETIRED'
        WHERE catalogId = :id AND generation = :generation AND state = 'RESERVED'
          AND NOT EXISTS (SELECT 1 FROM opds_feeds WHERE id = :id AND credentialGeneration = :generation)
    """)
    abstract suspend fun abandon(id: Long, generation: String): Int

    @Query("""
        SELECT * FROM opds_credential_journal j WHERE j.state = 'RETIRED'
          AND NOT EXISTS (SELECT 1 FROM opds_feeds f WHERE f.id = j.catalogId AND f.credentialGeneration = j.generation)
        ORDER BY j.catalogId, j.generation
    """)
    abstract suspend fun retiredCandidates(): List<OpdsCredentialJournalEntity>

    @Transaction
    open suspend fun reserve(
        id: Long, url: String, username: String?, password: String?, previous: String?, generation: String
    ): Boolean {
        if (!matches(load(id), url, username, password, previous) || generation == previous ||
            entry(id, generation) != null || !previousIsActive(id, previous)
        ) return false
        insert(OpdsCredentialJournalEntity(id, generation, "RESERVED"))
        return true
    }

    @Transaction
    open suspend fun publish(
        id: Long, url: String, username: String?, password: String?, previous: String?, generation: String
    ): Boolean {
        if (!matches(load(id), url, username, password, previous) || generation == previous ||
            entry(id, generation)?.state != "RESERVED" || !previousIsActive(id, previous) ||
            compareAndPublish(id, url, username, password, previous, generation) != 1
        ) return false
        // Throwing rolls back the row update as well as all journal transitions.
        check(transition(id, generation, "RESERVED", "ACTIVE") == 1) { "OPDS journal publish conflict" }
        if (previous != null) {
            check(transition(id, previous, "ACTIVE", "RETIRED") == 1) { "OPDS journal retirement conflict" }
        }
        return true
    }

    private suspend fun previousIsActive(id: Long, previous: String?): Boolean =
        previous == null || entry(id, previous)?.state == "ACTIVE"

    private fun matches(row: OpdsEntity?, url: String, username: String?, password: String?, previous: String?): Boolean =
        row != null && row.url == url && row.username == username && row.password == password &&
            row.credentialGeneration == previous
}
