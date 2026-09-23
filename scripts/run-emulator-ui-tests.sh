#!/usr/bin/env bash
set -euo pipefail

api_level="36"
avd_name="pittech-ci-api-${api_level}"
system_image="system-images;android-${api_level};google_apis;x86_64"
sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
avdmanager_bin="$sdk_root/cmdline-tools/latest/bin/avdmanager"
emulator_bin="$sdk_root/emulator/emulator"
output_dir="${GITHUB_WORKSPACE:-$(pwd)}/app/build/ci-emulator"
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
export ANDROID_AVD_HOME="${RUNNER_TEMP:-/tmp}/pittech-avd"
mkdir -p "$ANDROID_AVD_HOME"

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

echo "Creating a clean Pixel 2 emulator image for API ${api_level}."
printf 'no\n' | "$avdmanager_bin" create avd \
  --force \
  --name "$avd_name" \
  --package "$system_image" \
  --device pixel_2

"$emulator_bin" \
  -avd "$avd_name" \
  -no-window \
  -gpu swiftshader_indirect \
  -noaudio \
  -no-boot-anim \
  -no-snapshot \
  -camera-back none \
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

timeout 30 adb shell settings put global window_animation_scale 0
timeout 30 adb shell settings put global transition_animation_scale 0
timeout 30 adb shell settings put global animator_duration_scale 0
timeout 30 adb shell settings put system font_scale 1.15

echo "Running Compose UI tests on the booted emulator."
app_apk="${GITHUB_WORKSPACE:-$(pwd)}/app/build/outputs/apk/debug/app-debug.apk"
test_apk="${GITHUB_WORKSPACE:-$(pwd)}/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
if [[ ! -f "$app_apk" || ! -f "$test_apk" ]]; then
  echo "Build the app and instrumented test APKs before running this script." >&2
  echo "Expected: $app_apk and $test_apk" >&2
  exit 1
fi

timeout 120 adb install -r "$app_apk"
timeout 120 adb install -r -t "$test_apk"

instrumentation_target="$(adb shell pm list instrumentation | sed -n 's/^instrumentation:\([^ ]*\) (target=com\.pittech)$/\1/p' | head -n 1 | tr -d '\r')"
if [[ -z "$instrumentation_target" ]]; then
  echo "Could not find the installed PitTech instrumentation runner." >&2
  adb shell pm list instrumentation >&2
  exit 1
fi

test_output="$output_dir/instrumented-tests.txt"
echo "Running $instrumentation_target without uninstalling the app afterward."
timeout 25m adb shell am instrument -w -r \
  -e class com.pittech.PitTechUserFlowsTest \
  "$instrumentation_target" | tee "$test_output"
if ! grep -q '^INSTRUMENTATION_CODE: 0' "$test_output"; then
  echo "PitTech UI tests did not report a successful instrumentation result." >&2
  exit 1
fi

pull_app_screenshot() {
  local name="$1"
  timeout 20 adb exec-out run-as com.pittech cat "files/pittech-ui-test/${name}.png" > "$output_dir/${name}.png"
  if [[ ! -s "$output_dir/${name}.png" ]]; then
    echo "No ${name} screenshot was produced." >&2
    exit 1
  fi
  echo "Saved emulator screenshot: ${name}.png"
}

pull_app_screenshot home-empty
pull_app_screenshot cook-saved

echo "Restarting PitTech to verify its local cook survives a process restart."
adb shell am force-stop com.pittech
timeout 60 adb shell am start -W -n com.pittech/com.pittech.MainActivity

window_dump="$output_dir/restarted-window.xml"
deadline=$((SECONDS + 30))
until (( SECONDS >= deadline )); do
  timeout 20 adb shell uiautomator dump /sdcard/pittech-window.xml >/dev/null 2>&1 || true
  timeout 20 adb exec-out cat /sdcard/pittech-window.xml > "$window_dump" 2>/dev/null || true
  if grep -q "Saturday brisket" "$window_dump" && grep -q "Whole packer" "$window_dump"; then
    break
  fi
  sleep 2
done

python3 - "$window_dump" <<'PY'
import sys
import xml.etree.ElementTree as ET

path = sys.argv[1]
root = ET.parse(path).getroot()
visible_text = " ".join(
    node.attrib.get("text", "") + " " + node.attrib.get("content-desc", "")
    for node in root.iter()
)
expected = ("Saturday brisket", "Whole packer")
missing = [value for value in expected if value not in visible_text]
if missing:
    raise SystemExit("Cook data was not visible after restart: " + ", ".join(missing))
print("Cook title and dish are visible after force-stop and relaunch.")
PY

timeout 20 adb exec-out screencap -p > "$output_dir/restarted-home.png"
