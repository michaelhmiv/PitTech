#!/usr/bin/env python3
"""Completeness guard for the iOS test shard map (issue #134).

Fails when the XCTestCase classes (and, for method-split classes, the test
methods) present in the Swift test sources do not match
scripts/ios-test-shards.json exactly. A new or renamed test must be assigned
deliberately; nothing may run unlisted, be listed twice, or linger in the
map after deletion.
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAP_PATH = ROOT / "scripts" / "ios-test-shards.json"
CLASS_RE = re.compile(r"\bclass\s+([A-Za-z0-9_]+)\s*:\s*XCTestCase\b")
METHOD_RE = re.compile(r"\bfunc\s+(test[A-Za-z0-9_]*)\s*\(")


def swift_files(target_dir: Path) -> list[Path]:
    return sorted(target_dir.rglob("*.swift"))


def source_classes(files: list[Path]) -> set[str]:
    found: set[str] = set()
    for swift in files:
        found.update(CLASS_RE.findall(swift.read_text()))
    return found


def source_methods(files: list[Path], class_name: str) -> set[str]:
    """Test methods declared in the file(s) that declare the class."""
    methods: set[str] = set()
    for swift in files:
        text = swift.read_text()
        if re.search(rf"\bclass\s+{re.escape(class_name)}\s*:\s*XCTestCase\b", text):
            methods.update(METHOD_RE.findall(text))
    return methods


def main() -> int:
    shard_map = json.loads(MAP_PATH.read_text())

    unit_files = swift_files(ROOT / "ios" / shard_map["unitTarget"])
    ui_files = swift_files(ROOT / "ios" / shard_map["uiTarget"])
    unit_source = source_classes(unit_files)
    ui_source = source_classes(ui_files)

    problems: list[str] = []

    # Unit: flat class list, exact match.
    mapped_unit = shard_map["unitClasses"]
    if len(mapped_unit) != len(set(mapped_unit)):
        dupes = sorted({n for n in mapped_unit if mapped_unit.count(n) > 1})
        problems.append(f"unit: assigned more than once: {dupes}")
    for name in sorted(unit_source - set(mapped_unit)):
        problems.append(f"unit: in source but not in the shard map: {name}")
    for name in sorted(set(mapped_unit) - unit_source):
        problems.append(f"unit: in the shard map but not in source: {name}")

    # UI: entries are "Class" (whole class) or "Class/method".
    whole: dict[str, str] = {}
    split: dict[str, dict[str, str]] = {}
    for shard, entries in shard_map["uiShards"].items():
        for entry in entries:
            if "/" in entry:
                class_name, method = entry.split("/", 1)
                per_class = split.setdefault(class_name, {})
                if method in per_class:
                    problems.append(
                        f"ui: {entry} assigned to both {per_class[method]} and {shard}"
                    )
                per_class[method] = shard
            else:
                if entry in whole:
                    problems.append(
                        f"ui: {entry} assigned to both {whole[entry]} and {shard}"
                    )
                whole[entry] = shard

    for class_name in sorted(set(whole) & set(split)):
        problems.append(f"ui: {class_name} is assigned both whole and by method")

    mapped_ui_classes = set(whole) | set(split)
    for name in sorted(ui_source - mapped_ui_classes):
        problems.append(f"ui: in source but not in the shard map: {name}")
    for name in sorted(mapped_ui_classes - ui_source):
        problems.append(f"ui: in the shard map but not in source: {name}")

    # Method-split classes: every source method assigned exactly once
    # (duplicates and cross-shard repeats already flagged above).
    for class_name, per_class in sorted(split.items()):
        if class_name not in ui_source:
            continue
        methods_in_source = source_methods(ui_files, class_name)
        for method in sorted(methods_in_source - set(per_class)):
            problems.append(f"ui: {class_name}/{method} in source but not assigned")
        for method in sorted(set(per_class) - methods_in_source):
            problems.append(f"ui: {class_name}/{method} assigned but not in source")

    if problems:
        print("iOS test shard map is out of date:")
        for problem in problems:
            print(f"  - {problem}")
        return 1

    split_note = ", ".join(
        f"{name} split across {len(set(per_class.values()))} shards "
        f"({len(per_class)} methods)"
        for name, per_class in sorted(split.items())
    )
    print(
        f"Shard map OK: {len(mapped_unit)} unit classes, "
        f"{len(mapped_ui_classes)} UI classes across "
        f"{len(shard_map['uiShards'])} shards"
        + (f"; {split_note}." if split_note else ".")
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
