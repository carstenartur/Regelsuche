#!/usr/bin/env python3
"""Verify scoped JMH GC-profiler evidence for AST and search benchmarks."""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
from typing import Any

REQUIRED = (
    "de.regelsuche.benchmark.RewriteProgramBenchmarks.directAstRewriteSource",
    "de.regelsuche.benchmark.RewriteProgramBenchmarks.preparedAstRewriteSource",
    "de.regelsuche.benchmark.PreparedAstSearchBenchmarks.referenceFixedWorkSearch",
    "de.regelsuche.benchmark.PreparedAstSearchBenchmarks.preparedFixedWorkSearch",
    "de.regelsuche.benchmark.PreparedAstSearchBenchmarks.referenceTargetedSearch",
    "de.regelsuche.benchmark.PreparedAstSearchBenchmarks.preparedTargetedSearch",
)
GC_ALLOC_RATE_NORM = "·gc.alloc.rate.norm"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--result", required=True, type=Path)
    parser.add_argument("--summary-output", required=True, type=Path)
    return parser.parse_args()


def finite_number(value: Any, label: str) -> float:
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        raise SystemExit(f"{label} must be numeric")
    number = float(value)
    if not math.isfinite(number) or number < 0.0:
        raise SystemExit(f"{label} must be finite and non-negative")
    return number


def metric(entry: dict[str, Any], name: str) -> dict[str, Any]:
    secondary = entry.get("secondaryMetrics")
    if not isinstance(secondary, dict):
        raise SystemExit(f"{entry.get('benchmark')}: secondaryMetrics missing")
    value = secondary.get(name)
    if not isinstance(value, dict):
        raise SystemExit(f"{entry.get('benchmark')}: {name} missing")
    return value


def main() -> None:
    args = parse_args()
    payload = json.loads(args.result.read_text(encoding="utf-8"))
    if not isinstance(payload, list):
        raise SystemExit("JMH allocation result must be a JSON array")

    by_name: dict[str, dict[str, Any]] = {}
    for entry in payload:
        if not isinstance(entry, dict) or not isinstance(entry.get("benchmark"), str):
            raise SystemExit("invalid JMH benchmark entry")
        by_name[entry["benchmark"]] = entry

    missing = sorted(set(REQUIRED) - set(by_name))
    if missing:
        raise SystemExit(f"required allocation benchmarks missing: {missing}")

    rows: list[dict[str, Any]] = []
    for name in REQUIRED:
        entry = by_name[name]
        primary = entry.get("primaryMetric")
        if not isinstance(primary, dict):
            raise SystemExit(f"{name}: primaryMetric missing")
        if primary.get("scoreUnit") != "ms/op":
            raise SystemExit(
                f"{name}: expected primary unit ms/op, found {primary.get('scoreUnit')}")
        score = finite_number(primary.get("score"), f"{name} primary score")

        allocation = metric(entry, GC_ALLOC_RATE_NORM)
        if allocation.get("scoreUnit") != "B/op":
            raise SystemExit(
                f"{name}: expected {GC_ALLOC_RATE_NORM} unit B/op, "
                f"found {allocation.get('scoreUnit')}")
        bytes_per_op = finite_number(
            allocation.get("score"), f"{name} {GC_ALLOC_RATE_NORM}")

        secondary = entry.get("secondaryMetrics", {})
        counters: dict[str, float] = {}
        for counter in (
            "searches",
            "exploredStates",
            "expandedStates",
            "generatedTransformations",
            "enqueuedStates",
            "reachedTargets",
        ):
            candidate = secondary.get(counter)
            if isinstance(candidate, dict) and "score" in candidate:
                counters[counter] = finite_number(
                    candidate["score"], f"{name} {counter}")

        rows.append(
            {
                "benchmark": name,
                "millisecondsPerOperation": score,
                "bytesPerOperation": bytes_per_op,
                "workCounters": counters,
            }
        )

    summary = {
        "schema": "regelsuche.jmh-allocation-summary/v1",
        "primaryUnit": "ms/op",
        "allocationUnit": "B/op",
        "benchmarks": rows,
    }
    args.summary_output.parent.mkdir(parents=True, exist_ok=True)
    args.summary_output.write_text(
        json.dumps(summary, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(
        f"verified {len(rows)} JMH allocation benchmarks: "
        f"{args.summary_output}"
    )


if __name__ == "__main__":
    main()
