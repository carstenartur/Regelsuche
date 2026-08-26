#!/usr/bin/env python3
"""Validate and render native/GraalPy/CPython factorization measurements."""

from __future__ import annotations

import argparse
import html
import json
import math
from pathlib import Path
from typing import Any

PREFIX = "de.regelsuche.math.sympy.SymPyFactorizationBenchmarks."
EXPECTED = {
    PREFIX + "nativeBackendWarm": "native-backend-warm",
    PREFIX + "nativeEndToEndWarm": "native-end-to-end-warm",
    PREFIX + "graalPyBackendWarm": "graalpy-backend-warm",
    PREFIX + "graalPyEndToEndWarm": "graalpy-end-to-end-warm",
    PREFIX + "graalPyEndToEndCold": "graalpy-end-to-end-cold",
    PREFIX + "cpythonOneShotEndToEnd": "cpython-one-shot-end-to-end",
}


def fail(message: str) -> None:
    raise SystemExit(f"SymPy factorization benchmark invalid: {message}")


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def number(value: Any, label: str) -> float:
    require(
        isinstance(value, (int, float)) and not isinstance(value, bool),
        f"{label} must be numeric",
    )
    result = float(value)
    require(math.isfinite(result) and result >= 0.0,
            f"{label} must be finite and nonnegative")
    return result


def load(path: Path) -> Any:
    require(path.is_file(), f"missing JMH result: {path}")
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"cannot read {path}: {error}")


def measurements(result: Any) -> dict[str, dict[str, Any]]:
    require(isinstance(result, list) and result,
            "top-level JMH result must be a nonempty array")
    retained: dict[str, dict[str, Any]] = {}
    for index, entry in enumerate(result):
        require(isinstance(entry, dict), f"entry {index} must be an object")
        benchmark = entry.get("benchmark")
        require(benchmark in EXPECTED,
                f"undeclared benchmark identity: {benchmark!r}")
        require(benchmark not in retained,
                f"duplicate benchmark identity: {benchmark}")
        require(entry.get("mode") == "avgt",
                f"{benchmark} must use average time")
        require(entry.get("threads") == 1,
                f"{benchmark} must use one thread")
        metric = entry.get("primaryMetric")
        require(isinstance(metric, dict),
                f"{benchmark} has no primaryMetric")
        require(metric.get("scoreUnit") == "ms/op",
                f"{benchmark} must report ms/op")
        retained[benchmark] = {
            "id": EXPECTED[benchmark],
            "benchmark": benchmark,
            "scoreMillis": number(metric.get("score"), benchmark + ".score"),
            "scoreErrorMillis": number(
                metric.get("scoreError"), benchmark + ".scoreError"),
            "forks": entry.get("forks"),
            "warmupIterations": entry.get("warmupIterations"),
            "measurementIterations": entry.get("measurementIterations"),
            "warmupTime": entry.get("warmupTime"),
            "measurementTime": entry.get("measurementTime"),
        }
    require(set(retained) == set(EXPECTED),
            "benchmark matrix is incomplete")
    return retained


def ratio(numerator: float, denominator: float) -> float | None:
    return None if denominator == 0.0 else numerator / denominator


def summary(retained: dict[str, dict[str, Any]]) -> dict[str, Any]:
    by_id = {entry["id"]: entry for entry in retained.values()}
    native_backend = by_id["native-backend-warm"]["scoreMillis"]
    native_e2e = by_id["native-end-to-end-warm"]["scoreMillis"]
    graal_backend = by_id["graalpy-backend-warm"]["scoreMillis"]
    graal_e2e = by_id["graalpy-end-to-end-warm"]["scoreMillis"]
    graal_cold = by_id["graalpy-end-to-end-cold"]["scoreMillis"]
    cpython = by_id["cpython-one-shot-end-to-end"]["scoreMillis"]
    return {
        "schema": "regelsuche.sympy-factorization-performance/v1",
        "claimPolicy": "DIAGNOSTIC_TRACKS_NO_RELATIVE_WINNER_GATE",
        "sharedCase": "binary-homogeneous-quartic-x4-plus-4y4",
        "measurements": sorted(by_id.values(), key=lambda item: item["id"]),
        "ratios": {
            "graalpyWarmToNativeWarmEndToEnd": ratio(graal_e2e, native_e2e),
            "graalpyColdToWarmEndToEnd": ratio(graal_cold, graal_e2e),
            "cpythonOneShotToGraalpyWarmEndToEnd": ratio(cpython, graal_e2e),
            "nativeVerifierInclusiveToBackend": ratio(native_e2e, native_backend),
            "graalpyVerifierInclusiveToBackend": ratio(graal_e2e, graal_backend),
        },
        "interpretation": [
            "warm embedded measurements reuse an initialized GraalPy context",
            "cold embedded measurements include context creation and SymPy import",
            "CPython one-shot measurements include process and interpreter startup",
            "backend tracks exclude the common Regelsuche product verifier",
            "end-to-end tracks include the same FactorizationVerifier boundary",
            "timings are environment-specific engineering diagnostics, not mathematical evidence",
        ],
    }


def render(report: dict[str, Any], output: Path) -> None:
    rows = []
    for item in report["measurements"]:
        rows.append(
            "<tr>"
            f"<td><code>{html.escape(item['id'])}</code></td>"
            f"<td>{item['scoreMillis']:.6g}</td>"
            f"<td>{item['scoreErrorMillis']:.3g}</td>"
            "</tr>"
        )
    ratio_rows = []
    for name, value in report["ratios"].items():
        rendered = "n/a" if value is None else f"{value:.6g}×"
        ratio_rows.append(
            "<tr>"
            f"<td><code>{html.escape(name)}</code></td>"
            f"<td>{rendered}</td>"
            "</tr>"
        )
    notes = "".join(
        f"<li>{html.escape(note)}</li>"
        for note in report["interpretation"]
    )
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
        "<title>Regelsuche SymPy factorization performance</title>"
        "<style>body{font-family:system-ui,sans-serif;margin:2rem;max-width:1200px}"
        "table{border-collapse:collapse;width:100%;margin-bottom:2rem}"
        "td,th{border:1px solid #ddd;padding:.5rem;text-align:left}"
        "th{background:#f6f8fa}code{font-size:.9em}</style></head><body>"
        "<h1>Regelsuche SymPy factorization performance</h1>"
        "<p>One exact shared quartic request, separated by runtime and verifier boundary. "
        "No relative winner threshold is enforced.</p>"
        "<h2>Measurements</h2><table><thead><tr><th>Track</th>"
        "<th>ms/op</th><th>error</th></tr></thead><tbody>"
        + "\n".join(rows)
        + "</tbody></table><h2>Ratios</h2><table><thead><tr>"
        "<th>Ratio</th><th>Value</th></tr></thead><tbody>"
        + "\n".join(ratio_rows)
        + "</tbody></table><h2>Interpretation boundary</h2><ul>"
        + notes
        + "</ul></body></html>\n",
        encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--result", required=True, type=Path)
    parser.add_argument("--summary-output", required=True, type=Path)
    parser.add_argument("--report-output", required=True, type=Path)
    args = parser.parse_args()

    report = summary(measurements(load(args.result)))
    args.summary_output.parent.mkdir(parents=True, exist_ok=True)
    args.summary_output.write_text(
        json.dumps(report, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    render(report, args.report_output)
    print(f"sympyFactorizationBenchmark={args.result}")
    print(f"sympyFactorizationSummary={args.summary_output}")
    print(f"sympyFactorizationReport={args.report_output}")
    print("sympy-factorization-benchmark-contract=valid")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
