#!/usr/bin/env python3
"""Require selected files in two directories to be byte-identical."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import sys


def collect(root: Path, pattern: str) -> dict[str, bytes]:
    if not root.is_dir():
        raise RuntimeError(f"missing directory: {root}")
    files = {
        path.relative_to(root).as_posix(): path.read_bytes()
        for path in sorted(root.rglob(pattern))
        if path.is_file()
    }
    if not files:
        raise RuntimeError(f"no files matching {pattern!r} under {root}")
    return files


def compare(left_root: Path, right_root: Path, pattern: str) -> None:
    left = collect(left_root, pattern)
    right = collect(right_root, pattern)
    missing = sorted(set(left) - set(right))
    extra = sorted(set(right) - set(left))
    changed = sorted(name for name in set(left) & set(right) if left[name] != right[name])
    if not (missing or extra or changed):
        print(
            f"byte-identical={len(left)} files; "
            f"left={left_root}; right={right_root}; pattern={pattern}"
        )
        return
    details: list[str] = []
    if missing:
        details.append("missing=" + ", ".join(missing))
    if extra:
        details.append("extra=" + ", ".join(extra))
    if changed:
        hashes = []
        for name in changed:
            hashes.append(
                f"{name} "
                f"({hashlib.sha256(left[name]).hexdigest()} != "
                f"{hashlib.sha256(right[name]).hexdigest()})"
            )
        details.append("changed=" + ", ".join(hashes))
    raise RuntimeError("directory evidence differs: " + "; ".join(details))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--left", type=Path, required=True)
    parser.add_argument("--right", type=Path, required=True)
    parser.add_argument("--include", default="*")
    args = parser.parse_args()
    try:
        compare(args.left.resolve(), args.right.resolve(), args.include)
    except (RuntimeError, OSError) as error:
        print(f"deterministic evidence verification failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
