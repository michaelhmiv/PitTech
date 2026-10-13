#!/usr/bin/env python3
"""Validate curated store notes; generate commit-specific Firebase Dev notes."""
from __future__ import annotations

import argparse
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
PLAY = ROOT / "distribution/play/whatsnew/whatsnew-en-US"
IOS = ROOT / "distribution/app-store/whats-new-en-US.txt"


def validate_text(path: Path, maximum: int) -> None:
    if not path.is_file():
        raise ValueError(f"Missing release notes: {path}")
    content = path.read_text(encoding="utf-8").strip()
    if not content:
        raise ValueError(f"Release notes are empty: {path}")
    if len(content) > maximum:
        raise ValueError(f"Release notes exceed {maximum} characters: {path}")
    if re.search(r"\b(TODO|TBD|PLACEHOLDER|LOREM IPSUM)\b", content, re.I):
        raise ValueError(f"Placeholder in release notes: {path}")


def commit_title(commit: str) -> str:
    if not re.fullmatch(r"[a-fA-F0-9]{7,40}", commit):
        raise ValueError("Invalid Git commit SHA")
    output = subprocess.check_output(
        ["git", "show", "-s", "--format=%s%n%b", commit],
        cwd=ROOT, text=True,
    )
    lines = [line.strip() for line in output.splitlines() if line.strip()]
    if not lines:
        raise ValueError("Git commit has no title")
    title = lines[0]
    if title.startswith("Merge pull request") and len(lines) > 1:
        title = lines[1]
    title = re.sub(r"\s+", " ", title)
    return title[:237] + "..." if len(title) > 240 else title


def render_dev(commit: str, version: str, repo: str) -> str:
    if not re.fullmatch(r"[A-Za-z0-9_.-]{1,70}", version):
        raise ValueError("Invalid release version")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
        raise ValueError("Invalid repository name")
    return (
        f"PitTech Dev {version}\n"
        f"Changed in this build:\n- {commit_title(commit)}\n"
        f"Source: https://github.com/{repo}/commit/{commit}\n"
        "This is a test build. Experimental connections may be unavailable or interrupted.\n"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("validate")
    dev = commands.add_parser("dev")
    for key in ("commit", "version", "repo", "output"):
        dev.add_argument("--" + key, required=True)
    args = parser.parse_args()
    if args.command == "validate":
        validate_text(PLAY, 500)
        validate_text(IOS, 4000)
        print("Store update notes passed validation")
    else:
        target = Path(args.output)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(render_dev(args.commit, args.version, args.repo), encoding="utf-8")
        print(f"Created notes: {target}")


if __name__ == "__main__":
    main()
