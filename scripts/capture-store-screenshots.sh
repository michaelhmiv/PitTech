#!/usr/bin/env bash
# Captures store / landing-page screenshots of the REAL PitTech app on a
# clean API 36 emulator for the manual store-screenshots workflow.
#
# The capture itself is an instrumentation test
# (com.pittech.StoreScreenshotCaptureTest) that seeds the proven
# FlowingLiveScreenTest brisket board — a live cook six hours in plus its
# finished predecessor — walks the real UI (Meals home, Live Board, Cook
# Timeline, the finished cook's record), and writes full-device PNGs into
# the app sandbox. This script owns the emulator lifecycle around it:
# fresh AVD, one boot retry on a fresh AVD (the PR #297 pattern — a
# corrupt runner system image can kill the first boot), APK installs,
# permission pre-grants so no system dialog lands in a shot, the single
# test run, and the PNG pull + validation.
set -euo pipefail

avd_name="pittech-screenshots-api-36"
device_name="pixel_2"
system_image="system-images;android-36;google_apis;x86_64"
sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
avdmanager_bin="${sdk_root}/cmdline-tools/latest/bin/avdmanager"
emulator_bin="${sdk_root}/emulator/emulator"
output_dir="${GITHUB_WORKSPACE:-$(pwd)}/app/build/store-screenshots"
emulator_pid=""

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
export ANDROID_AVD_HOME="${RUNNER_TEMP:-/tmp}/pittech-screenshots-avd"
mkdir -p "$ANDROID_AVD_HOME"

stop_emulator() {
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
  boot_emulator_once
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
timeout 30 adb shell pm grant com.pittech.debug android.permission.POST_NOTIFICATIONS || true
timeout 30 adb shell pm grant com.pittech.debug android.permission.BLUETOOTH_SCAN || true
timeout 30 adb shell pm grant com.pittech.debug android.permission.BLUETOOTH_CONNECT || true
timeout 30 adb shell pm grant com.pittech.debug android.permission.ACCESS_FINE_LOCATION || true
timeout 30 adb shell pm grant com.pittech.debug android.permission.CAMERA || true

echo "Running the store screenshot capture test."
test_output="$output_dir/capture-test.txt"
timeout 15m adb shell am instrument -w -r \
  -e class 'com.pittech.StoreScreenshotCaptureTest' \
  com.pittech.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$test_output"
if ! grep -q 'OK (1 test)' "$test_output" || grep -q '^INSTRUMENTATION_STATUS_CODE: -2' "$test_output"; then
  echo "The store screenshot capture test did not pass." >&2
  timeout 20 adb exec-out screencap -p > "$output_dir/final-emulator-screen.png" 2>/dev/null || true
  exit 1
fi

# Pull and validate: a real rendered phone screen is a PNG well above
# blank-frame size; anything else means the capture is not shippable.
declare -A shots=( [home]=store-home [live-board]=store-live-board [timeline]=store-timeline [history]=store-history )
for name in "${!shots[@]}"; do
  remote="files/pittech-ui-test/${shots[$name]}.png"
  local_file="$output_dir/${name}.png"
  timeout 30 adb exec-out run-as com.pittech.debug cat "$remote" > "$local_file"
  magic="$(head -c 4 "$local_file" | od -An -tx1 | tr -d ' \n')"
  size="$(stat -c%s "$local_file")"
  if [[ "$magic" != "89504e47" || "$size" -lt 20000 ]]; then
    echo "Pulled screenshot $name.png is not a valid rendered PNG (magic=$magic size=$size)." >&2
    exit 1
  fi
  echo "Captured $name.png ($size bytes)."
done

echo "All store screenshots captured into $output_dir."
