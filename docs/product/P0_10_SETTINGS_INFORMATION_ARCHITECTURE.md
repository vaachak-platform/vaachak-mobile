# P0-10 — Settings information architecture (PR #73 candidate)

**Baseline:** `main` at `c1cacfe8e773fa0f14591e7a9f98bdf79f8540e0` (PR #71 merged).  
**Target:** `feat/p0-settings-navigation` → `main` (independent of open PR #72).  
**Status:** Complete replacement source and UI regression tests delivered. Android build/device acceptance PENDING.

## Six settings destinations

1. **General** — Reader profiles, local multi-user mode, Offline Mode, app appearance, existing sync shortcut.
2. **Library & Sync** — Library visuals, sync configuration, OPDS catalog management.
3. **Reading & TTS** — EPUB appearance, dictionaries, existing TTS options and legacy library shortcut.
4. **Comics** — Persisted per-vault comic reading direction, fit, scroll and e-ink toggles; the future comic engine is not claimed to be implemented.
5. **Audiobooks** — Honest placeholder for the upcoming local audiobook engine, with the existing TTS destination as a compatibility shortcut.
6. **AI & Providers** — Distinct discoverable Gemini, direct Cloudflare Visualize and optional Private Text Gateway entries, all opening the existing AI configuration screen. No new provider, network call, endpoint, fallback, or credential storage introduced.

## Compatibility contract

- Preserve every existing `Tid.Settings.*` test tag, `settings_category_comics`, `settings_category_audio`, `SettingsScreenContent`, `SettingsUiActions`, and public settings composables.
- Preserve the production NavHost routes (`Screen.AiConfig`, `Screen.SyncSettings`, `Screen.TTS`, `Screen.Bookshelf`, `Screen.CatalogManage`). No dead destinations or new route registrations needed.
- Preserve compact Back navigation to the chooser and two-pane adaptive layout at large widths/font scales, including saved category state.
- Preserve per-vault comic settings, profiles and Offline Mode callbacks. Existing catalog, EPUB, TTS and e-ink settings must keep working.
- Keep the existing offline accessibility alert text so Maestro/Compose tests do not lose a known diagnostic. Provider **configuration** remains accessible when Offline Mode is active, but no AI network call is permitted by the offline policy.
- Preserve old tiles as compatibility shortcuts rather than silently removing access. Separate redundant shortcuts can be cleaned up in a later focused UX review.
- No code from PR #72 is included; this PR neither adds nor changes credential storage.

## Acceptance and device checks

- Verify that all six destinations appear on a compact phone and that Back returns to the chooser.
- Verify that the category chooser remains visible on tablets/BOOX and large fonts, and no category detail overflows horizontally.
- Navigate to Library visuals, OPDS catalogs, Sync Configuration, book appearance, TTS, dictionary, and each AI/provider tile; verify Back and saved values.
- Toggle Offline Mode, verify it blocks network AI, and verify provider settings remain available without submitting network requests.
- Switch local profiles; verify their comic options remain independent and existing sync-account safeguards remain intact.
- Rotate/rescale the device while a category is open and verify stable navigation.
- Run `./gradlew :leisure:testDebugUnitTest :leisure:assembleDebug :core:testDebugUnitTest`, `./gradlew detekt`, and UI instrumented tests on available device/emulator.
- Leave `P0-10` acceptance status **PENDING** until automated and device evidence is collected.

## Not part of this PR

- Real CBZ/CBR reading engine or local audiobook playback engine.
- PR #72 security changes, credential migration and coverage gate.
- Release signing, package ID, new AI routes or a new settings data model.
