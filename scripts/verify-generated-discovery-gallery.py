#!/usr/bin/env python3
"""Fail when the generated discovery gallery differs from the checkout."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

PATHS = ["docs/generated/discovery", "docs/demo-gallery.md", "README.md"]
DIAGNOSTIC = Path("app/build/reports/tests/gallery-verification/diff.txt")


def run(*arguments: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", *arguments],
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )


def main() -> int:
    diff = run("diff", "--no-ext-diff", "--", *PATHS)
    status = run("status", "--porcelain", "--", *PATHS)
    if diff.stdout or status.stdout.strip():
        report = "".join(
            (
                "# Generated discovery gallery drift\n\n",
                "## git status --porcelain\n",
                status.stdout or "(clean)\n",
                "\n## git diff\n",
                diff.stdout or "(no tracked-file diff; inspect untracked paths above)\n",
            )
        )
        DIAGNOSTIC.parent.mkdir(parents=True, exist_ok=True)
        DIAGNOSTIC.write_text(report, encoding="utf-8")
        print(report, file=sys.stderr, end="")
        print(f"Diagnostic written to {DIAGNOSTIC}", file=sys.stderr)
        return 1
    print("OK: generated discovery gallery matches the checkout")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
