#!/usr/bin/env python3
"""Render coverage and test badge pages from local Gradle reports."""

from __future__ import annotations

import argparse
import html
import json
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

def write_json(path: Path, payload: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(payload, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )


def escape(value: object) -> str:
    return html.escape(str(value), quote=True)


def module_name(path: Path) -> str:
    return path.parts[0] if path.parts and path.parts[0] != "build" else "root"


def counters(root: ET.Element) -> dict[str, tuple[int, int]]:
    values = dict.fromkeys(("INSTRUCTION", "BRANCH", "LINE", "COMPLEXITY", "METHOD", "CLASS"), (0, 0))
    seen: set[str] = set()
    for item in root.findall("counter"):
        kind = item.attrib["type"]
        value = (int(item.attrib["missed"]), int(item.attrib["covered"]))
        if kind not in values or kind in seen or min(value) < 0:
            raise ValueError(f"Invalid or duplicate JaCoCo counter: {kind}")
        seen.add(kind)
        values[kind] = value
    return values


def generate_coverage(public: Path, sympy_inputs: Path | None = None) -> float:
    """Render with JaCoCo itself, then bind the published counters to the gate."""
    policy = json.loads(Path("config/quality/coverage-policy.json").read_text(encoding="utf-8"))
    gate = json.loads(Path("build/reports/quality/coverage-report.json").read_text(encoding="utf-8"))
    modules = sorted(policy["modules"])
    if (not modules or policy["schema"] != "regelsuche.quality.coverage-policy/v1"
            or gate["schema"] != "regelsuche.quality.coverage-report/v1"
            or gate["status"] != "PASSED"
            or any(gate[key] for key in ("missingModules", "undeclaredModules", "violations"))
            or sorted(row["module"] for row in gate["modules"]) != modules):
        raise ValueError("Coverage policy, successful gate and JaCoCo report modules must agree")
    coverage_dir = public / "coverage"
    command = ["java", "-cp", "build/reports/quality/coverage-report-runtime/*",
               "de.regelsuche.quality.coverage.JacocoAggregateReport", str(coverage_dir.resolve())]
    for module in modules:
        inputs = (sympy_inputs if module == "regelsuche-math-sympy" and sympy_inputs is not None
                  else Path(module) / "build/reports/jacoco/test/inputs")
        command.extend((module, str(inputs.resolve())))
    subprocess.run(command, check=True)
    aggregate = ET.parse(coverage_dir / "jacoco.xml").getroot()
    groups = aggregate.findall("group")
    if (sorted(group.attrib["name"] for group in groups) != modules
            or not (coverage_dir / "index.html").is_file()):
        raise ValueError("JaCoCo must render every policy module exactly once")
    rendered = {group.attrib["name"]: counters(group) for group in groups}
    totals = dict.fromkeys(rendered[modules[0]], (0, 0))
    for row in gate["modules"]:
        module = row["module"]
        canonical = counters(ET.parse(
            Path(module) / "build/reports/jacoco/test/jacocoTestReport.xml").getroot())
        if rendered[module] != canonical:
            raise ValueError(f"Rendered JaCoCo counters differ from measured report: {module}")
        for kind in ("line", "branch"):
            if canonical[kind.upper()] != (row[kind]["missed"], row[kind]["covered"]):
                raise ValueError(f"Coverage gate and published {kind} counts differ: {module}")
        for kind, (missed, covered) in canonical.items():
            totals[kind] = (totals[kind][0] + missed, totals[kind][1] + covered)
    if counters(aggregate) != totals:
        raise ValueError("JaCoCo aggregate does not equal the complete module totals")
    for kind in ("line", "branch"):
        measured = gate["aggregate"][kind]
        if totals[kind.upper()] != (measured["missed"], measured["covered"]):
            raise ValueError(f"Coverage gate and published aggregate {kind} counts differ")
    missed, covered = totals["LINE"]
    percent = 100.0 * covered / (missed + covered) if missed + covered else 100.0
    write_json(coverage_dir / "badge.json", {
        "schemaVersion": 1, "label": "coverage (lines)",
        "message": f"{percent:.2f}%", "color": "brightgreen",
    })
    return percent


def generate_tests(public: Path) -> tuple[int, int, int]:
    tests_dir = public / "tests"
    tests_dir.mkdir(parents=True, exist_ok=True)
    total_tests = failures = errors = skipped = 0
    suites: list[tuple[str, str, str, int, int, int]] = []

    for report in sorted(Path(".").glob("**/build/test-results/*/TEST-*.xml")):
        try:
            root = ET.parse(report).getroot()
        except ET.ParseError as error:
            print(f"Skipping malformed JUnit XML {report}: {error}")
            continue
        suite_tests = int(root.get("tests", 0))
        suite_failures = int(root.get("failures", 0))
        suite_errors = int(root.get("errors", 0))
        suite_skipped = int(root.get("skipped", 0))
        total_tests += suite_tests
        failures += suite_failures
        errors += suite_errors
        skipped += suite_skipped
        parts = report.parts
        task = parts[parts.index("test-results") + 1]
        suites.append(
            (
                module_name(report),
                task,
                root.get("name", report.stem),
                suite_tests,
                suite_failures + suite_errors,
                suite_skipped,
            )
        )

    report_links: dict[tuple[str, str], str] = {}
    for report_dir in sorted(Path(".").glob("**/build/reports/tests/*")):
        if not report_dir.is_dir() or not (report_dir / "index.html").is_file():
            continue
        parts = report_dir.parts
        task = parts[parts.index("tests") + 1]
        module = module_name(report_dir)
        target_name = f"{module}-{task}"
        target = tests_dir / "reports" / target_name
        shutil.copytree(report_dir, target, dirs_exist_ok=True)
        report_links[(module, task)] = f"reports/{target_name}/index.html"

    failed = failures + errors
    passed = total_tests - failed - skipped
    message = f"{failed}/{total_tests} failed" if failed else f"{passed} passed"
    write_json(
        tests_dir / "badge.json",
        {
            "schemaVersion": 1,
            "label": "tests",
            "message": message,
            "color": "red" if failed else "brightgreen",
        },
    )

    rows: list[str] = []
    for module, task, name, count, suite_failed, suite_skipped in suites:
        link = report_links.get((module, task))
        report_link = f'<a href="{escape(link)}">HTML</a>' if link else ""
        rows.append(
            f"<tr><td>{escape(module)}</td><td>{escape(task)}</td>"
            f"<td>{escape(name)}</td><td>{count}</td><td>{suite_failed}</td>"
            f"<td>{suite_skipped}</td><td>{report_link}</td></tr>"
        )
    rendered_rows = "\n".join(rows) or (
        '<tr><td colspan="7">No JUnit XML reports were generated.</td></tr>'
    )
    (tests_dir / "index.html").write_text(
        "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
        "<title>Regelsuche Tests</title>"
        "<style>body{font-family:system-ui,sans-serif;margin:2rem;max-width:1200px}"
        "table{border-collapse:collapse;width:100%}td,th{border:1px solid #ddd;"
        "padding:.5rem;text-align:left}th{background:#f6f8fa}</style></head><body>"
        f"<h1>Regelsuche Tests</h1><p><strong>{passed}</strong> passed, "
        f"<strong>{failed}</strong> failed, <strong>{skipped}</strong> skipped, "
        f"<strong>{total_tests}</strong> total.</p><table><thead><tr><th>Module</th>"
        "<th>Task</th><th>Suite</th><th>Tests</th><th>Failed</th><th>Skipped</th>"
        f"<th>Report</th></tr></thead><tbody>{rendered_rows}</tbody></table>"
        "</body></html>\n",
        encoding="utf-8",
    )
    return total_tests, failed, skipped


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--tests-only", action="store_true")
    mode.add_argument("--coverage-only", action="store_true")
    parser.add_argument("--sympy-inputs", type=Path)
    args = parser.parse_args()
    public = Path("public")
    if not args.tests_only:
        print(f"Coverage: {generate_coverage(public, args.sympy_inputs):.2f}% lines")
    if not args.coverage_only:
        total, failed, skipped = generate_tests(public)
        print(f"Tests: {total} ({failed} failed, {skipped} skipped)")


if __name__ == "__main__":
    main()
