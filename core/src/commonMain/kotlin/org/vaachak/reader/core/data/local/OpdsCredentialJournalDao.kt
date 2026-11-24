package org.vaachak.reader.core.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
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

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertCatalogRow(row: OpdsEntity): Long

    @Update(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun updateCatalogRow(row: OpdsEntity): Int

    @Delete
    protected abstract suspend fun deleteCatalogRow(row: OpdsEntity): Int

    @Query("UPDATE opds_feeds SET title = :title, url = :url WHERE id = :id")
    protected abstract suspend fun updateMetadata(id: Long, title: String, url: String): Int

    @Query("""
        SELECT * FROM opds_credential_journal WHERE state IN ('RESERVED', 'PENDING_CREATE')
        ORDER BY catalogId, generation
    """)
    abstract suspend fun interruptedCandidates(): List<OpdsCredentialJournalEntity>

    @Query("SELECT COUNT(*) FROM opds_feeds")
    protected abstract suspend fun rowCount(): Int

    @Transaction
    open suspend fun seedDefaultsIfEmpty() {
        if (rowCount() != 0) return
        insertCatalogRow(OpdsEntity(title = "Project Gutenberg", url = "https://gutendex.com/books"))
        insertCatalogRow(OpdsEntity(title = "ManyBooks", url = "https://manybooks.net/opds"))
    }

    /** The only new-row inputs accepted by this lifecycle contain no legacy fields. */
    private fun requireNewRow(row: OpdsEntity) {
        require(row.id == 0L && row.username == null && row.password == null &&
            row.credentialGeneration == null) { "Invalid new OPDS catalog" }
    }

    @Transaction
    open suspend fun createAnonymous(row: OpdsEntity): Long {
        requireNewRow(row)
        return insertCatalogRow(row)
    }

    /** A pending row allocates a stable ID but is invisible through OpdsDao until publication. */
    @Transaction
    open suspend fun reserveCreate(row: OpdsEntity, generation: String): Long {
        requireNewRow(row)
        val id = insertCatalogRow(row.copy(credentialGeneration = generation))
        check(id > 0) { "OPDS catalog identity allocation failed" }
        insert(OpdsCredentialJournalEntity(id, generation, "PENDING_CREATE"))
        return id
    }

    @Transaction
    open suspend fun publishCreate(expected: OpdsEntity): Boolean {
        val generation = expected.credentialGeneration ?: return false
        if (expected.username != null || expected.password != null || load(expected.id) != expected ||
            entry(expected.id, generation)?.state != "PENDING_CREATE"
        ) return false
        check(transition(expected.id, generation, "PENDING_CREATE", "ACTIVE") == 1) {
            "OPDS pending publication conflict"
        }
        return true
    }

    /** Never delete a published row, even when publication committed before an error surfaced. */
    @Transaction
    open suspend fun discardPending(id: Long, generation: String): Boolean {
        if (entry(id, generation)?.state != "PENDING_CREATE") return false
        val row = load(id)
        if (row != null && row.credentialGeneration == generation) {
            if (row.username != null || row.password != null) return false
            check(deleteCatalogRow(row) == 1) { "OPDS pending deletion conflict" }
        }
        check(transition(id, generation, "PENDING_CREATE", "RETIRED") == 1) {
            "OPDS pending retirement conflict"
        }
        return true
    }

    /** Compare the full row, including metadata, and update only metadata for Keep. */
    @Transaction
    open suspend fun keepMetadata(expected: OpdsEntity, title: String, url: String): Boolean {
        if (!isCurrent(expected)) return false
        check(updateMetadata(expected.id, title, url) == 1) { "OPDS metadata update conflict" }
        return true
    }

    /** Publish metadata and encrypted generation together; never persist the replacement pair. */
    @Transaction
    open suspend fun publishReplacement(
        expected: OpdsEntity, title: String, url: String, generation: String
    ): Boolean {
        if (!isCurrent(expected) || generation == expected.credentialGeneration ||
            entry(expected.id, generation)?.state != "RESERVED"
        ) return false
        check(updateCatalogRow(expected.copy(title = title, url = url, username = null,
            password = null, credentialGeneration = generation)) == 1) { "OPDS replacement conflict" }
        check(transition(expected.id, generation, "RESERVED", "ACTIVE") == 1) {
            "OPDS replacement publication conflict"
        }
        retirePrevious(expected)
        return true
    }

    /** Explicit Remove is allowed without decrypting; retirement remains transactional. */
    @Transaction
    open suspend fun removeCredentials(expected: OpdsEntity, title: String, url: String): Boolean {
        if (!isCurrent(expected)) return false
        check(updateCatalogRow(expected.copy(title = title, url = url, username = null,
            password = null, credentialGeneration = null)) == 1) { "OPDS removal conflict" }
        retirePrevious(expected)
        return true
    }

    @Transaction
    open suspend fun deleteCatalog(expected: OpdsEntity): Boolean {
        if (!isCurrent(expected)) return false
        check(deleteCatalogRow(expected) == 1) { "OPDS catalog deletion conflict" }
        retirePrevious(expected)
        return true
    }

    private suspend fun isCurrent(expected: OpdsEntity): Boolean =
        load(expected.id) == expected && previousIsActive(expected.id, expected.credentialGeneration)

    private suspend fun retirePrevious(expected: OpdsEntity) {
        expected.credentialGeneration?.let { generation ->
            check(transition(expected.id, generation, "ACTIVE", "RETIRED") == 1) {
                "OPDS previous generation retirement conflict"
            }
        }
    }

    private suspend fun previousIsActive(id: Long, previous: String?): Boolean =
        previous == null || entry(id, previous)?.state == "ACTIVE"

    private fun matches(row: OpdsEntity?, url: String, username: String?, password: String?, previous: String?): Boolean =
        row != null && row.url == url && row.username == username && row.password == password &&
            row.credentialGeneration == previous
}
