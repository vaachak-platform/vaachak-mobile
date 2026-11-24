# P0-12C — durable OPDS credential journal and Room v4

Base: `d7173c76b0c80928cf19a53e32c1ab2cd5317851` (PR #72). Includes the unchanged P0-12B foundation if not already committed. **Candidate only; no production credential migration is invoked by this slice. SEC-03 remains open.**

## Implementation

- Room v3→v4 adds nullable `opds_feeds.credentialGeneration` and `opds_credential_journal` (composite primary key catalogId/generation, non-secret state). No existing row is changed by schema migration; no authentication is required to open the database. Reader profiles, Books, highlights, and sync vault tables are untouched.
- Register the migration on Android and iOS. Existing Android v2→v3 SQL also supports the configured SQLiteConnection/BundledSQLiteDriver path; retain the SupportSQLiteDatabase overload for legacy compatibility and the existing test. iOS builder registers both driver migrations and uses the bundled driver. iOS compilation and upgrade compatibility still need platform validation.
- `RoomOpdsCredentialJournal` implements the P0-12B journal interface. Reserve checks the expected catalog ID/URL/legacy pair/active ref and requires a new generation. Publish checks the same snapshot and reservation, then clears both legacy columns, publishes the new ref, activates its journal record, and retires a previous generation in one Room transaction. An unexpected transition throws to roll back the entire transaction. Abandon only retires a still-reserved generation that is not referenced by the catalog row.
- Retired-candidate query is intentionally read-only. No filesystem removal/reconciliation is performed; no auto-deletion of a Keystore key or files. The next activation slice must coordinate reconciliation with a shared mutation mutex and ensure unreferenced status before removal.

## Validation

`OpdsRoomJournalTest` adds Android JVM/Robolectric tests using the actual Room database and bundled driver (not run in the packaging environment). It creates disposable synthetic database fixtures; v3 is simulated by removing the two v4 additions from an otherwise identical newly created DB and resetting the version. This is not a historical released APK upgrade fixture. It covers upgrade and preserved profile, storage-protocol migration with a fake backend, stale/abandoned snapshots, delayed abandon after commit, replacement retirement, and trigger-induced rollback. The existing DatabaseModule test checks both registered migrations.

The package-level `tools/check_sql.py` independently runs actual SQL strings extracted from the Kotlin source against Python's SQLite on synthetic fixtures. Seven tests passed. It does not exercise KSP-generated DAO code, Room annotations/transactions, Android Keystore, or device migration. Full Gradle and iOS verification is required; no production or personal device was accessed.

## Activation gates (NOT implemented here)

1. Make the shared authenticated backend durable across abrupt power loss (fsync/atomic replacement review, singleton key creation). Do not invoke migration until storage durability is established.
2. Wire all catalog CRUD and network requests through stable row IDs, shared serialization, and fail-closed credentials, including duplicate URL handling and explicit origin changes. Existing repository still writes plaintext credentials and old DAO can bypass journal; new DAO is not a security boundary until all writers are integrated.
3. Catalog editor must distinguish Keep, Replace, Remove; preserve locked/legacy values, mask typed password, show safe save/retry errors, and close only after success. New inserts need recoverable stable ID and pending state.
4. Run authenticated migration for all legacy rows; preserve failed legacy values and never send them as plaintext fallback. Reconcile reservations/retired generations safely. Validate SQLite secure-delete/WAL checkpoint behavior and disclose limits of historical flash remanence/backups. A null column is not forensic erasure.
5. Exercise real v3 released-app fixture on disposable Android device; validate v2→v4 and iOS compatibility, positive PIN and negative no-lock, restart, interruption, invalidation, lock expiry, and public catalog regression. Do not install v4 over personal data or downgrade a v4 database to older app without an explicitly tested rollback plan.
6. Continue PR #72 security ledger and SEC-04 owner decision; final parent CI and device acceptance required. Do not mark #72 ready/merge on this slice.
