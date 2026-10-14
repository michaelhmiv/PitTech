#!/usr/bin/env python3
"""Collect App Store tour screenshots from an xcresult attachments export.

Usage: collect-tour-screenshots.py <export-dir> <output-dir> <family>

Reads manifest.json in <export-dir>, finds PNG attachments whose names carry a
two-digit ordering prefix (e.g. "01-Meals-Home"), and copies them into
<output-dir> as PitTech-<family>-<name>.png.
"""
import json
import pathlib
import re
import shutil
import sys

export_dir = pathlib.Path(sys.argv[1])
output_dir = pathlib.Path(sys.argv[2])
family = sys.argv[3]
output_dir.mkdir(parents=True, exist_ok=True)

manifest = json.loads((export_dir / "manifest.json").read_text())
copied = 0
for case in manifest:
    for attachment in case.get("attachments", []):
        name = attachment.get("suggestedHumanReadableName", "")
        base = name.split("_0_")[0]
        if not re.match(r"^\d{2}-", base):
            continue
        source = export_dir / attachment["exportedFileName"]
        if not source.exists():
            continue
        target = output_dir / f"PitTech-{family}-{base}.png"
        shutil.copy2(source, target)
        print(f"tour screenshot: {target.name}")
        copied += 1

if copied == 0:
    raise SystemExit("No tour screenshots were found in the export.")
print(f"Collected {copied} tour screenshots for {family}.")
