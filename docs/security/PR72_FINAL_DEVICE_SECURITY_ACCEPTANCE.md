# PR #72 — Final device security acceptance ledger

**Status: NOT APPROVED for `main`** until the rows below have real test results recorded, with device/build and observed outcome. Synthetic credentials only; do not paste personal tokens, passwords, logcat dumps, databases, or private file contents into PRs.

## Evidence available before this deliverable

- PR #72 automated CI (Android CI, CodeQL, MobSF, lint, Sonar) reported green at head `5432b84` as of November 24, 2026.
- Samsung SM-S938U1 ran 68/68 Leisure instrumentation tests successfully (Comics reader tests), but that does **not** establish credential migration or auth-expiry behavior.
- BOOX Leaf3C ran 13/13 Core instrumentation tests successfully. Some authenticated Keystore tests contain `assumeNoException` and may skip their positive path when authentication is absent. Count passing tests and separately inspect skipped tests and the secure-lock state.

## Mandatory evidence matrix (fill in after running)

| ID | Device / scenario | Required observed result | Actual result / evidence | Approval |
| --- | --- | --- | --- | --- |
| AUTH-01 | Dedicated PIN-enabled Android emulator, device credentials refreshed | VAG2 and VAS2 encrypted round trips, v2 key has `isUserAuthenticationRequired=true`; stored file contains no plaintext | NOT RUN | BLOCKED |
| AUTH-02 | BOOX with PIN enabled (if supported and acceptable to owner) | Positive authenticated credential save/read, restart, and lock timeout; otherwise document unsupported device and product decision | NOT VERIFIED | BLOCKED |
| AUTH-03 | Samsung secure-lock workflow using non-destructive app UI and synthetic test accounts only | Can save/read Gemini, Cloudflare, sync, and gateway credentials; no credential leakage | NOT VERIFIED | BLOCKED |
| MIG-01 | Disposable legacy-profile fixture on emulator only | Plaintext DataStore -> authenticated storage; no loss on failed migration or app restart | NOT VERIFIED | BLOCKED |
| MIG-02 | Disposable VAG1 / old sync ciphertext fixtures | Encrypted files rewritten to authenticated VAG2/VAS2; old aliases preserved where required | NOT VERIFIED | BLOCKED |
| SYNC-01 | Dedicated test endpoint over trusted HTTPS | Works immediately after recent auth; after timeout, password access fails closed without logging or deleting secrets | NOT VERIFIED | BLOCKED |
| SYNC-02 | Lock/unlock/reboot of dedicated test device | Background sync deferred after authorization expiry and resumes only after unlock with fresh credentials | NOT VERIFIED | BLOCKED |
| NET-01 | OPDS/Calibre different-host download | `Authorization` header absent on cross-host or cross-port; same-origin remains authenticated | Unit tests included, device not verified | PENDING |
| DB-01 | Existing OPDS/Calibre catalog credentials | At-rest migration completed and verified, **or** explicit risk acceptance recorded | NOT REMEDIATED | BLOCKED |

## Device safety and procedure

1. Use a dedicated emulator or disposable test device for instrumentation. Gradle's connected Android test can change or uninstall the app and affect app data. **Do not run it on the data-bearing Samsung or BOOX installation.**
2. Enable a PIN on the dedicated test device, unlock immediately, run the Core Keystore instrumentation tests, and inspect reports for skips as well as pass/fail counts. Use only synthetic fixtures.
3. For user devices, use *manual, non-destructive UI acceptance* only. Never execute `adb shell pm clear`, `adb uninstall`, `adb install -r`, `git clean`, root commands, or pull application databases.
4. Test recent-authorized sync and expired authorization against a dedicated HTTPS endpoint; verify that no unattended bypass occurs. Do not include network payloads with real secrets in reports.
5. Record device model, Android version, test method, synthetic fixture ID, pass/fail/skip, and sanitized evidence link. Re-run PR #72 automated CI after every merged correction.

### Suggested emulator-only commands

```bash
./gradlew :core:testDebugUnitTest :leisure:testDebugUnitTest :leisure:assembleDebug :core:lintDebug :leisure:lintDebug detekt
# Only if adb lists a DISPOSABLE emulator/test device, with a secure PIN set and recently unlocked:
./gradlew :core:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
```

## Release decision

- [ ] Automated CI green on final PR #72 head
- [ ] Positive Keystore and negative no-lock cases proved
- [ ] Legacy migration and reboot/expiry proved without data loss
- [ ] OPDS at-rest credential risk remediated or explicitly accepted
- [ ] Samsung and BOOX compatibility evidence reviewed
- [ ] Project owner approves marking draft PR #72 ready

Approval record: **pending**. Date: **pending**. Reviewer: **pending**.
