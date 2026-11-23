# PR #58 — Isolated Compose instrumentation host (runtime fix)

## Observed failure

On the Samsung SM-S938U1, `:core:connectedDebugAndroidTest` completed all 4 tests,
but all 10 `:leisure:connectedDebugAndroidTest` cases failed with
`IllegalStateException: No compose hierarchies found in the app`.
Application Kotlin and `compileDebugAndroidTestKotlin` had already compiled.
This is a shared UI test-host/root-attachment failure, **not** evidence that all 10
production Settings behaviors are defective.

## Full-file changes

- New `ComposeTestHostActivity` is a **debug-only, non-exported, empty** `ComponentActivity`.
  Its manifest entry is in the complete `leisure/src/debug/AndroidManifest.xml`.
  Release APKs are unaffected; no Hilt graph, database, network, or real secrets are involved.
- Both Settings and AI Compose tests use `createAndroidComposeRule<ComposeTestHostActivity>()`
  instead of relying on an implicit generic activity.
- Added `ComposeHostSmokeTest` to distinguish host/lifecycle failures from real UI assertions.
- No production Settings/AI code, Gradle dependencies, Sonar quality gate, or signing changes.

## Device validation order

Keep the Samsung screen **on and unlocked**, and disable its Don't Keep Activities
developer setting. Use a booted emulator or attached device for instrumented tests.

```bash
./gradlew :leisure:assembleDebugAndroidTest
./gradlew :leisure:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.vaachak.reader.leisure.ui.settings.ComposeHostSmokeTest
./gradlew :leisure:createDebugAndroidTestCoverageReport
./gradlew :core:createDebugAndroidTestCoverageReport
```

The smoke test must pass **before** running all Settings tests. If it still reports
`No compose hierarchies found`, do not change production UI or weaken the coverage gate.
Capture logs instead:

```bash
adb logcat -c
./gradlew :leisure:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.vaachak.reader.leisure.ui.settings.ComposeHostSmokeTest
adb logcat -d -v time -s AndroidRuntime:E ActivityTaskManager:I ComposeTestHost:D TestRunner:I \
  > "$HOME/Downloads/vaachak-compose-host-logcat.txt"
```

Inspect `leisure/build/reports/androidTests/connected/debug/index.html` and any
AndroidRuntime exception as well. Do not publish tokens or personal information.

If the smoke test passes but a screen test fails, investigate that specific
assertion and fix the smallest relevant test or behavior. The 80% new-code
coverage and Security A requirements remain unchanged; only a successful
Sonar analysis can validate them. Vaachak remains the source of truth.

## Installation and PR scope

Extract from repository root, review `git diff --check` and source changes,
then commit only these five paths plus this document. Avoid staging signing keys,
Android package-verification artifacts, or other untracked local files.
