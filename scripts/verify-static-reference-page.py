#!/usr/bin/env python3
"""Verify the locally generated static reference page contract."""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path("app/build/reports/static-reference-page")
REQUIRED_FILES = (
    "index.html",
    "timeline.html",
    "observatory.html",
    "timeline.md",
    "version.txt",
)
REQUIRED_INDEX_MARKERS = (
    ("LIVE EXECUTION", "REPLAYED FROM CANONICAL", "PRE-GENERATED REFERENCE"),
    ("runReferenceCampaign",),
    ("generateStaticReferencePage",),
    ("Dockerfile.proof",),
)


def main() -> int:
    failures: list[str] = []
    for relative in REQUIRED_FILES:
        path = ROOT / relative
        if not path.is_file() or path.stat().st_size == 0:
            failures.append(f"missing or empty: {path}")

    index = ROOT / "index.html"
    if index.is_file():
        text = index.read_text(encoding="utf-8")
        for alternatives in REQUIRED_INDEX_MARKERS:
            if not any(marker in text for marker in alternatives):
                failures.append(
                    "index.html is missing one of: " + ", ".join(alternatives)
                )

    if failures:
        print("Static reference page validation failed:", file=sys.stderr)
        print("\n".join(failures), file=sys.stderr)
        return 1
    print("OK: static reference page contract is complete")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
