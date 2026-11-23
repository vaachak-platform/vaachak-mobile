# Vaachak Mobile Roadmap

**Repository:** [`vaachak-platform/vaachak-mobile`](https://github.com/vaachak-platform/vaachak-mobile)  
**Maintained in Git:** this file is the canonical product roadmap for the Android `:leisure` app and shared `:core` module.  
**Updated:** 2026-11-23  
**Status:** planned and in-progress work is **not** accepted until verified; see the [acceptance matrix](docs/product/ACCEPTANCE_MATRIX.csv).

## Product direction and boundaries

Build one calm, privacy-first, offline-first Leisure reading application for **EPUB/text, comics and audiobooks**, with separate format-specific reading experiences and unified library/settings navigation. Preserve the existing reader, highlights, bookmarks, profile/vault boundaries, sync, Calibre/OPDS, dictionaries, local TTS, Indian-language accessibility, and e-ink behavior.

**Provider invariants (release blockers):** direct **Gemini** continues to support text tasks; direct **Cloudflare** continues to power image *Visualize*; a user-configured **Private/Self-hosted AI Gateway** is an optional third path for **text only**. Selection is per vault, switching never deletes other providers' credentials, and cross-provider fallback is **disabled by default**. Offline Mode blocks **all** network AI while reading, comics, local audiobook playback and on-device TTS remain usable. Neither public builds nor diagnostics contain private gateway infrastructure or credentials.

This roadmap incorporates the [combined requirements](docs/product/VAACHAK_LEISURE_REQUIREMENTS.md) and their [24 acceptance tests](docs/product/ACCEPTANCE_MATRIX.csv) without asserting that requested functionality already exists. Reconcile against the developer's latest comics/audio branch and merged PR #58 instead of replacing newer work with an older public `main` snapshot.

## Priority definitions and delivery gates

| Priority | When it ships | Acceptance rule |
|---|---|---|
| **P0 — mandatory** | With the coordinated comics/audio/settings integration | **Blocks merge/release** if any P0 acceptance test fails |
| **P1 — coordinated release** | Same combined feature release, unless explicitly deferred | Complete with evidence or document the exceptional deferral |
| **P2 — resilience** | Prefer combined release; named maintenance issue if deferred | Record owner, risk and follow-up issue |
| **P3 — future platform** | Separate post-qualification release | Do not implement or claim until a new contract/review approves it |

All acceptance statuses start as **PENDING** in the matrix. Change to passing only after recording actual automated, emulator and device evidence. A merged PR, green CI build or matching source code alone is **not** proof that a feature-level acceptance test passed.

## Coordinated Leisure release: delivery tracks

### Phase A — Preserve users, provider routing and navigation (P0)

| Track | Work required | Acceptance IDs |
|---|---|---|
| Existing direct providers | Keep Gemini BYOK text (**Explain**, **Who Is This**, **Recap**, **Recall**), Cloudflare BYOK *Visualize*, existing AI destination and Cloudflare tile/deep link after upgrade | `P0-01`, `P0-02` |
| Optional private text Gateway | User-entered HTTPS endpoint/token/model alias, existing R7.1 `/v1` text compatibility, profile-scoped selection, no embedded private infrastructure, no silent provider fallback or credential deletion | `P0-03`, `P0-04`, `P0-05`, `P0-06` |
| Offline privacy | Offline Mode blocks network Gemini, Cloudflare and Gateway; EPUB, comics, audiobooks and local TTS continue offline | `P0-07` |
| Secrets and transport | Encrypt/migrate credentials, eliminate plaintext remnants and sensitive logs, enforce normal HTTPS/TLS validation, protect reading excerpts and vault isolation | `P0-08`, `P0-09` |
| Settings information architecture | Dedicated General, Library & Sync, Reading/TTS, Comics, Audiobooks and AI & Providers destinations; stable navigation, saved settings, responsive e-ink/tablet layouts | `P0-10` |

**Security/compatibility decision to verify:** existing Keystore authentication changes must be tested against devices without a secure lock screen (including BOOX). Do not silently weaken encryption, destroy credentials or treat a successful Samsung run as proof of e-ink compatibility. Preserve normal offline reading if a private credential cannot be stored.

### Phase B — Comics and audiobooks, independent of TTS (P0 + P1)

| Track | Work required | Acceptance IDs |
|---|---|---|
| Comics import and defaults | Reconcile SableOS CBZ/image-folder importer, safely bound archive extraction, natural page ordering, progress and deduplication; preserve five modes `PAGED_LTR`, `PAGED_RTL`, `VERTICAL_PAGER`, `CONTINUOUS_VERTICAL`, `WEBTOON`, with `FIT_PAGE` / `FIT_WIDTH` behavior | `P0-11` |
| Local audiobooks and defaults | Supported local audio formats subject to decoder support; speeds `0.5×` through `3.0×` per specification, skip back `5/10/15/30s`, forward `10/15/30/60s`; defaults `1.0×`, `15s`, `30s`; background/offline local playback | `P0-12` |
| Preserve EPUB and TTS | Existing EPUB reading state, highlights, bookmarks, sync and on-device TTS voices/language/pitch/read-aloud remain independent of audiobook playback settings | `P0-13` |
| Comic overrides | Per-title viewing mode/fit overrides, explicit reset to global defaults, position/orientation restoration | `P1-01` |
| Audiobook playback depth | Per-title speed/skip overrides, resume/chapter/seek, audio focus, background service, MediaSession/notification/lock-screen controls | `P1-02` |
| Local media security | Bound CBZ decompression/images, validate URI permissions, graceful storage relinking, no cross-vault media leaks | `P1-06` |

**Do not replace the already-in-progress SableOS comics/audio port wholesale.** Map parity with the existing implementation, preserve its schema and tests, and add only the missing pieces.

### Phase C — Provider diagnostics, accessible UI and recovery (P1 + P2)

| Track | Work required | Acceptance IDs |
|---|---|---|
| Test Connection | Saved, configured Gateway only; bounded authenticated compatibility check that does **not** send excerpts or dummy inference prompts; report precisely which capability was verified | `P1-03` |
| Actionable errors | Distinguish offline, invalid URL, DNS, TLS, no Tailscale route, 401/403, 429/quota, 5xx, timeout, cancellation and missing model; no secret-bearing messages | `P1-04` |
| Media/settings accessibility | All reader/provider subpages reachable with stable `Tid`/Maestro semantics, visible Back/Save, large text, portrait/landscape and e-ink usability | `P1-05` |
| Lifecycle/cancellation | Bounded provider timeouts and cancellation when leaving reading context | `P2-01` |
| Honest connection state | Show active provider, truthful verification status and safe retry behavior; do not infer backend health from an HTTPS connection alone | `P2-02` |
| Media stress/interruption | Long M4B seek, low memory, rotation, Bluetooth handoff, headphone removal and calls | `P2-03` |

### Phase D — Separate future AI platform release (P3; non-blocking)

| Track | Constraint | Acceptance IDs |
|---|---|---|
| New central Gateway routing | Versioned task contracts, server-side prompt orchestration, optional explicit-consent fallback only after independently verified ingress, API compatibility and routing; direct Gemini and Cloudflare remain supported | `P3-01` |
| Image/vision/audio AI | OCR, comic vision, audiobook transcription and any gateway-backed image generation require separately qualified endpoints, clear user consent and privacy review | `P3-02` |

**Non-goal for the coordinated Leisure release:** do **not** migrate the working R7.1 text integration to future `reading.*` task contracts or claim that the current Gateway provides image/vision capabilities.

## Existing Vaachak roadmap priorities — preserved

These tracks remain on the roadmap and should not be accidentally displaced by the coordinated comics/audio effort. Dates and owners have **not** been assigned where the repository does not provide them.

| Existing track | Status / next planning step | Relationship to Leisure release |
|---|---|---|
| **EPUB reading quality** | Continuous improvement: typography, layout, bookmarks, highlights, dictionaries, navigation and non-destructive migration | Regression gate during Phase A/B |
| **PDF reading** | Planned; define reader architecture, import, progress, annotation and device matrix in a separate proposal | Independent feature; **not** a new P0 blocker |
| **Audiobooks** | Coordinated P0/P1 track above; reconcile the existing port and avoid double-counting a second audiobook roadmap item | Phase B |
| **Comics** | Coordinated P0/P1 track above; reconcile the existing port | Phase B |
| **E-ink device UX** | Continuous: low-animation behavior, contrast, key mapping, BOOX compatibility and larger layouts | Cross-cutting acceptance across Phases A–C |
| **Indian-language accessibility** | Continue improving reading fonts, encoding, local language/TTS support and usability | Preserve during Phase B, then expand separately |
| **Privacy-first sync and Calibre/OPDS** | Preserve existing data model, vault boundaries, catalogs, offline queueing and settings | Regression gate during Phase A/B |
| **iOS and broader platform expansion** | Future platform planning after Android core stability and format boundaries are proven | Separate workstream, no premature delivery date |

## Required verification before closing the combined feature release

- **Upgrades:** retain a previously configured direct Gemini profile, direct Cloudflare image configuration and existing private-Gateway profile; no silent provider change or lost secrets.
- **Routing:** test Gemini-only, Gemini+Cloudflare, Gateway+Cloudflare, Gateway-only and unconfigured profiles; inspect fake-HTTP destinations and confirm no implicit fallback.
- **Offline:** verify zero network AI requests and uninterrupted local EPUB/comic/audio/TTS usage.
- **Profiles:** verify switching vaults cannot expose credentials, defaults or media state from another vault unless explicitly device-global.
- **Comics:** five modes, fit and override/reset, safe import, storage grants, position restoration.
- **Audiobooks:** all required speed/skip options, TTS isolation, resume, background, audio focus/MediaSession and interruptions.
- **Settings:** phone/tablet/e-ink layouts, large font, rotation, process restart, Save/Back, existing Cloudflare destination and stable test IDs.
- **Security:** check APK/source/logs/backups for secrets and prompt logging, valid TLS only, safe archive decompression and non-destructive migration.
- **Release evidence:** test matrix statuses with test names/devices/commit evidence, screenshots, changed-file inventory, migration notes and documented P2 deferrals. Never mark an unexecuted acceptance case passed.

See the complete definitions and verification methods in [ACCEPTANCE_MATRIX.csv](docs/product/ACCEPTANCE_MATRIX.csv). Maintain **that CSV as the single source of truth for case-by-case verification status**; this roadmap tracks priorities and dependencies, not duplicate pass/fail cells.

## Roadmap maintenance protocol

1. Update `ROADMAP.md` for changes to scope, sequencing and product decisions. Use a PR, with review for changes to P0 security/privacy or provider behavior.
2. Update `docs/product/VAACHAK_LEISURE_REQUIREMENTS.md` when normative acceptance behavior changes; record the rationale in the PR.
3. Update `docs/product/ACCEPTANCE_MATRIX.csv` for evidence-backed results only. Keep acceptance IDs stable; add new IDs rather than renumbering old ones.
4. Link GitHub issues and milestone PRs to acceptance IDs (for example, `P0-05`) in their descriptions. Reconcile against existing open/closed issues before creating duplicates.
5. Keep `README.md` as a high-level overview; link readers to the root `ROADMAP.md`. Do not let README's shorter planned-feature list become a second roadmap.
6. Maintain explicit release gates and traceability. CI/Sonar quality checks remain independent of, and additional to, product acceptance.

## References

- [Combined Leisure requirements](docs/product/VAACHAK_LEISURE_REQUIREMENTS.md) — binding scope, release behavior, security and UX details.
- [Acceptance matrix](docs/product/ACCEPTANCE_MATRIX.csv) — 24 stable P0–P3 acceptance IDs with expected verification.
- [Roadmap issue reconciliation guide](docs/product/ROADMAP_BACKLOG.md) — proposed grouped work items to compare with any existing GitHub issues.
- [Vaachak repository](https://github.com/vaachak-platform/vaachak-mobile) and the existing README for product direction.
