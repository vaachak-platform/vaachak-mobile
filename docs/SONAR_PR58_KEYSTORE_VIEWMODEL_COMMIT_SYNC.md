# PR #58 — resolve cross-module authenticated Keystore compilation mismatch

## Proven failure

All three supplied GitHub CI logs fail during `:leisure:compileDebugKotlin` with:

- `SettingsViewModel.kt:21:48` — unresolved `GatewayCredentialUnavailableException` import;
- `SettingsViewModel.kt:376:25` — unresolved exception type in a `catch` clause;
- `SettingsViewModel.kt:378:41` — unresolved `.message` cascading from the missing type.

In the same CI logs `PrivateGatewaySecrets.kt` shows the `@ApplicationContext` annotation at line **28**, which matches the earlier `v1` implementation. The authenticated `v2` replacement has the annotation at line **38** and declares `GatewayCredentialUnavailableException` at package scope. Therefore the Core security implementation and the Leisure ViewModel being compiled are from different revisions. The JVM/Gradle `expect`/`actual`, Kotlin annotation-target, and other warnings are not the root cause.

## Full-file replacements in this ZIP

1. `core/src/androidMain/kotlin/org/vaachak/reader/core/data/repository/PrivateGatewaySecrets.kt` — authenticated `v2` Android Keystore key; `GatewayCredentialUnavailableException` declaration; migrates `VAG1` data after authorization; fails closed on devices without a secure screen lock.
2. `core/src/androidInstrumentedTest/kotlin/org/vaachak/reader/core/data/repository/PrivateGatewaySecretsInstrumentedTest.kt` — security-compatible tests for devices **with** and **without** a secure lock.
3. `leisure/src/main/java/org/vaachak/reader/leisure/ui/settings/SettingsViewModel.kt` — retains the `AiGatewayUpdate` and `AiConfigSaveRequest` contracts, and catches the named safe security exception.
4. This document.

**Do not selectively commit just the ViewModel.** The exception is defined in Core, and both modules must be committed on the same PR revision. Do not add a duplicate class under the Leisure package or remove device authentication to make a build pass.

## Local application and validation

```bash
cd /Users/piyushdaiya/Documents/projects/vaachak-platform/vaachak-mobile
git switch feature/ereader-adaptive-settings
unzip -o ~/Downloads/vaachak-pr58-keystore-viewmodel-commit-sync-full-files.zip -d .
git diff --check

./gradlew :core:compileDebugKotlinAndroid :leisure:compileDebugKotlin \
  :core:testDebugUnitTest :leisure:assembleDebugAndroidTest
```

With a test device connected, unlocked, and using a configured secure screen lock for authenticated-key round-trip tests:

```bash
./gradlew :core:createDebugAndroidTestCoverageReport \
  :leisure:createDebugAndroidTestCoverageReport
```

Stage **all three Kotlin files** and the documentation together:

```bash
git add \
  core/src/androidMain/kotlin/org/vaachak/reader/core/data/repository/PrivateGatewaySecrets.kt \
  core/src/androidInstrumentedTest/kotlin/org/vaachak/reader/core/data/repository/PrivateGatewaySecretsInstrumentedTest.kt \
  leisure/src/main/java/org/vaachak/reader/leisure/ui/settings/SettingsViewModel.kt \
  docs/SONAR_PR58_KEYSTORE_VIEWMODEL_COMMIT_SYNC.md

git diff --cached --check
git diff --cached --stat
git diff --cached --name-only
```

The staged list MUST show the Core production source and the Leisure ViewModel together. Commit and push only after local compilation succeeds:

```bash
git commit -m "fix: synchronize authenticated gateway exception across modules"
git push origin feature/ereader-adaptive-settings
```

Confirm the pushed tree contains the declared exception:

```bash
git grep -n 'class GatewayCredentialUnavailableException' HEAD -- \
  core/src/androidMain/kotlin/org/vaachak/reader/core/data/repository/PrivateGatewaySecrets.kt
git grep -n 'GatewayCredentialUnavailableException' HEAD -- \
  leisure/src/main/java/org/vaachak/reader/leisure/ui/settings/SettingsViewModel.kt
```

If either output is empty, stop before relying on CI: both revisions were not committed.

## Security / quality gate notes

This keeps device-credential-authenticated KeyStore enforcement and makes private gateway token persistence unavailable on BOOX devices lacking a secure lock. Offline reading remains independent. On an Android emulator without a secure screen lock, the instrumented test explicitly verifies fail-closed behavior rather than expecting successful storage. Existing device credentials and `VAG1` migration must still be tested on a Samsung before merging. No SonarCloud exclusions or thresholds are changed. This source sync fixes the compile error; it does not guarantee the >=80% new-code coverage or Security A ratings. Keep PR #58 draft until both CI and device testing are complete.
