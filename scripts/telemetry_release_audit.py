#!/usr/bin/env python3
"""Fail closed before publishing new PitTech device integrations."""
from __future__ import annotations
import argparse
import sys
import zipfile
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
    for variant in ("main", "release"):
        for source in (root / "app/src" / variant / "java").rglob("*.kt"):
            text = source.read_text()
            for name in FORBIDDEN_CLASSES:
                if "class " + name in text or "object " + name in text:
                    issues.append("Legacy controller implementation in public source: " + str(source.relative_to(root)))
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

FORBIDDEN_CLASSES = (
    "PrimePolarisApi", "PrimePolarisMonitor", "PitBossBackend", "TraegerBackend",
    "PitBossCloudTransport", "GrillCloudTransport", "GrillirGWifiProvisioner",
    "MongooseBleRpcProbe", "BluetoothGattInspector", "ControllerProbeDiagnostics",
    "GrillirGCloudMonitorScreen", "GrillirGWifiSetupPanel", "ControllerDiagnosticsScreen",
)
FORBIDDEN_ENDPOINTS = (b"/auth/m2s/email/login", b"/dms/queryDeviceRealTimeData", b"api.grillirg.com",
                       b"api.prime-polaris.com", b"socket.dansonscorp.com",
                       b"mobile-iot-api.iot.traegergrills.io", b"auth-api.iot.traegergrills.io")

def audit_artifact(path: Path) -> list[str]:
    issues = []
    with zipfile.ZipFile(path) as archive:
        dex = [entry for entry in archive.namelist() if entry.endswith(".dex")]
        if not dex:
            return ["Artifact has no compiled DEX to inspect: " + path.name]
        for entry in dex:
            data = archive.read(entry)
            if not data.startswith(b"dex\n"):
                issues.append("Invalid DEX in artifact: " + entry)
            for name in FORBIDDEN_CLASSES:
                token = ("Lcom/pittech/" + ("ui/" if name.endswith(("Screen", "Panel")) else "devices/") + name).encode()
                if token in data:
                    issues.append("Legacy controller class compiled into public artifact: " + name)
            for token in FORBIDDEN_ENDPOINTS:
                if token in data:
                    issues.append("Private controller endpoint compiled into public artifact: " + token.decode())
    return issues

def audit_dependencies(path: Path) -> list[str]:
    text = path.read_text().lower()
    return ["Unofficial legacy dependency in public graph: " + name for name in ("go-traeger", "pytboss", "grillirg", "primepolaris") if name in text]

def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--root", default=".")
    p.add_argument("--strict", action="store_true",
                   help="Reject any public build with unresolved blockers")
    p.add_argument("--artifact", action="append", default=[])
    p.add_argument("--dependencies")
    args = p.parse_args()
    findings = audit(Path(args.root))
    for artifact in args.artifact:
        findings.extend(audit_artifact(Path(artifact)))
    if args.dependencies:
        findings.extend(audit_dependencies(Path(args.dependencies)))
    if findings:
        print("NOT CLEARED for publicly advertised telemetry:")
        for issue in findings:
            print(" - " + issue)
        return 1 if args.strict else 0
    print("Source audit passed; current provider terms and real-device tests still require review.")
    return 0

if __name__ == "__main__":
    sys.exit(main())
