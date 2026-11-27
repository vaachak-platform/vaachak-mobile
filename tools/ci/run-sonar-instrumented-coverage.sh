#!/usr/bin/env bash
# Android instrumentation stage for the disposable GitHub Actions API 35 emulator.
# Run from the repository root via android-emulator-runner's single-command script.
set -Eeuo pipefail

if [[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ]]; then
  echo "ERROR: Authenticated coverage setup is restricted to an Android emulator." >&2
  exit 1
fi

# Validate the CI emulator against the locally qualified
# Pixel 7 API 35 Google APIs display baseline.
echo "=== CI emulator configuration ==="

adb devices -l
emulator -version

api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
abi="$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
boot="$(adb shell getprop sys.boot_completed | tr -d '\r')"
size="$(adb shell wm size | tr -d '\r')"
density="$(adb shell wm density | tr -d '\r')"

echo "API: $api"
echo "ABI: $abi"
echo "Boot completed: $boot"
echo "$size"
echo "$density"

echo "Build fingerprint:"
adb shell getprop ro.build.fingerprint

echo "Device model:"
adb shell getprop ro.product.model

echo "CPU information:"
adb shell cat /proc/cpuinfo | grep -c '^processor' || true

echo "Memory information:"
adb shell cat /proc/meminfo | head -n 3

echo "Data partition:"
adb shell df -h /data

if [[ "$api" != "35" ]]; then
  echo "ERROR: Expected Android API 35." >&2
  exit 1
fi

if [[ "$abi" != "x86_64" ]]; then
  echo "ERROR: Expected x86_64 CI system image." >&2
  exit 1
fi

if [[ "$boot" != "1" ]]; then
  echo "ERROR: Emulator has not completed boot." >&2
  exit 1
fi

if [[ "$size" != "Physical size: 1080x2400" ]]; then
  echo "ERROR: Expected Pixel 7 display without overrides." >&2
  exit 1
fi

if [[ "$density" != "Physical density: 420" ]]; then
  echo "ERROR: Expected Pixel 7 density without overrides." >&2
  exit 1
fi

echo "=== Animation configuration ==="

for setting in \
  window_animation_scale \
  transition_animation_scale \
  animator_duration_scale
do
  printf '%s: ' "$setting"
  adb shell settings get global "$setting"
done

echo "PASS: CI emulator API, ABI, boot and display verified."

# Compile both instrumentation APKs BEFORE provisioning/unlocking the temporary
# device PIN. Gradle compilation can consume more than the Keystore's 60-second
# credential validity window, so no build preparation should follow authentication.
./gradlew :core:assembleDebugAndroidTest :leisure:assembleDebugAndroidTest --console=plain --warning-mode=summary --no-problems-report --no-daemon --no-configuration-cache

# This PIN exists ONLY on a freshly created, disposable CI emulator. Never
# run this script on physical devices or use the credential in production.
CI_EMULATOR_PIN="638294"
pin_installed=false
cleanup() {
  if [[ "$pin_installed" == true ]]; then
    adb shell locksettings clear --old "$CI_EMULATOR_PIN" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

if ! adb shell locksettings set-pin "$CI_EMULATOR_PIN" >/dev/null; then
  echo "ERROR: Cannot provision a secure device credential on the CI emulator." >&2
  exit 1
fi
pin_installed=true

# Stay awake during test execution without removing device authentication.
adb shell svc power stayon true

# A real PIN unlock must precede cryptographic use; setting a PIN or verifying
# its value alone is not a substitute for authenticating at the keyguard.
adb shell input keyevent KEYCODE_SLEEP
sleep 1
adb shell input keyevent KEYCODE_WAKEUP
sleep 1
adb shell input keyevent KEYCODE_MENU
sleep 1

# On some API 35 emulator images, MENU does not expose the PIN entry field.
# Swipe up to display it, using the physical display resolution rather than
# coordinates fixed to a particular emulator profile.
physical_size="$(adb shell wm size | tr -d '\r' | sed -nE 's/^Physical size: ([0-9]+)x([0-9]+)$/\1 \2/p' | head -n 1)"
if [[ "$physical_size" =~ ^([0-9]+)[[:space:]]+([0-9]+)$ ]]; then
  screen_width="${BASH_REMATCH[1]}"
  screen_height="${BASH_REMATCH[2]}"
  adb shell input swipe "$((screen_width / 2))" "$((screen_height * 85 / 100))" "$((screen_width / 2))" "$((screen_height * 25 / 100))" 350
fi
adb shell input text "$CI_EMULATOR_PIN"
adb shell input keyevent KEYCODE_ENTER
sleep 2

# Confirm the configured credential is valid (the Core tests themselves
# verify that it actually authorized Android Keystore). Do not print the PIN.
if ! adb shell locksettings verify --old "$CI_EMULATOR_PIN" >/dev/null; then
  echo "ERROR: Emulator PIN verification failed." >&2
  exit 1
fi

# Non-sensitive diagnostic; AOSP variants differ in dumpsys trust formatting.
echo 'Emulator keyguard state after PIN entry (diagnostic only):'
adb shell dumpsys trust 2>/dev/null | grep -Ei 'deviceLocked|isDeviceLocked' | head -n 6 || true

# CRITICAL ORDER: the authentication-bound key has a 60-second validity window.
# The previous CI run executed 34 Leisure tests BEFORE 9 Core tests and four
# Core crypto tests failed with "must be unlocked using its PIN". Running both
# report tasks in one Gradle invocation does not guarantee task order.
# Execute Core as a standalone invocation IMMEDIATELY after PIN entry, then
# execute the longer Leisure suite. Both reports are still generated and later
# passed to SonarCloud unchanged.
echo 'Running Core authenticated Keystore tests and Core coverage FIRST.'
./gradlew :core:createDebugAndroidTestCoverageReport -Pandroid.testInstrumentationRunnerArguments.vaachakAuthCi=true --stacktrace --console=plain --warning-mode=summary --no-problems-report --no-daemon --no-configuration-cache

echo 'Core authenticated tests passed. Running Leisure Compose tests and coverage.'
./gradlew :leisure:createDebugAndroidTestCoverageReport -Pandroid.testInstrumentationRunnerArguments.vaachakAuthCi=true --stacktrace --console=plain --warning-mode=summary --no-problems-report --no-daemon --no-configuration-cache
