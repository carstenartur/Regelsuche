#!/usr/bin/env python3
"""Characterize the versioned JMH v3 uncertainty-aware decision authority."""

from __future__ import annotations

import argparse
import copy
import hashlib
import importlib.util
import json
import sys
import tempfile
from pathlib import Path
from typing import Any

sys.dont_write_bytecode = True


def write(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def git_blob_sha1(path: Path) -> str:
    data = path.read_bytes()
    return hashlib.sha1(f"blob {len(data)}\0".encode("ascii") + data).hexdigest()


def benchmark(
    name: str = "example.Benchmark.work",
    score: float = 1.0,
    unit: str = "us/op",
    score_error: float = 0.1,
) -> dict[str, Any]:
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
            "scoreError": score_error,
            "scoreUnit": unit,
        },
        "secondaryMetrics": {},
    }


def load_verifier(path: Path):
    spec = importlib.util.spec_from_file_location("jmh_regression_verifier_v3", path)
    if spec is None or spec.loader is None:
        raise SystemExit(f"cannot import verifier: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def arguments(
    repository_root: Path,
    result_path: Path,
    threshold_policy: Path,
    decision_policy: Path,
    json_output: Path,
    markdown_output: Path,
) -> list[str]:
    return [
        "verify-jmh-regression-v3.py",
        "--repository-root",
        str(repository_root),
        "--result",
        str(result_path),
        "--threshold-policy",
        str(threshold_policy),
        "--decision-policy",
        str(decision_policy),
        "--json-output",
        str(json_output),
        "--markdown-output",
        str(markdown_output),
    ]


def execute(
    verifier,
    root: Path,
    label: str,
    result: list[dict[str, Any]],
    expected_exit: int,
    expected_status: str | None = None,
) -> dict[str, Any]:
    result_path = root / f"{label}-result.json"
    json_output = root / f"{label}-report.json"
    write(result_path, result)
    previous = sys.argv
    sys.argv = arguments(
        root,
        result_path,
        root / "threshold-policy.json",
        root / "decision-policy.json",
        json_output,
        root / f"{label}-report.md",
    )
    try:
        try:
            return_code = verifier.main()
        except SystemExit as error:
            return_code = error.code if isinstance(error.code, int) else 1
    finally:
        sys.argv = previous
    if return_code != expected_exit:
        raise SystemExit(
            f"{label}: expected exit {expected_exit}, found {return_code}"
        )
    report = json.loads(json_output.read_text(encoding="utf-8"))
    if expected_status is None:
        expected_status = "PASSED" if expected_exit == 0 else "FAILED"
    if report.get("status") != expected_status:
        raise SystemExit(
            f"{label}: expected status {expected_status}, found {report.get('status')}"
        )
    if report.get("schema") != "regelsuche.quality.jmh-regression-report/v3":
        raise SystemExit(f"{label}: unexpected report schema {report.get('schema')}")
    return report


def execute_policy_failure(
    verifier,
    root: Path,
    label: str,
    threshold_policy: dict[str, Any],
    decision_policy: dict[str, Any],
    expected_message: str,
    *,
    threshold_path_override: str | None = None,
    threshold_blob_override: str | None = None,
) -> None:
    case_root = root / label
    case_root.mkdir(parents=True, exist_ok=True)
    threshold_path = case_root / "threshold-policy.json"
    decision_path = case_root / "decision-policy.json"
    result_path = case_root / "result.json"
    json_output = case_root / "report.json"
    write(threshold_path, threshold_policy)
    bound_decision = copy.deepcopy(decision_policy)
    bound_decision["thresholdPolicyPath"] = (
        threshold_path_override if threshold_path_override is not None else "threshold-policy.json"
    )
    bound_decision["thresholdPolicyGitBlobSha1"] = (
        threshold_blob_override
        if threshold_blob_override is not None
        else git_blob_sha1(threshold_path)
    )
    write(decision_path, bound_decision)
    write(result_path, [benchmark()])
    previous = sys.argv
    sys.argv = arguments(
        case_root,
        result_path,
        threshold_path,
        decision_path,
        json_output,
        case_root / "report.md",
    )
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


def only_row(report: dict[str, Any], label: str) -> dict[str, Any]:
    rows = report.get("benchmarks")
    if not isinstance(rows, list) or len(rows) != 1 or not isinstance(rows[0], dict):
        raise SystemExit(f"{label}: expected one benchmark row")
    return rows[0]


def assert_decision_score(report: dict[str, Any], expected: float, label: str) -> None:
    actual = only_row(report, label).get("decisionScore")
    if not isinstance(actual, (int, float)) or abs(float(actual) - expected) > 1e-12:
        raise SystemExit(f"{label}: expected decisionScore {expected}, found {actual}")


def assert_precision(report: dict[str, Any], expected: str, label: str) -> None:
    actual = only_row(report, label).get("precisionStatus")
    if actual != expected:
        raise SystemExit(f"{label}: expected precision {expected}, found {actual}")


def assert_row_status(report: dict[str, Any], expected: str, label: str) -> None:
    actual = only_row(report, label).get("status")
    if actual != expected:
        raise SystemExit(f"{label}: expected row status {expected}, found {actual}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--verifier", required=True, type=Path)
    args = parser.parse_args()
    verifier = load_verifier(args.verifier.resolve())

    with tempfile.TemporaryDirectory(prefix="regelsuche-jmh-v3-") as directory:
        root = Path(directory)
        threshold_policy = {
            "schema": "regelsuche.quality.jmh-regression-policy/v2",
            "baselineRevision": "synthetic",
            "baselineArtifactDigest": "sha256:" + "0" * 64,
            "claimBoundary": "synthetic frozen threshold inventory",
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
        threshold_path = root / "threshold-policy.json"
        write(threshold_path, threshold_policy)
        decision_policy = {
            "schema": "regelsuche.quality.jmh-regression-decision-policy/v3",
            "thresholdPolicyPath": "threshold-policy.json",
            "thresholdPolicySchema": "regelsuche.quality.jmh-regression-policy/v2",
            "thresholdPolicyGitBlobSha1": git_blob_sha1(threshold_path),
            "decisionStatistic": "max(0, currentScore - currentScoreError)",
            "failureCondition": "decisionScore > maximumAllowedScore",
            "inconclusiveCondition": (
                "currentScore > maximumAllowedScore && "
                "decisionScore <= maximumAllowedScore"
            ),
            "boundaryPolicy": "inclusive-pass",
            "lowPrecisionDiagnostic": "currentScoreError >= currentScore",
            "claimBoundary": "synthetic uncertainty-aware decision authority",
        }
        write(root / "decision-policy.json", decision_policy)

        ordinary_pass = execute(verifier, root, "pass", [benchmark()], 0)
        assert_decision_score(ordinary_pass, 0.9, "pass")
        assert_precision(ordinary_pass, "MEASURED", "pass")
        assert_row_status(ordinary_pass, "PASSED", "pass")
        if ordinary_pass.get("lowPrecisionCount") != 0:
            raise SystemExit("pass: unexpected low-precision measurement")

        uncertainty_below_threshold = execute(
            verifier,
            root,
            "uncertainty-below-threshold",
            [benchmark(score=1.4, score_error=0.2)],
            0,
        )
        assert_decision_score(
            uncertainty_below_threshold, 1.2, "uncertainty-below-threshold"
        )
        assert_row_status(
            uncertainty_below_threshold, "PASSED", "uncertainty-below-threshold"
        )

        boundary_pass = execute(
            verifier,
            root,
            "boundary-pass",
            [benchmark(score=1.5, score_error=0.0)],
            0,
        )
        assert_decision_score(boundary_pass, 1.5, "boundary-pass")
        assert_row_status(boundary_pass, "PASSED", "boundary-pass")

        clamped_pass = execute(
            verifier,
            root,
            "clamped-pass",
            [benchmark(score=0.1, score_error=0.2)],
            0,
        )
        assert_decision_score(clamped_pass, 0.0, "clamped-pass")
        assert_precision(clamped_pass, "LOW_PRECISION", "clamped-pass")
        assert_row_status(clamped_pass, "PASSED", "clamped-pass")
        if clamped_pass.get("lowPrecisionBenchmarks") != ["example.Benchmark.work"]:
            raise SystemExit("clamped-pass: low-precision evidence was not retained")

        overlap_inconclusive = execute(
            verifier,
            root,
            "overlap-inconclusive",
            [benchmark(score=1.6, score_error=0.2)],
            1,
            "INCONCLUSIVE",
        )
        assert_decision_score(overlap_inconclusive, 1.4, "overlap-inconclusive")
        assert_precision(overlap_inconclusive, "MEASURED", "overlap-inconclusive")
        assert_row_status(overlap_inconclusive, "INCONCLUSIVE", "overlap-inconclusive")
        if overlap_inconclusive.get("inconclusiveBenchmarks") != [
            "example.Benchmark.work"
        ]:
            raise SystemExit(
                "overlap-inconclusive: inconclusive benchmark evidence was not retained"
            )
        if overlap_inconclusive.get("violations"):
            raise SystemExit(
                "overlap-inconclusive: uncertainty overlap was misclassified as a violation"
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
        regression = execute(
            verifier,
            root,
            "regression",
            [benchmark(score=1.7, score_error=0.1)],
            1,
        )
        assert_row_status(regression, "FAILED", "regression")
        zero_error_regression = execute(
            verifier,
            root,
            "zero-error-regression",
            [benchmark(score=1.6, score_error=0.0)],
            1,
        )
        assert_row_status(
            zero_error_regression, "FAILED", "zero-error-regression"
        )

        missing_family = copy.deepcopy(threshold_policy)
        del missing_family["benchmarks"][0]["family"]
        execute_policy_failure(
            verifier,
            root,
            "missing-family",
            missing_family,
            decision_policy,
            "unsupported or missing family",
        )

        missing_error = copy.deepcopy(threshold_policy)
        del missing_error["benchmarks"][0]["baselineScoreError"]
        execute_policy_failure(
            verifier,
            root,
            "missing-baseline-error",
            missing_error,
            decision_policy,
            "baselineScoreError must be numeric",
        )

        wrong_decision = copy.deepcopy(decision_policy)
        wrong_decision["decisionStatistic"] = "currentScore"
        execute_policy_failure(
            verifier,
            root,
            "wrong-decision-statistic",
            threshold_policy,
            wrong_decision,
            "unsupported decision statistic",
        )

        wrong_inconclusive = copy.deepcopy(decision_policy)
        wrong_inconclusive["inconclusiveCondition"] = "currentScore > maximumAllowedScore"
        execute_policy_failure(
            verifier,
            root,
            "wrong-inconclusive-condition",
            threshold_policy,
            wrong_inconclusive,
            "unsupported inconclusive condition",
        )

        wrong_threshold_schema = copy.deepcopy(decision_policy)
        wrong_threshold_schema["thresholdPolicySchema"] = "regelsuche.quality.jmh-regression-policy/v1"
        execute_policy_failure(
            verifier,
            root,
            "wrong-threshold-schema",
            threshold_policy,
            wrong_threshold_schema,
            "thresholdPolicySchema differs",
        )

        execute_policy_failure(
            verifier,
            root,
            "wrong-threshold-path",
            threshold_policy,
            decision_policy,
            "threshold policy path differs",
            threshold_path_override="other-threshold-policy.json",
        )

        execute_policy_failure(
            verifier,
            root,
            "wrong-threshold-content",
            threshold_policy,
            decision_policy,
            "threshold policy content differs",
            threshold_blob_override="0" * 40,
        )

    print(
        "JMH regression verifier v3 characterization passed: "
        "4 passes, 1 inconclusive fail-closed case, 12 rejection cases"
    )


if __name__ == "__main__":
    main()
