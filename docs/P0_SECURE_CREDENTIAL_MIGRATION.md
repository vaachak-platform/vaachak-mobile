# P0-08 / P0-09 — secure credential migration (review candidate)

**Baseline:** `vaachak-platform/vaachak-mobile` `main` at commit `c1cacfe8e773fa0f14591e7a9f98bdf79f8540e0` (PR #71 merged).  
**Status:** PR #72 follow-up for SonarCloud kotlin:S6288; complete replacement sources. Android build, migration and device acceptance remain PENDING.

## Architecture and intended behavior

- `PrivateGatewaySecrets` retains the existing VAG1/VAG2 file format, Gateway key aliases, vault-bound AES-GCM AAD, and recent device-credential authentication policy for private Gateway secrets.
- Direct Gemini and Cloudflare keys share the **authenticated** Android Keystore envelope but have independently named vault-bound files. The internal namespace (`vaachak_credential_v1`) keeps their content separate from the Gateway file and from each other. No provider fallback is added.
- **SonarCloud kotlin:S6288 remediation:** New sync-password encryption now uses the existing **device-credential-authenticated Android Keystore v2 key** (`setUserAuthenticationRequired(true)` and, on Android 11+, `setUserAuthenticationParameters(60, AUTH_DEVICE_CREDENTIAL)`). The sync ciphertext envelope is `VAS2`, distinct from the historical `VAG2` format. No new non-authenticated Keystore keys are created.
- **Compatibility migration:** Existing `VAG2` SYNC_PASSWORD ciphertext encrypted under `vaachak.background.sync.key.v1` is recognized separately. Reading it requires successful recent device authorization *before* opening the historical key. It is migrated to authenticated `VAS2` using an atomic replacement only after successful encryption; on failure the old ciphertext remains unchanged. Keep the v1 alias read-only because other vaults may still need migration; never generate it on new installs.
- **Functional tradeoff (do not conceal):** This change disables truly unattended, password-based sync once the 60-second device-credential window has expired, and prevents persistent sync passwords on BOOX devices without secure screen locks. This is a direct consequence of applying the Sonar-recommended policy. If unattended sync is mandatory, the right alternative is to explicitly review/accept the Sonar hotspot with a documented threat model and retain the previous device-bound non-authenticated sync key, not to bypass authentication checks or invent a plaintext fallback. **Do not merge without accepting this product tradeoff.**
- Legacy plaintext `gemini_api_key`, `cloudflare_token`, and `sync_password` are migrated lazily when their flows are collected. A captured vault ID and a mutex are used throughout each transaction.
- Migration writes the encrypted value, decrypts it for verification, and only then removes the plaintext preference and increments a nonsecret DataStore revision. If it fails, the plaintext is retained for retry and **not used as a network fallback**.
- New writes go only to encrypted files. Explicit logout/credential clearing deletes the named secure file and removes any legacy DataStore value. Saving an empty AI form over a still-present legacy key fails closed rather than deleting it.
- Android backup remains disabled in `AndroidManifest.xml`. The complete replacement XML rules also exclude app files, databases, and preferences from Android backup/device transfer. Test actual manifest merger and backup behavior on target SDK/device versions.
- Existing EPUB, comics preferences, TTS, provider selection, and app release signing logic are untouched.

## User-facing limitations and recovery

Gemini, Cloudflare, Gateway **and sync-password** secure storage now require a configured device PIN/pattern/password and an authentication event within the 60-second window. Unlocking the UI via fingerprint or leaving the screen on does not necessarily satisfy device-credential authorization. On BOOX devices without a secure lock, local/offline reading remains usable, but credential storage and network authentication are unavailable; legacy preferences/ciphertext are retained for retry. Never silently fall back to plaintext.

An invalidated or deleted Android Keystore key cannot decrypt its ciphertext. The user must explicitly re-enter that credential. The current UI does not yet present a dedicated per-provider key-recovery workflow; test whether the safe existing error handling is sufficient.

A partial multi-field save can update one encrypted slot before another fails. The old plaintext entries are not removed until all writes are verified. A subsequent retry must succeed without losing credentials. A full cross-file atomic transaction is not claimed.

## Must verify before PR is ready to merge

1. Confirm the branch is based on the exact baseline commit or revalidate against newer `main` if it moved.
2. Run `./gradlew :core:testDebugUnitTest :leisure:testDebugUnitTest :leisure:assembleDebug` and `./gradlew detekt`.
3. Run `./gradlew :core:connectedDebugAndroidTest` on a secure unlocked Android device/emulator and a BOOX with no lock screen; record test results and any skipped tests.
4. Upgrade a sanitized existing installation with legacy Gemini, Cloudflare, sync and Gateway credentials; verify migration, app restart, process interruption and per-vault data isolation.
5. Confirm Gemini text, direct Cloudflare Visualize and optional Gateway text continue independently; test Offline Mode and the no-fallback contract.
6. Verify sync correctly fails closed (rather than silently authenticating) after the 60-second window, reboot and lock-screen changes. Document/review the intentional **loss of unattended password-based sync** before merge. Use a future explicit user-authenticated sync flow or a separately reviewed constrained server-issued token if background sync is a hard requirement.
7. Check that no secrets are included in logcat, backups, diagnostic exports or APK assets; verify Android TLS/certificate behavior and the full error-display surface.
8. Audit Calibre/OPDS catalog credentials in Room and any other secret persistence separately. This package does **not** change those records.
9. Keep `P0-08` and `P0-09` PENDING in `docs/product/ACCEPTANCE_MATRIX.csv` until actual evidence exists. Green CI alone is not enough.

## Unchanged in this package

- Full SettingsViewModel and AI Settings UI; unsafe raw sync error message formatting may still exist and needs a separate review.
- OPDS/Calibre password storage, full networking/TLS clients and provider connectivity diagnostics.
- Release signing, branch protection, CI workflows, AndroidManifest, existing release artifacts, comics/audio integration.

## Sonar hotspot closure caveat

- The hotspot in `PrivateGatewaySecrets.kt` was raised on the former `backgroundSyncKey` generator, which explicitly used `.setUserAuthenticationRequired(false)`. This generator is now removed; all new keys use the authenticated v2 generator. CI/quality gate must be rerun; passing Sonar is not claimed from static inspection alone.
- Existing SYNC_PASSWORD `VAG2` ciphertext and its `vaachak.background.sync.key.v1` alias remain readable only during a strict one-way migration. Test migration with a sanitized real pre-upgrade app fixture. Avoid deleting historical keys or reverting to an old release after re-encryption.
- Full background-sync functionality is **not** preserved under the remediation. This is an explicit decision for the PR reviewer, not an incidental behavioral change.

## Recovery / rollback caution

Do not roll back to a production version that only reads plaintext DataStore credentials after the new version has migrated them: that older version may see empty keys. Test rollback/re-entry with sanitized fixtures, and do not promise rollback safety without a designed compatibility strategy.
