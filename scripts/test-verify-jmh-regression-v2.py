#!/usr/bin/env python3
"""Characterize the JMH v2 verifier with one pass and four fail-closed cases."""

from __future__ import annotations

import argparse
import importlib.util
import json
import sys
import tempfile
from pathlib import Path
from typing import Any


def write(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def benchmark(
    name: str = "example.Benchmark.work",
    score: float = 1.0,
    unit: str = "us/op",
) -> dict:
    return {
        "jmhVersion": "1.36",
        "benchmark": name,
        "mode": "avgt",
        "forks": 1,
        "jdkVersion": "21.0.11",
        "warmupIterations": 2,
        "measurementIterations": 3,
        "primaryMetric": {
            "score": score,
            "scoreError": 0.1,
            "scoreUnit": unit,
        },
        "secondaryMetrics": {},
    }


def load_verifier(path: Path):
    spec = importlib.util.spec_from_file_location("jmh_regression_verifier", path)
    if spec is None or spec.loader is None:
        raise SystemExit(f"cannot import verifier: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def execute(verifier, root: Path, label: str, result: list[dict], expected: int) -> None:
    result_path = root / f"{label}-result.json"
    json_output = root / f"{label}-report.json"
    markdown_output = root / f"{label}-report.md"
    write(result_path, result)
    previous = sys.argv
    sys.argv = [
        "verify-jmh-regression-v2.py",
        "--result",
        str(result_path),
        "--policy",
        str(root / "policy.json"),
        "--json-output",
        str(json_output),
        "--markdown-output",
        str(markdown_output),
    ]
    try:
        try:
            return_code = verifier.main()
        except SystemExit as error:
            return_code = error.code if isinstance(error.code, int) else 1
    finally:
        sys.argv = previous
    if return_code != expected:
        raise SystemExit(f"{label}: expected exit {expected}, found {return_code}")
    report = json.loads(json_output.read_text(encoding="utf-8"))
    expected_status = "PASSED" if expected == 0 else "FAILED"
    if report.get("status") != expected_status:
        raise SystemExit(
            f"{label}: expected status {expected_status}, found {report.get('status')}"
        )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--verifier", required=True, type=Path)
    args = parser.parse_args()
    verifier = load_verifier(args.verifier.resolve())
    with tempfile.TemporaryDirectory(prefix="regelsuche-jmh-v2-") as directory:
        root = Path(directory)
        policy = {
            "schema": "regelsuche.quality.jmh-regression-policy/v2",
            "baselineRevision": "synthetic",
            "baselineArtifactDigest": "sha256:" + "0" * 64,
            "claimBoundary": "synthetic verifier characterization",
            "execution": {
                "jmhVersion": "1.36",
                "jdkMajor": 21,
                "mode": "avgt",
                "forks": 1,
                "warmupIterations": 2,
                "measurementIterations": 3,
            },
            "benchmarks": [
                {
                    "benchmark": "example.Benchmark.work",
                    "family": "SYNTHETIC",
                    "unit": "us/op",
                    "baselineScore": 1.0,
                    "baselineScoreError": 0.1,
                    "maximumAllowedScore": 1.5,
                    "maximumMultiplier": 1.5,
                }
            ],
        }
        write(root / "policy.json", policy)
        execute(verifier, root, "pass", [benchmark()], 0)
        execute(verifier, root, "missing", [], 1)
        execute(
            verifier,
            root,
            "unexpected",
            [benchmark(), benchmark("example.Benchmark.other")],
            1,
        )
        execute(verifier, root, "wrong-unit", [benchmark(unit="ms/op")], 1)
        execute(verifier, root, "regression", [benchmark(score=1.6)], 1)
    print("JMH regression verifier characterization passed: 1 positive, 4 negative")


if __name__ == "__main__":
    main()
