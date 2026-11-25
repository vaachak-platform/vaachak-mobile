# P0-11 — Comic library (CBZ + CBR) functionality increment

**Branch:** `feat/p0-11-comics-import`, stacked onto `feat/p0-secure-provider-credentials` (draft PR #72).  
**Scope:** Comics only. Existing Books UI/UX, EPUB imports, duplicate handling, catalog integration, synchronization and reader behavior are unchanged.  
**Status:** Local functionality acceptance required before commit; P0-11 is not fully complete.

## User-facing capabilities

- Import `.cbz` (ZIP) and `.cbr` (RAR4/RAR5) through Android's OpenDocument picker.
- Display the revised approved Comics-only UI: compact toolbar, collapsed search, sorting, status chips, a three-column cover grid on normal phones, progress/Continue badges, and a floating import action. The Books tab retains its existing design and functionality.
- The comic reader now uses immersive page viewing, a hideable toolbar and foot controls, page tap-zones, a manga direction option, and continuous vertical reading.
- CBZ originals remain SAF documents with persisted read permission; CBR originals are read via SAF, then copied to a private per-vault location for random-access RAR reading.
- Reject duplicate document references, SHA-256 content matches (even renamed), and identical titles within the same vault. Existing entries are retained; the user sees a duplicate explanation.
- Preserve legacy CBZ records stored by the earlier local test build, defaulting their format to CBZ.
- Support offline page viewing, resume position, comics reading-direction settings, fit modes, e-ink behavior and local removal.
- Keep CBR imported bytes in `noBackupFilesDir`, outside Android backup. Removing a CBR record deletes only the owned copy for that vault, never the original picked file.

## Decoder and license decision

- `com.github.junrar:junrar:8.1.2`, Maven Central, source https://github.com/junrar/junrar.
- Junrar v8 supports RAR4/RAR5 and later RAR readers, Java without JNI. Only the decoder API is used; **no RAR-compression/archiver implementation** is shipped.
- The source is distributed under the **UnRAR license**, which allows RAR extraction in other software, but forbids reuse to build a compatible RAR archiver. **This is source-available, not an OSI-approved open-source license.** Review this license for Vaachak redistribution before release; see https://github.com/junrar/junrar/blob/v8.1.2/LICENSE.
- Confirmed upstream reference: https://github.com/sableos-project/sableos-android/tree/main/apps/reader/reader-engine-comic uses **Junrar 7.5.5**, `RarComicSource.kt`, `ZipComicSource.kt`, and signature-based opening in `ComicOpener.kt`. Vaachak retains pinned Junrar 8.1.2, without copying upstream reader code.

## Archive trust and resource restrictions

- Never write archive entry paths to the filesystem. Inspect and stream entries using the decoder.
- Reject archive path traversal, absolute/ambiguous paths, duplicate case-folded entry paths, encrypted archives and multi-volume archives.
- Reject empty archives, unsupported signatures, excessive entries/pages, oversized compressed archives and oversized uncompressed entries. Actual signatures select RAR/ZIP even when the `.cbz`/`.cbr` extension is mislabeled.
- CBR import checks archive headers and reads/validates its first image; reading subsequent pages validates the selected page without decompressing the entire archive. Archive metadata is checked each time; corruption in an unread page is reported when that page is opened.
- Caps: 2 GiB compressed archive, 20,000 entries, 5,000 images, 64 MiB decoded bytes per image, 8 GiB declared/streamed total content, and a 256 MiB RAR dictionary. ZIP-only unrecognized entries are capped at 4 MiB each. Excessive compression ratios are rejected when their packed sizes are available.
- Decode image dimensions first; sample down to a bounded RGB_565 bitmap before Compose rendering. Network access is never needed.

## Functional acceptance on Samsung before commit

1. Confirm Books tab, existing EPUBs, catalog imports, sync and Books duplicate rejection are unchanged. Do not apply any Books changes in this deliverable.
2. In Comics, import a valid CBZ with 1/2/10 image names; open, turn pages, exit, resume, rotate, restart app.
3. In Comics, import a valid **RAR4 CBR** larger than the previous 160 MiB cap with JPG/PNG pages, including an archive with compressed entries; confirm page order and page display. If you have no large sample, test the largest available CBR.
4. Import a valid **RAR5 CBR** and confirm the same. (RAR5 compatibility must be verified on the real phone; the standalone compiler check is not runtime validation.)
5. Import a ZIP comic renamed `.cbr` and confirm the signature correctly identifies it as CBZ. Try a broken RAR, password-protected RAR, multi-volume RAR, ZIP traversal fixture and decompression-bomb fixture; confirm safe failure with no crash.
6. Re-import the same CBZ and CBR via the same URI, then rename/copy them to a different URI and retry; confirm duplicate rejection. Import a different comic with the same title and confirm intentional title collision rejection.
7. Switch local reader vaults; confirm shelf entries and resume positions remain isolated. Import the same content in another vault and confirm independent storage.
8. Remove a CBR from the shelf; confirm the original external file remains intact. Remove a CBZ; confirm its source remains. Reopen other comics successfully.
9. Verify e-ink/BOOX where available, Offline Mode, orientation, large fonts, and search/filter/Continue behavior. On the Samsung S25, confirm three cover columns and a compact toolbar without the old large Comics heading.
10. Open Import and verify the picker filters to known CBZ/CBR/ZIP/RAR MIME types. Android SAF does not offer portable extension-only filtering: some generic ZIP/RAR files may still appear, and providers that report `.cbr` as `application/octet-stream` may hide it. Regardless, Vaachak must reject unsupported extensions and signatures cleanly.
11. Test the full-screen reader: tap zones, manga direction, single-page fit, and continuous vertical scroll. Confirm exit/reopen restores progress.

## Explicitly deferred

- Catalog-based comic imports and cross-device comic sync are **not** wired to the existing EPUB sync/catalog engine. Those will need format-aware models and tests without altering Books behavior.
- Archives above 2 GiB and RAR dictionaries above 256 MiB remain intentionally unsupported. Measure import and reader performance on BOOX-class memory before increasing them further.
- CBR thumbnails remain best-effort local reading previews. There is no full metadata editing, folder import, spread/double-page mode, or image prefetch yet.
- The Audiobooks design remains approved but is not implemented here.

## Test policy

Build, unit tests, detekt, Android instrumentation, then physical-device functional acceptance **before committing**. If any test fails, fix full files and repeat. Only after acceptance: commit, push, create stacked PR and review CI; parent PR #72 requires its own security review.
