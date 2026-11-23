# PR #58 — SettingsScreen condition-coverage matrix

## Purpose

Focus exclusively on **SettingsScreen.kt** condition coverage. The last supplied SonarCloud analysis reported **68.8%** new-code coverage for this file, with **28 uncovered lines and 146 uncovered conditions**. We are not changing Sonar thresholds or production Settings behavior.

## Additive files

- `leisure/src/androidTest/kotlin/org/vaachak/reader/leisure/ui/settings/SettingsScreenConditionMatrixInstrumentedTest.kt`
- `leisure/src/androidTest/kotlin/org/vaachak/reader/leisure/ui/settings/SettingsScreenActionRoutesInstrumentedTest.kt`

Both are **new full source files** and preserve all existing tests, especially `SettingsComposeInstrumentedTest.kt` and `SettingsConditionCoverageInstrumentedTest.kt`. They use the actual `SettingsScreenContent` composable, not a surrogate UI.

## What is exercised

| Test | Matrix/actions | Behavior under test |
|---|---:|---|
| Wide category matrix | 144 category-state scenarios | 3 themes × 2 offline states × 2 multi-user states × 2 account auth states × 6 categories; two-pane chooser remains visible; detail pane contents match state |
| Compact category matrix | 36 category-state scenarios | 3 themes × 2 offline states × 6 categories; large-font compact branch, category-selection and back navigation |
| Comic preference matrix | 128 preference combinations | Exhausts three two-valued comic enum preferences and four booleans, plus real radio/switch callbacks and preservation of unrelated settings |
| Large-font wide layout | 2 panes | Exercises the large-font width threshold and a category switch |
| Profile dialog and toggles | Multiple real callbacks | Signed-in cloud warning, signed-out cancel, whitespace name rejection, valid profile selection, multi-user on/off, offline on/off |
| Navigation actions | 10 actual destinations | Local profiles, sync, theme, bookshelf, book appearance, TTS (two entry points), dictionary, catalogs and AI; a minimal real `NavHost` verifies each route |

**Important:** 144 + 36 + 128 describes **308 runtime input scenarios**, **not** 308 distinct JaCoCo condition outcomes. Sonar condition coverage depends on the actual compiler-emitted branches and the new-code baseline. The requested goal of covering approximately 100 currently missed conditions must be verified by the next JaCoCo/SonarCloud analysis; it cannot be guaranteed from source inspection alone.

The test-specific `Density` converts the device's current screen pixel width into either a reliable wide 1280 dp viewport or compact 410 dp viewport. The physical emulator/phone configuration and production layout policy do not change.

## Validate on Mac

```bash
./gradlew :leisure:assembleDebugAndroidTest

./gradlew :leisure:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.vaachak.reader.leisure.ui.settings.SettingsScreenConditionMatrixInstrumentedTest

./gradlew :leisure:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.vaachak.reader.leisure.ui.settings.SettingsScreenActionRoutesInstrumentedTest

# Generate a report from the FULL suite, not just a selected class:
./gradlew :core:createDebugAndroidTestCoverageReport :leisure:createDebugAndroidTestCoverageReport
```

Check the local report:

`leisure/build/reports/coverage/androidTest/debug/connected/index.html`

For a meaningful before/after comparison, record the line and branch counters for `SettingsScreenKt` in `leisure/build/reports/coverage/androidTest/debug/connected/report.xml`, then compare SonarCloud's **new-code** conditions on PR #58. They are not identical measures.

## Risk management

These are Android instrumentation tests, so the Gradle build, device behavior, and next SonarCloud scan must validate them. No tests were executed in the packaging environment because it does not provide the Android SDK/emulator.

No change is made to Keystore security, reader preferences, navigation routes, production `SettingsScreen.kt`, or the Sonar quality gate. If a matrix case fails, preserve the assertion and inspect the reported case instead of dropping the test or excluding the file from coverage.
