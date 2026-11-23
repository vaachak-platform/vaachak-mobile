# Settings ViewModel test compatibility

This change replaces **only** `SettingsViewModelTest.kt`, using the current Vaachak API exposed by `SettingsRepository` and `SettingsViewModel`. It does not change application or signing code.

## Reason

The adaptive Settings feature branch previously had a test reference to `settingsRepo.privateGatewaySettings`, a property that is not part of the current `SettingsRepository` contract. The production contract instead exposes `aiTextProvider`, `privateGatewayUrl`, `privateGatewayModel`, `loadPrivateGatewayToken()`, and saving methods. The outdated test prevented compilation of `:leisure:compileDebugUnitTestKotlin`, even though the app itself compiled and installed successfully.

## Covered behavior

- Logout clears sync profile and restores default vault.
- Comics settings are persisted through the ViewModel.
- The legacy four-argument AI settings save remains supported.
- Private gateway provider, URL and model updates are delegated to the repository.
- Blank private gateway token retains the existing token.
- Explicitly clearing the token does not rewrite unrelated gateway preferences.

## Validation

Run from the project root on the feature branch:

```sh
./gradlew :leisure:compileDebugUnitTestKotlin
./gradlew :leisure:testDebugUnitTest :leisure:assembleDebug
```

Review Gradle test reports before merging. Device installation was already successful according to the developer's earlier build log, but is not a substitute for passing unit tests. No CI workflow edits are necessary for this test-only correction; Android CI should run.

## Note

The test file is a full replacement, not a generated patch. Back up or review `git diff` if the local feature branch contains additional unpublished test cases.
