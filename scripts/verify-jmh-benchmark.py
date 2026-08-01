#!/usr/bin/env python3
"""Validate JMH evidence, enforce a unit-aware baseline and render reports."""

from __future__ import annotations

import argparse
import html
import json
import math
import re
import sys
from pathlib import Path
from typing import Any


def fail(message: str) -> None:
    raise SystemExit(f"JMH benchmark result invalid: {message}")


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def finite_number(value: Any, label: str, *, non_negative: bool = False) -> float:
    require(
        isinstance(value, (int, float)) and not isinstance(value, bool),
        f"{label} must be numeric",
    )
    number = float(value)
    require(math.isfinite(number), f"{label} must be finite")
    if non_negative:
        require(number >= 0.0, f"{label} must be non-negative")
    return number


def normalized_duration(value: Any, label: str) -> str:
    require(isinstance(value, str) and value.strip(), f"{label} must be a duration string")
    normalized = re.sub(r"\s+", "", value).lower()
    require(re.fullmatch(r"[1-9][0-9]*(ns|us|ms|s|m|h)", normalized) is not None,
            f"{label} has unsupported duration syntax: {value}")
    return normalized


def load_json(path: Path, label: str) -> Any:
    require(path.is_file(), f"missing {label}: {path}")
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"cannot read {label} {path}: {error}")


def load_baseline(path: Path) -> dict[str, Any]:
    baseline = load_json(path, "JMH baseline")
    require(isinstance(baseline, dict), "baseline must be an object")
    require(
        baseline.get("schema") == "regelsuche.quality.jmh-baseline/v2",
        "unsupported baseline schema",
    )
    revision = baseline.get("baselineRevision")
    require(isinstance(revision, str) and len(revision) == 40, "invalid baselineRevision")
    policy = baseline.get("measurementPolicy")
    require(isinstance(policy, dict), "measurementPolicy missing")
    require(policy.get("mode") == "avgt", "baseline mode must be avgt")
    for field in ("forks", "warmupIterations", "measurementIterations"):
        require(
            isinstance(policy.get(field), int) and policy[field] >= 1,
            f"measurementPolicy.{field} must be positive",
        )
    normalized_duration(policy.get("warmupTime"), "measurementPolicy.warmupTime")
    normalized_duration(policy.get("measurementTime"), "measurementPolicy.measurementTime")
    ratio = finite_number(
        policy.get("materialRegressionRatio"),
        "materialRegressionRatio",
        non_negative=True,
    )
    require(ratio > 1.0, "materialRegressionRatio must be greater than one")
    benchmarks = baseline.get("benchmarks")
    require(isinstance(benchmarks, dict) and benchmarks, "baseline benchmarks missing")
    for benchmark, value in benchmarks.items():
        require(isinstance(benchmark, str) and benchmark, "invalid baseline benchmark id")
        require(isinstance(value, dict), f"{benchmark} baseline is not an object")
        require(
            isinstance(value.get("scoreUnit"), str) and value["scoreUnit"],
            f"{benchmark} baseline scoreUnit missing",
        )
        finite_number(
            value.get("baselineScore"),
            f"{benchmark} baselineScore",
            non_negative=True,
        )
    return baseline


def validate_results(
    results: Any,
    baseline: dict[str, Any],
) -> list[dict[str, Any]]:
    require(isinstance(results, list), "top-level result must be an array")
    require(bool(results), "no benchmark results were retained")
    policy = baseline["measurementPolicy"]
    expected = baseline["benchmarks"]
    by_name: dict[str, dict[str, Any]] = {}

    for index, result in enumerate(results):
        require(isinstance(result, dict), f"result {index} is not an object")
        benchmark = result.get("benchmark")
        require(
            isinstance(benchmark, str) and benchmark,
            f"result {index} has no benchmark id",
        )
        require(not result.get("params"), f"{benchmark} unexpectedly has parameters")
        require(benchmark not in by_name, f"duplicate benchmark identity: {benchmark}")
        require(result.get("mode") == policy["mode"], f"{benchmark} mode differs")
        require(result.get("forks") == policy["forks"], f"{benchmark} forks differ")
        require(result.get("threads") == 1, f"{benchmark} threads differ")
        require(result.get("warmupBatchSize") == 1, f"{benchmark} warmupBatchSize differs")
        require(result.get("measurementBatchSize") == 1,
                f"{benchmark} measurementBatchSize differs")
        require(
            result.get("warmupIterations") == policy["warmupIterations"],
            f"{benchmark} warmupIterations differ",
        )
        require(
            result.get("measurementIterations") == policy["measurementIterations"],
            f"{benchmark} measurementIterations differ",
        )
        require(
            normalized_duration(result.get("warmupTime"), f"{benchmark} warmupTime")
            == normalized_duration(policy["warmupTime"], "measurementPolicy.warmupTime"),
            f"{benchmark} warmupTime differs",
        )
        require(
            normalized_duration(result.get("measurementTime"), f"{benchmark} measurementTime")
            == normalized_duration(policy["measurementTime"], "measurementPolicy.measurementTime"),
            f"{benchmark} measurementTime differs",
        )
        metric = result.get("primaryMetric")
        require(isinstance(metric, dict), f"{benchmark} has no primaryMetric")
        score = finite_number(metric.get("score"), f"{benchmark} score", non_negative=True)
        score_error = finite_number(
            metric.get("scoreError"),
            f"{benchmark} scoreError",
            non_negative=True,
        )
        unit = metric.get("scoreUnit")
        require(isinstance(unit, str) and unit, f"{benchmark} has no score unit")
        by_name[benchmark] = {
            "result": result,
            "score": score,
            "scoreError": score_error,
            "scoreUnit": unit,
        }

    actual_names = set(by_name)
    expected_names = set(expected)
    missing = sorted(expected_names - actual_names)
    undeclared = sorted(actual_names - expected_names)
    require(not missing, f"baseline benchmarks missing from result: {missing}")
    require(not undeclared, f"undeclared benchmarks in result: {undeclared}")

    rows: list[dict[str, Any]] = []
    violations: list[str] = []
    ratio = float(policy["materialRegressionRatio"])
    for benchmark in sorted(expected):
        current = by_name[benchmark]
        declared = expected[benchmark]
        require(
            current["scoreUnit"] == declared["scoreUnit"],
            f"{benchmark} unit differs: {current['scoreUnit']} != {declared['scoreUnit']}",
        )
        baseline_score = float(declared["baselineScore"])
        threshold = baseline_score * ratio
        lower_bound = max(0.0, current["score"] - current["scoreError"])
        regressed = lower_bound > threshold
        if regressed:
            violations.append(
                f"{benchmark}: lowerBound={lower_bound:.9g} {current['scoreUnit']} "
                f"> threshold={threshold:.9g} {current['scoreUnit']}"
            )
        rows.append(
            {
                "benchmark": benchmark,
                "mode": current["result"]["mode"],
                "score": current["score"],
                "scoreError": current["scoreError"],
                "scoreUnit": current["scoreUnit"],
                "baselineScore": baseline_score,
                "threshold": threshold,
                "lowerBound": lower_bound,
                "scoreRatio": current["score"] / baseline_score if baseline_score else 0.0,
                "status": "REGRESSION" if regressed else "PASS",
            }
        )
    require(not violations, "material regressions: " + "; ".join(violations))
    return rows


def render_report(
    rows: list[dict[str, Any]],
    output: Path,
    baseline_revision: str,
) -> None:
    html_rows: list[str] = []
    for row in rows:
        html_rows.append(
            "<tr>"
            f"<td><code>{html.escape(row['benchmark'])}</code></td>"
            f"<td>{html.escape(row['mode'])}</td>"
            f"<td>{row['score']:.6g} ± {row['scoreError']:.3g}</td>"
            f"<td>{html.escape(row['scoreUnit'])}</td>"
            f"<td>{row['baselineScore']:.6g}</td>"
            f"<td>{row['scoreRatio']:.3f}×</td>"
            f"<td>{html.escape(row['status'])}</td>"
            "</tr>"
        )
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
        "<title>Regelsuche JMH Benchmarks</title>"
        "<style>body{font-family:system-ui,sans-serif;margin:2rem;max-width:1400px}"
        "table{border-collapse:collapse;width:100%}td,th{border:1px solid #ddd;"
        "padding:.5rem;text-align:left}th{background:#f6f8fa}code{font-size:.9em}"
        "</style></head><body><h1>Regelsuche JMH Benchmarks</h1>"
        f"<p>{len(rows)} unit-aware benchmark identities. Baseline revision "
        f"<code>{html.escape(baseline_revision)}</code>.</p>"
        "<table><thead><tr><th>Benchmark</th><th>Mode</th><th>Current</th>"
        "<th>Unit</th><th>Baseline</th><th>Ratio</th><th>Status</th>"
        "</tr></thead><tbody>"
        + "\n".join(html_rows)
        + "</tbody></table></body></html>\n",
        encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--result", type=Path, default=Path("app/build/reports/jmh/result.json"))
    parser.add_argument("--baseline", type=Path, default=Path("config/quality/jmh-baseline.json"))
    parser.add_argument("--badge-output", type=Path, default=Path("public/dev/bench/badge.json"))
    parser.add_argument("--report-output", type=Path, default=Path("public/dev/bench/index.html"))
    args = parser.parse_args()

    baseline = load_baseline(args.baseline)
    results = load_json(args.result, "canonical JMH result")
    rows = validate_results(results, baseline)

    badge = {
        "schemaVersion": 1,
        "label": "performance",
        "message": f"{len(rows)} benchmarks stable",
        "color": "brightgreen",
    }
    args.badge_output.parent.mkdir(parents=True, exist_ok=True)
    args.badge_output.write_text(
        json.dumps(badge, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )
    render_report(rows, args.report_output, baseline["baselineRevision"])

    print(f"jmhResult={args.result}")
    print(f"jmhBaseline={args.baseline}")
    print(f"benchmarkCount={len(rows)}")
    print(f"performanceBadge={args.badge_output}")
    print(f"performanceReport={args.report_output}")
    print("jmh-benchmark-contract=valid")
    return 0


if __name__ == "__main__":
    sys.exit(main())
