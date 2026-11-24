package org.vaachak.reader.core.data.local

import androidx.room.Entity

/** Non-secret journal. Tombstones deliberately survive catalog deletion. */
@Entity(tableName = "opds_credential_journal", primaryKeys = ["catalogId", "generation"])
data class OpdsCredentialJournalEntity(
    val catalogId: Long,
    val generation: String,
    val state: String
)
