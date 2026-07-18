#!/usr/bin/env python3
"""Fail when the generated discovery gallery differs from the checkout."""

from __future__ import annotations

import subprocess
import sys

PATHS = ["docs/generated/discovery", "docs/demo-gallery.md", "README.md"]


def run(*arguments: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", *arguments],
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )


def main() -> int:
    diff = run("diff", "--exit-code", "--", *PATHS)
    status = run("status", "--porcelain", "--", *PATHS)
    if diff.returncode != 0 or status.stdout.strip():
        if diff.stdout:
            print(diff.stdout, file=sys.stderr, end="")
        if status.stdout:
            print("Generated or untracked gallery drift:", file=sys.stderr)
            print(status.stdout, file=sys.stderr, end="")
        return 1
    print("OK: generated discovery gallery matches the checkout")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
