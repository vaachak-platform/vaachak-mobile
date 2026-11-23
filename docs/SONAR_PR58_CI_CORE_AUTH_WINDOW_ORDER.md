# PR #58 — Authenticated Android Keystore test window in Sonar CI

## Observed failure

On the API 35 CI emulator, `:leisure:connectedDebugAndroidTest` passed **34/34** tests. The build then ran `:core:connectedDebugAndroidTest` (9 tests), where four authenticated Keystore tests failed with:

> `CI emulator must be unlocked using its PIN before Keystore tests`

These failures originate in `PrivateGatewaySecretsInstrumentedTest.writeWithAvailableAuthentication`. The `EmulatorConsole` warnings are unrelated to the test assertions.

## Cause and change

`PrivateGatewaySecrets` uses `setUserAuthenticationParameters(60, AUTH_DEVICE_CREDENTIAL)`. The previous CI helper provisioned and unlocked the temporary emulator PIN and then passed **both coverage tasks to one Gradle invocation**. The emulator executed all 34 Leisure tests before Core, consuming the short authorization window. The logs are consistent with PIN authorization expiry, though they do not by themselves prove a successful initial keyguard unlock.

This replacement helper:

1. Builds both test APKs **before** setting/unlocking the temporary PIN.
2. Restricts credential automation to an Android emulator and keeps a cleanup trap.
3. Uses keyguard wake/entry events, adds a resolution-aware swipe for API 35 PIN entry, and records non-secret keyguard diagnostics.
4. Runs `:core:createDebugAndroidTestCoverageReport` **alone and first**, directly after the PIN entry, so Core cannot be scheduled behind the 34 Leisure tests.
5. Only after Core passes, runs `:leisure:createDebugAndroidTestCoverageReport` in a separate Gradle invocation.
6. Preserves the Sonar workflow's existing validation that Core and Leisure JVM and Android JaCoCo XML reports are present. No exclusions, skips, security-policy relaxation or test removals.

The Android Keystore's **60-second PIN validity policy is unchanged**, and the CI credential exists only on the disposable emulator. If PIN entry itself is unsuccessful or the Core suite still exceeds the authorization window, CI deliberately remains red and the detailed Core instrumentation report should be inspected; the next change must prove actual authentication rather than disable the tests.

## Local validation and Git instructions

From the repository root:

```bash
bash -n tools/ci/run-sonar-instrumented-coverage.sh
./gradlew :core:testDebugUnitTest :leisure:assembleDebugAndroidTest

git add tools/ci/run-sonar-instrumented-coverage.sh \
  docs/SONAR_PR58_CI_CORE_AUTH_WINDOW_ORDER.md
git diff --cached --check
git diff --cached --stat
```

Do **not** run the CI helper against a physical Samsung or BOOX device. It is restricted to the disposable emulator. CI is the test of the actual PIN unlock. In the GitHub Actions output check for `Running Core authenticated Keystore tests and Core coverage FIRST.`, **9/9 Core tests** and **34/34 Leisure tests**, followed by XML verification and SonarCloud analysis.

If CI still fails, capture the Core XML test report and the keyguard diagnostic immediately after PIN entry. Do not increase the 60-second security window to make tests pass.
