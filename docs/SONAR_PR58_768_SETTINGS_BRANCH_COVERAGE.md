# PR #58 — 76.8% new-code coverage follow-up

## Baseline (SonarQube Cloud PR #58)

- New-code coverage: **76.8%**; gate: **80%**.
- `SettingsScreen.kt`: **68.8%**, 28 uncovered new-code lines and 146 uncovered new-code conditions.
- `AiConfigScreen.kt`: **77.3%**, 8 uncovered new-code lines and 22 uncovered new-code conditions.
- `SettingsRepository.kt`: **80.2%**; the earlier data-store coverage additions are working.
- `PrivateGatewaySecrets.kt`: **85.4%**; keep authenticated Android Keystore behavior unchanged.

## Included additions

Two **new full Kotlin source files** are added; no existing files are overwritten:

1. `leisure/src/androidTest/kotlin/org/vaachak/reader/leisure/ui/settings/SettingsConditionCoverageInstrumentedTest.kt` — 6 behavioral tests for compact back handling, cross-category offline state, LCD wide category selection, theme changes, independent multi-user/offline toggles, and signed-out vault switching.
2. `leisure/src/androidTest/kotlin/org/vaachak/reader/leisure/ui/settings/AiConfigConditionCoverageInstrumentedTest.kt` — 4 behavioral tests for invalid-then-corrected gateway configuration, missing model recovery, live state refresh, and header/mask interaction with both theme modes.

The tests use the existing `ComposeTestHostActivity`, actual production `SettingsScreenContent` / `AiConfigForm`, stable test IDs and local callbacks. No secrets, network calls, Hilt graph or production Settings changes are required.

## Validation on Android

From repository root:

```bash
./gradlew :leisure:assembleDebugAndroidTest
./gradlew :leisure:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.vaachak.reader.leisure.ui.settings.SettingsConditionCoverageInstrumentedTest
./gradlew :leisure:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.vaachak.reader.leisure.ui.settings.AiConfigConditionCoverageInstrumentedTest
```

Then on a connected unlocked Android device, run the existing full coverage pipeline:

```bash
./gradlew :core:createDebugAndroidTestCoverageReport :leisure:createDebugAndroidTestCoverageReport
```

Only commit/push after **all** new tests and the full suite pass. The hosted emulator also must pass: Samsung-only success is insufficient.

## Verify the effect, don't guess

The Sonar metric is a combination of lines and conditions on **new code**. No new test can guarantee that the metric reaches 80%. If the next scan is still below 80%, inspect the exact uncovered XML `<line nr=... mi=... ci=... mb=... cb=...>` entries for `SettingsScreen.kt` / `AiConfigScreen.kt` and target **those** branches instead of writing general additional tests. The local aggregate instrumented JaCoCo percentages and Sonar PR new-code metric are not interchangeable.

The Sonar coverage import, security gate, code-duplication gate and Android authenticated Keystore implementation must not be bypassed or weakened for the coverage score. Verify the Security A status separately.

## Source-version note

These tests target the code contracts from the latest previously delivered PR #58 Settings and AI form replacements. If the branch has changed those contracts since, align tests to the actual branch **before committing**. No Android SDK/emulator execution was available while building this ZIP.
