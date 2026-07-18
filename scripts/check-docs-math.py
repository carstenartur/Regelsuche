#!/usr/bin/env python3
"""Reject indented display-math fences outside Markdown code blocks."""

from __future__ import annotations

import re
import sys
from pathlib import Path

INDENTED_DISPLAY_MATH = re.compile(r"^[ \t]+\$\$")
FENCE = re.compile(r"^\s*```")


def violations(root: Path) -> list[str]:
    failures: list[str] = []
    for markdown in sorted(root.rglob("*.md")):
        in_fence = False
        for line_number, line in enumerate(
            markdown.read_text(encoding="utf-8").splitlines(), start=1
        ):
            if FENCE.match(line):
                in_fence = not in_fence
                continue
            if not in_fence and INDENTED_DISPLAY_MATH.match(line):
                failures.append(
                    f"{markdown}:{line_number}: indented display math `$$`"
                )
    return failures


def self_check() -> None:
    sample = [
        "```md",
        "  $$",
        "x^2",
        "  $$",
        "```",
        "",
        "  $$",
        "x^2",
        "  $$",
    ]
    in_fence = False
    hits: list[int] = []
    for line_number, line in enumerate(sample, start=1):
        if FENCE.match(line):
            in_fence = not in_fence
            continue
        if not in_fence and INDENTED_DISPLAY_MATH.match(line):
            hits.append(line_number)
    if hits != [7, 9]:
        raise RuntimeError(f"documentation lint self-check failed: {hits}")


def main() -> int:
    self_check()
    failures = violations(Path("docs"))
    if failures:
        print("Found indented display-math blocks:", file=sys.stderr)
        print("\n".join(failures), file=sys.stderr)
        return 1
    print("OK: no indented display-math blocks in docs/**/*.md")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
