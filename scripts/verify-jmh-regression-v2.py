#!/usr/bin/env python3
"""Verify the complete unit-aware JMH inventory against a finite v2 ratchet."""

from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path
from typing import Any

POLICY_SCHEMA = "regelsuche.quality.jmh-regression-policy/v2"
REPORT_SCHEMA = "regelsuche.quality.jmh-regression-report/v2"


def fail(message: str) -> None:
    raise SystemExit(f"JMH regression gate failed: {message}")


def load_json(path: Path, label: str) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"cannot read {label} {path}: {error}")


def finite_positive(value: Any, label: str) -> float:
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        fail(f"{label} must be numeric")
    number = float(value)
    if not math.isfinite(number) or number <= 0.0:
        fail(f"{label} must be finite and positive")
    return number


def integer(value: Any, label: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool):
        fail(f"{label} must be an integer")
    return value


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--result", "--results", dest="result", required=True, type=Path)
    parser.add_argument("--policy", required=True, type=Path)
    parser.add_argument(
        "--json-output", "--report-json", dest="json_output", required=True, type=Path
    )
    parser.add_argument(
        "--markdown-output",
        "--report-md",
        dest="markdown_output",
        required=True,
        type=Path,
    )
    args = parser.parse_args()

    policy = load_json(args.policy, "policy")
    if not isinstance(policy, dict) or policy.get("schema") != POLICY_SCHEMA:
        fail("unsupported policy schema")
    execution = policy.get("execution")
    configured = policy.get("benchmarks")
    if not isinstance(execution, dict):
        fail("execution policy must be an object")
    if not isinstance(configured, list) or not configured:
        fail("policy must contain benchmarks")

    policy_by_name: dict[str, dict[str, Any]] = {}
    for row in configured:
        if not isinstance(row, dict):
            fail("benchmark policies must be objects")
        name = row.get("benchmark")
        if not isinstance(name, str) or not name:
            fail("benchmark policy is missing benchmark")
        if name in policy_by_name:
            fail(f"duplicate benchmark policy: {name}")
        unit = row.get("unit")
        if unit not in {"us/op", "ms/op"}:
            fail(f"{name}: unsupported unit {unit}")
        baseline = finite_positive(row.get("baselineScore"), f"{name} baselineScore")
        maximum = finite_positive(
            row.get("maximumAllowedScore"), f"{name} maximumAllowedScore"
        )
        multiplier = finite_positive(
            row.get("maximumMultiplier"), f"{name} maximumMultiplier"
        )
        if maximum + 1e-12 < baseline or abs(maximum - baseline * multiplier) > max(
            1e-9, maximum * 1e-9
        ):
            fail(f"{name}: inconsistent finite threshold")
        policy_by_name[name] = row

    payload = load_json(args.result, "JMH result")
    if not isinstance(payload, list):
        fail("JMH result must be an array")
    current_by_name: dict[str, dict[str, Any]] = {}
    for entry in payload:
        if not isinstance(entry, dict):
            fail("JMH entries must be objects")
        name = entry.get("benchmark")
        if not isinstance(name, str) or not name:
            fail("JMH entry is missing benchmark")
        if name in current_by_name:
            fail(f"duplicate JMH result: {name}")
        current_by_name[name] = entry

    missing = sorted(set(policy_by_name) - set(current_by_name))
    unexpected = sorted(set(current_by_name) - set(policy_by_name))
    violations: list[str] = []
    if missing:
        violations.append("missing benchmarks: " + ", ".join(missing))
    if unexpected:
        violations.append("undeclared benchmarks: " + ", ".join(unexpected))

    rows: list[dict[str, Any]] = []
    expected_jdk = integer(execution.get("jdkMajor"), "execution.jdkMajor")
    for name in sorted(set(policy_by_name) & set(current_by_name)):
        expected = policy_by_name[name]
        entry = current_by_name[name]
        primary = entry.get("primaryMetric")
        if not isinstance(primary, dict):
            fail(f"{name}: primaryMetric missing")
        score = finite_positive(primary.get("score"), f"{name} score")
        score_error = finite_positive(primary.get("scoreError"), f"{name} scoreError")
        unit = primary.get("scoreUnit")
        row_violations: list[str] = []

        checks = (
            ("JMH version", entry.get("jmhVersion"), execution.get("jmhVersion")),
            ("mode", entry.get("mode"), execution.get("mode")),
            ("forks", entry.get("forks"), execution.get("forks")),
            (
                "warmup iterations",
                entry.get("warmupIterations"),
                execution.get("warmupIterations"),
            ),
            (
                "measurement iterations",
                entry.get("measurementIterations"),
                execution.get("measurementIterations"),
            ),
            ("unit", unit, expected.get("unit")),
        )
        for label, actual, required in checks:
            if actual != required:
                row_violations.append(
                    f"{label} differs: expected {required}, found {actual}"
                )

        jdk_version = str(entry.get("jdkVersion", ""))
        if not (
            jdk_version == str(expected_jdk)
            or jdk_version.startswith(f"{expected_jdk}.")
        ):
            row_violations.append(
                f"JDK major differs: expected {expected_jdk}, found {jdk_version}"
            )

        maximum = float(expected["maximumAllowedScore"])
        regression_percent = 100.0 * (
            score / float(expected["baselineScore"]) - 1.0
        )
        if score > maximum + max(1e-12, maximum * 1e-12):
            row_violations.append(
                f"score {score:.9f} {unit} exceeds {maximum:.9f} {unit}"
            )

        violations.extend(f"{name}: {item}" for item in row_violations)
        rows.append(
            {
                "benchmark": name,
                "family": expected["family"],
                "unit": unit,
                "baselineScore": expected["baselineScore"],
                "baselineScoreError": expected["baselineScoreError"],
                "currentScore": score,
                "currentScoreError": score_error,
                "maximumAllowedScore": maximum,
                "regressionPercent": round(regression_percent, 6),
                "status": "PASSED" if not row_violations else "FAILED",
                "violations": row_violations,
            }
        )

    report = {
        "schema": REPORT_SCHEMA,
        "policy": str(args.policy),
        "baselineRevision": policy.get("baselineRevision"),
        "baselineArtifactDigest": policy.get("baselineArtifactDigest"),
        "claimBoundary": policy.get("claimBoundary"),
        "status": "PASSED" if not violations else "FAILED",
        "benchmarkCount": len(rows),
        "missingBenchmarks": missing,
        "unexpectedBenchmarks": unexpected,
        "benchmarks": rows,
        "violations": violations,
    }
    args.json_output.parent.mkdir(parents=True, exist_ok=True)
    args.markdown_output.parent.mkdir(parents=True, exist_ok=True)
    args.json_output.write_text(
        json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )

    lines = [
        "# JMH regression gate v2",
        "",
        f"Status: **{report['status']}**",
        "",
        str(policy.get("claimBoundary", "")),
        "",
        "| Benchmark | Unit | Baseline | Current | Maximum | Change | Status |",
        "| --- | --- | ---: | ---: | ---: | ---: | --- |",
    ]
    for row in rows:
        lines.append(
            f"| `{row['benchmark']}` | `{row['unit']}` | "
            f"{row['baselineScore']:.6f} | {row['currentScore']:.6f} | "
            f"{row['maximumAllowedScore']:.6f} | "
            f"{row['regressionPercent']:+.2f}% | {row['status']} |"
        )
    if violations:
        lines.extend(["", "## Violations", ""])
        lines.extend(f"- {item}" for item in violations)
    args.markdown_output.write_text("\n".join(lines) + "\n", encoding="utf-8")

    print(f"jmhRegressionStatus={report['status']}")
    print(f"jmhRegressionBenchmarks={len(rows)}")
    print(f"jmhRegressionReport={args.json_output}")
    if violations:
        for violation in violations:
            print(f"jmhRegressionViolation={violation}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
