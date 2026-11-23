# PR #61 candidate — Phase A provider coexistence and safe errors

**Base:** `main` after PR #60 (`9020e0fab065e43877ce7c3b1752255fa7003235` at preparation).

**Scope:** P0-01, P0-02, P0-05, P0-06, P0-07 and a narrow P0-09 error-disclosure fix. **Do not mark those acceptance IDs PASSED yet**; automated unit tests are only part of their required evidence.

## Source changes

- Preserve selected-provider-only text routing (direct Gemini or explicitly selected private Gateway), and independent direct Cloudflare Visualize; never send an image request to the text-only Gateway.
- Make missing Cloudflare configuration clearly identify Cloudflare, not generic AI Settings.
- Require a valid HTTPS origin for configured Cloudflare URLs, without silently rewriting HTTP to HTTPS. Keep arbitrary permitted HTTPS paths and query strings unchanged; reject embedded URL userinfo and fragments.
- Never surface raw Cloudflare error bodies or arbitrary exception messages that could echo bearer credentials, configured URLs, personal hostnames or reading excerpts. Map known HTTP and transport failure categories to fixed messages.
- Likewise redact arbitrary AI text exception messages. Preserve explicit missing Gemini key guidance and the Android Keystore unlock guidance; keep cancellation as cancellation, not a user-facing success-like error string.
- Expand Robolectric tests for both provider choices, Offline Mode, no cross-provider fallback, safe error strings, and multi-vault retention of independent keys and preferences.

## Intentionally out of scope

- Encrypting/migrating **direct Gemini and Cloudflare credentials**: the currently merged `SettingsRepository` stores those values in vault-scoped DataStore string preferences. This requires its own audited migration design and tests (P0-08). Do not mark credentials-at-rest protection complete because the separate private-Gateway token already uses Keystore.
- Real Gemini network calls, live Cloudflare image billing, private R7.1 Gateway device/Tailscale validation, or secure-lock BOOX testing. These require opt-in device/integration verification.
- Any future central task Gateway or image/vision backend integration (P3).

## Validate before PR review

```bash
./gradlew :core:testDebugUnitTest :core:jacocoDebugUnitTestReport
./gradlew :leisure:testDebugUnitTest :leisure:assembleDebug
./gradlew detekt :core:lintDebug :leisure:lintDebug
```

Follow CI checks and SonarCloud. On Samsung and representative BOOX devices, verify direct Gemini text and direct Cloudflare image are not regressed, Gateway text remains opt-in, both text providers can coexist with Cloudflare, and Offline Mode disables network AI without disabling EPUB/TTS/local media. Do not use private credentials in tests or screenshots.

## Repository files

1. `core/src/androidMain/kotlin/org/vaachak/reader/core/data/repository/AiRepository.kt` (complete replacement)
2. `core/src/androidUnitTest/kotlin/org/vaachak/reader/core/data/repository/AiRepositoryUnitTest.kt` (complete replacement)
3. `core/src/androidUnitTest/kotlin/org/vaachak/reader/core/data/repository/SettingsRepositoryProviderCoexistenceUnitTest.kt` (new)
4. `docs/product/PR61_PROVIDER_COEXISTENCE_PRIVACY.md` (new)

The production code and tests were statically reviewed against the GitHub `main` source. There is no Android SDK/Gradle emulator run in the preparation environment; compilation and test success must be established in CI.
