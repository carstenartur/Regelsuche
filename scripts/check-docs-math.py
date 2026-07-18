#!/usr/bin/env python3
"""Check Markdown display-math blocks without depending on GitHub Actions."""

from __future__ import annotations

import argparse
import re
from pathlib import Path
from typing import Iterable

FENCE = re.compile(r"^\s*(```|~~~)")
INDENTED_DISPLAY_MATH = re.compile(r"^[ \t]+\$\$")


def find_indented_display_math(lines: Iterable[str]) -> list[int]:
    """Return one-based line numbers for invalid blocks outside code fences."""
    fence_marker: str | None = None
    failures: list[int] = []

    for line_number, line in enumerate(lines, start=1):
        fence = FENCE.match(line)
        if fence:
            marker = fence.group(1)
            if fence_marker is None:
                fence_marker = marker
            elif marker == fence_marker:
                fence_marker = None
            continue
        if fence_marker is None and INDENTED_DISPLAY_MATH.match(line):
            failures.append(line_number)

    return failures


def self_test() -> None:
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
        "~~~text",
        "  $$",
        "~~~",
    ]
    actual = find_indented_display_math(sample)
    expected = [7, 9]
    if actual != expected:
        raise RuntimeError(
            f"internal documentation-math lint self-test failed: "
            f"expected {expected}, found {actual}"
        )


def check(root: Path) -> list[str]:
    failures: list[str] = []
    for markdown in sorted(root.rglob("*.md")):
        lines = markdown.read_text(encoding="utf-8").splitlines()
        for line_number in find_indented_display_math(lines):
            failures.append(
                f"{markdown}:{line_number}: indented display math `$$`"
            )
    return failures


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "root",
        nargs="?",
        type=Path,
        default=Path("docs"),
        help="Markdown tree to inspect (default: docs)",
    )
    args = parser.parse_args()

    self_test()
    failures = check(args.root)
    if failures:
        print("Found indented display-math blocks:")
        for failure in failures:
            print(failure)
        return 1

    print(f"OK: no indented display-math blocks in {args.root}/**/*.md")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
