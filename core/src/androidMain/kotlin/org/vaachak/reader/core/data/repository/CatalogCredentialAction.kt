package org.vaachak.reader.core.data.repository

/** Explicit user intent; a blank editor field never implies credential removal. */
enum class CatalogCredentialAction { KEEP, REPLACE, REMOVE }

/** Contains counts only, never catalog credentials or backend diagnostics. */
data class OpdsMigrationReport(val migrated: Int, val remaining: Int)
