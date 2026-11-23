#!/usr/bin/env bash
# Android instrumentation stage for the disposable GitHub Actions API 35 emulator.
# Run from the repository root via android-emulator-runner's one-command script input.
set -Eeuo pipefail

if [[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ]]; then
  echo "ERROR: Authenticated coverage setup is restricted to an Android emulator." >&2
  exit 1
fi

adb shell wm size
adb shell wm density

# Build BOTH instrumentation APKs before authorizing Keystore keys. The production
# V2 keys require a device PIN unlock within 60 seconds before cryptographic use.
./gradlew :core:assembleDebugAndroidTest :leisure:assembleDebugAndroidTest --console=plain --warning-mode=summary --no-problems-report --no-daemon --no-configuration-cache

# This fixed PIN exists ONLY inside a freshly-created, disposable CI emulator.
# Never use it on a real device or in a production key-management workflow.
CI_EMULATOR_PIN="638294"
pin_installed=false
cleanup() {
  if [[ "$pin_installed" == true ]]; then
    adb shell locksettings clear --old "$CI_EMULATOR_PIN" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

# Android's CTS exercises auth-bound keys by provisioning a PIN and submitting
# it through the real keyguard. Merely setting the PIN does not grant key use.
if ! adb shell locksettings set-pin "$CI_EMULATOR_PIN" >/dev/null; then
  echo "ERROR: Cannot provision a secure device credential on the API 35 emulator." >&2
  exit 1
fi
pin_installed=true

# Lock, wake, and authenticate through Android's real keyguard.
adb shell input keyevent KEYCODE_SLEEP
sleep 1
adb shell input keyevent KEYCODE_WAKEUP
sleep 1
adb shell input keyevent KEYCODE_MENU
sleep 1
adb shell input text "$CI_EMULATOR_PIN"
adb shell input keyevent KEYCODE_ENTER
sleep 1

# Verify the configured credential without printing it or logging plaintext keys.
if ! adb shell locksettings verify --old "$CI_EMULATOR_PIN" >/dev/null; then
  echo "ERROR: Emulator credential verification failed." >&2
  exit 1
fi

# Keeps the emulator awake during the 24+ Compose tests. Does not remove auth.
adb shell svc power stayon true

./gradlew :core:createDebugAndroidTestCoverageReport :leisure:createDebugAndroidTestCoverageReport -Pandroid.testInstrumentationRunnerArguments.vaachakAuthCi=true --stacktrace --console=plain --warning-mode=summary --no-problems-report --no-daemon --no-configuration-cache
