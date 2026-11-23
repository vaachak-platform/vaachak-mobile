# P0-08 / P0-09 — secure credential migration (review candidate)

**Baseline:** `vaachak-platform/vaachak-mobile` `main` at commit `c1cacfe8e773fa0f14591e7a9f98bdf79f8540e0` (PR #71 merged).  
**Status:** Complete replacement sources prepared; **NOT compiled or device-accepted**. Acceptance statuses remain PENDING.

## Architecture and intended behavior

- `PrivateGatewaySecrets` retains the existing VAG1/VAG2 file format, Gateway key aliases, vault-bound AES-GCM AAD, and recent device-credential authentication policy for private Gateway secrets.
- Direct Gemini and Cloudflare keys share the **authenticated** Android Keystore envelope but have independently named vault-bound files. The internal namespace (`vaachak_credential_v1`) keeps their content separate from the Gateway file and from each other. No provider fallback is added.
- Sync passwords use the same AES-256-GCM file envelope, but with a **separate non-user-authenticated Android Keystore key**. This is deliberate: sync may run unattended or when a screen lock has expired, including on BOOX devices without a secure screen lock. The sync key remains device-bound and is not backed up; it is **not** reused for AI tokens. Threat model and background sync tests are mandatory before merge.
- Legacy plaintext `gemini_api_key`, `cloudflare_token`, and `sync_password` are migrated lazily when their flows are collected. A captured vault ID and a mutex are used throughout each transaction.
- Migration writes the encrypted value, decrypts it for verification, and only then removes the plaintext preference and increments a nonsecret DataStore revision. If it fails, the plaintext is retained for retry and **not used as a network fallback**.
- New writes go only to encrypted files. Explicit logout/credential clearing deletes the named secure file and removes any legacy DataStore value. Saving an empty AI form over a still-present legacy key fails closed rather than deleting it.
- Android backup remains disabled in `AndroidManifest.xml`. The complete replacement XML rules also exclude app files, databases, and preferences from Android backup/device transfer. Test actual manifest merger and backup behavior on target SDK/device versions.
- Existing EPUB, comics preferences, TTS, provider selection, and app release signing logic are untouched.

## User-facing limitations and recovery

Direct Gemini/Cloudflare keys require a configured Android PIN/pattern/password and a recent device-credential unlock (same 60-second policy as existing Gateway tokens). On a BOOX device without a secure lock, local reading remains usable; existing plaintext AI credentials are retained until they can migrate but are not returned to the network caller. Turn on a secure screen lock, unlock, and retry rather than resetting the vault.

An invalidated or deleted Android Keystore key cannot decrypt its ciphertext. The user must explicitly re-enter that credential. The current UI does not yet present a dedicated per-provider key-recovery workflow; test whether the safe existing error handling is sufficient.

A partial multi-field save can update one encrypted slot before another fails. The old plaintext entries are not removed until all writes are verified. A subsequent retry must succeed without losing credentials. A full cross-file atomic transaction is not claimed.

## Must verify before PR is ready to merge

1. Confirm the branch is based on the exact baseline commit or revalidate against newer `main` if it moved.
2. Run `./gradlew :core:testDebugUnitTest :leisure:testDebugUnitTest :leisure:assembleDebug` and `./gradlew detekt`.
3. Run `./gradlew :core:connectedDebugAndroidTest` on a secure unlocked Android device/emulator and a BOOX with no lock screen; record test results and any skipped tests.
4. Upgrade a sanitized existing installation with legacy Gemini, Cloudflare, sync and Gateway credentials; verify migration, app restart, process interruption and per-vault data isolation.
5. Confirm Gemini text, direct Cloudflare Visualize and optional Gateway text continue independently; test Offline Mode and the no-fallback contract.
6. Confirm unattended/background sync after 60+ seconds, reboot/unlock, keyguard changes, and device/user profile changes; no new auto-auth failures.
7. Check that no secrets are included in logcat, backups, diagnostic exports or APK assets; verify Android TLS/certificate behavior and the full error-display surface.
8. Audit Calibre/OPDS catalog credentials in Room and any other secret persistence separately. This package does **not** change those records.
9. Keep `P0-08` and `P0-09` PENDING in `docs/product/ACCEPTANCE_MATRIX.csv` until actual evidence exists. Green CI alone is not enough.

## Unchanged in this package

- Full SettingsViewModel and AI Settings UI; unsafe raw sync error message formatting may still exist and needs a separate review.
- OPDS/Calibre password storage, full networking/TLS clients and provider connectivity diagnostics.
- Release signing, branch protection, CI workflows, AndroidManifest, existing release artifacts, comics/audio integration.

## Recovery / rollback caution

Do not roll back to a production version that only reads plaintext DataStore credentials after the new version has migrated them: that older version may see empty keys. Test rollback/re-entry with sanitized fixtures, and do not promise rollback safety without a designed compatibility strategy.
