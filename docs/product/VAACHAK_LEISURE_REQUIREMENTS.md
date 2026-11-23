# Vaachak Leisure — Combined Comics, Audiobooks, Settings & AI Provider Requirements

**Handoff:** 2026-11-23  
**Target:** `vaachak-platform/vaachak-mobile`, Leisure Android application (`:leisure`, shared `:core`)  
**Integration reference:** `aimindseye/zinwaq25-sableos`, `sable-src/apps/common/reader/`  
**Decision:** Preserve direct Gemini and Cloudflare for public/BYOK users; private AI-G732 Gateway is strictly optional for users who configure their own gateway.  
**Status:** Requirements only; no application changes made by this handoff.

## 0. Binding product decisions and non-goals

1. **One Leisure app, three reading formats:** preserve EPUB/text reader; integrate comics and audiobooks as peer content types under an extensible library and Settings experience. Comics and audiobook functionality being ported from SableOS is already in progress; reconcile against that work rather than overwrite it.
2. **AI provider coexistence is mandatory:** retain working **direct Gemini text** and **direct Cloudflare visualization** integrations for any user with their own credentials. The private **AI Gateway** is an *additional opt-in*, never a prerequisite to install, read, import, listen, visualize via Cloudflare, or configure direct Gemini.
3. **Personal infrastructure stays personal:** no hard-coded private tailnet hostname, AI-G732 IP, bearer token, model credentials, personal proxy or personal user profile in public source, defaults, sample data, CI logs or distributable APK. User chooses and enters their own HTTPS gateway endpoint. Preserve the already qualified private gateway settings on existing installations.
4. **No unrequested provider migration:** do not silently move a Gemini/Cloudflare user onto private Gateway or route their reading excerpts through a different provider. Any cross-provider fallback is **off by default** and needs explicit opt-in and clear data-destination disclosure.
5. **Offline first:** EPUB, comics, audiobook playback, bookmarks, progress, settings, local TTS and library navigation continue offline. Offline Mode blocks *all network AI paths* (Gemini, Cloudflare and Gateway); it must not disable local audiobook playback or on-device TTS.
6. **Preserve data and UI:** retain existing profiles/vaults, EPUB reading state, bookmarks, highlights, sync, Calibre/OPDS catalogs, dictionaries, e-ink options and release/signing configuration. Current changes in the developer's comics/audio branch take precedence over public `main` where newer.
7. **This is not the new centralized task-Gateway migration.** The private R7.1 text endpoint currently works. Do not switch to future task contracts or enable local visualization/vision until the new homelab AI Gateway contracts, live ingress and provider-routing behavior are qualified separately.

## 1. Priorities / delivery gates

| Priority | Scope | Release rule |
|---|---|---|
| **P0 — Must ship with comics/audio settings integration** | Provider preservation, credentials, offline privacy, migration, settings navigation and media isolation | Block merge/release if any P0 acceptance test fails |
| **P1 — Ship as part of the same coordinated UI effort** | AI diagnostics, error clarity, per-media defaults, format-specific reader controls, meaningful tests | Complete for the combined feature release; explicit deferral must be documented |
| **P2 — Quality / resilience** | Connection status, cancellation, retry UX, stronger accessibility/e-ink flows, lifecycle recovery | Prefer same release; can be subsequent maintenance release with named issue |
| **P3 — Separate future Gateway platform work** | Central task contracts, versioned server-side prompts, global provider orchestration/quotas, local image and OCR/vision features | Not a blocker; do not prematurely implement or claim support |

## 2. Settings information architecture (P0)

Use the existing Compose `SettingsScreen`/`SettingsViewModel`/navigation, preserve any SableOS Global/Book grouping already merged, and add explicit, readable destinations. One valid layout:

- **General:** Profiles & vaults, Appearance / e-ink, Privacy & Offline Mode, About.
- **Library & Sync:** Local storage/imports, Calibre/OPDS catalogs, sync, backups and restore (do not conflate backup with live sync).
- **Reading:** EPUB/text defaults (fonts, margins, layout), dictionaries, Speech & Narration (on-device TTS).
- **Comics:** comic viewing defaults; manga/webtoon modes and fit; file-import guidance.
- **Audiobooks:** audiobook playback defaults (speed/skip), sleep timer and background playback; separate from TTS.
- **AI & Providers:** AI enablement, text provider selector, Gemini settings, Cloudflare visualization settings, optional Private/Self-hosted Gateway settings, Auto-save Recaps, Test Connection, error/help text.

### Required UX behavior

- Each entry opens a dedicated screen with a visible title and Back navigation; system Back returns to Settings; save/cancel behaviors are consistent and clear.
- No oversized, permanently expanded provider forms that bury comics/audio controls. Providers use their own panels or subpages, with secret fields masked by default.
- Preserve the **existing Cloudflare tile/deep-link behavior**: it opens the established AI Configuration destination, not a dead end.
- Separate **Global defaults** from **per-book/per-comic/per-audiobook overrides**. When a title has an override, changing Global defaults does not overwrite that title; offer a deliberate “Reset to default.”
- Scroll and controls must work on phone portrait, tablet and e-ink screens, with large fonts and landscape. No clipped Save or Back buttons or inaccessible final rows. Respect system and user theme; avoid hard-coded low-contrast colors and unnecessary animation on e-ink.
- Preserve existing stable test IDs/semantics where already used (`Tid`, Maestro); add new stable identifiers for provider selection, tests, comic defaults, audiobook defaults, error states and Back navigation.
- Never wipe settings on navigation, process death, rotation, changing profiles, upgrading APK, or enabling comics/audio.

## 3. AI provider architecture (P0)

### 3.1 Direct Gemini (public/BYOK; **must remain fully functional**)

- Configure own Gemini key; masked entry with show/hide, save/update/delete, never print or embed it.
- Text tasks: **Explain**, **Who Is This**, **Recap**, **Recall**. Preserve useful spoiler-aware prompts, context limits, and Gemini-first response quality.
- Existing direct Gemini users remain on Gemini after upgrade, with their key and settings retained. New users with no key see `Not configured`, not a hardcoded private Gateway destination.
- A missing/exhausted Gemini key must not disable Cloudflare or offline reading.

### 3.2 Direct Cloudflare (public/BYOK; **must remain fully functional**)

- Preserve configurable Cloudflare URL and token and the existing **Visualize** image-generation action independent of Gemini text selection.
- Keep `Cloudflare` visible in AI Configuration, including when Private Gateway is selected for text.
- Visualize must report `Cloudflare not configured` if required fields are absent; do not silently redirect it to Gateway text completions or expose content without user intent.
- Remove/redact prompt and raw provider-error logging in production (public repository's `AiRepository.kt` currently logs a visualization prompt with `Log.d`; that is a concrete audit target).

### 3.3 Optional Private / Self-hosted AI Gateway (personal/advanced)

- Text-provider selector supports at least `Gemini (direct)` and `Private / Self-hosted Gateway`; selection is **per profile/vault** and persists.
- Advanced gateway form: user-entered HTTPS base URL, masked token, optional model alias, explicit Save, Clear and Test Connection. No default private hostname or token shipped.
- Preserve previously verified private R7.1 connection semantics: OpenAI-compatible `/v1` endpoint, `local-chat` model alias, Vaachak-specific bearer credential, Tailscale-connected Android **primary profile**. Treat the known `8443` tailnet URL as a private installation value, not public template data.
- Reuse the existing validated private client where present in the working branch. Do not overwrite it with the older public `main` implementation.
- Text-only capability remains authoritative for now. **Visualize stays on directly configured Cloudflare** unless a separately tested image-capable Gateway contract is later approved.
- If a selected Gateway is unreachable, present an actionable error and keep all offline reading features usable. Other users' Gemini/Cloudflare flows must remain independent.

### 3.4 Routing / fallback contract

| User configuration | Explain/Who/Recap/Recall | Visualize | Offline Mode |
|---|---|---|---|
| Gemini only | Direct Gemini | Unavailable until Cloudflare configured | Blocks network AI |
| Gemini + Cloudflare | Direct Gemini | Direct Cloudflare | Blocks both |
| Gateway + Cloudflare | Private Gateway text | Direct Cloudflare | Blocks both |
| Gateway only | Private Gateway text | Unavailable until Cloudflare configured | Blocks network AI |
| Neither | Clear setup guidance | Clear setup guidance | Normal offline reading |

- Default routing is **selected-provider only**. Do not implement automatic cross-provider fallback as a hidden side effect.
- Optional future **Gemini-first then Gateway on confirmed quota exhaustion** needs an explicit per-profile opt-in, provider availability checks, visible destination change and tests. Do not fall back on wrong credentials (401/403), TLS errors, Tailscale disconnection or timeouts by default; do not fall back from text to Cloudflare image API.
- Maintain separate provider-specific secrets. Switching providers never deletes other provider credentials unless the user explicitly clears them.

## 4. Security / privacy / settings migration (P0)

- Treat API keys, Gateway bearer token, Cloudflare token and sync credentials as secrets. Inspect existing `SettingsRepository` (public branch uses `stringPreferencesKey` in vault-scoped DataStore) and **verify actual at-rest encryption**. If not protected, implement Android Keystore-backed encryption with versioned, one-time migration; never leave plaintext duplicates in old preferences, Android backup, logs or exported diagnostics. Account for unavailable/invalidated Keystore keys with explicit recovery guidance, not destructive resets.
- Never bundle secrets or private endpoints in build constants, resources, git history, crash reports, analytics, tests, screenshots or default configs. Redact Authorization headers, URLs with embedded tokens, query parameters carrying secrets and reading excerpts.
- Enforce HTTPS with normal TLS hostname/certificate validation. No `trustAll`, cleartext fallback, global certificate bypass or URL normalization that silently changes the intended origin. Distinguish `invalid URL`, `DNS failure`, `TLS validation`, `Tailscale unreachable`, `401/403`, `429 quota`, `5xx`, `timeout`, `cancelled`, and `model unavailable`.
- Bound transmitted reading context to what the user action needs; never send entire books, comic pages or raw audio in background. Clearly disclose network/provider destination before first use of each path; respect Offline Mode and user's privacy choices.
- AI configuration, privacy preference and provider credentials should be profile-scoped using existing vault semantics; explicitly test switching profiles cannot surface another profile's credentials. Define separately any deliberate *device-global* nonsecret appearance/media defaults.
- Migration is non-destructive: old Gemini/Cloudflare keys and user preferences survive; older audiobook/TTS setting names remain recognized; EPUB highlights, bookmarks, sync identifiers and book hashes remain stable. Support upgrade + rollback tests with a sanitized local backup fixture.
- New comic and audiobook importers must protect against malicious archives, zip-slip, excessive entries/decompression, oversized images and unsupported media; preserve Android Storage Access Framework permissions and graceful relink when grants are revoked.

## 5. Comics and settings (P0/P1)

Source parity reference: SableOS `ComicViewSettings`, `ComicDefaultsScreen`, `ComicImporter`, `ComicReaderScreen`.

- **P0:** Integrate existing SableOS CBZ import + image-folder import where implemented, with safe archive limits, duplicate detection, readable error messages, stable library entry and last-read position. Do not regress EPUB import.
- **P0:** Global defaults selectable from all five existing SableOS modes: `PAGED_LTR`, `PAGED_RTL` (manga), `VERTICAL_PAGER`, `CONTINUOUS_VERTICAL`, `WEBTOON`. Preserve fit `FIT_PAGE` / `FIT_WIDTH`, with continuous/webtoon effectively width-fit; do not apply irrelevant fit settings.
- **P1:** Each comic can override mode and fit; overrides persist through app restart, profile switch and upgrade; Reset to Global Defaults returns intended behavior.
- **P1:** Correct natural ordering of pages, usable touch/keyboard navigation, no accidental page reversal in RTL, safe scroll position restore, e-ink-friendly rendering and bounded image cache.
- **P1:** Clear UI for missing/revoked storage grants, corrupt CBZ, unreadable pages and duplicate import; never silently delete the source archive. Comic metadata/cover/image display should remain private and offline-capable.
- **P2:** Large-screen/square-screen, low-memory, rotation, webtoon long-strip and archive-corruption tests; keyboard mapping configurable when appropriate.

## 6. Audiobooks versus TTS (P0/P1)

Source parity reference: SableOS `AudioPlaybackSettings`, `AudioSettingsStore`, `AudiobookPlaybackService`, `AudiobookImporter`, `AudiobookDefaultsScreen`.

- **P0:** Audiobook files/folders supported by source loader where device decoders permit (SableOS enumerates `mp3`, `m4a`, `m4b`, `aac`, `ogg`, `opus`, `flac`, `wav`); local playback works independently of network AI and TTS.
- **P0:** Dedicated Audiobooks defaults: speed choices `0.5×, 0.75×, 1.0×, 1.1×, 1.25×, 1.5×, 1.75×, 2.0×, 2.5×, 3.0×`; skip-back choices `5, 10, 15, 30 s`; skip-forward choices `10, 15, 30, 60 s`. Defaults are `1.0×`, `15 s` back, `30 s` forward.
- **P0:** Keep **Speech & Narration (TTS)** separate: Android TTS voice, language, pitch, read-aloud settings, per-book pagination and text highlighting must remain unchanged. Audio playback settings do not overwrite TTS settings.
- **P1:** Per-audiobook playback overrides, chapter navigation, resume position, play/pause/seek, sleep timer, media notification and lock-screen media controls via Android MediaSession; respond appropriately to headphone unplug, audio focus changes, phone calls and Bluetooth handoff.
- **P1:** Background playback must be controlled by user intent, use appropriate Android foreground media service behavior and stop cleanly; do not leak playback into a different local profile.
- **P1:** Storage access/relink and duplicate handling, secure filename/media metadata handling, proper behavior after process death or permission revoke.
- **P2:** Playback-rate persistence, seek during large M4B, long-duration progress throttling, accessibility descriptions, e-ink low-animation controls and compatibility testing on phones/tablets.

## 7. AI diagnostics, user errors and connection state (P1/P2)

**P1 — Test AI Gateway Connection:**
- Accessible from Gateway settings; only executes when user taps and has saved a configured URL/token. Perform a **bounded authenticated compatibility/health check** that matches the deployed Gateway contract, with no reading excerpts, dummy prompts or expensive model inference unless contract specifically requires an explicitly consented test.
- Report: endpoint reachable; TLS validated; credential accepted or rejected; model alias/compatible endpoint available when demonstrable. Never report a healthy model merely because HTTPS succeeded. Mask hostname if sensitive and never expose token in UI/errors/logs.
- Optional equivalent `Test Gemini` and `Test Cloudflare` buttons should use provider-appropriate low-cost/safe semantics and clearly label what is actually verified.

**P1 — Error states:** Short actionable error labels for DNS, Tailscale disconnected, TLS, auth, rate/quota, provider unavailable, timeout, offline, cancellation. Offer a Retry only for safe idempotent operations and don't replay generated-image requests blindly.

**P2 — Resilience:** Configurable bounded connect/request timeouts, cancellation when leaving reader, progress states, nonblocking background/Compose state management and provider-label display `Gemini`, `Cloudflare` or `Private Gateway` where useful. Never show false `Connected` based solely on internet access or Tailscale being installed.

## 8. Implementation architecture guidance

- Preserve `:core` domain/provider boundary and `:leisure` Compose UI. Add a provider selector abstraction, typed provider capabilities (`TEXT`, `IMAGE`), per-vault provider selection, typed errors and testable use-cases. Existing `AiRepository` code paths should be delegates/adapters, not duplicated prompt copies in screens.
- Maintain contract compatibility with existing R7.1 OpenAI-style text requests and `local-chat`; client should not hardcode backend physical model names (the private gateway decides provider/model).
- Reuse SableOS importers, comic/audio codecs and player view models where licenses, dependency graph, package names and migration schemas permit. **Reconcile code and schemas**; do not wholesale copy the SableOS Settings screen over current Vaachak implementation.
- Separate private config from distributable defaults, and avoid API-key handling in Compose UI beyond securely edited masked fields. Async health checks and operations must be lifecycle-safe.
- Treat `Visualize` as an image-capability action and avoid routing it to a text-only backend. Comics image-based AI, OCR and audiobook transcription are separate opt-in future features.

## 9. Release acceptance criteria — all mandatory for P0/P1 closure

See `ACCEPTANCE_MATRIX.csv` for IDs and detailed manual/automated test outcomes. At minimum:

1. Existing direct Gemini user upgrades: key and text tasks still work; cloud path stays direct; no private Gateway required.
2. Existing Cloudflare user upgrades: URL/token and Visualize continue to work irrespective of text provider.
3. Private Gateway user upgrades: prior `8443`/`/v1`/`local-chat` setup still works on Android primary profile with Tailscale connected; public APK includes **no personal URL or credentials**.
4. Switch `Gemini ↔ Gateway` without clearing credentials; provider selected per vault; new vault shows no prior vault's secrets.
5. Offline Mode prevents any network AI requests (including Cloudflare), while EPUB, comics, audiobooks and on-device TTS remain available.
6. Gemini quota/auth/TLS/network errors are distinguished; no silent fallback or cross-provider content transfer.
7. Comics: five modes, fit and per-title override + reset; imports and bookmarks/positions persist.
8. Audiobooks: specified speed/skip choices, per-title overrides and resume; background and MediaSession behavior tested; TTS settings preserved independently.
9. Settings work in portrait/landscape/large text/e-ink; Cloudflare tile and existing AI route still work; no data loss on rotation, navigation or process death.
10. Security tests: no credentials/prompts in logs/build output; TLS validation intact; safe archive/URI handling; static analysis, unit and UI tests; debug and unsigned/release builds as applicable.
11. PR includes before/after settings screenshots, test matrix, changed-file inventory, migration notes, and explicit failures/deferred P2/P3; do not claim passing without actual device testing.

## 10. Delivery sequence and coordination with new homelab Gateway

**Phase A (P0):** Freeze branch and verify current installed R7.1 state; add unified navigation and provider coexistence, secrets/privacy/migration guardrails.  
**Phase B (P0/P1):** Merge SableOS comics/audio settings and per-title defaults; regression-test EPUB/TTS/sync/Cloudflare/Gemini/private Gateway together.  
**Phase C (P1/P2):** Add Test Connection, typed failures, lifecycle and UI/accessibility tests; validate on S25 Ultra primary profile plus representative tablet/e-ink devices.  
**Phase D (P3; future):** When the new AI Gateway is independently qualified, optionally implement server task names (`reading.explain`, `reading.who`, `reading.recap`, `reading.recall`, `reading.visualize` only where supported), centrally versioned prompts, privacy-aware task routing and explicitly consented Gemini→local fallback. **Direct public providers remain supported** even after this phase.

## 11. Confirmed source anchors (reviewed public `main`, not necessarily the user's working branch)

- Vaachak app: https://github.com/vaachak-platform/vaachak-mobile
- Vaachak AI form: `leisure/src/main/java/org/vaachak/reader/leisure/ui/settings/AiConfigScreen.kt` (Gemini and Cloudflare settings)
- Vaachak provider: `core/src/androidMain/kotlin/org/vaachak/reader/core/data/repository/AiRepository.kt` (Gemini text / Cloudflare image)
- Vaachak preferences: `core/src/androidMain/kotlin/org/vaachak/reader/core/data/repository/SettingsRepository.kt` (vault-scoped preferences; **encryption requires inspection**)
- SableOS reader source: https://github.com/aimindseye/zinwaq25-sableos/tree/main/sable-src/apps/common/reader
- SableOS `MediaDefaultsScreens.kt`, `ComicViewSettings.kt`, `AudioPlaybackSettings.kt`, `AudioSettingsStore.kt`, `ComicImporter.kt`, `AudiobookImporter.kt` supply the media-mode/defaults/parity reference.

**Acceptance rule:** This specification documents requested behavior, not a claim that public `main` already contains the locally qualified private Gateway work. Verify and preserve the current local branch/commit before implementation.
