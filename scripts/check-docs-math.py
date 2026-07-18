#!/usr/bin/env python3
"""Reject indented display-math delimiters outside fenced code blocks."""

from __future__ import annotations

import re
import sys
from pathlib import Path

INDENTED_DISPLAY_MATH = re.compile(r"^[ \t]+\$\$")
FENCE = re.compile(r"^\s*```")


def find_indented_display_math(lines: list[str]) -> list[int]:
    """Return one-based line numbers for invalid delimiters outside fences."""
    in_fence = False
    hits: list[int] = []
    for line_number, line in enumerate(lines, start=1):
        if FENCE.match(line):
            in_fence = not in_fence
            continue
        if not in_fence and INDENTED_DISPLAY_MATH.match(line):
            hits.append(line_number)
    return hits


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
    ]
    actual = find_indented_display_math(sample)
    if actual != [7, 9]:
        raise RuntimeError(f"internal lint self-test failed: {actual}")


def main() -> int:
    self_test()
    failures: list[str] = []
    for markdown_file in sorted(Path("docs").rglob("*.md")):
        try:
            lines = markdown_file.read_text(encoding="utf-8").splitlines()
        except OSError as exc:
            print(f"Unable to read {markdown_file}: {exc}", file=sys.stderr)
            return 2
        failures.extend(
            f"{markdown_file}:{line_number}: indented display math `$$`"
            for line_number in find_indented_display_math(lines)
        )

    if failures:
        print("Found indented display-math blocks:")
        print("\n".join(failures))
        return 1

    print("OK: no indented display-math blocks in docs/**/*.md")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
