# PR #87 audiobook quality recovery

Baseline candidate: `85592e6436957f058098d4c7d2ba53947ac11be9`.
PR base: `af8d3f8efb06b9aa5d4709d3b83e61c354ac2aee`.

The initial quality gate failed at 58.5% new-code coverage and B reliability, despite 79 successful Leisure instrumented tests. Keep the original PR, security policies, CI workflows, coverage exclusions, dependencies and acceptance thresholds unchanged.

## Findings addressed

- AudioImporter: use the existing configurable dispatcher; check failed partial artwork deletion and stage cleanup; isolate bounded, cancellable stream copy and platform metadata reading.
- AudioScreens: use the existing configurable dispatcher, extract shelf header/list/dialog and operation handling, extract pure shelf filtering/sorting, use integer-specific Compose state, expose explicit removal semantics for testing.
- AudioPlaybackService: use a Runnable lambda, replace the chained conditional command dispatch with `when`, use the existing configurable main dispatcher. Preserve serial progress persistence, Continue semantics and local-only media restrictions.
- AudioStore: check removal cleanup and issue a sanitized warning if residual app-private media remains. No change to atomic index ordering or vault identity.

## Verification

Keep all earlier tests. Add unit tests for bounded copy, cancellation, timeline/filter/sort, simulated SAF provider traversal and malformed data, atomic index read/validation, and removed-title progress. Add actual Android metadata/artwork, service transport/settings/sleep/completion and rejected-open tests, Compose player/header/settings/filter tests, and a real MediaStore-backed synthetic picker import, duplicate and removal test. The MP3 fixture is generated from a sine wave and solid-color artwork; no personal or third-party content.

The host-side coverage preflight requires at least 80% conservative estimated coverage for changed executable lines and branches across the whole PR (not merely this correction). It combines unit and instrumented JaCoCo XML without double-counting branches: XML lacks identities, so use the largest covered count rather than adding potentially overlapping conditions. The exact Sonar new-code definition and merged branch coverage can differ; SonarCloud remains authoritative. No coverage result is claimed before actual Android execution.

Physical device, long M4B, notification, Bluetooth and relink limitations from the integrated delivery remain open; this is a quality correction rather than a claim of complete audiobook acceptance.
