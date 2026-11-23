# PR #58 — SonarCloud 71.5% follow-up

## Scope of this delivery

Source of truth: the Vaachak Android sources and CI workflow already under PR #58.
This archive replaces complete files. It does **not** modify runtime key-access policy,
raise targetSdk, weaken the quality gate, or exclude new production code from Sonar coverage.

1. Pin `reactivecircus/android-emulator-runner` to its 40-character v2.38.0 commit SHA.
2. Remove exactly three unused locals from `SettingsScreenContent` (the model still supplies those values to the details pane).
3. Add Robolectric unit tests for `AiRepository` offline handling, missing Gemini key, private gateway success/failure, Cloudflare image success, empty image, HTTP failure, timeout and general errors.
4. Extend the existing Compose instrumentation coverage with authenticated cloud-account blocking, valid local profile changes, multi-user toggle, LCD category navigation and two extra comic switches.

## Run before committing

```sh
./gradlew :core:testDebugUnitTest :core:jacocoDebugUnitTestReport \
  :leisure:testDebugUnitTest :leisure:assembleDebug :leisure:assembleDebugAndroidTest
./gradlew :core:createDebugAndroidTestCoverageReport \
  :leisure:createDebugAndroidTestCoverageReport
```

Use an attached unlocked Android device for the instrumented coverage commands.
CI reruns the suite on an API 35 AVD and scans all four XML reports. Before committing,
inspect the source changes and run `git diff --check`.

## SonarCloud acceptance checklist

- New-code **combined line + condition** coverage >= 80%; current user-reported baseline 71.5%.
- Security rating A and reliability rating A; new-code duplication <= 3%.
- No unpinned GitHub Actions in `sonar.yml`.
- The new `AiRepositoryUnitTest` produces covered LINE / BRANCH counters in the core JVM JaCoCo XML.
- Both Compose and Keystore instrumented XML reports are still included and imported.
- No new unresolved security vulnerabilities introduced.

These tests target missing coverage, but **80% cannot be guaranteed before the next scanner run**.
Do not merge this draft PR until its required checks and on-device testing pass.

## Security review: PrivateGatewaySecrets.kt

Sonar flags `setUserAuthenticationRequired(false)` as an Android Keystore security **hotspot**.
A hotspot is a review requirement, not automatically a confirmed vulnerability.
The app currently permits non-biometric BOOX readers and unattended reader actions.
Changing this call to `true` can require a device lock or user authentication and
would break those supported use cases.

Threat model and current mitigations:

- The AES-256-GCM key is non-exportable in Android Keystore; the Android app context holds ciphertext under `noBackupFilesDir`.
- Ciphertext is scoped by vault ID; GCM includes integrity authentication; token validation rejects whitespace and overlong tokens.
- Stored ciphertext is not intended to be included in Android app backups; plaintext keys are not logged.
- Users or malware with control of an already-unlocked app may still invoke app features with the stored credential; Keystore encryption alone does **not** provide an app lock.
- A compromised OS, rooted device, or malicious application code can access the decrypted token while Vaachak is running.
- Legacy plaintext migration in `SettingsRepository.loadPrivateGatewayToken()` should be reviewed for backup, crash and downgrade implications.

**Action for the repository security owner:** Review the hotspot in SonarCloud with the
above evidence and the BOOX support requirement. Mark it reviewed/safe only if the threat
model and residual risk are acceptable, otherwise file a redesign issue (optional app/vault
lock or per-device authentication policy). This package does not change the risk policy
or claim that a manual review has been performed.

## Other 9-issue-list items intentionally deferred

- Room testing 2.8.4 -> 2.8.5: plan a coordinated Room version update, including the runtime and codegen KMP dependencies.
- Robolectric 4.16.1 -> 4.18.1: evaluate in a standalone compatibility PR with Kotlin, Gradle 8.13, and API 36 tests.
- Coroutines-test 1.10.2 -> 1.11.0: align with production coroutine dependency compatibility; don't upgrade an isolated test artifact blindly.
- `targetSdk` from 36 to the newest stable platform: treat as an intentional platform migration requiring device validation and AGP / compileSdk compatibility checks.
- None of those low-severity maintenance notices explains the coverage shortfall or substitutes for the security hotspot review.
