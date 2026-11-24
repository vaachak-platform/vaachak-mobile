# P0-08 / P0-09 — Credential and Sync Security Acceptance

**Base:** `feat/p0-secure-provider-credentials` (PR #72).  
**Prepared against head:** `765853b038e7ca67d8856870759301027e5a312e` (2026-11-23).  
**Status:** candidate / NOT release-approved. Complete the evidence table below before marking PR #72 ready.

## Why this follow-up is necessary

PR #72 has green CI, including Sonar, but CI green alone does not certify device credential
availability, migration, endpoint TLS, or protection of unrelated OPDS credentials.

A code audit found that `SyncApi` used Ktor `Logging(LogLevel.ALL)`, including on
credential-bearing JSON requests. It also accepted caller-supplied `http://` sync URLs.
This package removes the HTTP logging plugin, rejects plaintext sync endpoints,
rejects embedded URL credentials/queries, and disables redirects for sync requests.
Local sync servers must serve valid HTTPS. HTTPS certificate validation remains enabled;
we intentionally do **not** add a trust-all manager or cleartext exception.

## Release-blocking risk register

| ID | Risk / decision | Current status | Acceptance requirement |
|---|---|---|---|
| SEC-01 | Sync requests/response could be logged with password-bearing request bodies | Fix included; needs test/device verification | No body/header HTTP logging; no token in synthetic logcat checks |
| SEC-02 | Explicit HTTP sync server endpoint permitted | Fix included; needs test/device verification | Reject HTTP and redirects; HTTPS with trusted certificate succeeds |
| SEC-03 | OPDS/Calibre `OpdsEntity` includes username/password in Room columns | **OPEN** | Make a documented security decision; preferably encrypt credentials at rest with a migration, or explicitly approve residual risk. This package does not modify Room schema or Books. |
| SEC-04 | Recent device authorization expires after 60 seconds, including new `SYNC_PASSWORD` | **Expected fail-closed behavior** | Confirm sync defers without leaking/falling back and resumes after device-credential unlock; do not claim truly unattended background sync works. Product owner must accept this limitation or plan a separate design. |
| SEC-05 | Legacy plaintext/Gateway VAG1 migration | Unit/instrumentation coverage exists; device upgrade still pending | Test with a disposable pre-upgrade installation containing **synthetic** data. Upgrade in place and verify no loss, no plaintext persistence, and correct envelope type. |
| SEC-06 | Android backup / cross-user credential isolation | Static and instrumentation checks included; device evidence pending | Verify backup settings, multi-vault isolation and no plaintext restore on test device |

## Automated evidence commands

From repository root on the **new security acceptance branch**:

```bash
bash tools/security/p0-security-preflight.sh
./gradlew :core:testDebugUnitTest :leisure:testDebugUnitTest :leisure:assembleDebug detekt
./gradlew :core:lintDebug :leisure:lintDebug
```

Run core instrumentation on a **dedicated test emulator / spare device**; do NOT
run unguarded instrumentation on the Samsung installation containing personal Books/Comics:

```bash
./gradlew :core:connectedDebugAndroidTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
```

For a test image with an enrolled device PIN, unlock with the PIN immediately before
Keystore tests. A no-lock emulator should exercise **fail closed**. A secure device may
skip interactive-auth dependent cases unless explicitly configured; note any skips in
results. To require authentication in existing tests, pass their instrumentation argument
`vaachakAuthCi=true` through the supported Android test instrumentation mechanism.
Do not set that flag on an unprovisioned no-lock emulator.

## Manual acceptance matrix — use synthetic credentials only

Fill actual results, dates, devices, Android versions and CI links here.
**None of the rows below is implicitly passed by green CI.**

| Case | Samsung S25 | BOOX (model / firmware) | Evidence / observations |
|---|---|---|---|
| A1. PIN enabled, unlock, save Gemini / Cloudflare / Gateway and sync credentials | Pending | Pending | |
| A2. Switch local vault and confirm credentials are isolated | Pending | Pending | |
| A3. Close app, force-stop, reopen after PIN unlock; secrets remain accessible | Pending | Pending | |
| A4. Reboot, unlock with PIN, open app; verify ciphertext persisted and no plaintext recreated | Pending | Pending | |
| A5. After >60 seconds without fresh PIN authentication, sync fails closed; after PIN unlock, recovery succeeds | Pending | Pending | |
| A6. With PIN disabled on a **disposable** device, new encrypted credentials refuse to save; offline reading still works | Pending | Pending | |
| A7. Upgrade from previous app with synthetic legacy plaintext and VAG1 ciphertext; retry after lock/unlock | Pending | Pending | |
| A8. HTTPS sync login, register, health, and vault sync; local HTTPS reverse proxy if used | Pending | Pending | |
| A9. `http://` sync URL and invalid/self-signed TLS certificate fail closed (no silent HTTP downgrade) | Pending | Pending | |
| A10. Synthetic credentials absent from logcat and diagnostic UI; no HTTP request-body logs | Pending | Pending | |
| A11. OPDS/Calibre authenticated HTTPS feeds and downloads work; no HTTP Basic credentials sent over cleartext | Pending | Pending | |
| A12. Books EPUB import, Comics CBZ/CBR, navigation and reading progress regression smoke | Pending | Pending | |
| A13. Data backup/transfer excludes local credential files and databases | Pending | Pending | |

### Important device rules

- Confirm Android user using `adb shell pm list users`; **use user 0** for owner only. Do
  not run `pm clear`, uninstall/reinstall, test-run cleanup, or backup/restore against the
  personal Samsung installation. Debug app ID is `org.vaachak.reader.leisure.debug`.
- Do not run Gradle connected tests on the primary device just to collect coverage;
  Android Gradle Plugin may remove APKs or reset test app state.
- Never put real access tokens, passwords, cookies, or private URLs in PR descriptions,
  terminal transcripts, bug reports, screenshots, or CI artifacts.
- For A7, use a dedicated test installation with a controlled upgrade path and **only
  synthetic secrets**. Android private app directories are not ordinarily readable over ADB.
  Do not root a personal device or patch its preferences to manufacture a legacy state.
- If BOOX has no device PIN, expected behavior is **no secure credential storage**.
  Existing offline reading must remain functional. Do not bypass the screen-lock check.
- After each test, record *pass/fail/blocked* and observed app behavior, never secret values.

## Security review references and ownership

1. `core/src/androidMain/kotlin/org/vaachak/reader/core/data/repository/PrivateGatewaySecrets.kt`: VAG2/VAS2 authenticated AES-GCM files in `noBackupFilesDir`, 60-second device-credential window, VAG1 migration.
2. `core/src/androidMain/kotlin/org/vaachak/reader/core/data/repository/SettingsRepository.kt`: per-vault encryption and non-destructive plaintext migration.
3. `core/src/androidMain/kotlin/org/vaachak/reader/core/data/remote/SyncApi.kt`: redaction/TLS/redirect fix in this package.
4. `core/src/androidMain/kotlin/org/vaachak/reader/core/data/repository/OpdsRepository.kt`: HTTPS-only OPDS requests, but Room feed credentials still need at-rest decision.
5. `leisure/src/main/AndroidManifest.xml`, `res/xml/backup_rules.xml`, and `res/xml/data_extraction_rules.xml`: backups disabled/excluded.

## Approval gate

Do not merge PR #72 into `main` based only on green CI. A reviewer must record:
- all required matrix rows passed or explicitly waived with owner and rationale;
- SEC-03 OPDS/Calibre-at-rest decision and SEC-04 background-sync limitation decision;
- complete Android CI, Sonar, CodeQL, MobSF, lint results for the integration commit;
- no real credentials in logs, reports, or test fixtures.

When signed off, mark PR #72 ready, then merge and delete the feature branch. Do not
merge this branch or PR #72 into `main` until security decisions are closed.
