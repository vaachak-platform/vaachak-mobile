# PR #58 — Compose Settings category selector test fix

## Failure observed (Samsung SM-S938U1, Android 17)

After the debug-only `ComposeTestHostActivity` fix, the dedicated Compose host smoke test passed, all four AI configuration UI tests passed, and all four core device tests passed. The six remaining Settings UI tests failed to locate the category chooser using exact `ContentDescription = "Settings: ..."` matchers.

## Root cause and scope

The production `SettingsCategoryChooser` already attaches stable, intentional test identifiers to each category via `Modifier.tid(item.tag)`. Vaachak's `tid()` applies both `testTag` and content-description semantics. The category rows also attach their own merged accessibility semantics. An exact content-description matcher is not a stable way to select these controls across the semantics tree.

This delivery replaces selectors in the **entire** `SettingsComposeInstrumentedTest.kt` file with `onNodeWithTag(...)`, reusing established `Tid.Settings.*` values for General, Books, Library & content and Intelligence, and existing production tags for Comics and Audio. No production source is changed. Assertions for real Settings content, saved Comics choices, navigation, offline state and the two-pane layout remain in place.

## Installation

Extract the ZIP into the repository root **on branch `feature/ereader-adaptive-settings`**. It contains the full test file, not a patch script.

## Local validation

With an unlocked Android device attached:

```sh
./gradlew :leisure:assembleDebugAndroidTest
./gradlew :leisure:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.vaachak.reader.leisure.ui.settings.SettingsComposeInstrumentedTest
```

If successful, run the entire device test and coverage path:

```sh
./gradlew :core:createDebugAndroidTestCoverageReport :leisure:createDebugAndroidTestCoverageReport
```

The full test suite must pass before pushing and before SonarCloud analysis can be relied upon. If a later assertion fails after category selection, use the specific assertion failure and `leisure/build/reports/androidTests/connected/debug/index.html` to refine the test, not to disable or skip it.

## Coverage and SonarCloud policy

This resolves a test targeting issue; it **does not guarantee** 80% new-code coverage or Security Rating A. The new CI-generated instrumented JaCoCo report still needs to be verified after execution. Do not exclude production UI code or lower quality-gate thresholds to pass PR #58.

## Review note

Since `tid()` supplies content descriptions as well as test tags, Vaachak should review Settings accessibility semantics in a separate focused change; stable test tags are not a substitute for human-readable TalkBack labels. Any accessibility changes should be validated on both Samsung and BOOX before release.
