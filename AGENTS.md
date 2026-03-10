# Vaachak Leisure - Codex instructions

## Goal
Make the Android Compose UI automation-friendly for Maestro + AI by adding:
- Stable test IDs (Tid contract)
- Modifier.tid() and Modifier.testState()
- Wrapper components (TidIconButton, TidScreen, etc.)
- Screen root IDs for each main screen

## Constraints
- Do NOT change UI visuals or behavior.
- Keep accessibility: existing human-friendly content descriptions should remain where appropriate.
- Add stable automation IDs via semantics on the clickable container nodes.
- Prefer minimal diffs. Avoid large architectural rewrites.

## Where to implement
- Add testability utilities under:
  leisure/src/main/java/org/vaachak/reader/leisure/ui/testability/

## Conventions
- IDs use snake_case.
- Prefix by feature:
  screen_*, tab_*, library_*, reader_*, settings_*, catalog_*, highlights_*, login_*
- For lists:
  library_book_<bookHash> (and optionally library_book_0, library_book_1...)

## Build/Test
- After changes: run `./gradlew :leisure:assembleDebug` (or your normal build command).
- If tests exist, run relevant unit tests.