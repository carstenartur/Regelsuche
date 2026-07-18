#!/usr/bin/env python3
"""Render coverage and test badge pages from local Gradle reports."""

from __future__ import annotations

import html
import json
import shutil
import xml.etree.ElementTree as ET
from pathlib import Path

GREEN = 80
YELLOW = 60


def badge_colour(percent: int) -> str:
    if percent >= GREEN:
        return "brightgreen"
    if percent >= YELLOW:
        return "yellow"
    return "red"


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


def generate_coverage(public: Path) -> int:
    coverage_dir = public / "coverage"
    coverage_dir.mkdir(parents=True, exist_ok=True)
    covered = 0
    missed = 0
    reports: list[tuple[str, int, str]] = []

    pattern = "**/build/reports/jacoco/**/jacocoTestReport.xml"
    for report in sorted(Path(".").glob(pattern)):
        try:
            root = ET.parse(report).getroot()
        except ET.ParseError as error:
            print(f"Skipping malformed JaCoCo XML {report}: {error}")
            continue
        report_covered = 0
        report_missed = 0
        for counter in root.iter("counter"):
            if counter.get("type") != "INSTRUCTION":
                continue
            report_covered += int(counter.get("covered", 0))
            report_missed += int(counter.get("missed", 0))
        covered += report_covered
        missed += report_missed
        total = report_covered + report_missed
        percent = round(report_covered / total * 100) if total else 0
        module = module_name(report)
        html_source = report.parent / "html"
        if html_source.is_dir():
            target = coverage_dir / "modules" / module
            shutil.copytree(html_source, target, dirs_exist_ok=True)
            reports.append((module, percent, f"modules/{module}/index.html"))

    total = covered + missed
    percent = round(covered / total * 100) if total else 0
    write_json(
        coverage_dir / "badge.json",
        {
            "schemaVersion": 1,
            "label": "coverage",
            "message": f"{percent}%",
            "color": badge_colour(percent),
        },
    )
    rows = "\n".join(
        f'<tr><td><a href="{escape(link)}">{escape(module)}</a></td>'
        f"<td>{module_percent}%</td></tr>"
        for module, module_percent, link in reports
    ) or '<tr><td colspan="2">No JaCoCo HTML reports were generated.</td></tr>'
    (coverage_dir / "index.html").write_text(
        "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
        "<title>Regelsuche Coverage</title>"
        "<style>body{font-family:system-ui,sans-serif;margin:2rem;max-width:960px}"
        "table{border-collapse:collapse;width:100%}td,th{border:1px solid #ddd;"
        "padding:.5rem;text-align:left}th{background:#f6f8fa}</style></head><body>"
        f"<h1>Regelsuche Coverage</h1><p>Total instruction coverage: "
        f"<strong>{percent}%</strong></p><table><thead><tr><th>Module</th>"
        f"<th>Instruction coverage</th></tr></thead><tbody>{rows}</tbody></table>"
        "</body></html>\n",
        encoding="utf-8",
    )
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
    public = Path("public")
    percent = generate_coverage(public)
    total, failed, skipped = generate_tests(public)
    print(f"Coverage: {percent}%   Tests: {total} ({failed} failed, {skipped} skipped)")


if __name__ == "__main__":
    main()
