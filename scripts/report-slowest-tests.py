#!/usr/bin/env python3
"""Render checkout-owned slow-test and task-duration evidence from JUnit XML."""

from __future__ import annotations

import argparse
import json
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path


def fail(message: str) -> None:
    raise SystemExit(f"slow-test report failed: {message}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--limit", type=int, default=100)
    parser.add_argument("--slow-seconds", type=float, default=5.0)
    parser.add_argument(
        "--json-output", type=Path, default=Path("build/reports/quality/slow-tests.json")
    )
    parser.add_argument(
        "--markdown-output", type=Path, default=Path("build/reports/quality/slow-tests.md")
    )
    args = parser.parse_args()
    if args.limit < 1 or args.slow_seconds < 0:
        fail("invalid reporting limits")

    root = args.root.resolve()
    cases: list[dict] = []
    suites_seen = 0
    for path in sorted(root.glob("**/build/test-results/**/*.xml")):
        if "/binary/" in path.as_posix():
            continue
        try:
            suite = ET.parse(path).getroot()
        except (OSError, ET.ParseError):
            continue
        suites_seen += 1
        module = path.relative_to(root).parts[0]
        for case in suite.iter("testcase"):
            try:
                duration = float(case.get("time", "0") or 0)
            except ValueError:
                duration = 0.0
            cases.append(
                {
                    "module": module,
                    "className": case.get("classname", ""),
                    "testName": case.get("name", ""),
                    "seconds": round(duration, 6),
                    "failed": any(
                        child.tag in {"failure", "error"} for child in list(case)
                    ),
                }
            )
    if not cases:
        fail("no JUnit test cases found")

    ordered = sorted(
        cases,
        key=lambda item: (
            -item["seconds"], item["module"], item["className"], item["testName"]
        ),
    )
    class_seconds: dict[tuple[str, str], float] = defaultdict(float)
    class_count: dict[tuple[str, str], int] = defaultdict(int)
    for case in cases:
        key = (case["module"], case["className"])
        class_seconds[key] += case["seconds"]
        class_count[key] += 1
    classes = [
        {
            "module": module,
            "className": class_name,
            "seconds": round(seconds, 6),
            "testCount": class_count[(module, class_name)],
        }
        for (module, class_name), seconds in sorted(
            class_seconds.items(), key=lambda item: (-item[1], item[0])
        )
    ]
    report = {
        "schema": "regelsuche.quality.slow-tests/v1",
        "suiteCount": suites_seen,
        "testCount": len(cases),
        "totalTestSeconds": round(sum(item["seconds"] for item in cases), 6),
        "slowThresholdSeconds": args.slow_seconds,
        "slowTestCount": sum(item["seconds"] >= args.slow_seconds for item in cases),
        "slowestTests": ordered[: args.limit],
        "slowestClasses": classes[: args.limit],
    }

    json_output = args.json_output if args.json_output.is_absolute() else root / args.json_output
    markdown_output = (
        args.markdown_output
        if args.markdown_output.is_absolute()
        else root / args.markdown_output
    )
    json_output.parent.mkdir(parents=True, exist_ok=True)
    markdown_output.parent.mkdir(parents=True, exist_ok=True)
    json_output.write_text(
        json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    lines = [
        "# Slow-test report",
        "",
        f"Parsed **{len(cases)}** tests from **{suites_seen}** JUnit suites. "
        f"Tests at or above **{args.slow_seconds:.1f}s**: **{report['slowTestCount']}**.",
        "",
        "## Slowest tests",
        "",
        "| Seconds | Module | Test |",
        "| ---: | --- | --- |",
    ]
    for item in report["slowestTests"]:
        lines.append(
            f"| {item['seconds']:.3f} | `{item['module']}` | "
            f"`{item['className']}.{item['testName']}` |"
        )
    lines.extend(
        [
            "",
            "## Slowest test classes",
            "",
            "| Seconds | Tests | Module | Class |",
            "| ---: | ---: | --- | --- |",
        ]
    )
    for item in report["slowestClasses"]:
        lines.append(
            f"| {item['seconds']:.3f} | {item['testCount']} | "
            f"`{item['module']}` | `{item['className']}` |"
        )
    markdown_output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"testCount={len(cases)}")
    print(f"slowTestCount={report['slowTestCount']}")
    print(f"slowTestReport={json_output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
