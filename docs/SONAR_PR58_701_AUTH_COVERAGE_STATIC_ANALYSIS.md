# PR #58: Sonar new-code coverage 70.1% — authenticated Keystore CI and static-analysis remediation

## Diagnosis

The SonarCloud Quality Gate is at 70.1% new-code coverage (minimum 80%).
`PrivateGatewaySecrets.kt` has 53 uncovered new lines and 24 uncovered conditions after
adding device-authentication requirements. The CI emulator previously had no
secure lock, so the integration tests exercised the fail-closed path rather
than encryption, authenticated reads and migration.

## Full-file changes

1. `PrivateGatewaySecrets.kt`: keep `.setUserAuthenticationRequired(true)`,
   `AUTH_DEVICE_CREDENTIAL` and VAG1-to-VAG2 migration; replace four repeated
   AES/GCM transformations with one constant, and qualify the Hilt
   `@param:ApplicationContext` annotation.
2. `PrivateGatewaySecretsInstrumentedTest.kt`: retain four existing tests;
   add five more for invalid tokens, envelope boundaries, idempotent deletion,
   tampering/cross-vault AAD and authenticated VAG1 migration. The migration
   fixture never overwrites or deletes a pre-existing v1 key alias.
3. `ComposeTestHostActivity.kt`: replace four Android `Log.d` calls with
   `Timber.tag(TAG).d`; the existing `libs.timber` dependency remains unchanged.
4. `.github/workflows/sonar.yml`: preserve the pinned emulator action,
   report verification, JaCoCo inputs and SonarCloud quality gate. Its one-line
   `script` now calls a dedicated Bash file, avoiding the runner's known
   handling of multiline script commands.
5. `tools/ci/run-sonar-instrumented-coverage.sh`: build instrumentation APKs
   BEFORE configuring a **throwaway emulator-only** PIN; then use Android's
   real keyguard to authenticate, run both coverage tasks and remove the PIN.
   This mirrors the device-authenticated keystore strategy used in Android CTS.

## Security constraints

- **Never** run the CI script against a physical phone or a non-emulator.
- The fixed CI PIN is **not a user credential** and must only exist on a newly
  created disposable GitHub Actions AVD. The script checks `ro.kernel.qemu`.
- The runtime Keystore remains device-authenticated. No plaintext fallback or
  blanket test exclusion has been added.
- A BOOX reader without a secure lock still cannot persist private gateway
  credentials. Ordinary offline reading is unaffected.
- Encrypted VAG1 data is migrated only after device authorization. The v1
  alias is not removed globally because other vaults may retain VAG1 records.
- Android Keystore authentication validity is 60 seconds. Core instrumented
  tests run first, immediately after the CI credential unlock.

## Validation

```bash
./gradlew :core:testDebugUnitTest :leisure:testDebugUnitTest \
  :leisure:assembleDebugAndroidTest
./gradlew :core:createDebugAndroidTestCoverageReport \
  :leisure:createDebugAndroidTestCoverageReport
```

For the local Samsung, unlock using its PIN/pattern/password immediately
before instrumented tests. Only CI uses the disposable emulator PIN.

```bash
git diff --check
git diff --stat
gh pr checks 58
```

Expected: Sonar stops reporting duplicate AES/GCM string and direct `Log.d`.
Core/Leisure instrumentation should pass, four XML reports should import,
and the Keystore's authenticated paths should gain coverage. **Do not assume
80% will be reached without the actual Sonar analysis**; the large volume of
uncovered `SettingsScreen.kt` conditions and `SettingsRepository.kt` paths
may require a subsequent focused test increment.

No production read/write contract is changed beyond the already merged
requirement for device authentication.
