#!/usr/bin/env python3
"""Fail-closed verification of repository JaCoCo XML coverage reports."""

from __future__ import annotations

import argparse
import json
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class Counter:
    missed: int
    covered: int

    @property
    def total(self) -> int:
        return self.missed + self.covered

    @property
    def ratio(self) -> float:
        return 1.0 if self.total == 0 else self.covered / self.total

    def plus(self, other: "Counter") -> "Counter":
        return Counter(self.missed + other.missed, self.covered + other.covered)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=".", help="Repository root")
    parser.add_argument("--policy", default="config/coverage-policy.json")
    parser.add_argument(
        "--output",
        default="build/reports/coverage-policy/coverage-policy-report.json",
    )
    return parser.parse_args()


def load_policy(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        policy = json.load(handle)
    if policy.get("schemaVersion") != "regelsuche.coverage-policy/v1":
        raise ValueError("unsupported coverage-policy schemaVersion")
    return policy


def parse_report(path: Path) -> dict[str, Counter]:
    root = ET.parse(path).getroot()
    result: dict[str, Counter] = {}
    for element in root.findall("counter"):
        kind = element.attrib.get("type")
        if kind not in {"LINE", "BRANCH"}:
            continue
        result[kind.lower()] = Counter(
            missed=int(element.attrib["missed"]),
            covered=int(element.attrib["covered"]),
        )
    if "line" not in result:
        raise ValueError(f"JaCoCo report has no LINE counter: {path}")
    return result


def check_minimum(
    failures: list[str], scope: str, metric: str, actual: Counter | None, minimum: Any
) -> None:
    if minimum is None:
        return
    if actual is None:
        failures.append(f"{scope}: required {metric} counter is missing")
        return
    threshold = float(minimum)
    if actual.ratio + 1e-12 < threshold:
        failures.append(
            f"{scope}: {metric} coverage {actual.ratio:.4%} is below {threshold:.4%}"
        )


def main() -> int:
    args = parse_args()
    root = Path(args.root).resolve()
    policy_path = root / args.policy
    output_path = root / args.output
    policy = load_policy(policy_path)

    report_paths = sorted(root.glob("*/build/reports/jacoco/test/jacocoTestReport.xml"))
    if not report_paths:
        raise ValueError("no module JaCoCo XML reports found")

    modules: dict[str, dict[str, Counter]] = {}
    for report_path in report_paths:
        module_name = report_path.relative_to(root).parts[0]
        if module_name in modules:
            raise ValueError(f"duplicate JaCoCo report for module {module_name}")
        modules[module_name] = parse_report(report_path)

    aggregate: dict[str, Counter] = {}
    for counters in modules.values():
        for metric, counter in counters.items():
            aggregate[metric] = aggregate.get(metric, Counter(0, 0)).plus(counter)

    failures: list[str] = []
    aggregate_policy = policy.get("aggregate", {})
    check_minimum(failures, "aggregate", "line", aggregate.get("line"), aggregate_policy.get("lineMinimum"))
    check_minimum(failures, "aggregate", "branch", aggregate.get("branch"), aggregate_policy.get("branchMinimum"))

    module_policy = policy.get("modules", {})
    for module_name, thresholds in sorted(module_policy.items()):
        counters = modules.get(module_name)
        if counters is None:
            if thresholds.get("required", False):
                failures.append(f"required module report is missing: {module_name}")
            continue
        check_minimum(failures, module_name, "line", counters.get("line"), thresholds.get("lineMinimum"))
        check_minimum(failures, module_name, "branch", counters.get("branch"), thresholds.get("branchMinimum"))

    report = {
        "schemaVersion": "regelsuche.coverage-policy-report/v1",
        "policy": str(policy_path.relative_to(root)),
        "status": "FAILED" if failures else "PASSED",
        "aggregate": {
            metric: {
                "missed": counter.missed,
                "covered": counter.covered,
                "total": counter.total,
                "ratio": counter.ratio,
            }
            for metric, counter in sorted(aggregate.items())
        },
        "modules": {
            module: {
                metric: {
                    "missed": counter.missed,
                    "covered": counter.covered,
                    "total": counter.total,
                    "ratio": counter.ratio,
                }
                for metric, counter in sorted(counters.items())
            }
            for module, counters in sorted(modules.items())
        },
        "failures": failures,
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    if failures:
        for failure in failures:
            print(f"coverage policy violation: {failure}", file=sys.stderr)
        return 1

    print(
        "coverage policy passed: "
        f"line={aggregate['line'].ratio:.2%}, "
        f"branch={aggregate.get('branch', Counter(0, 0)).ratio:.2%}"
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, ET.ParseError, json.JSONDecodeError) as error:
        print(f"coverage policy verification failed closed: {error}", file=sys.stderr)
        raise SystemExit(2)
