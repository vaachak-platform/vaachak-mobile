# Proposed GitHub issue reconciliation for the Leisure roadmap

**Status:** Proposed workstream groupings only; not GitHub issues, not implementation completion evidence. Check the current repository's branches, PRs and issues before creating new items. The authoritative priorities are in [`../../ROADMAP.md`](../../ROADMAP.md), and acceptance status lives only in [`ACCEPTANCE_MATRIX.csv`](ACCEPTANCE_MATRIX.csv).

| Proposed issue title (search existing first) | Priority | Acceptance IDs | Dependency / notes |
|---|---|---|---|
| Preserve direct Gemini and Cloudflare BYOK after Leisure upgrade | P0 | `P0-01`, `P0-02` | Must not regress the existing AI settings route or Visualize capability |
| Support optional per-vault private text Gateway without silent provider fallback | P0 | `P0-03`, `P0-04`, `P0-05`, `P0-06` | R7.1 text-only compatibility; no private defaults; independent credentials |
| Verify offline isolation and secure settings/credential migration | P0 | `P0-07`, `P0-08`, `P0-09` | Includes Keystore/BOOX decision, TLS and sensitive logging |
| Reconcile Settings navigation and persistence across media formats | P0 | `P0-10` | Preserve existing screen/view-model navigation and semantic IDs |
| Complete safe comics import and five-mode global reading defaults | P0 | `P0-11` | Integrate with existing SableOS-based port, not replace it |
| Complete offline audiobook import/playback and playback defaults | P0 | `P0-12` | Must not overlap or mutate on-device TTS defaults |
| Guard EPUB progress, bookmarks, sync and TTS regressions | P0 | `P0-13` | Cross-format regression test suite |
| Add per-comic override/reset and robust position recovery | P1 | `P1-01` | Depends on comic global defaults |
| Add per-audiobook overrides, resume and MediaSession/focus behavior | P1 | `P1-02` | Depends on local audiobook playback core |
| Add privacy-safe Test Connection and typed provider errors | P1 | `P1-03`, `P1-04` | Provider-specific checks; never send reading excerpts to a diagnostic endpoint |
| Complete accessible Settings navigation and safe storage handling | P1 | `P1-05`, `P1-06` | E-ink, large text, archive limits, URI revoke/relink |
| Provider lifecycle and honest retry/status UI | P2 | `P2-01`, `P2-02` | Can be a named follow-up if explicitly deferred |
| Media stress tests and interruption recovery | P2 | `P2-03` | M4B/rotation/low memory/Bluetooth/headphone/calls |
| Qualify centralized AI task Gateway contract (future only) | P3 | `P3-01` | Separate release; direct Gemini/Cloudflare survive |
| Design opt-in OCR/vision/transcription (future only) | P3 | `P3-02` | Separate privacy and capability reviews |

## Legacy roadmap items to retain independently

- EPUB reading experience improvements, e-ink optimization, Indian-language support, privacy-first sync and Calibre/OPDS remain ongoing commitments.
- **PDF support** is planned separately. Do not conflate PDF import/reader implementation with comic CBZ import.
- **iOS expansion** remains a separate platform milestone, not an implied deliverable of this Android feature effort.
- Audiobook functionality already underway should be linked to the coordinated P0/P1 track instead of duplicated as a second backlog epic.

## Suggested issue format

**Title:** specific observable user capability.  
**Acceptance IDs:** stable identifiers from `ACCEPTANCE_MATRIX.csv`.  
**Scope:** what changes in `:core`, `:leisure`, storage and/or testing.  
**Non-goals:** no private hostnames/secrets in source; no hidden provider fallback; no silent schema migration.  
**Verification:** automated tests, fake-provider tests, Samsung/BOOX device cases and evidence links.  
**Dependencies:** earlier workstream/issue URLs if they exist.  
**Status:** do not mark done until matrix evidence and release-gate review are complete.

## README discoverability

In the existing README `## Roadmap` section, add a single link pointing to the repository-root file without replacing unrelated README content:

`See the [living Vaachak roadmap](ROADMAP.md) for current priorities, delivery gates and requirements traceability.`
