#!/usr/bin/env python3
"""Compare AI-knowledge method hotspots with a versioned checkout baseline."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import sys
from pathlib import Path


POLICY_SCHEMA = "regelsuche.quality.complexity-hotspot-policy/v1"


def fail(message: str) -> None:
    raise SystemExit(f"complexity hotspot gate failed: {message}")


def read_json(path: Path, label: str) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"cannot read {label} {path}: {error}")
    if not isinstance(value, dict):
        fail(f"{label} must be an object")
    return value


def key(item: dict) -> str:
    source = item.get("sourceFile")
    signature = item.get("signature")
    if not isinstance(source, str) or not source or not isinstance(signature, str) or not signature:
        fail("hotspot entries require sourceFile and signature")
    return source + "|" + signature


def canonical(item: dict) -> dict:
    return {
        "sourceFile": item["sourceFile"],
        "type": str(item.get("type", "")),
        "signature": item["signature"],
        "cyclomaticComplexity": int(item.get("cyclomaticComplexity", 0)),
        "cognitiveComplexity": int(item.get("cognitiveComplexity", 0)),
        "maxNestingDepth": int(item.get("maxNestingDepth", 0)),
    }


def active_exceptions(policy: dict) -> dict[str, dict]:
    result: dict[str, dict] = {}
    today = dt.date.today()
    for item in policy.get("exceptions", []):
        if not isinstance(item, dict):
            fail("complexity exceptions must be objects")
        for field in (
            "id",
            "sourceFile",
            "signature",
            "maximumCognitiveComplexity",
            "maximumCyclomaticComplexity",
            "rationale",
            "expiresOn",
        ):
            if field not in item:
                fail(f"complexity exception is missing {field}")
        try:
            expires = dt.date.fromisoformat(str(item["expiresOn"]))
        except ValueError as error:
            fail(f"invalid exception expiry for {item['id']}: {error}")
        if expires < today:
            fail(f"complexity exception {item['id']} expired on {expires}")
        identity = key(item)
        if identity in result:
            fail(f"duplicate complexity exception for {identity}")
        result[identity] = item
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--complexity", type=Path, default=Path("build/ai-knowledge/complexity.json")
    )
    parser.add_argument(
        "--policy", type=Path, default=Path("config/quality/complexity-hotspots.json")
    )
    parser.add_argument(
        "--json-output",
        type=Path,
        default=Path("build/reports/quality/complexity-hotspot-report.json"),
    )
    parser.add_argument(
        "--markdown-output",
        type=Path,
        default=Path("build/reports/quality/complexity-hotspot-report.md"),
    )
    args = parser.parse_args()

    current_document = read_json(args.complexity, "complexity report")
    policy = read_json(args.policy, "hotspot policy")
    if policy.get("schema") != POLICY_SCHEMA:
        fail("unsupported hotspot policy schema")
    code = current_document.get("codeComplexity")
    if not isinstance(code, dict):
        fail("complexity report has no codeComplexity object")

    current_by_key: dict[str, dict] = {}
    for field in ("topCyclomaticMethods", "topCognitiveMethods"):
        values = code.get(field)
        if not isinstance(values, list) or not values:
            fail(f"complexity report has no {field}")
        for raw in values:
            item = canonical(raw)
            current_by_key[key(item)] = item

    baseline_values = policy.get("baselineHotspots")
    if not isinstance(baseline_values, list) or not baseline_values:
        fail("hotspot policy has no baselineHotspots")
    baseline_by_key: dict[str, dict] = {}
    for raw in baseline_values:
        item = canonical(raw)
        identity = key(item)
        if identity in baseline_by_key:
            fail(f"duplicate hotspot baseline for {identity}")
        baseline_by_key[identity] = item

    exceptions = active_exceptions(policy)
    cognitive_delta = int(policy.get("allowedCognitiveIncrease", 0))
    cyclomatic_delta = int(policy.get("allowedCyclomaticIncrease", 0))
    changes: list[dict] = []
    violations: list[str] = []
    for identity, current in sorted(current_by_key.items()):
        baseline = baseline_by_key.get(identity)
        exception = exceptions.get(identity)
        if baseline is None:
            status = "NEW_HOTSPOT"
            cognitive_increase = current["cognitiveComplexity"]
            cyclomatic_increase = current["cyclomaticComplexity"]
        else:
            cognitive_increase = (
                current["cognitiveComplexity"] - baseline["cognitiveComplexity"]
            )
            cyclomatic_increase = (
                current["cyclomaticComplexity"] - baseline["cyclomaticComplexity"]
            )
            status = (
                "WORSENED"
                if cognitive_increase > cognitive_delta
                or cyclomatic_increase > cyclomatic_delta
                else "UNCHANGED_OR_IMPROVED"
            )
        allowed = False
        exception_id = None
        if exception is not None:
            allowed = (
                current["cognitiveComplexity"]
                <= int(exception["maximumCognitiveComplexity"])
                and current["cyclomaticComplexity"]
                <= int(exception["maximumCyclomaticComplexity"])
            )
            exception_id = exception["id"]
        blocked = status in {"NEW_HOTSPOT", "WORSENED"} and not allowed
        if blocked:
            violations.append(
                f"{status}: {current['sourceFile']} :: {current['signature']} "
                f"(cognitive={current['cognitiveComplexity']}, "
                f"cyclomatic={current['cyclomaticComplexity']})"
            )
        changes.append(
            {
                "identity": identity,
                "status": status,
                "blocked": blocked,
                "exceptionId": exception_id,
                "cognitiveIncrease": cognitive_increase,
                "cyclomaticIncrease": cyclomatic_increase,
                "baseline": baseline,
                "current": current,
            }
        )

    retired = [
        baseline_by_key[identity]
        for identity in sorted(set(baseline_by_key) - set(current_by_key))
    ]
    report = {
        "schema": "regelsuche.quality.complexity-hotspot-report/v1",
        "status": "PASSED" if not violations else "FAILED",
        "allowedCognitiveIncrease": cognitive_delta,
        "allowedCyclomaticIncrease": cyclomatic_delta,
        "currentHotspotCount": len(current_by_key),
        "changes": changes,
        "retiredOrNoLongerTopHotspots": retired,
        "activeExceptions": sorted(item["id"] for item in exceptions.values()),
        "violations": violations,
    }
    args.json_output.parent.mkdir(parents=True, exist_ok=True)
    args.markdown_output.parent.mkdir(parents=True, exist_ok=True)
    args.json_output.write_text(
        json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    lines = [
        "# Cognitive-complexity hotspot trend",
        "",
        f"Status: **{report['status']}**",
        "",
        "| Status | Cognitive Δ | Cyclomatic Δ | Method |",
        "| --- | ---: | ---: | --- |",
    ]
    for change in changes:
        current = change["current"]
        lines.append(
            f"| {change['status']} | {change['cognitiveIncrease']:+d} | "
            f"{change['cyclomaticIncrease']:+d} | "
            f"`{current['sourceFile']} :: {current['signature']}` |"
        )
    lines.extend(
        [
            "",
            "## Exception format",
            "",
            "An exception must name an exact source/signature pair, finite cognitive and "
            "cyclomatic ceilings, a rationale and an ISO expiry date. Expired, duplicate "
            "or ceiling-exceeding exceptions fail closed.",
        ]
    )
    if violations:
        lines.extend(["", "## Violations", ""])
        lines.extend(f"- {item}" for item in violations)
    args.markdown_output.write_text("\n".join(lines) + "\n", encoding="utf-8")

    print(f"complexityHotspotStatus={report['status']}")
    print(f"complexityHotspotCount={len(current_by_key)}")
    if violations:
        for violation in violations:
            print(f"complexityHotspotViolation={violation}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
