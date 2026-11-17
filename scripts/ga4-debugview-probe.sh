#!/usr/bin/env bash
# GA4 DebugView probe (issue #322, Phase C-2). THROWAWAY: lives only on
# the work/ga4-debugview-probe branch and must never merge.
#
# Boots a clean API 36 emulator (the capture-store-screenshots.sh pattern),
# installs the configured debug build (package com.pittech.debug — a client
# in app/google-services.json, so the google-services plugin applies and
# PitTechAnalytics initializes for real), turns on Firebase Analytics
# debug mode + FA verbose logging BEFORE the app's first launch, runs
# Ga4DebugViewProbeTest (real UI: Home -> Recipes -> Settings -> start a
# cook -> Live board -> Timeline), dwells so the SDK flushes, and records
# the exact UTC window plus the FA logcat as artifacts for the GA4
# DebugView confirmation (property 564773286).
set -uo pipefail

avd_name="pittech-ga4-probe-api-36"
device_name="pixel_2"
system_image="system-images;android-36;google_apis;x86_64"
sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
avdmanager_bin="${sdk_root}/cmdline-tools/latest/bin/avdmanager"
emulator_bin="${sdk_root}/emulator/emulator"
output_dir="${GITHUB_WORKSPACE:-$(pwd)}/app/build/ga4-probe"
emulator_pid=""
logcat_pid=""
pkg="com.pittech.debug"

if [[ -z "$sdk_root" ]]; then
  echo "ANDROID_SDK_ROOT or ANDROID_HOME must point to the Android SDK." >&2
  exit 1
fi
if [[ ! -x "$avdmanager_bin" ]]; then
  avdmanager_bin="$(find "$sdk_root/cmdline-tools" -mindepth 3 -maxdepth 3 -type f -name avdmanager 2>/dev/null | sort -V | tail -n 1)"
fi
if [[ ! -x "$avdmanager_bin" || ! -x "$emulator_bin" ]]; then
  echo "Android SDK command-line tools and emulator are not installed in $sdk_root." >&2
  exit 1
fi

mkdir -p "$output_dir"
export ANDROID_AVD_HOME="${RUNNER_TEMP:-/tmp}/pittech-ga4-probe-avd"
mkdir -p "$ANDROID_AVD_HOME"

stop_emulator() {
  if [[ -n "$logcat_pid" ]]; then
    kill "$logcat_pid" >/dev/null 2>&1 || true
  fi
  adb emu kill >/dev/null 2>&1 || true
  if [[ -n "$emulator_pid" ]]; then
    kill "$emulator_pid" >/dev/null 2>&1 || true
  fi
  return 0
}
trap stop_emulator EXIT

boot_emulator_once() {
  printf 'no\n' | "$avdmanager_bin" create avd \
    --force \
    --name "$avd_name" \
    --package "$system_image" \
    --device "$device_name" || return 1

  setsid "$emulator_bin" \
    -avd "$avd_name" \
    -no-window \
    -gpu lavapipe \
    -noaudio \
    -no-boot-anim \
    -no-snapshot \
    -camera-back none \
    -wipe-data \
    > "$output_dir/emulator.log" 2>&1 &
  emulator_pid=$!

  adb start-server || return 1
  timeout 240 adb wait-for-device || return 1
  local boot_deadline=$((SECONDS + 600))
  until [[ "$(timeout 15 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)" == "1" ]]; do
    if (( SECONDS >= boot_deadline )); then
      echo "The Android emulator did not finish booting within 10 minutes." >&2
      cat "$output_dir/emulator.log" >&2
      return 1
    fi
    if ! kill -0 "$emulator_pid" 2>/dev/null; then
      echo "The Android emulator process exited before boot completed." >&2
      cat "$output_dir/emulator.log" >&2
      return 1
    fi
    sleep 5
  done
  echo "Emulator booted."
}

if ! boot_emulator_once; then
  echo "First boot attempt failed; retrying once on a fresh AVD."
  stop_emulator
  sleep 5
  boot_emulator_once || exit 1
fi

app_apk="${GITHUB_WORKSPACE:-$(pwd)}/app/build/outputs/apk/debug/app-debug.apk"
test_apk="${GITHUB_WORKSPACE:-$(pwd)}/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
if [[ ! -f "$app_apk" || ! -f "$test_apk" ]]; then
  echo "Build the app and instrumented test APKs before running this script." >&2
  echo "Expected: $app_apk and $test_apk" >&2
  exit 1
fi

install_apk() {
  local apk="$1"
  local extra_args="${2:-}"
  if ! timeout 180 adb install -r $extra_args "$apk"; then
    echo "First install attempt failed for $apk; retrying once." >&2
    timeout 180 adb install -r $extra_args "$apk"
  fi
}

echo "Installing the app and instrumentation test APKs."
install_apk "$app_apk"
install_apk "$test_apk" "-t"

echo "Preparing the device (disable lock screen, keep screen on, pre-grant permissions)."
timeout 30 adb shell locksettings set-disabled true
timeout 30 adb shell svc power stayon true
timeout 30 adb shell settings put system screen_off_timeout 1800000
timeout 30 adb shell pm grant "$pkg" android.permission.POST_NOTIFICATIONS || true
timeout 30 adb shell pm grant "$pkg" android.permission.BLUETOOTH_SCAN || true
timeout 30 adb shell pm grant "$pkg" android.permission.BLUETOOTH_CONNECT || true
timeout 30 adb shell pm grant "$pkg" android.permission.ACCESS_FINE_LOCATION || true
timeout 30 adb shell pm grant "$pkg" android.permission.CAMERA || true

echo "Enabling Firebase Analytics debug mode + FA verbose logging for $pkg (before first launch)."
timeout 30 adb shell setprop debug.firebase.analytics.app "$pkg"
timeout 30 adb shell setprop log.tag.FA VERBOSE
timeout 30 adb shell setprop log.tag.FA-SVC VERBOSE
timeout 30 adb shell setprop log.tag.FirebaseApp VERBOSE
timeout 30 adb shell getprop debug.firebase.analytics.app | tee "$output_dir/debug-prop-check.txt" || true

timeout 30 adb logcat -c || true

# The deliverable window: app running with debug mode on starts now.
window_start="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
{
  echo "package=$pkg"
  echo "window_start_utc=$window_start"
} > "$output_dir/probe-window.txt"
echo "PROBE WINDOW START (UTC): $window_start"

echo "Starting FA logcat capture."
adb logcat -v threadtime FA:V FA-SVC:V FirebaseApp:V '*:S' > "$output_dir/logcat-fa.txt" 2>&1 &
logcat_pid=$!

echo "Running the GA4 DebugView probe test."
test_output="$output_dir/probe-test.txt"
timeout 20m adb shell am instrument -w -r \
  -e class 'com.pittech.Ga4DebugViewProbeTest' \
  com.pittech.debug.test/androidx.test.runner.AndroidJUnitRunner > "$test_output" 2>&1
test_rc=$?
cat "$test_output"

echo "Dwell 60s with the app foregrounded so the FA SDK flushes."
timeout 30 adb shell am start -n "$pkg/com.pittech.MainActivity" || true
sleep 60

window_end="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "window_end_utc=$window_end" >> "$output_dir/probe-window.txt"
echo "PROBE WINDOW END (UTC): $window_end"

# Belt-and-braces: a full FA dump in case the streaming capture missed lines.
timeout 60 adb logcat -d -v threadtime FA:V FA-SVC:V FirebaseApp:V '*:S' > "$output_dir/logcat-fa-dump.txt" 2>&1 || true
if [[ -n "$logcat_pid" ]]; then
  kill "$logcat_pid" >/dev/null 2>&1 || true
  logcat_pid=""
fi

echo "--- FA event evidence (Logging event lines) ---"
grep -h "Logging event" "$output_dir/logcat-fa.txt" "$output_dir/logcat-fa-dump.txt" 2>/dev/null | sort -u | head -60 || true
echo "--- FA init evidence ---"
grep -h -m 5 "App measurement\|FirebaseApp initialization\|firebase" "$output_dir/logcat-fa.txt" 2>/dev/null | head -10 || true

if [[ $test_rc -ne 0 ]] || ! grep -q 'OK (1 test)' "$test_output" || grep -q '^INSTRUMENTATION_STATUS_CODE: -2' "$test_output"; then
  echo "The GA4 DebugView probe test did not pass (rc=$test_rc)." >&2
  timeout 20 adb exec-out screencap -p > "$output_dir/final-emulator-screen.png" 2>/dev/null || true
  exit 1
fi

echo "GA4 DebugView probe complete. Window: $window_start .. $window_end (UTC), package $pkg."
