# AI repository / Settings repository compatibility

## Why this replacement exists

The adaptive Settings delivery added per-vault Comics options to `SettingsRepository`, but its source snapshot did not include provider-selection and private-gateway APIs referenced by the working copy of `AiRepository.kt`. The Android build stopped in `:core:compileDebugKotlinAndroid` before the UI could be exercised.

This fix supplies **complete replacements for `SettingsRepository.kt` and `AiRepository.kt`**, based on the user's uploaded current files. The Settings repository retains Comics preferences and all previous settings, and adds:

- `aiTextProvider: Flow<AiTextProvider>` (defaults to Gemini when no preference is saved)
- `privateGatewayUrl: Flow<String>`
- `privateGatewayModel: Flow<String>`
- `loadPrivateGatewayToken(): String`
- `setAiTextProvider()`, `savePrivateGatewaySettings()`, and `savePrivateGatewayToken()`

The `AiRepository` replacement changes only the `when` expression to return a clear error for unsupported provider variants instead of failing compilation when the provider type expands.

These settings use the same active-vault DataStore isolation mechanism as existing AI and reading preferences. The provider string is stored as `GEMINI` or `PRIVATE_GATEWAY`. This is a compatibility repair, **not** the planned dynamic Gemini-model selection feature or the Cloudflare Worker review.

## Credential-handling caution

Existing Vaachak settings store Gemini and Cloudflare credentials in preference DataStores. For compatibility with that architecture, the new gateway token is likewise stored per vault as a preference value. **Android Preferences DataStore is not encrypted by default.** This fix does not claim the gateway token is encrypted; do not use a highly privileged long-lived gateway token until credential storage has been hardened. Prefer narrowly scoped, revocable server tokens. Do not put tokens in Git or build logs.

## Local validation

After extracting the source ZIP on the existing feature branch, run:

```bash
./gradlew :core:compileDebugKotlinAndroid
./gradlew :leisure:testDebugUnitTest :leisure:assembleDebug
./gradlew :leisure:installDebug
```

On the phone/e-reader: open Settings, verify the Comics preferences and AI configuration screens, confirm existing Gemini operation and (if configured) the private gateway. Do not merge until the build and device checks pass.

## Limitations

The supplied reference archive did not include the newer `AiTextProvider` and `PrivateGatewayClient` source definitions that exist in the user's local checkout. This source replacement therefore imports the existing local `AiTextProvider`; it does not generate or overwrite those local model and gateway-client files. The full Android build was not available in the preparation environment.
