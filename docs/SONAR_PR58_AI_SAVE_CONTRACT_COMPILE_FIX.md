# PR #58: restore the typed AI save request API after Keystore security changes

## Root cause

The previous Keystore/security replacement of `SettingsViewModel.kt` accidentally
removed top-level `AiConfigSaveRequest` and `AiGatewayUpdate` types and the
`saveAiConfig(AiConfigSaveRequest, onSaved)` overload. Production
`AiConfigScreen.kt`, Compose tests, and unit tests reference this API.

## Targeted fix

This replacement restores both public data classes and the typed save overload.
It preserves the legacy four-field `saveAiConfig` overload, the underlying
`saveAiConfigInternal` behavior, token retention semantics, secret-safe generic
exception messages, and the explicit `GatewayCredentialUnavailableException`
catch introduced for authenticated Android Keystore keys. It removes the
unintended nine-argument overload, keeping the earlier Sonar maintainability
refactor intact.

**No changes** to `PrivateGatewaySecrets.kt`, its Android authentication policy,
or to Compose form behavior are required for this compile error.

## Validate

```sh
./gradlew :leisure:compileDebugKotlin :leisure:compileDebugAndroidTestKotlin \
  :core:testDebugUnitTest :leisure:testDebugUnitTest
./gradlew :core:createDebugAndroidTestCoverageReport \
  :leisure:createDebugAndroidTestCoverageReport
```

Run the instrumented step with an Android device connected/unlocked. Confirm the
API 35 emulator workflow, four JaCoCo reports, and Sonar quality gate. Keep
PR #58 in draft until new-code coverage >=80%, Security A, Reliability A, and
<=3% duplication, with Samsung and BOOX compatibility checks.

## Limitations

This ZIP is checked for complete source-file consistency against the last
known-working typed ViewModel implementation and preservation of the current
security-specific exception catch. Android/Gradle compilation was not available
in the packaging environment.
