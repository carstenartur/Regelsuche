#!/usr/bin/env python3
"""Join isolated SymPy JaCoCo evidence and run the checkout coverage gate."""

from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import subprocess
import sys
from pathlib import Path

CANONICAL_SYMPY_REPORT = Path(
    "regelsuche-math-sympy/build/reports/jacoco/test/jacocoTestReport.xml"
)
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


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


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

    isolated_report = _inside(root, isolated, "isolated report")
    canonical_report = _inside(root, CANONICAL_SYMPY_REPORT, "canonical report")

    if isolated_report.is_symlink() or not isolated_report.is_file():
        raise CoverageConvergenceError(
            f"isolated SymPy JaCoCo report is missing or not a regular file: "
            f"{isolated_report}"
        )
    if canonical_report.exists() or canonical_report.is_symlink():
        raise CoverageConvergenceError(
            f"canonical SymPy JaCoCo report already exists; refusing stale or "
            f"ambiguous evidence: {canonical_report}"
        )
    classpath = _coverage_classpath(root)

    canonical_report.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(isolated_report, canonical_report)
    source_hash = _sha256(isolated_report)
    canonical_hash = _sha256(canonical_report)
    if canonical_hash != source_hash:
        raise CoverageConvergenceError(
            "copied SymPy JaCoCo report is not byte-identical to isolated evidence"
        )

    print(f"crossAuthorityCoverageSymPyReport=sha256:{canonical_hash}")
    subprocess.run(
        [
            "java",
            "-cp",
            classpath,
            COVERAGE_MAIN,
            "--root",
            str(root),
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
