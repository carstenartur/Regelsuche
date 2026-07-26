#!/usr/bin/env python3
"""Check repository Markdown structure without GitHub-specific tooling."""

from __future__ import annotations

import argparse
import re
from pathlib import Path
from typing import Iterable
from urllib.parse import unquote, urlsplit

FENCE = re.compile(r"^\s*(```|~~~)")
INDENTED_DISPLAY_MATH = re.compile(r"^[ \t]+\$\$")
MARKDOWN_TARGET = re.compile(r"]\(([^)\n]+)\)")
HTML_TARGET = re.compile(
    r"(?:href|src)\s*=\s*[\"']([^\"']+)[\"']", re.IGNORECASE
)
IGNORED_SCHEMES = {"http", "https", "mailto", "data", "javascript"}
EXCLUDED_LINK_FILES = {"README.legacy.md"}


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


def normalize_local_target(raw: str) -> str | None:
    target = raw.strip()
    if target.startswith("<") and ">" in target:
        target = target[1 : target.index(">")]
    else:
        # Markdown permits an optional whitespace-separated title.
        target = target.split(maxsplit=1)[0]
    target = unquote(target)
    if not target or target.startswith("#"):
        return None
    parsed = urlsplit(target)
    if parsed.scheme.lower() in IGNORED_SCHEMES or parsed.netloc:
        return None
    return parsed.path or None


def find_broken_local_links(markdown: Path, repository_root: Path) -> list[str]:
    if markdown.name in EXCLUDED_LINK_FILES:
        return []
    text = markdown.read_text(encoding="utf-8")
    repository_root = repository_root.resolve()
    failures: list[str] = []
    for pattern in (MARKDOWN_TARGET, HTML_TARGET):
        for match in pattern.finditer(text):
            relative = normalize_local_target(match.group(1))
            if relative is None:
                continue
            target = (markdown.parent / relative).resolve()
            line = text.count("\n", 0, match.start()) + 1
            try:
                target.relative_to(repository_root)
            except ValueError:
                failures.append(
                    f"{markdown}:{line}: link escapes repository: {match.group(1)}"
                )
                continue
            if not target.exists():
                failures.append(
                    f"{markdown}:{line}: missing local target: {match.group(1)}"
                )
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
            "internal documentation-math lint self-test failed: "
            f"expected {expected}, found {actual}"
        )
    if normalize_local_target("https://example.org/docs") is not None:
        raise RuntimeError("external URL must not be treated as a local link")
    if normalize_local_target("guide.md#section") != "guide.md":
        raise RuntimeError("local link normalization self-test failed")


def check(root: Path, repository_root: Path) -> list[str]:
    failures: list[str] = []
    markdown_files = sorted(root.rglob("*.md"))
    repository_readme = repository_root / "README.md"
    if repository_readme.is_file():
        markdown_files.insert(0, repository_readme)
    for markdown in markdown_files:
        lines = markdown.read_text(encoding="utf-8").splitlines()
        for line_number in find_indented_display_math(lines):
            failures.append(
                f"{markdown}:{line_number}: indented display math `$$`"
            )
        failures.extend(find_broken_local_links(markdown, repository_root))
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
    repository_root = Path(__file__).resolve().parents[1]
    failures = check(args.root, repository_root)
    if failures:
        print("Found documentation problems:")
        for failure in failures:
            print(failure)
        return 1

    print(
        f"OK: documentation math and local links are valid in "
        f"{args.root}/**/*.md and README.md"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
