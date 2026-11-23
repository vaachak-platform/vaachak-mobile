# PR #58 — Compose UI instrumentation and SonarCloud coverage

## Goal and ownership

Vaachak is the source of truth. SableOS test patterns are only references, not a replacement
for Vaachak state, navigation, security or UI behavior. PR #58's gate currently reports about
28% new-code coverage (80% required) and a C security rating (A required). The main uncovered
new UI is `SettingsScreen.kt` and `AiConfigScreen.kt`.

## What's in this delivery

- The debug build in both Android modules enables `enableAndroidTestCoverage`.
- Existing JVM JaCoCo unit reports continue to run.
- Production Settings has a small `SettingsContentState` / `SettingsUiActions` seam; the
  Hilt ViewModel still supplies production state and callbacks. The actual responsive
  chooser, detail panes, Comics, Offline Mode and navigation remain the same.
- The AI configuration screen shares an `AiConfigForm` composable with tests. Its existing
  validation and submit callbacks still invoke `SettingsViewModel.saveAiConfig` in production.
- The emulator tests render and interact with those **same production composables** on
  a standard Android test Activity. No real AI keys or remote calls are used.
- Device-only tests exercise the real AndroidKeyStore secret vault in the core module.
- CI runs an API 35 emulator with KVM, produces the AGP AndroidTest JaCoCo XML for
  both modules and feeds **four** explicit XML paths (two JVM + two AndroidTest) to Sonar.
  CI fails if a required report is absent; tests passing is not enough.

## Local commands (with a booted Android emulator)

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew \
  :core:testDebugUnitTest :leisure:testDebugUnitTest \
  :core:jacocoDebugUnitTestReport :leisure:jacocoDebugUnitTestReport \
  :core:createDebugAndroidTestCoverageReport \
  :leisure:createDebugAndroidTestCoverageReport \
  :leisure:assembleDebug
```

Find the instrumented reports:

```bash
find core/build/reports/coverage/androidTest/debug/connected \
     leisure/build/reports/coverage/androidTest/debug/connected \
     -type f -name '*.xml' -print
```

For local Android testing without running the instrumentation suites, `:leisure:assembleDebug`
remains supported. The debug APK instrumentation adds overhead; this is a test build and
must not change release APK signing or application IDs.

## Coverage signoff

1. Confirm both JVM and instrumented JaCoCo reports exist and contain a `<report>` element.
2. Verify the Sonar task imports all four reports **for the matching module**.
3. Inspect SonarCloud's *new uncovered lines* and *new uncovered conditions* lists after CI.
4. Require **new-code coverage >= 80%**, Security A, Reliability A, and duplication <=3%.
5. Add tests for real alternate states, errors and e-ink layouts. Never reduce the gate,
   exclude newly introduced application code, or mark findings as false positives to pass CI.
6. If code changes are required to fix a failing test, rerun local unit and emulator suites.
7. Keep PR #58 draft until CI and device regression checks pass.

### Security finding is separate from coverage

`PrivateGatewaySecrets` creates a non-exportable AndroidKeyStore key with
`setUserAuthenticationRequired(false)`, explicitly supporting BOOX devices without a lock screen.
This is a Sonar security review item, not a coverage item. Review it under Vaachak's threat
model, document why the unlocked-app risk is acceptable or provide compatible mitigation,
and use Sonar's review process for the specific finding. Do **not** flip it to `true`
blindly: on some e-readers that could render saved credentials unavailable. Device-only tests
check encryption, isolation and ciphertext corruption but do not substitute for review.

## Future deliverables — mandatory SonarCloud checklist

For every feature branch / draft PR:

- Confirm actual changed-source scope and Sonar new-code denominator before planning tests.
- Write unit and Android instrumented tests **at the same time** as production code.
- Consider branch-condition coverage, not only line coverage; test success, failure and null states.
- For Compose UI additions, include instrumentation and JaCoCo XML collection in CI.
- For network or credentials code, review SSRF, transport security, logs, storage and
  scanner security issues explicitly; use real AndroidKeyStore tests where required.
- Avoid new Detekt/Sonar maintainability issues (parameter lists, cognitive complexity,
  missed result checks, hardcoded dispatchers) and reliability regressions.
- Verify `:core:testDebugUnitTest`, `:leisure:testDebugUnitTest`, instrumented coverage,
  Android lint, Detekt and SonarCloud all pass **before merging**.
- Preserve BOOX/e-ink low-motion, large-font and profile isolation behavior.
- Ship **complete files in a ZIP**, with docs and CI updates, under a feature branch and
  draft PR; never merge until user has built and validated the device behavior.

## Tooling references

- Android Developers, *View code coverage reports*:
  https://developer.android.com/studio/test/coverage-report
- ReactiveCircus, Android Emulator Runner:
  https://github.com/ReactiveCircus/android-emulator-runner
- SonarSource, Gradle multi-module JaCoCo example:
  https://github.com/SonarSource/sonar-scanning-examples/tree/master/sonar-scanner-gradle/gradle-multimodule-coverage

## Known limitations

- Source was reconstructed from the user-uploaded PR #58 archive and the most recent
  remediation/replacement ZIPs available in this conversation. Review diffs if the local
  branch has since changed.
- Android SDK and emulator are unavailable in the creation environment. Compilation,
  instrumented execution and the actual Sonar percentage **have not been verified** here.
- The tests and coverage pipeline target the largest known gaps, but no precise coverage
  increase or 80% pass is guaranteed until the CI run completes.
- A single x86_64 API-35 emulator does not replace Samsung and BOOX device validation.
