# PR #58: Compose host stability in the expanded 24-test suite

## Evidence and scope

On Samsung `SM-S938U1 - 17`, the latest local build and JVM tests succeeded.
The Core Android instrumented suite passed 4/4. The enlarged Leisure suite
ran 24 tests; 21 passed and three Settings tests failed with
`IllegalStateException: No compose hierarchies found in the app`:

- `wideGeneralOfflineModeCanBeTurnedOnAndBackOff`
- `compactCategoriesExposeComicsAndAudioWithoutWrappedTabs`
- `lcdThemeKeepsSettingsCategoriesFunctional`

This exception means Compose's test owner cannot find an attached Compose
root **at the moment an assertion/action runs**. It does not, by itself,
prove the activity failed to launch, a production app crash, or a particular
screen-off event. Earlier short suites and the standalone host smoke test
passed. The longer suite makes an Android screen-off/Activity visibility
transition plausible, and startup attachment timing can also contribute.

## Complete-file replacements

1. `leisure/src/debug/java/org/vaachak/reader/leisure/testing/ComposeTestHostActivity.kt`
   - Keep the screen awake while the *debug-only test Activity* is visible
     using Android's `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON`.
   - Reapply the flag after the host resumes and log pause/destroy transitions
     using tag `VaachakComposeHost` to help diagnose any remaining failure.
   - The activity still has no `setContent()`, launcher, Hilt dependency or
     change to release behavior; it does **not** dismiss the keyguard.
2. `leisure/src/androidTest/kotlin/org/vaachak/reader/leisure/ui/settings/SettingsComposeInstrumentedTest.kt`
   - Preserve all fourteen Settings tests and behavioral assertions.
   - After `compose.setContent`, wait **up to ten seconds** for the actual
     `Tid.Screen.settings` semantics root to be attached, inspecting the
     unmerged tree with `atLeastOneRootRequired=false`. If the hierarchy never
     appears, the test still fails; there is no retry, skip or ignored test.

There are no production, SonarCloud threshold, security, JaCoCo or CI workflow
changes in this archive. The existing nine AI Compose tests, fourteen
Settings Compose tests and one smoke test remain 24 Leisure tests total.

## Validate on a physical device

Keep the Samsung unlocked and USB connected. From the repo root:

```bash
./gradlew :leisure:assembleDebugAndroidTest
./gradlew :leisure:connectedDebugAndroidTest
./gradlew :core:createDebugAndroidTestCoverageReport :leisure:createDebugAndroidTestCoverageReport
```

For a controlled screen-timeout diagnostic, the following also keeps the
connected device awake while on USB power. This is optional and temporary;
its effect can be reversed afterward:

```bash
adb shell svc power stayon usb
# After validation:
adb shell svc power stayon false
```

If the failure persists, collect **the exact Android process and lifecycle
messages** rather than blindly changing the assertions:

```bash
adb logcat -c
./gradlew :leisure:connectedDebugAndroidTest
adb logcat -d -v time \
  -s VaachakComposeHost:D AndroidRuntime:E ActivityTaskManager:I \
  > "$HOME/Downloads/vaachak-compose-host-lifecycle.txt"
```

Also inspect `leisure/build/reports/androidTests/connected/debug/index.html`.
If an Activity is destroyed during a test or an app exception is present,
that lifecycle/error evidence determines the next fix. Do not hide failures
by globally retrying or skipping UI tests.

## SonarCloud acceptance

The last reported new-code coverage was 74.0%, below the 80% gate. Coverage
cannot be reliably updated while the instrumented suite fails. Once the
suite and GitHub emulator pass, re-run the scanner and inspect condition
coverage, especially SettingsScreen and SettingsRepository. The Android
Keystore S6288 security issue is independent and requires a documented
security-owner decision; this archive does not change key authentication.
Do not mark the PR ready for merge until the CI and all quality-gate
requirements pass.

## Verification limits

Full files and ZIP contents were checked in the authoring environment;
Android test execution requires the user's SDK/device and is not claimed
here. Android documents FLAG_KEEP_SCREEN_ON as keeping the display on while
that window is visible:
https://developer.android.com/develop/background-work/background-tasks/awake/screen-on

Jetpack Compose test synchronization reference:
https://developer.android.com/develop/ui/compose/testing/synchronization
