#!/usr/bin/env python3
"""Render deterministic checkout-owned JMH history evidence.

The input snapshots are immutable, checksum-bound measurements. Every chart uses
milliseconds per operation and the same direction: lower on the chart is faster.
"""
from __future__ import annotations

import argparse
import hashlib
import html
import json
import math
import re
import sys
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

POLICY_SCHEMA = "regelsuche.quality.jmh-history-policy/v1"
SNAPSHOT_SCHEMA = "regelsuche.quality.jmh-history-snapshot/v1"
OUTPUT_SCHEMA = "regelsuche.quality.jmh-history/v1"
SHA256_RE = re.compile(r"^sha256:[0-9a-f]{64}$")
REVISION_RE = re.compile(r"^[0-9a-f]{40}$")
UNIT_TO_MS = {
    "ns/op": 1e-6,
    "us/op": 1e-3,
    "ms/op": 1.0,
    "s/op": 1e3,
}


class HistoryError(ValueError):
    pass


def _load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise HistoryError(f"cannot read JSON {path}: {exc}") from exc


def _sha256_bytes(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def _parse_timestamp(value: object, location: str) -> datetime:
    if not isinstance(value, str) or not value.endswith("Z"):
        raise HistoryError(f"{location} must be an ISO-8601 UTC timestamp ending in Z")
    try:
        return datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as exc:
        raise HistoryError(f"{location} is not a valid timestamp: {value}") from exc


def _require_number(value: object, location: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise HistoryError(f"{location} must be numeric")
    converted = float(value)
    if not math.isfinite(converted) or converted < 0.0:
        raise HistoryError(f"{location} must be finite and non-negative")
    return converted


def _format_ms(value: float) -> str:
    if value == 0:
        return "0"
    absolute = abs(value)
    if absolute < 0.001 or absolute >= 10000:
        return f"{value:.4e}"
    if absolute < 0.1:
        return f"{value:.6f}".rstrip("0").rstrip(".")
    if absolute < 10:
        return f"{value:.4f}".rstrip("0").rstrip(".")
    return f"{value:.3f}".rstrip("0").rstrip(".")


def _safe_name(benchmark: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", benchmark.lower()).strip("-")


@dataclass(frozen=True)
class Point:
    label: str
    recorded_at: str
    source_revision: str
    score_ms: float
    error_ms: float


@dataclass(frozen=True)
class BenchmarkHistory:
    benchmark: str
    family: str
    source_unit: str
    points: tuple[Point, ...]


def _render_svg(history: BenchmarkHistory, destination: Path) -> None:
    width, height = 960, 360
    left, right, top, bottom = 100, 30, 70, 75
    plot_width = width - left - right
    plot_height = height - top - bottom
    values = [p.score_ms + p.error_ms for p in history.points]
    y_max = max(values) if values else 1.0
    if y_max <= 0.0:
        y_max = 1.0
    y_max *= 1.10

    def x_pos(index: int) -> float:
        if len(history.points) == 1:
            return left + plot_width / 2
        return left + plot_width * index / (len(history.points) - 1)

    def y_pos(value: float) -> float:
        bounded = max(0.0, min(value, y_max))
        return top + plot_height * (1.0 - bounded / y_max)

    def line(x1: float, y1: float, x2: float, y2: float, extra: str = "") -> str:
        return (
            f'<line x1="{x1:.2f}" y1="{y1:.2f}" x2="{x2:.2f}" y2="{y2:.2f}" '
            f'stroke="currentColor" stroke-width="1" {extra}/>'
        )

    parts = [
        '<svg xmlns="http://www.w3.org/2000/svg" '
        f'viewBox="0 0 {width} {height}" role="img" '
        f'aria-labelledby="title description">',
        '<style>text{font-family:system-ui,sans-serif;fill:currentColor}'
        '.axis{font-size:12px}.title{font-size:17px;font-weight:600}'
        '.subtitle{font-size:13px}.point{fill:currentColor}</style>',
        f'<title id="title">{html.escape(history.benchmark)}</title>',
        '<desc id="description">Historische mittlere Laufzeit in Millisekunden pro Operation. '
        'Niedrigere Punkte sind schneller und besser.</desc>',
        f'<text class="title" x="{left}" y="27">{html.escape(history.benchmark)}</text>',
        f'<text class="subtitle" x="{left}" y="49">ms/op — unten ist schneller/besser; Fehlerbalken: JMH scoreError</text>',
        line(left, top, left, top + plot_height),
        line(left, top + plot_height, left + plot_width, top + plot_height),
    ]

    for tick in range(5):
        value = y_max * tick / 4
        y = y_pos(value)
        parts.append(line(left - 5, y, left + plot_width, y, 'stroke-opacity="0.18"'))
        parts.append(
            f'<text class="axis" x="{left - 9}" y="{y + 4:.2f}" text-anchor="end">'
            f'{html.escape(_format_ms(value))}</text>'
        )

    polyline_points = " ".join(
        f"{x_pos(i):.2f},{y_pos(point.score_ms):.2f}"
        for i, point in enumerate(history.points)
    )
    parts.append(
        f'<polyline points="{polyline_points}" fill="none" stroke="currentColor" '
        'stroke-width="2"/>'
    )

    for i, point in enumerate(history.points):
        x = x_pos(i)
        y = y_pos(point.score_ms)
        upper = y_pos(point.score_ms + point.error_ms)
        lower = y_pos(max(0.0, point.score_ms - point.error_ms))
        parts.extend([
            line(x, upper, x, lower),
            line(x - 5, upper, x + 5, upper),
            line(x - 5, lower, x + 5, lower),
            f'<circle class="point" cx="{x:.2f}" cy="{y:.2f}" r="4"/>',
            f'<text class="axis" x="{x:.2f}" y="{top + plot_height + 22}" text-anchor="middle">'
            f'{html.escape(point.recorded_at[:10])}</text>',
            f'<text class="axis" x="{x:.2f}" y="{top + plot_height + 40}" text-anchor="middle">'
            f'{html.escape(_format_ms(point.score_ms))}</text>',
        ])

    parts.append('</svg>')
    destination.write_text("\n".join(parts) + "\n", encoding="utf-8")


def render(history_policy_path: Path, regression_policy_path: Path, output_dir: Path) -> dict[str, Any]:
    policy_bytes = history_policy_path.read_bytes()
    policy = _load_json(history_policy_path)
    if not isinstance(policy, dict) or policy.get("schema") != POLICY_SCHEMA:
        raise HistoryError(f"history policy schema must be {POLICY_SCHEMA}")
    if policy.get("normalizedUnit") != "ms/op":
        raise HistoryError("history policy normalizedUnit must be ms/op")
    if policy.get("lowerIsBetter") is not True:
        raise HistoryError("history policy must declare lowerIsBetter=true")
    snapshot_specs = policy.get("snapshots")
    if not isinstance(snapshot_specs, list) or len(snapshot_specs) < 2:
        raise HistoryError("history policy must retain at least two snapshots")

    regression = _load_json(regression_policy_path)
    if not isinstance(regression, dict):
        raise HistoryError("regression policy must be an object")
    declared_entries = regression.get("benchmarks")
    if not isinstance(declared_entries, list) or not declared_entries:
        raise HistoryError("regression policy must declare benchmarks")
    declared: dict[str, tuple[str, str]] = {}
    for index, entry in enumerate(declared_entries):
        if not isinstance(entry, dict):
            raise HistoryError(f"regression benchmark {index} must be an object")
        benchmark = entry.get("benchmark")
        family = entry.get("family")
        unit = entry.get("unit")
        if not all(isinstance(v, str) and v for v in (benchmark, family, unit)):
            raise HistoryError(f"regression benchmark {index} has invalid identity/family/unit")
        if benchmark in declared:
            raise HistoryError(f"duplicate regression benchmark: {benchmark}")
        if unit not in UNIT_TO_MS:
            raise HistoryError(f"unsupported regression unit for {benchmark}: {unit}")
        declared[benchmark] = (family, unit)

    snapshots: list[dict[str, Any]] = []
    observed_labels: set[str] = set()
    observed_revisions: set[str] = set()
    previous_timestamp: datetime | None = None
    reference_execution: dict[str, Any] | None = None

    for index, spec in enumerate(snapshot_specs):
        if not isinstance(spec, dict):
            raise HistoryError(f"snapshot policy entry {index} must be an object")
        relative = spec.get("path")
        expected_digest = spec.get("sha256")
        if not isinstance(relative, str) or not relative:
            raise HistoryError(f"snapshot policy entry {index} path is invalid")
        if not isinstance(expected_digest, str) or not SHA256_RE.fullmatch(expected_digest):
            raise HistoryError(f"snapshot policy entry {index} sha256 is invalid")
        snapshot_path = history_policy_path.parent.parent.parent / relative
        try:
            snapshot_bytes = snapshot_path.read_bytes()
        except OSError as exc:
            raise HistoryError(f"cannot read retained snapshot {relative}: {exc}") from exc
        actual_digest = _sha256_bytes(snapshot_bytes)
        if actual_digest != expected_digest:
            raise HistoryError(
                f"snapshot digest mismatch for {relative}: expected {expected_digest}, found {actual_digest}"
            )
        snapshot = json.loads(snapshot_bytes)
        if not isinstance(snapshot, dict) or snapshot.get("schema") != SNAPSHOT_SCHEMA:
            raise HistoryError(f"snapshot {relative} schema must be {SNAPSHOT_SCHEMA}")
        label = snapshot.get("label")
        recorded_at = snapshot.get("recordedAt")
        source_revision = snapshot.get("sourceRevision")
        if not isinstance(label, str) or not label.strip():
            raise HistoryError(f"snapshot {relative} label is invalid")
        if label in observed_labels:
            raise HistoryError(f"duplicate snapshot label: {label}")
        observed_labels.add(label)
        timestamp = _parse_timestamp(recorded_at, f"snapshot {relative} recordedAt")
        if previous_timestamp is not None and timestamp <= previous_timestamp:
            raise HistoryError("snapshots must be strictly chronological in policy order")
        previous_timestamp = timestamp
        if not isinstance(source_revision, str) or not REVISION_RE.fullmatch(source_revision):
            raise HistoryError(f"snapshot {relative} sourceRevision is invalid")
        if source_revision in observed_revisions:
            raise HistoryError(f"duplicate snapshot sourceRevision: {source_revision}")
        observed_revisions.add(source_revision)

        execution = snapshot.get("execution")
        if not isinstance(execution, dict):
            raise HistoryError(f"snapshot {relative} execution must be an object")
        comparable_execution = {
            key: execution.get(key)
            for key in (
                "mode", "forks", "threads", "warmupIterations",
                "measurementIterations", "jmhVersion", "jdkMajor"
            )
        }
        if reference_execution is None:
            reference_execution = comparable_execution
        elif comparable_execution != reference_execution:
            raise HistoryError(
                f"snapshot {relative} execution contract differs from the first retained snapshot"
            )

        entries = snapshot.get("benchmarks")
        if not isinstance(entries, list):
            raise HistoryError(f"snapshot {relative} benchmarks must be an array")
        by_name: dict[str, dict[str, Any]] = {}
        for entry_index, entry in enumerate(entries):
            if not isinstance(entry, dict):
                raise HistoryError(f"snapshot {relative} benchmark {entry_index} must be an object")
            benchmark = entry.get("benchmark")
            if not isinstance(benchmark, str) or not benchmark:
                raise HistoryError(f"snapshot {relative} benchmark {entry_index} identity is invalid")
            if benchmark in by_name:
                raise HistoryError(f"snapshot {relative} duplicates benchmark {benchmark}")
            by_name[benchmark] = entry
        missing = sorted(set(declared) - set(by_name))
        unexpected = sorted(set(by_name) - set(declared))
        if missing or unexpected:
            raise HistoryError(
                f"snapshot {relative} benchmark inventory differs: missing={missing}, unexpected={unexpected}"
            )

        normalized_entries: list[dict[str, Any]] = []
        for benchmark in sorted(declared):
            expected_family, expected_unit = declared[benchmark]
            entry = by_name[benchmark]
            family = entry.get("family")
            unit = entry.get("unit")
            if family != expected_family:
                raise HistoryError(
                    f"snapshot {relative} family differs for {benchmark}: {family} != {expected_family}"
                )
            if unit != expected_unit:
                raise HistoryError(
                    f"snapshot {relative} unit differs for {benchmark}: {unit} != {expected_unit}"
                )
            score = _require_number(entry.get("score"), f"{relative}:{benchmark}:score")
            error = _require_number(entry.get("scoreError"), f"{relative}:{benchmark}:scoreError")
            factor = UNIT_TO_MS[unit]
            normalized_entries.append({
                "benchmark": benchmark,
                "family": family,
                "sourceUnit": unit,
                "scoreMsPerOp": score * factor,
                "scoreErrorMsPerOp": error * factor,
            })
        snapshots.append({
            "label": label,
            "recordedAt": recorded_at,
            "sourceRevision": source_revision,
            "sourceArtifactDigest": snapshot.get("sourceArtifactDigest"),
            "snapshotPath": relative,
            "snapshotDigest": actual_digest,
            "benchmarks": normalized_entries,
        })

    histories: list[BenchmarkHistory] = []
    for benchmark in sorted(declared):
        family, unit = declared[benchmark]
        points: list[Point] = []
        for snapshot in snapshots:
            entry = next(item for item in snapshot["benchmarks"] if item["benchmark"] == benchmark)
            points.append(Point(
                label=snapshot["label"],
                recorded_at=snapshot["recordedAt"],
                source_revision=snapshot["sourceRevision"],
                score_ms=entry["scoreMsPerOp"],
                error_ms=entry["scoreErrorMsPerOp"],
            ))
        histories.append(BenchmarkHistory(benchmark, family, unit, tuple(points)))

    output_dir.mkdir(parents=True, exist_ok=True)
    charts_dir = output_dir / "charts"
    charts_dir.mkdir(parents=True, exist_ok=True)
    chart_files: dict[str, str] = {}
    for history in histories:
        filename = _safe_name(history.benchmark) + ".svg"
        _render_svg(history, charts_dir / filename)
        chart_files[history.benchmark] = f"charts/{filename}"

    normalized_output = {
        "schema": OUTPUT_SCHEMA,
        "status": "PASSED",
        "claimBoundary": policy.get("claimBoundary"),
        "normalizedUnit": "ms/op",
        "lowerIsBetter": True,
        "historyPolicyDigest": _sha256_bytes(policy_bytes),
        "regressionPolicyDigest": _sha256_bytes(regression_policy_path.read_bytes()),
        "snapshotCount": len(snapshots),
        "benchmarkCount": len(histories),
        "snapshots": [
            {key: snapshot.get(key) for key in (
                "label", "recordedAt", "sourceRevision", "sourceArtifactDigest",
                "snapshotPath", "snapshotDigest"
            )}
            for snapshot in snapshots
        ],
        "benchmarks": [
            {
                "benchmark": history.benchmark,
                "family": history.family,
                "sourceUnit": history.source_unit,
                "chart": chart_files[history.benchmark],
                "points": [
                    {
                        "label": point.label,
                        "recordedAt": point.recorded_at,
                        "sourceRevision": point.source_revision,
                        "scoreMsPerOp": point.score_ms,
                        "scoreErrorMsPerOp": point.error_ms,
                    }
                    for point in history.points
                ],
            }
            for history in histories
        ],
    }
    (output_dir / "history.json").write_text(
        json.dumps(normalized_output, sort_keys=True, indent=2) + "\n",
        encoding="utf-8",
    )

    labels = [snapshot["label"] for snapshot in snapshots]
    lines = [
        "# JMH benchmark history",
        "",
        str(policy.get("claimBoundary", "")),
        "",
        "All chart and table values use **ms/op**. Lower values and lower points are faster/better.",
        "",
        "## Retained snapshots",
        "",
        "| Snapshot | Recorded at | Source revision | Snapshot digest |",
        "| --- | --- | --- | --- |",
    ]
    for snapshot in snapshots:
        lines.append(
            f"| {snapshot['label']} | {snapshot['recordedAt']} | "
            f"`{snapshot['sourceRevision']}` | `{snapshot['snapshotDigest']}` |"
        )
    for family in sorted({history.family for history in histories}):
        lines.extend(["", f"## {family}", ""])
        header = "| Benchmark | " + " | ".join(labels) + " | Change vs first | Chart |"
        separator = "| --- | " + " | ".join("---:" for _ in labels) + " | ---: | --- |"
        lines.extend([header, separator])
        for history in (item for item in histories if item.family == family):
            values = [point.score_ms for point in history.points]
            first, last = values[0], values[-1]
            change = 0.0 if first == 0 else (last / first - 1.0) * 100.0
            value_cells = " | ".join(_format_ms(value) for value in values)
            lines.append(
                f"| `{history.benchmark}` | {value_cells} | {change:+.2f}% | "
                f"[SVG]({chart_files[history.benchmark]}) |"
            )
    (output_dir / "history.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    return normalized_output


def _parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--history-policy", type=Path, required=True)
    parser.add_argument("--regression-policy", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = _parse_args(sys.argv[1:] if argv is None else argv)
    try:
        result = render(args.history_policy, args.regression_policy, args.output_dir)
    except (HistoryError, OSError, json.JSONDecodeError) as exc:
        print(f"jmhHistoryStatus=FAILED\njmhHistoryViolation={exc}", file=sys.stderr)
        return 1
    print("jmhHistoryStatus=PASSED")
    print(f"jmhHistorySnapshots={result['snapshotCount']}")
    print(f"jmhHistoryBenchmarks={result['benchmarkCount']}")
    print(f"jmhHistoryReport={args.output_dir / 'history.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
