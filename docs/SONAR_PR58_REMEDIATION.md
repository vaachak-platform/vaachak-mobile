# PR #58 SonarCloud remediation (November 2026)

This branch uses the current **vaachak-mobile** source. No SableOS application code is being used as a replacement baseline.

## Changes

- `PrivateGatewayClient`: portable coroutine dispatcher from `AppCoroutineConfig`, Kotlin `check` validation for HTTP response and missing text, expanded failure-path tests.
- `SettingsViewModel`: replaces the weak `Any?` vararg AI-settings API with a typed overload; restores the configured provider, gateway URL and model to UI state. Legacy four-field AI saves remain supported.
- `AiRepository`: aligns HTTP status logging with the existing Timber convention, without logging credentials or response bodies.
- `SettingsRepository` / `PrivateGatewaySecrets`: the previous code saved private-gateway credentials **as plaintext in Preferences DataStore**, while the AES-GCM Keystore implementation existed but was unused. New writes now go through the encrypted storage and legacy plaintext values are migrated on the next read. Migration only removes plaintext after successful encrypted storage. Existing vault separation is preserved.
- Tests cover successful gateway calls, unauthorized responses, blank responses, missing text, invalid input, vault isolation, provider persistence, callback failures, and comic preferences.
- Sonar workflow fails early if a JaCoCo XML report expected by the quality gate is missing; docs-only trigger filters remain unchanged.

## Security hotspot decision

`PrivateGatewaySecrets` uses a non-exportable Android Keystore AES-GCM key but does not require per-use biometric/device credential authentication. This is deliberate to support BOOX/e-ink readers that may not have secure lock hardware and reader use cases that require uninterrupted actions. The ciphertext is stored in `noBackupFilesDir`, and no gateway credentials are logged. **Residual risk:** an attacker controlling an unlocked app session can use the saved gateway identity. Use revocable, narrowly scoped server-side tokens. Sonar security hotspots must be reviewed in SonarQube Cloud by an authorized reviewer; do not mark them safe without assessing Vaachak's device and vault threat model.

This change only encrypts the *private gateway* token. Existing Gemini/Cloudflare credentials in Preferences DataStore remain a separate security-hardening item.

## Local verification

```bash
./gradlew :core:testDebugUnitTest :leisure:testDebugUnitTest \
  :core:jacocoDebugUnitTestReport :leisure:jacocoDebugUnitTestReport \
  :leisure:assembleDebug
./gradlew :leisure:installDebug
```

On a test profile with a previously configured gateway key, verify one-time migration, reboot/resume, profile switching, clearing a key, and access to the gateway. Recheck Gemini/Cloudflare settings, Comics preferences and adaptive Settings on Samsung S25 Ultra and BOOX.

## Sonar coverage gate

SonarCloud previously reported **16.1% coverage on new code**. This replacement adds tests but **has not been built or analyzed in this environment**. It is not possible to claim the 80% gate has been met without a fresh Sonar analysis and per-file coverage values. In particular, the new Compose `SettingsScreen` contains UI code that might need instrumented Compose UI tests and JaCoCo aggregation to reach the quality gate. Do not exclude real application code or relax the gate to force a pass.

Use SonarCloud's per-file coverage UI or the `api/measures/component_tree` endpoint for PR 58 to identify uncovered files and lines. Run a new Sonar scan after applying this delivery, and continue adding behavior-focused tests for uncovered code.
