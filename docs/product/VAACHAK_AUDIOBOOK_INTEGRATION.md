# Integrated local audiobook candidate

Base: `af8d3f8efb06b9aa5d4709d3b83e61c354ac2aee` (merged PR 72). Acceptance remains pending until actual Android/CI evidence is collected. The implementation is independent of EPUB, Comics, TTS, sync credentials, Room v4 and release signing.

## Continue behavior

Behavior reference: SableOS Android reader commit `330783a09a0ada267727595548c3d52595fd2785`, `apps/reader/reader-model/src/main/kotlin/org/sableos/reader/library/LibraryFilters.kt`, `LibraryViews.kt`, and `apps/reader/leisure/src/main/java/org/sableos/reader/audio/AudiobookPlaybackService.kt`. Vaachak independently implements the same eligibility rule: opened, positive progress, not finished; most-recent first. Continue starts at the saved global millisecond position (mapped to track and offset). The service saves every 10 seconds during playback and on seek, pause, completion, switch, task removal and service destruction. Abrupt process death or power loss may lose the last checkpoint or queued write. No autoplay after process/device restart. Completed books leave Continue and can be replayed.

## Storage and playback

Selected documents or folder audio tracks are copied via SAF to private no-backup storage scoped to a hashed vault ID. No source URI is persisted; cloud-backed providers may need network access during selection/copy, but the copied book plays without that provider. This deliberately differs from SableOS's in-place persistent URI design. No permissions for broad external storage, no network audio source, no archive unpacking, no change to Room. Limits: 500 books per vault, 128 tracks per book, depth 8/1024 document nodes per folder, 8 GiB per file, 16 GiB per book, 14 days total duration, 16 MiB index. SHA-256 content hashes reject duplicate ordered track lists in the same vault. Import writes synced staged files before index publication; failed or interrupted publication can leave an unreferenced private copy. Removing a title commits index removal first, then removes its copied files; no forensic erasure claim. Private audio files/settings do not participate in existing cloud sync or backup. Re-import missing or corrupt copies; automated position-preserving relink is not implemented.

Media3 ExoPlayer and MediaSessionService use the existing 1.9.2 catalog version. Audio focus and becoming-noisy handling enabled; service is private, trusted session controllers get transport access without playlist replacement. Profile switch clears the current service playlist; UI state is filtered by active vault. Notification and lock-screen behavior requires actual device verification. Embedded Nero MP4 `chpl` chapters are parsed with bounded box traversal, folder tracks are naturally ordered and become chapters. Other embedded chapter encodings (including QuickTime chapter tracks) fall back to track markers. Optional first-track artwork is limited to source files <=32 MiB, embedded bytes <=2 MiB and 4096px dimension bounds before downsampling; larger/missing art is omitted. Codec and metadata support depend on Android/Media3.

Preferences: all 10 specified speeds, 4 back intervals, 4 forward intervals; defaults 1×/15s/30s. Title overrides snapshot the three playback preferences and do not change on later default edits until reset. Timer options Off/15/30/60 minutes/end-of-chapter. Timer stops within the service polling interval (500ms), not guaranteed sample-accurate. Playback controls are scrollable text and buttons; physical BOOX/large-font/Bluetooth/interruption acceptance is pending.

## Acceptance mapping (candidate only)

- P0-12: local import, Continue, playback/defaults, background service; automated model and Android fixtures included.
- P0-13: full existing regression suite; no TTS or EPUB schema changes.
- P1-02: per-title overrides, chapter/seek/resume, audio focus, session, sleep timer; system notification/lock-screen still require hardware/device evidence.
- P1-05: Audio tab and Settings destination, test tags; actual device visual/accessibility verification pending.
- P1-06: copy-based isolation and bounded input; position-preserving relink not implemented.
- P2-03: long M4B, physical interruptions/rotation/low memory remain explicit follow-up verification; not claimed passed.

No acceptance matrix status has been marked PASS by this candidate. No CI threshold, coverage exclusion or signing configuration change.
