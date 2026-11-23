# PR #58 — Compose instrumented-test import correction

The `:leisure:compileDebugAndroidTestKotlin` task failed because two instrumented test classes imported `androidx.compose.ui.test.assertExists` as a top-level function. `assertExists` is actually a method on `SemanticsNodeInteraction` and is available on the return value of `onNodeWithTag`, `onNodeWithText`, etc., without that import.

## Replacements

- `leisure/src/androidTest/kotlin/org/vaachak/reader/leisure/ui/settings/AiConfigComposeInstrumentedTest.kt`
- `leisure/src/androidTest/kotlin/org/vaachak/reader/leisure/ui/settings/SettingsComposeInstrumentedTest.kt`

The complete test sources are included, unchanged except for removal of the invalid import. Their `.assertExists()` assertions remain intact.

## Validate

```sh
./gradlew :leisure:compileDebugAndroidTestKotlin :leisure:assembleDebugAndroidTest
./gradlew :core:testDebugUnitTest :leisure:testDebugUnitTest :leisure:assembleDebug
```

On a running Android device or emulator:

```sh
./gradlew :core:createDebugAndroidTestCoverageReport :leisure:createDebugAndroidTestCoverageReport
```

Confirm instrumented tests pass and both coverage XML reports are generated, then push only the full replacement files to `feature/ereader-adaptive-settings` to update PR #58. The fix addresses a compilation error only; it does not by itself prove the SonarCloud 80% new-code coverage requirement is met. The Keystore security finding remains a separate review.
