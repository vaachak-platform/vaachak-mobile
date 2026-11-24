# P0-11 / Increment A — Secure CBZ import and offline reading

**Baseline:** `main` at `b4ece3b5f9115fae382d104a7a77b2450ebc6b19` (PR #73 merged).  
**Stack:** `feat/p0-11-comics-import` (child) -> `feat/p0-secure-provider-credentials` (PR #72 parent) -> `main`.  
**Acceptance:** P0-11 remains **PENDING**: this is a working CBZ increment, not complete P0-11.

## Implemented

- The existing **Comics tab** now opens a CBZ shelf instead of a placeholder.
- Import using the Android OpenDocument SAF picker, retaining read permission across restarts.
- CBZ page discovery, natural numeric page order, JPG/JPEG/PNG/WebP images and read-only page display.
- Persist URI references and page position independently for each active local vault. This is device-local metadata, not the EPUB books table, and is not synchronized.
- Bounded ZIP intake: path traversal and ambiguous duplicate names rejected; no filesystem extraction; compressed input, uncompressed page, metadata, entry count, page count and total expansion limits; decoding samples excessively large images before rendering.
- Re-check the archive when the reader opens or moves to another page, so a replaced document is never trusted solely because it passed import-time inspection.
- Honor existing comic direction and fit preferences; e-ink devices render without animated transitions.
- Reader back navigation and local removal do not delete the original device file.

## Explicit remaining scope

- Folder-of-images import, CBR/RAR support, five complete reader modes, per-title overrides and page rotation UX are **not implemented** here.
- Import is limited to `.cbz` documents whose providers support durable OpenDocument permissions.
- This increment does not add covers/thumbnails, library sync, shared database migrations, or full memory-pressure benchmarks.
- No claims of BOOX device acceptance or release readiness are made.

## Security and device acceptance

1. Run JVM archive tests and leisure instrumented UI tests on a real/emulated device.
2. Import a sanitized CBZ containing JPG/PNG/WebP pages, page across it, exit/relaunch and confirm resume.
3. Import deliberately hostile CBZ fixtures (path traversal, duplicates, decompression bombs, enormous images), and confirm rejection without any filesystem extraction.
4. Switch profiles and confirm comics and positions do not appear in the other vault.
5. Revoke SAF document permission or remove the original CBZ; confirm user-facing failure with no crash.
6. Verify large comics on Samsung and BOOX, e-ink fit/direction, and offline operation.
7. Do not mark `P0-11` complete until the remaining original acceptance requirements are verified.

## PR and coverage

The child PR is compared with its parent security branch. Successful CI on the child does **not** automatically close the parent PR #72's credential-security acceptance or quality-gate concerns. Merge child into parent only after child CI is green; merge parent into `main` only after its own gates and compatibility review are satisfied.
