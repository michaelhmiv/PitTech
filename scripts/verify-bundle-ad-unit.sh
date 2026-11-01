#!/usr/bin/env bash
# Verify that a built release App Bundle carries the expected AdMob native
# ad unit for the Play track it was built for (issue #251).
#
# Usage:
#   scripts/verify-bundle-ad-unit.sh <bundle.aab> <live|test> <BuildConfig.java>
#
# The two candidate unit IDs are read from app/build.gradle.kts so this
# script never drifts from the build's single source of truth. The check is:
#   1. The generated BuildConfig.java configures exactly the expected unit
#      in PITTECH_NATIVE_AD_UNIT_ID (the value the app actually requests).
#   2. The expected unit string is present in the bundle's compiled dex,
#      proving the value survived into the artifact being shipped.
#   3. A test-ads bundle must not contain PitTech's live unit anywhere in
#      its dex. The live unit has exactly one possible source (BuildConfig),
#      so its presence would prove the flag leaked. The mirror check is
#      deliberately NOT applied to live bundles: Google's ads SDK may embed
#      Google's own sample IDs, which are not PitTech's configuration.
set -euo pipefail

bundle_path="${1:?usage: verify-bundle-ad-unit.sh <bundle.aab> <live|test> <BuildConfig.java>}"
mode="${2:?usage: verify-bundle-ad-unit.sh <bundle.aab> <live|test> <BuildConfig.java>}"
build_config_java="${3:?usage: verify-bundle-ad-unit.sh <bundle.aab> <live|test> <BuildConfig.java>}"
gradle_file="app/build.gradle.kts"

fail() {
  echo "::error::$1"
  exit 1
}

[[ "$mode" == "live" || "$mode" == "test" ]] || fail "Mode must be 'live' or 'test', got '$mode'."
[[ -f "$bundle_path" ]] || fail "Bundle not found: $bundle_path"
[[ -f "$build_config_java" ]] || fail "Generated BuildConfig.java not found: $build_config_java"
[[ -f "$gradle_file" ]] || fail "Gradle build file not found: $gradle_file (run from the repository root)."

test_unit="$(sed -n 's/.*val testNativeAdUnitId = "\(.*\)".*/\1/p' "$gradle_file")"
live_unit="$(sed -n 's/.*val liveNativeAdUnitId = "\(.*\)".*/\1/p' "$gradle_file")"
[[ -n "$test_unit" ]] || fail "Could not read testNativeAdUnitId from $gradle_file."
[[ -n "$live_unit" ]] || fail "Could not read liveNativeAdUnitId from $gradle_file."
[[ "$test_unit" != "$live_unit" ]] || fail "Test and live ad units in $gradle_file are identical; nothing to verify."

if [[ "$mode" == "live" ]]; then
  expected_unit="$live_unit"
else
  expected_unit="$test_unit"
fi

configured_unit="$(sed -n 's/.*PITTECH_NATIVE_AD_UNIT_ID = "\(.*\)".*/\1/p' "$build_config_java")"
[[ -n "$configured_unit" ]] || fail "PITTECH_NATIVE_AD_UNIT_ID not found in $build_config_java."
if [[ "$configured_unit" != "$expected_unit" ]]; then
  fail "BuildConfig configures ad unit '$configured_unit' but a '$mode' bundle must carry '$expected_unit'."
fi

dex_dir="$(mktemp -d)"
trap 'rm -rf "$dex_dir"' EXIT
unzip -q -o "$bundle_path" 'base/dex/*.dex' -d "$dex_dir" \
  || fail "Could not extract base/dex from $bundle_path."

if ! grep -aFq "$expected_unit" "$dex_dir"/base/dex/*.dex; then
  fail "Expected ad unit '$expected_unit' is not present in the bundle's dex; the '$mode' configuration did not reach the artifact."
fi

if [[ "$mode" == "test" ]] && grep -aFq "$live_unit" "$dex_dir"/base/dex/*.dex; then
  fail "Live ad unit '$live_unit' is present in a test-ads bundle's dex; the live-ads flag leaked into a non-live build."
fi

echo "Bundle ad unit verified: mode=$mode unit=$expected_unit"
