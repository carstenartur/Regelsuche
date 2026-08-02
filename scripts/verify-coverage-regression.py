#!/usr/bin/env python3
"""Fail closed on repository and module JaCoCo coverage regressions."""

from __future__ import annotations

import argparse
import json
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Counter:
    missed: int
    covered: int

    @property
    def percent(self) -> float:
        total = self.missed + self.covered
        return 100.0 if total == 0 else 100.0 * self.covered / total

    def plus(self, other: "Counter") -> "Counter":
        return Counter(self.missed + other.missed, self.covered + other.covered)


def fail(message: str) -> None:
    raise SystemExit(f"coverage gate failed: {message}")


def load_policy(path: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"cannot read policy {path}: {error}")
    if value.get("schema") != "regelsuche.quality.coverage-policy/v1":
        fail("unsupported coverage policy schema")
    modules = value.get("modules")
    if not isinstance(modules, dict) or not modules:
        fail("policy must declare at least one module")
    return value


def counter(root: ET.Element, kind: str) -> Counter:
    matches = [item for item in root.findall("counter") if item.get("type") == kind]
    if len(matches) != 1:
        fail(f"JaCoCo report must contain one top-level {kind} counter")
    item = matches[0]
    return Counter(int(item.get("missed", "0")), int(item.get("covered", "0")))


def module_from_report(repository: Path, report: Path) -> str:
    relative = report.relative_to(repository)
    if len(relative.parts) < 2:
        fail(f"cannot derive module from {relative}")
    return relative.parts[0]


def measure(repository: Path) -> dict[str, dict[str, Counter]]:
    reports = sorted(repository.glob("*/build/reports/jacoco/test/jacocoTestReport.xml"))
    reports += sorted(repository.glob("build/reports/jacoco/test/jacocoTestReport.xml"))
    if not reports:
        fail("no JaCoCo XML reports found")
    result: dict[str, dict[str, Counter]] = {}
    for report in reports:
        module = module_from_report(repository, report)
        if module in result:
            fail(f"duplicate JaCoCo report for module {module}")
        try:
            root = ET.parse(report).getroot()
        except (OSError, ET.ParseError) as error:
            fail(f"cannot parse {report}: {error}")
        result[module] = {
            "line": counter(root, "LINE"),
            "branch": counter(root, "BRANCH"),
        }
    return result


def rounded(value: float) -> float:
    return round(value + 1e-12, 4)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument(
        "--policy", type=Path, default=Path("config/quality/coverage-policy.json")
    )
    parser.add_argument(
        "--json-output", type=Path, default=Path("build/reports/quality/coverage-report.json")
    )
    parser.add_argument(
        "--markdown-output", type=Path, default=Path("build/reports/quality/coverage-report.md")
    )
    args = parser.parse_args()

    repository = args.root.resolve()
    policy_path = args.policy if args.policy.is_absolute() else repository / args.policy
    policy = load_policy(policy_path)
    measured = measure(repository)
    declared = set(policy["modules"])
    actual = set(measured)
    missing = sorted(declared - actual)
    undeclared = sorted(actual - declared)
    violations: list[str] = []
    if missing:
        violations.append("missing covered modules: " + ", ".join(missing))
    if undeclared:
        violations.append("undeclared covered modules: " + ", ".join(undeclared))

    aggregate_line = Counter(0, 0)
    aggregate_branch = Counter(0, 0)
    module_rows: list[dict] = []
    for module in sorted(actual):
        line = measured[module]["line"]
        branch = measured[module]["branch"]
        aggregate_line = aggregate_line.plus(line)
        aggregate_branch = aggregate_branch.plus(branch)
        floors = policy["modules"].get(module)
        row = {
            "module": module,
            "line": {
                "missed": line.missed,
                "covered": line.covered,
                "percent": rounded(line.percent),
                "minimumPercent": None if floors is None else floors["lineMinimumPercent"],
            },
            "branch": {
                "missed": branch.missed,
                "covered": branch.covered,
                "percent": rounded(branch.percent),
                "minimumPercent": None if floors is None else floors["branchMinimumPercent"],
            },
        }
        if floors is not None:
            if line.percent + 1e-9 < float(floors["lineMinimumPercent"]):
                violations.append(
                    f"{module} line coverage {line.percent:.4f}% is below "
                    f"{floors['lineMinimumPercent']:.4f}%"
                )
            if branch.percent + 1e-9 < float(floors["branchMinimumPercent"]):
                violations.append(
                    f"{module} branch coverage {branch.percent:.4f}% is below "
                    f"{floors['branchMinimumPercent']:.4f}%"
                )
        module_rows.append(row)

    aggregate_policy = policy["aggregate"]
    if aggregate_line.percent + 1e-9 < float(aggregate_policy["lineMinimumPercent"]):
        violations.append(
            f"aggregate line coverage {aggregate_line.percent:.4f}% is below "
            f"{aggregate_policy['lineMinimumPercent']:.4f}%"
        )
    if aggregate_branch.percent + 1e-9 < float(aggregate_policy["branchMinimumPercent"]):
        violations.append(
            f"aggregate branch coverage {aggregate_branch.percent:.4f}% is below "
            f"{aggregate_policy['branchMinimumPercent']:.4f}%"
        )

    report = {
        "schema": "regelsuche.quality.coverage-report/v1",
        "policy": str(policy_path.relative_to(repository)),
        "ratchetPolicy": policy["ratchetPolicy"],
        "status": "PASSED" if not violations else "FAILED",
        "aggregate": {
            "line": {
                "missed": aggregate_line.missed,
                "covered": aggregate_line.covered,
                "percent": rounded(aggregate_line.percent),
                "minimumPercent": aggregate_policy["lineMinimumPercent"],
            },
            "branch": {
                "missed": aggregate_branch.missed,
                "covered": aggregate_branch.covered,
                "percent": rounded(aggregate_branch.percent),
                "minimumPercent": aggregate_policy["branchMinimumPercent"],
            },
        },
        "modules": module_rows,
        "missingModules": missing,
        "undeclaredModules": undeclared,
        "violations": violations,
    }

    json_output = args.json_output if args.json_output.is_absolute() else repository / args.json_output
    markdown_output = (
        args.markdown_output
        if args.markdown_output.is_absolute()
        else repository / args.markdown_output
    )
    json_output.parent.mkdir(parents=True, exist_ok=True)
    markdown_output.parent.mkdir(parents=True, exist_ok=True)
    json_output.write_text(
        json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )

    lines = [
        "# Coverage regression gate",
        "",
        f"Status: **{report['status']}**",
        "",
        "| Module | Lines | Floor | Branches | Floor |",
        "| --- | ---: | ---: | ---: | ---: |",
    ]
    for row in module_rows:
        lines.append(
            f"| `{row['module']}` | {row['line']['percent']:.2f}% | "
            f"{row['line']['minimumPercent']}% | {row['branch']['percent']:.2f}% | "
            f"{row['branch']['minimumPercent']}% |"
        )
    lines.extend(
        [
            "",
            f"Aggregate line coverage: **{aggregate_line.percent:.2f}%** "
            f"(floor {aggregate_policy['lineMinimumPercent']}%).",
            f"Aggregate branch coverage: **{aggregate_branch.percent:.2f}%** "
            f"(floor {aggregate_policy['branchMinimumPercent']}%).",
            "",
            "## Ratchet policy",
            "",
            policy["ratchetPolicy"],
        ]
    )
    if violations:
        lines.extend(["", "## Violations", ""])
        lines.extend(f"- {item}" for item in violations)
    markdown_output.write_text("\n".join(lines) + "\n", encoding="utf-8")

    print(f"coverageStatus={report['status']}")
    print(f"aggregateLinePercent={aggregate_line.percent:.4f}")
    print(f"aggregateBranchPercent={aggregate_branch.percent:.4f}")
    print(f"coverageReport={json_output}")
    if violations:
        for violation in violations:
            print(f"coverageViolation={violation}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
