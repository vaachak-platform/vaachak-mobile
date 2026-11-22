# Vaachak AI Settings ViewModel compatibility

This fix targets the source/API drift seen when compiling the `feature/ereader-adaptive-settings` branch on November 22, 2026. The `:core` module compiled, but `AiConfigScreen` called an expanded `saveAiConfig(...)` and `clearPrivateGatewayToken()` that were missing in the ViewModel from the adaptive Settings ZIP.

## Changes

- Replaces the **complete** `SettingsViewModel.kt` file, preserving the adaptive Settings logic and Comics preference support.
- Restores a `clearPrivateGatewayToken()` entrypoint in the ViewModel.
- Keeps four-argument `saveAiConfig(gemini, cfUrl, token, autoSave)` calls compatible.
- Accepts the branch's extended positional arguments (an `AiTextProvider` plus gateway URL/model/token) and the `onSaved` callback. Saves the provider and gateway settings per active vault.
- Preserves an existing gateway token when the form submits a blank token; explicit clearing uses the separate clear action.
- Calls `onSaved` **only after** persistence succeeds.
- Adds focused tests for gateway persistence and token clearing.

## Limitations and checks

The uploaded `main` source archive did not include the newer `AiConfigScreen` used in the developer's local feature branch. Therefore this fix deliberately **does not replace that screen** or modify the AI configuration model. The extended API supports positional arguments with one `AiTextProvider` and three strings. If the screen uses a different argument type or ordering, align it against the actual local source before merging; do not silently change the screen or remove the private gateway feature.

The private-gateway token is currently stored in per-vault Preferences DataStore, which is not encrypted by default. Move it to authenticated Android Keystore-backed encrypted storage before claiming secure secret storage. This fix does not alter encryption or the API transport.

## Validation

From the feature branch:

```bash
./gradlew :core:compileDebugKotlinAndroid :leisure:compileDebugKotlin
./gradlew :leisure:testDebugUnitTest :leisure:assembleDebug
./gradlew :leisure:installDebug
```

On device, test both Gemini and private-gateway configuration, that saving returns to the previous screen only after persistence, persistence across restart and vault switch, error handling, and clearing a stored token. No Android build has been run by the ZIP author; device validation is required before merging.
