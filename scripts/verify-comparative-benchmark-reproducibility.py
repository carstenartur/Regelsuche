#!/usr/bin/env python3
"""Validate and byte-compare checkout and container comparative benchmarks."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import subprocess
import sys


def fail(message: str) -> None:
    raise RuntimeError(message)


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def validate(root: Path, verifier: Path) -> None:
    completed = subprocess.run(
        [sys.executable, str(verifier), "--root", str(root)],
        check=False,
    )
    require(
        completed.returncode == 0,
        f"comparative benchmark validation failed for {root} "
        f"with exit code {completed.returncode}",
    )


def retained_files(root: Path) -> dict[str, bytes]:
    require(root.is_dir(), f"missing comparative benchmark root: {root}")
    files = {
        path.relative_to(root).as_posix(): path.read_bytes()
        for path in sorted(root.rglob("*"))
        if path.is_file()
    }
    require(bool(files), f"no retained files under {root}")
    return files


def compare(left_root: Path, right_root: Path, label: str) -> None:
    left = retained_files(left_root)
    right = retained_files(right_root)
    left_names = set(left)
    right_names = set(right)
    missing = sorted(left_names - right_names)
    extra = sorted(right_names - left_names)
    changed = sorted(
        name for name in left_names & right_names if left[name] != right[name]
    )
    if not (missing or extra or changed):
        print(f"{label}=byte-identical")
        return

    details: list[str] = []
    if missing:
        details.append("missing=" + ", ".join(missing))
    if extra:
        details.append("extra=" + ", ".join(extra))
    if changed:
        rendered = []
        for name in changed:
            left_hash = hashlib.sha256(left[name]).hexdigest()
            right_hash = hashlib.sha256(right[name]).hexdigest()
            rendered.append(f"{name} ({left_hash} != {right_hash})")
        details.append("changed=" + ", ".join(rendered))
    fail(f"{label} differs: {'; '.join(details)}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--run-a", type=Path, required=True)
    parser.add_argument("--run-b", type=Path, required=True)
    parser.add_argument("--container-run", type=Path, required=True)
    args = parser.parse_args()

    root = args.root.resolve()
    verifier = root / "scripts/verify-comparative-benchmark.py"
    run_a = (root / args.run_a).resolve() if not args.run_a.is_absolute() else args.run_a
    run_b = (root / args.run_b).resolve() if not args.run_b.is_absolute() else args.run_b
    container_run = (
        (root / args.container_run).resolve()
        if not args.container_run.is_absolute()
        else args.container_run
    )

    try:
        require(verifier.is_file(), f"missing benchmark verifier: {verifier}")
        for retained_root in (run_a, run_b, container_run):
            validate(retained_root, verifier)
        compare(run_a, run_b, "gradle-repeat")
        compare(run_a, container_run, "gradle-container")
    except (RuntimeError, OSError, ValueError) as error:
        print(f"comparative benchmark reproduction invalid: {error}", file=sys.stderr)
        return 1

    print("comparative-benchmark-reproducibility=valid")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
