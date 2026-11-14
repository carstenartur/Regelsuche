#!/usr/bin/env python3
"""Validate the retained Java classpath and invoke the checkout coverage gate."""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

COVERAGE_RUNTIME = Path("build/reports/quality/coverage-report-runtime")
COVERAGE_MAIN = "de.regelsuche.quality.coverage.CoverageRegressionMain"


class CoverageConvergenceError(RuntimeError):
    """Raised when authority evidence cannot be joined unambiguously."""


def _inside(root: Path, candidate: Path, label: str) -> Path:
    supplied = candidate if candidate.is_absolute() else root / candidate
    lexical = Path(os.path.abspath(supplied))
    try:
        lexical.relative_to(root)
    except ValueError as exc:
        raise CoverageConvergenceError(
            f"{label} escapes repository root: {lexical}"
        ) from exc

    resolved = lexical.resolve()
    try:
        resolved.relative_to(root)
    except ValueError as exc:
        raise CoverageConvergenceError(
            f"{label} resolves outside repository root: {resolved}"
        ) from exc
    return lexical


def _coverage_classpath(root: Path) -> str:
    runtime = _inside(root, COVERAGE_RUNTIME, "coverage verifier runtime")
    if runtime.is_symlink() or not runtime.is_dir():
        raise CoverageConvergenceError(f"coverage verifier runtime is missing: {runtime}")
    jars = sorted(runtime.glob("*.jar"))
    quality_jar = any(jar.stem == "regelsuche-quality" or jar.stem.startswith("regelsuche-quality-")
                      for jar in jars)
    if not quality_jar or any(jar.is_symlink() or not jar.is_file() for jar in jars):
        raise CoverageConvergenceError(f"coverage verifier runtime does not contain regular JARs: {runtime}")
    return os.pathsep.join(str(jar) for jar in jars)


def converge(root: Path, isolated: Path) -> None:
    root = root.resolve()
    if not root.is_dir():
        raise CoverageConvergenceError(f"repository root is not a directory: {root}")

    classpath = _coverage_classpath(root)
    subprocess.run(
        [
            "java",
            "-cp",
            classpath,
            COVERAGE_MAIN,
            "--root",
            str(root),
            "--isolated-report",
            str(isolated),
        ],
        cwd=root,
        check=True,
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        type=Path,
        required=True,
        help="Repository root containing the coverage policy and module reports.",
    )
    parser.add_argument(
        "--isolated-report",
        type=Path,
        required=True,
        help="Isolated SymPy jacocoTestReport.xml, relative to --root or absolute.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        converge(args.root, args.isolated_report)
    except CoverageConvergenceError as exc:
        print(f"crossAuthorityCoverageStatus=FAILED: {exc}", file=sys.stderr)
        return 1
    except subprocess.CalledProcessError as exc:
        return exc.returncode or 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
