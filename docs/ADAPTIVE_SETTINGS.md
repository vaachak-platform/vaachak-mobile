# Adaptive Settings for E-Ink Readers and Phones

This design targets Android reader devices including BOOX tablets as well as phones. It does **not** identify or special-case individual manufacturers. Settings reacts to the **space available to the app window** (including split-screen), device font scale, and Vaachak's existing `ThemeMode.E_INK` preference.

## Navigation and layout

- **Narrow windows:** a scrolling list of settings categories. Selecting a category opens a separate, scrolling detail pane. The arrow and Android Back return to categories.
- **Wide windows (at least 720dp available width):** a persistent 288dp category list plus a separate, scrollable detail pane. The content has a maximum reading width of 800dp to prevent very long lines on large e-readers.
- **Large accessibility text (font scale >= 1.3):** two panes require at least 920dp, avoiding squeezed controls and wrapped navigation labels.
- **Static transitions:** settings category changes are immediate, without transition animations. Rows are at least 72dp; setting controls at least 56dp. Text can wrap naturally.
- **E-ink:** the existing e-ink theme provides near-white paper backgrounds and high-contrast labels. The active section uses an outline, not color alone. No BOOX proprietary refresh APIs are required. Users can manage per-app refresh preferences through their device's E-Ink Center.

## Settings categories

General, Books & Reading, Comics, Audio, Library & Content, and Intelligence. Previous settings destinations (profiles, sync, appearance, TTS, dictionary, catalogs, Gemini) remain accessible. The old fixed-width Global/Book/Content/Intelligence tab row is removed.

## Comic preferences (foundation only)

This PR **does not implement the CBZ/CBR engine or import**. It stores the following settings in the current local vault's DataStore:

- LTR / RTL reading direction
- Single page / continuous vertical reading
- Fit page / fit width
- Tap-to-turn, volume-key navigation, page preloading, reduced motion

They are visible in Settings with a clear notice that they take effect once the Comics engine is integrated. The engine must consume `SettingsRepository.comicPreferences` when it is added. No Room schema change is required for the preferences.

## Test matrix

| Context | Expected outcome |
| --- | --- |
| Galaxy S25 Ultra portrait (font scale 1.0 and 1.4) | Single-pane navigation; no clipped category labels |
| Small e-reader portrait / narrow split-screen, <720dp | Single-pane navigation; all controls reachable by scrolling |
| BOOX ~8-10 inch in landscape, >=720dp usable width | Two-pane settings; categories stay visible on left |
| 10.3–13.3 inch BOOX portrait and landscape | Two panes whenever usable width allows, constrained content line length |
| Large-text e-reader, font scale >=1.3 at 820dp | Single-pane instead of crowding |
| Large-text e-reader at 1024dp | Two panes with readable labels |
| E-ink theme, grayscale | Active category visibly outlined; no color-only distinction |
| Reader profile switch | Comic options isolated per vault |
| Rotate / split-screen resize | Selected category survives recomposition; layout changes without navigation reset |
| Android Back from compact detail | Returns to Settings category list |

## Build and test

```bash
./gradlew :leisure:testDebugUnitTest :leisure:assembleDebug
./gradlew :leisure:installDebug
```

Manual tests on BOOX hardware are necessary for ghosting, refresh behavior, stylus targeting, and accessibility; these are not verifiable from a desktop build alone.

## CI policy

Pull requests and pushes containing **only** `docs/**` and `*.md` changes should not start Android, lint, CodeQL, MobSF, or Sonar workflows. Mixed code+docs changes run as normal. Weekly CodeQL and release-tag workflows remain unchanged.

**Branch protection:** if one of these workflows is configured as a *required* check, GitHub's `paths-ignore` may leave the check pending for docs-only PRs. In that case replace that workflow's trigger filter with an always-running lightweight status job and conditionally skip expensive build jobs, or adjust branch protection. Verify repository rules before relying on docs-only merges.
