#!/usr/bin/env bash
set -euo pipefail

api_level="${PITTECH_API_LEVEL:-36}"
device_name="${PITTECH_EMULATOR_DEVICE:-pixel_2}"
avd_name="pittech-ci-api-${api_level}"
if [[ "$api_level" == "37" ]]; then
  system_image="system-images;android-37.0;google_apis_ps16k;x86_64"
else
  system_image="system-images;android-${api_level};google_apis;x86_64"
fi
sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
avdmanager_bin="$sdk_root/cmdline-tools/latest/bin/avdmanager"
emulator_bin="$sdk_root/emulator/emulator"
output_dir="${GITHUB_WORKSPACE:-$(pwd)}/app/build/ci-emulator"
emulator_pid=""
emulator_memory_args=()
if (( api_level >= 37 )); then
  emulator_memory_args=(-memory 4096)
fi

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
  if (( result != 0 )); then
    for screenshot in reminder-after-log-now timeline-edit-dialog timeline-after-temperature-filter connected-cook-live connected-cook-final guidance-plan prep-checklist; do
      if timeout 10 adb shell run-as com.pittech.debug test -f "files/pittech-ui-test/${screenshot}.png"; then
        timeout 10 adb exec-out run-as com.pittech.debug cat "files/pittech-ui-test/${screenshot}.png" > "$output_dir/${screenshot}.png"
      fi
    done
  fi
  timeout 20 adb exec-out screencap -p > "$output_dir/final-emulator-screen.png" 2>/dev/null
  if [[ -n "$emulator_pid" ]]; then
    adb emu kill >/dev/null 2>&1
    wait "$emulator_pid" >/dev/null 2>&1
  fi
  return "$result"
}
trap collect_evidence_and_stop EXIT

echo "Creating a clean ${device_name} emulator image for API ${api_level}."
printf 'no\n' | "$avdmanager_bin" create avd \
  --force \
  --name "$avd_name" \
  --package "$system_image" \
  --device "$device_name"

avd_config="$ANDROID_AVD_HOME/${avd_name}.avd/config.ini"
if (( api_level >= 37 )); then
  if [[ ! -f "$avd_config" ]]; then
    echo "The emulator AVD config was not created: $avd_config" >&2
    exit 1
  fi
  if grep -q '^disk.dataPartition.size=' "$avd_config"; then
    sed -i 's/^disk.dataPartition.size=.*/disk.dataPartition.size=8G/' "$avd_config"
  else
    printf '\ndisk.dataPartition.size=8G\n' >> "$avd_config"
  fi
  configured_data_partition="$(grep '^disk.dataPartition.size=' "$avd_config")"
  if [[ "$configured_data_partition" != "disk.dataPartition.size=8G" ]]; then
    echo "Could not set the Android 17 emulator data partition size." >&2
    exit 1
  fi
  echo "Configured Android 17 emulator data partition: 8G."
fi

"$emulator_bin" \
  -avd "$avd_name" \
  -no-window \
  -gpu lavapipe \
  -noaudio \
  -no-boot-anim \
  -no-snapshot \
  -camera-back none \
  "${emulator_memory_args[@]}" \
  -wipe-data \
  > "$output_dir/emulator.log" 2>&1 &
emulator_pid=$!

adb start-server
timeout 600 adb wait-for-device
if (( api_level >= 37 )); then
  # This preview image aborts in SurfaceFlinger's RegionSampling thread when
  # its ranchu graphics mapper reads a color buffer through DMA.
  timeout 30 adb root >/dev/null 2>&1 || true
  timeout 60 adb wait-for-device
  shell_uid="$(timeout 10 adb shell id -u 2>/dev/null | tr -d '\r')"
  if [[ "$shell_uid" != "0" ]]; then
    echo "Android 17 emulator did not grant root shell access." >&2
    exit 1
  fi
  timeout 20 adb shell setprop debug.sf.luma_sampling 0
  luma_sampling_value="$(timeout 10 adb shell getprop debug.sf.luma_sampling 2>/dev/null | tr -d '\r')"
  if [[ "$luma_sampling_value" != "0" ]]; then
    echo "Could not disable SurfaceFlinger luma sampling." >&2
    exit 1
  fi
  old_surfaceflinger_pid="$(timeout 10 adb shell pidof surfaceflinger 2>/dev/null | tr -d '\r' || true)"
  timeout 20 adb shell stop surfaceflinger
  timeout 20 adb shell start surfaceflinger
  timeout 60 adb wait-for-device
  for _ in $(seq 1 30); do
    new_surfaceflinger_pid="$(timeout 10 adb shell pidof surfaceflinger 2>/dev/null | tr -d '\r' || true)"
    if [[ -n "$new_surfaceflinger_pid" && "$new_surfaceflinger_pid" != "$old_surfaceflinger_pid" ]]; then
      break
    fi
    sleep 1
  done
  if [[ -z "$new_surfaceflinger_pid" || "$new_surfaceflinger_pid" == "$old_surfaceflinger_pid" ]]; then
    echo "SurfaceFlinger did not restart with luma sampling disabled." >&2
    exit 1
  fi
fi
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
font_scale="${PITTECH_FONT_SCALE:-1.15}"
timeout 30 adb shell settings put system font_scale "$font_scale"
echo "Using Android font scale ${font_scale}."

echo "Running Compose UI tests on the booted emulator."
app_apk="${GITHUB_WORKSPACE:-$(pwd)}/app/build/outputs/apk/debug/app-debug.apk"
test_apk="${GITHUB_WORKSPACE:-$(pwd)}/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
if [[ ! -f "$app_apk" || ! -f "$test_apk" ]]; then
  echo "Build the app and instrumented test APKs before running this script." >&2
  echo "Expected: $app_apk and $test_apk" >&2
  exit 1
fi

previous_dir="${RUNNER_TEMP:-/tmp}/pittech-before"
if (( api_level == 36 )) && [[ -f "$previous_dir/app/build/outputs/apk/debug/app-debug.apk" ]]; then
  echo "Installing the previous release and saving a cook before updating in place."
  timeout 120 adb install -r "$previous_dir/app/build/outputs/apk/debug/app-debug.apk"
  timeout 120 adb install -r -t "$previous_dir/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
  previous_instrumentation="$(adb shell pm list instrumentation | sed -n 's/^instrumentation:\([^ ]*\) (target=com\.pittech\.debug)$/\1/p' | head -n 1 | tr -d '\r')"
  timeout 12m adb shell am instrument -w -r \
    -e class 'com.pittech.PitTechUserFlowsTest#test02_createCookWithDishAndPreparationAndSaveLocally' \
    "$previous_instrumentation" | tee "$output_dir/previous-release-cook.txt"
  grep -q '^INSTRUMENTATION_STATUS_CODE: 0' "$output_dir/previous-release-cook.txt"
  grep -q 'OK (1 test)' "$output_dir/previous-release-cook.txt"
  ! grep -q '^INSTRUMENTATION_STATUS_CODE: -2' "$output_dir/previous-release-cook.txt"
  timeout 20 adb shell am force-stop com.pittech.debug
fi

timeout 120 adb install -r "$app_apk"
timeout 120 adb install -r -t "$test_apk"

if (( api_level >= 37 )); then
  # Ahead-of-time compilation avoids startup ANRs while this preview image is
  # still doing first-boot dex work on the CI runner. Test assertions stay identical.
  timeout 180 adb shell cmd package compile -m speed -f com.pittech.debug
fi

instrumentation_target="$(adb shell pm list instrumentation | sed -n 's/^instrumentation:\([^ ]*\) (target=com\.pittech\.debug)$/\1/p' | head -n 1 | tr -d '\r')"
if [[ -z "$instrumentation_target" ]]; then
  echo "Could not find the installed PitTech instrumentation runner." >&2
  adb shell pm list instrumentation >&2
  exit 1
fi

if (( api_level == 36 )) && [[ -f "$previous_dir/app/build/outputs/apk/debug/app-debug.apk" ]]; then
  echo "Checking the previous cook and its ingredients after the APK and Room schema upgrade."
  timeout 12m adb shell am instrument -w -r \
    -e class com.pittech.UpgradeValidationTest \
    "$instrumentation_target" | tee "$output_dir/upgrade-validation.txt"
  grep -q '^INSTRUMENTATION_STATUS_CODE: 0' "$output_dir/upgrade-validation.txt"
  grep -q 'OK (1 test)' "$output_dir/upgrade-validation.txt"
  ! grep -q '^INSTRUMENTATION_STATUS_CODE: -2' "$output_dir/upgrade-validation.txt"
fi

cloud_test_output="$output_dir/grillirg-cloud-tests.txt"
cloud_test_selector='com.pittech.ui.GrillirGCloudMonitorScreenTest,com.pittech.devices.PrimePolarisSessionStoreTest'
echo "Running GrillirG cloud UI and encrypted-session tests on API $api_level."
timeout 8m adb shell am instrument -w -r \
  -e class "$cloud_test_selector" \
  "$instrumentation_target" | tee "$cloud_test_output"
cloud_passed_count="$(grep -c '^INSTRUMENTATION_STATUS_CODE: 0' "$cloud_test_output" || true)"
if [[ "$cloud_passed_count" -ne 5 ]] || ! grep -q 'OK (5 tests)' "$cloud_test_output" ||
   grep -q '^INSTRUMENTATION_STATUS_CODE: -2' "$cloud_test_output"; then
  echo "GrillirG cloud instrumentation tests did not all pass." >&2
  exit 1
fi

provider_test_output="$output_dir/connected-provider-tests.txt"
provider_test_selector='com.pittech.ui.ConnectedGrillProviderScreenTest,com.pittech.devices.ConnectedGrillSessionStoreTest,com.pittech.data.ConnectedGrillCookIntegrationTest,com.pittech.ConnectedGrillRecordingServiceTest'
echo "Running provider setup, credential isolation, and four-probe cook integration tests on API $api_level."
timeout 10m adb shell am instrument -w -r \
  -e class "$provider_test_selector" \
  "$instrumentation_target" | tee "$provider_test_output"
provider_passed_count="$(grep -c '^INSTRUMENTATION_STATUS_CODE: 0' "$provider_test_output" || true)"
if [[ "$provider_passed_count" -ne 11 ]] || ! grep -q 'OK (11 tests)' "$provider_test_output" ||
   grep -q '^INSTRUMENTATION_STATUS_CODE: -2' "$provider_test_output"; then
  echo "Connected-provider instrumentation tests did not all pass." >&2
  exit 1
fi
if timeout 10 adb shell run-as com.pittech.debug test -f files/pittech-ui-test/provider-four-probe-setup.png; then
  timeout 10 adb exec-out run-as com.pittech.debug cat files/pittech-ui-test/provider-four-probe-setup.png > "$output_dir/provider-four-probe-setup.png"
fi

recording_test_output="$output_dir/cook-recording-tests.txt"
recording_test_selector='com.pittech.data.CookRecordingIntegrationTest,com.pittech.CookRecordingServiceTest,com.pittech.ui.ConnectedCookScreenTest'
echo "Running connected-cook persistence, migration, background-service, and UI tests on API $api_level."
timeout 10m adb shell am instrument -w -r \
  -e class "$recording_test_selector" \
  "$instrumentation_target" | tee "$recording_test_output"
recording_passed_count="$(grep -c '^INSTRUMENTATION_STATUS_CODE: 0' "$recording_test_output" || true)"
if [[ "$recording_passed_count" -ne 10 ]] || ! grep -q 'OK (10 tests)' "$recording_test_output" ||
   grep -q '^INSTRUMENTATION_STATUS_CODE: -2' "$recording_test_output"; then
  echo "Connected-cook instrumentation tests did not all pass." >&2
  exit 1
fi
if timeout 10 adb shell run-as com.pittech.debug test -f files/pittech-ui-test/connected-cook-live.png; then
  timeout 10 adb exec-out run-as com.pittech.debug cat files/pittech-ui-test/connected-cook-live.png > "$output_dir/connected-cook-live.png"
fi

companion_test_output="$output_dir/companion-tests.txt"
echo "Running cook playbook persistence and archive tests."
timeout 8m adb shell am instrument -w -r \
  -e class 'com.pittech.PlaybookPersistenceTest,com.pittech.GuidanceIntegrationTest,com.pittech.GuidanceScreenTest,com.pittech.PreparationPersistenceTest,com.pittech.PreparationScreenTest' \
  "$instrumentation_target" | tee "$companion_test_output"
grep -q 'OK (7 tests)' "$companion_test_output"
! grep -q '^INSTRUMENTATION_STATUS_CODE: -2' "$companion_test_output"

test_output="$output_dir/instrumented-tests.txt"
if (( api_level >= 37 )); then
  test_selector='com.pittech.PitTechUserFlowsTest#test01_homeNavigationAndPrimaryActionAreClear,com.pittech.PitTechUserFlowsTest#test02_createCookWithDishAndPreparationAndSaveLocally'
  expected_tests=("test01_homeNavigationAndPrimaryActionAreClear" "test02_createCookWithDishAndPreparationAndSaveLocally")
else
  test_selector='com.pittech.PitTechUserFlowsTest'
  expected_tests=(
    "test00_reminderCheckInBecomesCookTimelineNoteAndRestores"
    "test01_homeNavigationAndPrimaryActionAreClear"
    "test02_createCookWithDishAndPreparationAndSaveLocally"
    "test03_timelineTemperatureResultsAndInsightsWork"
    "test04_portableArchiveAndWorkbookRoundTrip"
    "test05_crashReportIsVisibleAndCopyable"
    "test06_cameraPhotoUriAcceptsCameraOutput"
    "test07_photoLogIsOneEntryAndMissingAttachmentsBlockRestore"
    "test08_zipShareIntentUsesReadOnlyContentUri"
  )
fi
echo "Running $instrumentation_target tests for API $api_level without uninstalling the app afterward."
timeout 25m adb shell am instrument -w -r \
  -e class "$test_selector" \
  "$instrumentation_target" | tee "$test_output"
passed_test_count="$(grep -c '^INSTRUMENTATION_STATUS_CODE: 0' "$test_output" || true)"
if [[ "$passed_test_count" -ne "${#expected_tests[@]}" ]] ||
   ! grep -q "OK (${#expected_tests[@]} tests)" "$test_output" ||
   grep -q '^INSTRUMENTATION_STATUS_CODE: -2' "$test_output"; then
  echo "PitTech instrumentation did not report success for every UI test." >&2
  exit 1
fi
for expected_test in "${expected_tests[@]}"; do
  if ! grep -q "INSTRUMENTATION_STATUS: test=${expected_test}" "$test_output"; then
    echo "Expected UI test did not run: $expected_test" >&2
    exit 1
  fi
done
echo "All ${#expected_tests[@]} expected PitTech UI tests passed."

if (( api_level == 36 )); then
  controller_test_selector='com.pittech.ui.ControllerDiagnosticsScreenTest,com.pittech.devices.MongooseRpcInterrogationPlannerTest,com.pittech.devices.MongooseRpcResponseParserTest,com.pittech.devices.ControllerStableFingerprintTest,com.pittech.devices.ControllerDiagnosticSanitizerTest'
  controller_test_output="$output_dir/controller-diagnostics-tests.txt"
  controller_expected_count=18
  echo "Running controller interrogation UI, protocol, fingerprint, and sanitization tests."
  timeout 12m adb shell am instrument -w -r \
    -e class "$controller_test_selector" \
    "$instrumentation_target" | tee "$controller_test_output"
  controller_passed_count="$(grep -c '^INSTRUMENTATION_STATUS_CODE: 0' "$controller_test_output" || true)"
  if [[ "$controller_passed_count" -ne "$controller_expected_count" ]] ||
     ! grep -q "OK (${controller_expected_count} tests)" "$controller_test_output" ||
     grep -q '^INSTRUMENTATION_STATUS_CODE: -2' "$controller_test_output"; then
    echo "Controller interrogation instrumentation tests did not all pass." >&2
    exit 1
  fi
  echo "All ${controller_expected_count} controller interrogation instrumentation tests passed."
fi

assert_png_file() {
  local name="$1"
  local screenshot="$2"
  if ! python3 - "$screenshot" <<'PY'
import pathlib
import sys

if not pathlib.Path(sys.argv[1]).read_bytes().startswith(b"\x89PNG\r\n\x1a\n"):
    raise SystemExit(1)
PY
  then
    rm -f "$screenshot"
    echo "The ${name} screenshot is missing or invalid." >&2
    return 1
  fi
}

pull_app_screenshot() {
  local name="$1"
  local screenshot="$output_dir/${name}.png"
  timeout 20 adb exec-out run-as com.pittech.debug cat "files/pittech-ui-test/${name}.png" > "$screenshot"
  assert_png_file "$name" "$screenshot"
  echo "Saved emulator screenshot: ${name}.png"
}

pull_app_screenshot home-empty
pull_app_screenshot cook-saved
pull_app_screenshot settings-dark
pull_app_screenshot guidance-plan
pull_app_screenshot prep-checklist

if (( api_level >= 37 )); then
  echo "Android 17 launch and cook-save smoke checks passed."
  exit 0
fi

pull_app_screenshot cook-log-entry
pull_app_screenshot cook-live-actions
pull_app_screenshot cook-live-dashboard-top
pull_app_screenshot cook-live-dashboard
pull_app_screenshot reminder-check-in

check_saved_diagnostic_report() {
  local checkpoint="$1"
  local report_contents
  if ! report_contents="$(timeout 20 adb exec-out run-as com.pittech.debug cat files/pittech-last-crash.properties 2>/dev/null)"; then
    echo "The saved diagnostic report could not be read $checkpoint." >&2
    return 1
  fi
  if ! grep -Fq "Synthetic diagnostic for UI test" <<< "$report_contents"; then
    echo "The synthetic diagnostic report contents were missing $checkpoint." >&2
    return 1
  fi
  echo "The synthetic diagnostic report is present $checkpoint."
}

check_saved_diagnostic_report "after instrumentation"
echo "Restarting PitTech to verify its saved crash report survives a process restart."
adb shell am force-stop com.pittech.debug
check_saved_diagnostic_report "after force-stop"
timeout 60 adb shell am start -W -n com.pittech.debug/com.pittech.MainActivity
check_saved_diagnostic_report "after relaunch"

window_dump="$output_dir/restarted-window.xml"
deadline=$((SECONDS + 30))
until (( SECONDS >= deadline )); do
  timeout 20 adb shell uiautomator dump /sdcard/pittech-window.xml >/dev/null 2>&1 || true
  timeout 20 adb exec-out cat /sdcard/pittech-window.xml > "$window_dump" 2>/dev/null || true
  if grep -q "Synthetic diagnostic for UI test" "$window_dump"; then
    break
  fi
  sleep 2
done

read -r tap_x tap_y <<< "$(python3 - "$window_dump" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
visible_text = " ".join(
    node.attrib.get("text", "") + " " + node.attrib.get("content-desc", "")
    for node in root.iter()
)
if "PitTech stopped unexpectedly" not in visible_text:
    print("Visible UI after relaunch: " + visible_text, file=sys.stderr)
    raise SystemExit("Crash recovery screen was not shown after force-stop and relaunch.")
if "Synthetic diagnostic for UI test" not in visible_text:
    raise SystemExit("Saved crash summary was not visible after relaunch.")
if re.search(r"PT-[0-9A-F]{8}", visible_text) is None:
    raise SystemExit("Saved crash reference code was not visible after relaunch.")
button = next(
    (node for node in root.iter() if node.attrib.get("text") == "Continue to PitTech"),
    None,
)
if button is None:
    raise SystemExit("Continue button was not found on the crash recovery screen.")
bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", button.attrib.get("bounds", ""))
if bounds is None:
    raise SystemExit("Continue button bounds were missing from the accessibility tree.")
left, top, right, bottom = map(int, bounds.groups())
print((left + right) // 2, (top + bottom) // 2)
PY
)"
timeout 20 adb shell input tap "$tap_x" "$tap_y"

deadline=$((SECONDS + 30))
until (( SECONDS >= deadline )); do
  timeout 20 adb shell uiautomator dump /sdcard/pittech-window.xml >/dev/null 2>&1 || true
  timeout 20 adb exec-out cat /sdcard/pittech-window.xml > "$window_dump" 2>/dev/null || true
  if grep -q "Photo backup sample" "$window_dump" && grep -q "Whole packer" "$window_dump"; then
    break
  fi
  sleep 2
done

python3 - "$window_dump" <<'PY'
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
visible_text = " ".join(
    node.attrib.get("text", "") + " " + node.attrib.get("content-desc", "")
    for node in root.iter()
)
expected = ("Photo backup sample", "Whole packer")
missing = [value for value in expected if value not in visible_text]
if missing:
    raise SystemExit("Cook data was not visible after crash-report recovery: " + ", ".join(missing))
print("Crash report survived force-stop and relaunch; the saved cook is visible after continuing.")
PY

sleep 3
timeout 20 adb exec-out screencap -p > "$output_dir/restarted-home.png"
assert_png_file "restarted-home" "$output_dir/restarted-home.png"
