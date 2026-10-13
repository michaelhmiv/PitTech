#!/usr/bin/env python3
"""Inspect an unsigned Release Simulator bundle; never sign or upload it."""
import plistlib
import sys
from pathlib import Path

root = Path(sys.argv[1])
info = plistlib.loads((root / "Info.plist").read_bytes())
if info.get("UIBackgroundModes"):
    raise SystemExit("Public telemetry currently declares no background collection entitlement")
notices = (root / "TelemetryNotices.txt").read_text()
for required in ("MIT License", "Combustion", "Nordic", "Apache License", "Runtime Library Exception"):
    if required not in notices:
        raise SystemExit("Missing bundled dependency notice: " + required)
executable = root / info["CFBundleExecutable"]
data = executable.read_bytes()
for token in (b"PrimePolarisApi", b"TraegerBackend", b"PitBossCloudTransport", b"api.prime-polaris.com", b"socket.dansonscorp.com", b"mobile-iot-api.iot.traegergrills.io"):
    if token in data:
        raise SystemExit("Legacy controller implementation in native public executable: " + token.decode())
print("Unsigned iOS Release Simulator bundle passed notices, background-mode and legacy-transport checks.")
