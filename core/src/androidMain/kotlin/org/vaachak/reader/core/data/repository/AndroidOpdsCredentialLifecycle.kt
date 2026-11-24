package org.vaachak.reader.core.data.repository

import org.vaachak.reader.core.data.local.AppDatabase
import javax.inject.Inject
import javax.inject.Singleton

/** One shared mutex and authenticated backend for production CRUD, migration and reads. */
@Singleton
internal class AndroidOpdsCredentialLifecycle @Inject constructor(
    database: AppDatabase,
    storage: AndroidOpdsCredentialStorage,
    journal: RoomOpdsCredentialJournal
) {
    val lifecycle = OpdsCredentialLifecycle(database.opdsCredentialJournalDao(), storage.storage, journal)
}
