#!/usr/bin/env python3
"""Validate the canonical JMH result and render checkout-local reports."""

from __future__ import annotations

import argparse
import html
import json
import math
import sys
from pathlib import Path


def fail(message: str) -> None:
    raise SystemExit(f"JMH benchmark result invalid: {message}")


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def render_report(results: list[dict], output: Path) -> None:
    rows: list[str] = []
    for result in sorted(
        results,
        key=lambda item: (
            item["benchmark"],
            json.dumps(item.get("params", {}), sort_keys=True, separators=(",", ":")),
        ),
    ):
        metric = result["primaryMetric"]
        params = json.dumps(result.get("params", {}), sort_keys=True)
        rows.append(
            "<tr>"
            f"<td><code>{html.escape(result['benchmark'])}</code></td>"
            f"<td>{html.escape(result['mode'])}</td>"
            f"<td>{html.escape(format(metric['score'], '.6g'))}</td>"
            f"<td>{html.escape(metric['scoreUnit'])}</td>"
            f"<td><code>{html.escape(params)}</code></td>"
            "</tr>"
        )
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
        "<title>Regelsuche JMH Benchmarks</title>"
        "<style>body{font-family:system-ui,sans-serif;margin:2rem;max-width:1200px}"
        "table{border-collapse:collapse;width:100%}td,th{border:1px solid #ddd;"
        "padding:.5rem;text-align:left}th{background:#f6f8fa}code{font-size:.9em}"
        "</style></head><body><h1>Regelsuche JMH Benchmarks</h1>"
        f"<p>{len(results)} validated benchmark configurations from the latest "
        "successful <code>ciCheck</code> run.</p>"
        "<table><thead><tr><th>Benchmark</th><th>Mode</th><th>Score</th>"
        "<th>Unit</th><th>Parameters</th></tr></thead><tbody>"
        + "\n".join(rows)
        + "</tbody></table></body></html>\n",
        encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--result",
        type=Path,
        default=Path("app/build/reports/jmh/result.json"),
    )
    parser.add_argument(
        "--badge-output",
        type=Path,
        default=Path("public/dev/bench/badge.json"),
    )
    parser.add_argument(
        "--report-output",
        type=Path,
        default=Path("public/dev/bench/index.html"),
    )
    args = parser.parse_args()

    require(args.result.is_file(), f"missing canonical result: {args.result}")
    try:
        results = json.loads(args.result.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"cannot read {args.result}: {error}")

    require(isinstance(results, list), "top-level result must be an array")
    require(bool(results), "no benchmark results were retained")

    identities: set[tuple[str, str]] = set()
    for index, result in enumerate(results):
        require(isinstance(result, dict), f"result {index} is not an object")
        benchmark = result.get("benchmark")
        require(
            isinstance(benchmark, str) and bool(benchmark),
            f"result {index} has no benchmark id",
        )
        mode = result.get("mode")
        require(isinstance(mode, str) and bool(mode), f"{benchmark} has no benchmark mode")
        metric = result.get("primaryMetric")
        require(isinstance(metric, dict), f"{benchmark} has no primaryMetric")
        score = metric.get("score")
        require(
            isinstance(score, (int, float))
            and not isinstance(score, bool)
            and math.isfinite(float(score)),
            f"{benchmark} has a non-finite primary score",
        )
        unit = metric.get("scoreUnit")
        require(isinstance(unit, str) and bool(unit), f"{benchmark} has no score unit")
        params = result.get("params", {})
        require(isinstance(params, dict), f"{benchmark} params is not an object")
        parameter_identity = json.dumps(params, sort_keys=True, separators=(",", ":"))
        identity = (benchmark, parameter_identity)
        require(
            identity not in identities,
            f"duplicate benchmark/parameter identity: {benchmark}",
        )
        identities.add(identity)

    badge = {
        "schemaVersion": 1,
        "label": "performance",
        "message": f"{len(results)} benchmarks",
        "color": "blue",
    }
    args.badge_output.parent.mkdir(parents=True, exist_ok=True)
    args.badge_output.write_text(
        json.dumps(badge, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )
    render_report(results, args.report_output)

    print(f"jmhResult={args.result}")
    print(f"benchmarkCount={len(results)}")
    print(f"performanceBadge={args.badge_output}")
    print(f"performanceReport={args.report_output}")
    print("jmh-benchmark-contract=valid")
    return 0


if __name__ == "__main__":
    sys.exit(main())
