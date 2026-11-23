# PR #58: deterministic wide Settings UI test on GitHub Actions

## Failure addressed

The hosted API 35 emulator now starts and runs instrumented tests. Its latest
run passed all four `:core` tests and 10 of 11 `:leisure` tests. Only
`SettingsComposeInstrumentedTest.wideEinkSettingsShowsCategoryListAndDetailsTogether`
failed, looking for the exact `Offline Mode, Default: On. Cloud features disabled.`
semantics node before selecting a Settings category.

The old test simulated a wider device using `Density(0.75f, 1f)`. On the generic
GitHub Actions AVD, the available pixel width may translate to less than the
`SettingsLayoutPolicy` threshold of **720 dp**. In compact mode, the General
settings content is not on screen until General is selected. The error does not
prove an emulator boot problem or a production regression.

## Complete files in this package

- `leisure/src/androidTest/kotlin/org/vaachak/reader/leisure/ui/settings/SettingsComposeInstrumentedTest.kt`
- `.github/workflows/sonar.yml`
- `docs/SONAR_PR58_WIDE_LAYOUT_CI_FIX.md`

## Changes

1. The wide-only test obtains the instrumented Activity's actual display pixel
   width and derives `LocalDensity` for a **1200 dp** nominal viewport. This
   keeps the test above both the ordinary 720 dp and large-font 920 dp layout
   thresholds, with margin for small window insets, independent of the AVD
   hardware profile. The other five Settings UI tests use the normal density.
2. It asserts that **both** the Settings General category control and the
   `ACCOUNT & PROFILES` header are displayed *before* a category click. This
   proves that the category chooser and default General details coexist in
   the production two-pane UI, rather than merely asserting a shared control.
3. It switches to Comics and asserts the chooser remains displayed alongside
   `READING DIRECTION` and the manga reading-direction option.
4. The CI emulator `script` prints `adb shell wm size` and `adb shell wm density`
   before the Gradle invocation. Everything stays on **one physical script
   line** to avoid the previously observed literal-backslash Gradle task error.
5. No production Kotlin, authentication, network, Gradle dependencies, test
   assertions in the other five Settings cases, or SonarCloud thresholds change.

## Apply and test (feature/ereader-adaptive-settings)

From the repository root, unzip the package with overwrite enabled, then:

```sh
./gradlew :leisure:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.vaachak.reader.leisure.ui.settings.SettingsComposeInstrumentedTest

./gradlew :core:createDebugAndroidTestCoverageReport :leisure:createDebugAndroidTestCoverageReport
```

The **hosted** API 35 emulator remains the decisive acceptance environment for
this CI-specific failure; the connected Samsung is a second test environment.

Verify the new job passes all **4 Core + 11 Leisure = 15** Android instrumented
tests and that these JaCoCo XML reports are non-empty and imported by Sonar:

- `core/build/reports/coverage/androidTest/debug/connected/report.xml`
- `leisure/build/reports/coverage/androidTest/debug/connected/report.xml`
- `core/build/reports/jacoco/jacocoDebugUnitTestReport/jacocoDebugUnitTestReport.xml`
- `leisure/build/reports/jacoco/jacocoDebugUnitTestReport/jacocoDebugUnitTestReport.xml`

SonarCloud acceptance for PR #58 stays unchanged: **at least 80% new-code
coverage (including conditions), Security A, Reliability A, and at most 3%
duplication**. The separate `AiRepository.kt` coverage and security issue must
still be reviewed as needed. Do not merge until required checks are green.

This package was statically checked, **not executed on an Android emulator in
this build environment**. If CI still reports a failure, use the current
Android test report and the new `wm size`/`wm density` diagnostic values to
pinpoint the next change without hiding or skipping tests.
