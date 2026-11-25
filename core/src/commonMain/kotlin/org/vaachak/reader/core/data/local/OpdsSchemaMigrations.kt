package org.vaachak.reader.core.data.local

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/** Legacy 2→3 rebuild retained for driver-based Android and iOS upgrades. */
internal val OPDS_MIGRATION_2_3_SQL = listOf(
    """CREATE TABLE IF NOT EXISTS opds_feeds_new (
        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        title TEXT NOT NULL,
        url TEXT NOT NULL,
        username TEXT,
        password TEXT,
        isPredefined INTEGER NOT NULL DEFAULT 0
    )""".trimIndent(),
    """INSERT INTO opds_feeds_new (id, title, url, username, password, isPredefined)
        SELECT id, title, url, username, password, isPredefined FROM opds_feeds""".trimIndent(),
    "DROP TABLE opds_feeds",
    "ALTER TABLE opds_feeds_new RENAME TO opds_feeds"
)

val OPDS_DRIVER_MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        OPDS_MIGRATION_2_3_SQL.forEach { connection.execSQL(it) }
    }
}

/** Add only metadata. Do not require device authentication while Room opens the DB. */
val OPDS_MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE opds_feeds ADD COLUMN credentialGeneration TEXT")
        connection.execSQL("""
            CREATE TABLE IF NOT EXISTS opds_credential_journal (
                catalogId INTEGER NOT NULL,
                generation TEXT NOT NULL,
                state TEXT NOT NULL,
                PRIMARY KEY(catalogId, generation)
            )
        """.trimIndent())
    }
}
