#!/usr/bin/env python3
"""Fail closed before publishing new PitTech device integrations."""
from __future__ import annotations
import argparse
import sys
from pathlib import Path

APPROVED = ("combustion", "home_assistant", "fireboard", "flame_boss", "meater")
LEGACY_FILES = (
    "devices/PrimePolarisApi.kt",
    "devices/PrimePolarisMonitor.kt",
    "devices/PitBossBackend.kt",
    "devices/TraegerBackend.kt",
    "devices/GrillirGWifiProvisioner.kt",
    "ui/GrillirGWifiSetupPanel.kt",
    "ui/GrillirGCloudMonitorScreen.kt",
)

def audit(root: Path) -> list[str]:
    issues = []
    base = root / "app/src/main/java/com/pittech"
    contract = base / "devices/TelemetryContract.kt"
    if not contract.is_file():
        issues.append("The shared read-only telemetry contract is absent")
    else:
        content = contract.read_text()
        for name in APPROVED:
            if '"' + name + '"' not in content:
                issues.append("Missing approved provider in source registry: " + name)
        if '"grillirg"' in content:
            issues.append("An unapproved direct controller is registered")
    for path in LEGACY_FILES:
        if (base / path).is_file():
            issues.append("Legacy proprietary client in common public source: " + path)
    # Developer/local Home Assistant HTTP may be enabled only in non-production variants.
    for variant in ("main", "release"):
        manifest = root / "app/src" / variant / "AndroidManifest.xml"
        if variant == "main" and not manifest.exists():
            issues.append("Missing public AndroidManifest.xml")
        elif manifest.exists() and 'usesCleartextTraffic="true"' in manifest.read_text():
            issues.append("Cleartext network traffic is enabled in public Android variant: " + variant)
    evidence = root / "docs/APP_REVIEW_CONNECTION_EVIDENCE.md"
    if not evidence.is_file():
        issues.append("Missing App Review evidence index")
    else:
        text = evidence.read_text().lower()
        for name in APPROVED:
            if name.replace("_", " ") not in text:
                issues.append("Missing provider evidence: " + name)
    return issues

def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--root", default=".")
    p.add_argument("--strict", action="store_true",
                   help="Reject any public build with unresolved blockers")
    args = p.parse_args()
    findings = audit(Path(args.root))
    if findings:
        print("NOT CLEARED for publicly advertised telemetry:")
        for issue in findings:
            print(" - " + issue)
        return 1 if args.strict else 0
    print("Source audit passed; current provider terms and real-device tests still require review.")
    return 0

if __name__ == "__main__":
    sys.exit(main())
