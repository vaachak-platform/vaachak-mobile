# P0-11: Comics Sonar coverage and reliability remediation

## Context

Parent PR #72 (after merged stacked PR #74) failed SonarCloud with **41.6% new-code coverage** (target >=80%) and **B reliability on new code** (target A). Android CI, lint, CodeQL and MobSF previously passed.

## Scope

- Split the CBZ ZIP traversal into independent inspection, path, budget and image checks without relaxing the archive limits.
- Split the CBR RAR traversal into archive preflight, metadata validation, page indexing and bounded on-demand extraction. Preserve RAR4/RAR5 and Junrar 8.1.2.
- Extract the Comics shelf toolbar and immersive chrome into dedicated Compose functions, without altering established UI behavior.
- Use `AppCoroutineConfig.io` for blocking comic I/O; avoid redundant dispatcher switches when calling an already suspending cover loader.
- Handle failed private-file deletion explicitly; use Android KTX URI parsing.
- Place Junrar and Hilt lifecycle Compose dependencies in the Gradle version catalog, and use the current `hiltViewModel` API only in the Comics navigation route.
- Exclude binary Android fonts and raster images from UTF-8 source scanning (not from runtime or app packaging); no Comics code is excluded from coverage.
- Add per-vault, saved progress, CBR metadata validation, and Compose shelf/navigation tests.

## Coverage caveats

The cross-module JaCoCo unmatched-report messages in the previous Sonar log are normal cross-module attempts: the `core` and `leisure` reports were scanned by both modules. They do not prove all coverage was lost. Coverage must be checked in the actual subsequent PR #72 Sonar analysis. This package has **not** been demonstrated to lift new-code coverage to 80%; the gate stays mandatory.

## Validations

1. `./gradlew :core:testDebugUnitTest :leisure:testDebugUnitTest :leisure:assembleDebug detekt :core:lintDebug :leisure:lintDebug`
2. Run instrumentation tests on CI emulator or an emulator dedicated to automated tests, not the Samsung device containing your test library.
3. With an emulator: `./gradlew :leisure:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`
4. Upgrade manually on Samsung user 0 with `adb install --user 0 -r <debug-apk>`; never uninstall or clear app data.
5. Recheck CBZ and CBR import, duplicate rejection, per-vault library, fullscreen reader, zoom, resume, and all Books functionality.
6. Merge the stacked correction only after green local checks, then examine new PR #72 Sonar findings before merging #72 into main.

## Acceptance

- New-code coverage >= 80%, reliability A in SonarCloud; no quality-gate bypass or suppression.
- Credential-security checks on parent PR #72 remain independent release blockers.
