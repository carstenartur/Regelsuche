#!/usr/bin/env python3
"""Characterize the JMH v2 verifier with two passes and six fail-closed cases."""

from __future__ import annotations

import argparse
import copy
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


def arguments(
    root: Path,
    label: str,
    policy_path: Path,
    legacy_options: bool = False,
) -> list[str]:
    result_option = "--results" if legacy_options else "--result"
    json_option = "--report-json" if legacy_options else "--json-output"
    markdown_option = "--report-md" if legacy_options else "--markdown-output"
    return [
        "verify-jmh-regression-v2.py",
        result_option,
        str(root / f"{label}-result.json"),
        "--policy",
        str(policy_path),
        json_option,
        str(root / f"{label}-report.json"),
        markdown_option,
        str(root / f"{label}-report.md"),
    ]


def execute(
    verifier,
    root: Path,
    label: str,
    result: list[dict],
    expected: int,
    legacy_options: bool = False,
) -> None:
    result_path = root / f"{label}-result.json"
    json_output = root / f"{label}-report.json"
    write(result_path, result)
    previous = sys.argv
    sys.argv = arguments(root, label, root / "policy.json", legacy_options)
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


def execute_policy_failure(
    verifier,
    root: Path,
    label: str,
    policy: dict,
    expected_message: str,
) -> None:
    policy_path = root / f"{label}-policy.json"
    write(policy_path, policy)
    write(root / f"{label}-result.json", [benchmark()])
    previous = sys.argv
    sys.argv = arguments(root, label, policy_path)
    try:
        try:
            verifier.main()
        except SystemExit as error:
            message = str(error.code)
            if expected_message not in message:
                raise SystemExit(
                    f"{label}: expected diagnostic {expected_message!r}, found {message!r}"
                )
        else:
            raise SystemExit(f"{label}: malformed policy was accepted")
    finally:
        sys.argv = previous


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
                    "family": "CORE",
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
        execute(
            verifier,
            root,
            "legacy-options-pass",
            [benchmark()],
            0,
            legacy_options=True,
        )
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

        missing_family = copy.deepcopy(policy)
        del missing_family["benchmarks"][0]["family"]
        execute_policy_failure(
            verifier,
            root,
            "missing-family",
            missing_family,
            "unsupported or missing family",
        )
        missing_error = copy.deepcopy(policy)
        del missing_error["benchmarks"][0]["baselineScoreError"]
        execute_policy_failure(
            verifier,
            root,
            "missing-baseline-error",
            missing_error,
            "baselineScoreError must be numeric",
        )
    print("JMH regression verifier characterization passed: 2 positive, 6 negative")


if __name__ == "__main__":
    main()
