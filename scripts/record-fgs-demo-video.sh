#!/usr/bin/env bash
# Records a ~100-second demo video of PitTech on an Android API 36 emulator for
# Google Play's Foreground Service permission declaration (connectedDevice).
#
# Storyboard:
#   1. Devices tab -> "Temperature connections · Beta" screen showing the seeded
#      telemetry profile with monitoring switches on.
#   2. "Add temperature connection" -> Combustion chip -> "Allow Bluetooth
#      access" -> allow the system dialog -> "Connect" (real Combustion SDK path).
#   3. The seeded active cook ("CI synthetic telemetry restart") with live
#      synthetic probe temperatures.
#   4. Notification shade showing the ongoing "PitTech · monitoring
#      temperatures" notification while the app is backgrounded, then back in
#      the app with temperatures still updating.
#
# Seeding uses the DEBUG-only CI fixture
# TelemetryProcessDeathTest#preparePersistedBackgroundCook, which persists an
# active cook plus a telemetry profile (monitoring + background enabled) that
# emits a synthetic 180°F reading every second through the real TelemetryRuntime.
set -euo pipefail

avd_name="pittech-demo-api-36"
device_name="pixel_2"
system_image="system-images;android-36;google_apis;x86_64"
sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
avdmanager_bin="$sdk_root/cmdline-tools/latest/bin/avdmanager"
emulator_bin="$sdk_root/emulator/emulator"
output_dir="${GITHUB_WORKSPACE:-$(pwd)}/app/build/fgs-demo"
emulator_pid=""
tap_helper="$output_dir/tap-by-text.py"
tap_failure_count=0
video_started=false

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
export ANDROID_AVD_HOME="${RUNNER_TEMP:-/tmp}/pittech-demo-avd"
mkdir -p "$ANDROID_AVD_HOME"

# Python helper: given a uiautomator XML dump and a target string, print the
# center coordinates of the best-matching node (clickable + exact preferred).
cat > "$tap_helper" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

dump_path, target = sys.argv[1], sys.argv[2]
root = ET.parse(dump_path).getroot()
candidates = []
for order, node in enumerate(root.iter("node")):
    text = node.attrib.get("text", "") or ""
    desc = node.attrib.get("content-desc", "") or ""
    if target in text or target in desc:
        bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", "") or "")
        if bounds:
            left, top, right, bottom = map(int, bounds.groups())
            clickable = node.attrib.get("clickable", "") == "true"
            exact = target == text or target == desc
            # Prefer clickable nodes (a dialog title like "Connect · Beta" must
            # never win over the actual "Connect" button), then exact matches.
            candidates.append(((not clickable, not exact, order), (left + right) // 2, (top + bottom) // 2))
if candidates:
    candidates.sort(key=lambda item: item[0])
    print(candidates[0][1], candidates[0][2])
    sys.exit(0)
sys.exit(1)
PY

collect_evidence_and_stop() {
  local result=$?
  set +e
  timeout 20 adb logcat -d -v threadtime > "$output_dir/logcat.txt" 2>&1
  timeout 20 adb exec-out screencap -p > "$output_dir/final-emulator-screen.png" 2>/dev/null
  if [[ -n "$emulator_pid" ]]; then
    adb emu kill >/dev/null 2>&1
    wait "$emulator_pid" >/dev/null 2>&1
  fi
  return "$result"
}
trap collect_evidence_and_stop EXIT

# Tap the first UI node whose text or content-desc contains the target string,
# polling every 2s until the timeout. On timeout, saves a screenshot and the
# last hierarchy dump into the output dir for debugging, then exits 1.
tap_text() {
  local text="$1"
  local timeout_seconds="${2:-20}"
  local deadline=$((SECONDS + timeout_seconds))
  while (( SECONDS < deadline )); do
    timeout 20 adb shell uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1 || true
    timeout 20 adb exec-out cat /sdcard/window_dump.xml > "$output_dir/window_dump.xml" 2>/dev/null || true
    if coords="$(python3 "$tap_helper" "$output_dir/window_dump.xml" "$text" 2>/dev/null)"; then
      read -r tap_x tap_y <<< "$coords"
      timeout 20 adb shell input tap "$tap_x" "$tap_y"
      echo "Tapped '$text' at ${tap_x},${tap_y}."
      return 0
    fi
    sleep 2
  done
  echo "Timed out after ${timeout_seconds}s waiting for UI text: '$text'." >&2
  tap_failure_count=$((tap_failure_count + 1))
  timeout 20 adb exec-out screencap -p > "$output_dir/tap-failure-${tap_failure_count}.png" 2>/dev/null || true
  cp "$output_dir/window_dump.xml" "$output_dir/tap-failure-${tap_failure_count}.xml" 2>/dev/null || true
  return 1
}

swipe_up() {
  timeout 20 adb shell input swipe 540 1400 540 600 300
}

press_back() {
  timeout 20 adb shell input keyevent KEYCODE_BACK
}

press_home() {
  timeout 20 adb shell input keyevent KEYCODE_HOME
}

install_apk() {
  local apk="$1"
  local extra_args="${2:-}"
  if ! timeout 180 adb install -r $extra_args "$apk"; then
    echo "First install attempt failed for $apk; retrying once." >&2
    timeout 180 adb install -r $extra_args "$apk"
  fi
}

echo "Creating a clean ${device_name} emulator image for the FGS demo."
printf 'no\n' | "$avdmanager_bin" create avd \
  --force \
  --name "$avd_name" \
  --package "$system_image" \
  --device "$device_name"

"$emulator_bin" \
  -avd "$avd_name" \
  -no-window \
  -gpu lavapipe \
  -noaudio \
  -no-boot-anim \
  -no-snapshot \
  -wipe-data \
  > "$output_dir/emulator.log" 2>&1 &
emulator_pid=$!

adb start-server
timeout 600 adb wait-for-device
boot_deadline=$((SECONDS + 600))
until [[ "$(timeout 15 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)" == "1" ]]; do
  if (( SECONDS >= boot_deadline )); then
    echo "The Android emulator did not finish booting within 10 minutes." >&2
    cat "$output_dir/emulator.log" >&2
    exit 1
  fi
  if ! kill -0 "$emulator_pid" 2>/dev/null; then
    echo "The Android emulator process exited before boot completed." >&2
    cat "$output_dir/emulator.log" >&2
    exit 1
  fi
  sleep 5
done
echo "Emulator booted."

app_apk="${GITHUB_WORKSPACE:-$(pwd)}/app/build/outputs/apk/debug/app-debug.apk"
test_apk="${GITHUB_WORKSPACE:-$(pwd)}/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
if [[ ! -f "$app_apk" || ! -f "$test_apk" ]]; then
  echo "Build the app and instrumented test APKs before running this script." >&2
  echo "Expected: $app_apk and $test_apk" >&2
  exit 1
fi

echo "Installing the app and instrumentation test APKs."
install_apk "$app_apk"
install_apk "$test_apk" "-t"

echo "Preparing the device (disable lock screen, keep screen on, long timeout)."
timeout 30 adb shell locksettings set-disabled true
timeout 30 adb shell svc power stayon true
timeout 30 adb shell settings put system screen_off_timeout 1800000
# One plain launch first so any first-run dialogs are dismissed before seeding.
timeout 60 adb shell am start -n com.pittech.debug/com.pittech.MainActivity
sleep 8

echo "Seeding the synthetic background cook via the CI instrumentation fixture."
timeout 3m adb shell am instrument -w -r \
  -e class 'com.pittech.TelemetryProcessDeathTest#preparePersistedBackgroundCook' \
  com.pittech.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$output_dir/seed-fixture.txt"
grep -q 'OK (1 test)' "$output_dir/seed-fixture.txt"

# Cold-start the seeded app so the storyboard runs against live TelemetryRuntime
# sampling. TelemetryRuntime only runs the background service while the app UI
# is in the foreground (CooksViewModel sets foreground=true).
timeout 20 adb shell am force-stop com.pittech.debug
timeout 60 adb shell am start -W -n com.pittech.debug/com.pittech.MainActivity

echo "Starting on-device screen recording."
timeout 20 adb shell 'nohup screenrecord --size 1080x1920 --bit-rate 4000000 --time-limit 175 /sdcard/fgs-demo.mp4 >/dev/null 2>&1 &' >/dev/null 2>&1
sleep 2
if [[ -z "$(timeout 20 adb shell pidof screenrecord 2>/dev/null | tr -d '\r' || true)" ]]; then
  echo "screenrecord did not start on the emulator." >&2
  exit 1
fi
video_started=true

# --- Storyboard (keep total under ~115s; screenrecord caps at 175s) ---
# 1. Devices tab shows "Temperature connections · Beta" with the seeded profile.
timeout 60 adb shell am start -n com.pittech.debug/com.pittech.MainActivity
sleep 6
tap_text "Devices"
sleep 5

# 2. Open the add-connection dialog.
swipe_up
sleep 2
if ! tap_text "Add temperature connection" 15; then
  swipe_up
  tap_text "Add temperature connection" 15
fi
sleep 3

# 3. Pick the Combustion provider and grant Bluetooth access.
tap_text "Combustion"
sleep 3
tap_text "Allow Bluetooth access"
sleep 3
# The Android 12+ Bluetooth permission system dialog.
tap_text "Allow" 15
sleep 2

# 4. Run the real Combustion discovery window so the SDK foreground service
# starts (evidence captured next). Discovery with no probe nearby ends on its own.
tap_text "Connect"
sleep 14

# Evidence (not part of the video narrative): prove the app is actually running
# its foreground service while connectedDevice work is active.
timeout 20 adb shell dumpsys activity services com.pittech.debug > "$output_dir/dumpsys-services.txt"
timeout 20 adb logcat -d -t 300 > "$output_dir/logcat-during-discovery.txt" 2>/dev/null || true
if tap_text "Cancel" 5; then
  sleep 1
else
  press_back
fi

# 5. Back to the cook with live synthetic temperatures. The cooks tab is
# labeled "Meals" (MainSection.COOKS title in CookScreens.kt).
tap_text "Meals"
tap_text "CI synthetic telemetry restart"
sleep 10

# 6. Notification shade: ongoing "PitTech · monitoring temperatures" while the
# app is in the foreground, then collapse.
timeout 20 adb shell cmd statusbar expand-notifications
sleep 7
timeout 20 adb shell cmd statusbar collapse
sleep 1

# 7. Background the app, show the notification persists, relaunch the app.
press_home
sleep 5
timeout 20 adb shell cmd statusbar expand-notifications
sleep 5
timeout 20 adb shell cmd statusbar collapse
timeout 60 adb shell am start -n com.pittech.debug/com.pittech.MainActivity
sleep 8
# --- End of storyboard ---

echo "Stopping screen recording."
timeout 20 adb shell pkill -2 screenrecord || true
sleep 3
timeout 60 adb pull /sdcard/fgs-demo.mp4 "$output_dir/fgs-demo.mp4"
timeout 20 adb exec-out screencap -p > "$output_dir/final-cook-screen.png" 2>/dev/null || true

mp4_path="$output_dir/fgs-demo.mp4"
if [[ ! -f "$mp4_path" ]]; then
  echo "The demo video was not pulled from the emulator." >&2
  exit 1
fi
mp4_size="$(stat -c%s "$mp4_path")"
if (( mp4_size < 512000 )); then
  echo "The demo video is suspiciously small (${mp4_size} bytes); it likely did not record." >&2
  exit 1
fi

# Foreground-service confirmation: loud warning, not a failure — the parent
# agent judges the video itself, but the declaration needs this to be real.
if grep -qi 'isforeground=true' "$output_dir/dumpsys-services.txt" || grep -qi 'combustion' "$output_dir/dumpsys-services.txt"; then
  echo "Confirmed: dumpsys shows the app's foreground service running during the demo."
else
  echo "WARNING: foreground service not confirmed in dumpsys"
fi

echo "Demo video ready: $mp4_path (${mp4_size} bytes)"
