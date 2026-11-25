# P0-12 — OPDS/Calibre credential threat model and migration decision

**Reviewed branch:** `feat/p0-secure-provider-credentials` / PR #72, head `5432b84afe8679eb95f3d4d631976329d4f63e5d`.

**Status:** Transport authorization boundary patched by this deliverable; **database-at-rest risk remains open**.
Do **not** declare PR #72 security-accepted on the strength of this patch alone.

## Confirmed code facts

- `OpdsEntity` contains nullable `username` and `password` columns in the `opds_feeds` Room table. Existing `OpdsRepository.insertFeed`/`updateFeed` write them directly.
- `CatalogManagerScreen` edits those fields and `CatalogViewModel` reads and saves catalog models. Hiding or nulling values without an editing contract could silently delete saved credentials.
- `OpdsRepository.downloadPublication` previously attached Basic Authorization from `originalFeedUrl` to any HTTPS `downloadUrl`, even another host. A malicious catalog could cause secrets to be sent to an external origin. This deliverable scopes that header to the original HTTPS hostname **and effective port**; cross-origin downloads remain allowed *without* those credentials.
- An explicit `allowBackup=false` on the Leisure manifest and backup exclusions are present; these mitigate backup exposure but do **not** encrypt the Room database against local forensic compromise or privilege escalation.

## This PR's deliberately bounded behavior

1. A catalog on `https://host.example/opds` may authenticate downloads at `https://host.example/book.epub` or explicit `:443`; a catalog using `:8443` may authenticate only downloads on the same origin and port.
2. A different hostname, protocol, port, URL-embedded user-info, or malformed URL does not inherit the catalog's Basic Authorization header.
3. Cross-origin downloads may proceed anonymously; some external CDN downloads intentionally use this model. If a cross-origin endpoint truly requires credentials, it must have **separately scoped** authentication in a future design. No automatic credential forwarding exception.
4. No Room database version, column, Catalog UI, or Books reader change is made. Existing stored catalog credentials remain available until an at-rest migration is implemented safely.

## Follow-up design (required before closing the at-rest finding)

Use an app-global, non-exported Android Keystore-authenticated secret store (the existing authenticated `PrivateGatewaySecrets` mechanism is a candidate), with **stable per-record identifiers** rather than URL-only identifiers, so editing a catalog URL does not orphan credentials. The migration must:

- Track catalog records by stable ID; handle Room's auto-generated IDs on insert and preserve them on update.
- Write encrypted material to `noBackupFilesDir`, authenticate and read it back, and **only then** clear plaintext Room columns. A crash, missing PIN, invalidated key, or failed atomic write must leave recoverable legacy data, with no plaintext fallback for new credentials.
- Preserve a durable marker distinguishing *no credentials* from *encrypted-but-currently-locked*. Never treat locked or missing secrets as an instruction to overwrite/delete them when the edit dialog displays empty fields.
- Keep server usernames/passwords isolated by catalog ID and authenticate only against approved HTTPS origins. Protect secrets against logs, backup, clipboard, and diagnostics.
- Plan migrations for existing users and for devices with no secure lock (including BOOX), including a clear message explaining why credentials cannot be saved until a lock is configured. Offline reading must continue.
- Test app restart, denied authorization, expired authorization, key invalidation, duplicate URLs, URL changes, catalog deletion, backup exclusion, and failure between encrypted write and Room update.
- Prove the old Room plaintext columns are cleared for **all** records and that the app can still edit and download existing authenticated catalogs after migration.

**Release gate:** Either complete this separately reviewed at-rest migration with passing device evidence, or obtain an explicit documented product/security risk exception from the project owner. A green Sonar/CI status is not a substitute for that decision.
