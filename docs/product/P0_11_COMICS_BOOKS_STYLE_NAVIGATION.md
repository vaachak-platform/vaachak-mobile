# P0-11 — Books-style Comics navigation and reading session tracking

Status: **device acceptance pending**. Books is **untouched**.

## Required experience

- The Comics tab retains Vaachak's main global header and navigation tabs.
- Within Comics, **clock / Schedule** shows only comics whose reading session began; opening page 1 counts as started even if page index stays 0.
- **Folder** shows every comic in the current vault. Default selection is Folder.
- Folder view includes icon-only Search and Sort. Sort options are **Sort by Progress**, **Sort by Recent**, **Sort by Title** (same labels and order as Books).
- Remove the Reading/Unread/Completed/All status chips, large Comics heading, duplicate search bar, and in-card Continue buttons.
- An icon-only Add button opens the CBZ/CBR SAF picker; unsupported files may still be shown when Android file managers mislabel MIME, and the import validator rejects them after selection.
- Tapping a comic cover navigates to `comic_reader/{comicId}`, a **separate full-screen Compose NavHost destination**, with no global Vaachak tabs. System Back or toolbar Back returns to the Comics shelf, retaining the selected Comics main tab.
- The comic reader has page progress and left-right/manga/vertical mode controls. Storage and archive inspection retain the previously delivered CBZ/CBR safety limits.
- `lastOpenedAt` is saved per vault when a comic is opened and on subsequent page changes; existing comic metadata lacks this field and is still readable. Legacy comics with saved progress are included in Recent Reading.
- No changes to Books, EPUB reader, catalog, synchronization, library database schema or audiobook implementation.

## Local test sequence

1. Run `./gradlew :core:testDebugUnitTest :leisure:testDebugUnitTest :leisure:assembleDebug detekt`.
2. Run `./gradlew :leisure:connectedDebugAndroidTest` on the target device.
3. On dual-app Samsung, check `adb shell pm list users`, then `adb install --user 0 -r <debug-apk>`. Never uninstall/reinstall to test this package.
4. In Comics, verify Folder shows existing CBZ and CBR, and clock is empty for files not yet opened.
5. Open a comic: global navigation tabs disappear. Use hardware/system Back to return to Comics, not Books.
6. Switch clock: the comic is shown even if you only viewed its first page. Switch Folder: all comics are present.
7. Test Search and three sort choices. No status filter chips appear.
8. Restart Vaachak; Recent Reading and last viewed page survive. Existing Books and catalogs still work.
9. Re-import the same content under another filename: duplicate is rejected. Test a genuine RAR4 and RAR5 CBR, normal CBZ, and a corrupted archive.
10. Preserve the debug package and user profile: Android `adb install -r` updates in place only when package name, signature and user are compatible; verify them before installing. Local device profiles and SAF grants can vary. The revision does not migrate data from a different app ID or Android user.

## Automated regression

- `SafeCbzArchiveTest.rejectExcessiveMetadata` verifies the new **4 MiB** limit, not the obsolete 2 MiB threshold.
- `SafeCbzArchiveTest.rejectTooManyEntries` separately tests rejection after 20,000 entries.
- `ComicShelfInstrumentedTest.readingTabShowsOnlyComicsThatWereStarted` verifies first-page sessions and folder return.
- `ComicShelfInstrumentedTest.comicsShelfUsesBooksStyleSortMenuWithoutStatusChips` verifies controls match Books.

## Still not implemented

Comic cross-device sync, comic catalogs, image-folder import, advanced two-page spread, and the Audiobooks experience remain separate scope. This change does not claim those capabilities.
