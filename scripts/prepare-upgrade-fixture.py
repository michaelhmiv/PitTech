#!/usr/bin/env python3
"""Overlay test-only controller injection onto a previous checkout, never recovery implementation."""
from pathlib import Path
import shutil
import sys

candidate, previous = map(Path, sys.argv[1:])
for relative in (
    "app/src/debug/java/com/pittech/devices/UpgradeRecordingFixture.kt",
    "app/src/androidTest/java/com/pittech/LiveUpgradeRecordingTest.kt",
):
    target = previous / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(candidate / relative, target)
application = previous / "app/src/main/java/com/pittech/PitTechApplication.kt"
text = application.read_text()
needle = "private val sharedGrillMonitor by lazy { com.pittech.devices.ConnectedGrillMonitor.create(this) }"
replacement = """private val sharedGrillMonitor by lazy {
        val fixture = if (BuildConfig.DEBUG) runCatching {
            Class.forName("com.pittech.devices.UpgradeRecordingFixture")
                .getMethod("create", android.content.Context::class.java).invoke(null, this) as? com.pittech.devices.PolarisMonitorEngine
        }.getOrNull() else null
        fixture ?: com.pittech.devices.ConnectedGrillMonitor.create(this)
    }"""
if needle in text:
    application.write_text(text.replace(needle, replacement))
elif 'Class.forName("com.pittech.devices.UpgradeRecordingFixture")' not in text:
    raise SystemExit("The previous APK's fixture injection point changed; inspect it before upgrading.")
