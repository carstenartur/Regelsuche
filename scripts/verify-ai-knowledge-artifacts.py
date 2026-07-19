#!/usr/bin/env python3
"""Verify the generated AI-knowledge index from an ordinary checkout.

This script intentionally uses only the Python standard library. It owns the
file-presence and schema-v3 quality checks that used to live in GitHub Actions.
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path
from typing import Any


REQUIRED_FILES = (
    "index.json",
    "modules.json",
    "classes.json",
    "tests.json",
    "docs.json",
    "dependencies.json",
    "capabilities.json",
    "claims.json",
    "evidence.json",
    "complexity.json",
    "review-context.md",
    "context-packs/index.json",
)

EXPECTED_CONTEXT_METHOD = (
    "line-weighted-prioritized-capability-selector-working-set-proxy"
)
UNRESOLVED_REFERENCE_FIELDS = (
    "unresolvedCapabilityTypeReferences",
    "unresolvedCapabilityModuleReferences",
    "unresolvedCapabilityPackageReferences",
)


class VerificationError(ValueError):
    """Raised when retained AI-knowledge evidence violates the contract."""


def _reject_duplicate_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise VerificationError(f"duplicate JSON field: {key}")
        result[key] = value
    return result


def read_json(path: Path) -> Any:
    try:
        return json.loads(
            path.read_text(encoding="utf-8"),
            object_pairs_hook=_reject_duplicate_pairs,
        )
    except (OSError, UnicodeError, json.JSONDecodeError, VerificationError) as error:
        raise VerificationError(f"could not parse {path}: {error}") from error


def require_mapping(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise VerificationError(f"{label} must be a JSON object")
    return value


def require_positive_integer(value: Any, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise VerificationError(f"{label} must be a positive integer, got {value!r}")
    return value


def require_finite_number(value: Any, label: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise VerificationError(f"{label} must be numeric, got {value!r}")
    number = float(value)
    if not math.isfinite(number):
        raise VerificationError(f"{label} must be finite, got {value!r}")
    return number


def verify_required_files(root: Path) -> None:
    missing: list[str] = []
    empty: list[str] = []
    for relative_name in REQUIRED_FILES:
        path = root / relative_name
        if not path.is_file():
            missing.append(relative_name)
        elif path.stat().st_size == 0:
            empty.append(relative_name)
    if missing:
        raise VerificationError(
            "missing required AI-knowledge artifacts: " + ", ".join(missing)
        )
    if empty:
        raise VerificationError(
            "empty required AI-knowledge artifacts: " + ", ".join(empty)
        )


def verify_evidence(root: Path) -> int:
    raw = read_json(root / "evidence.json")
    evidence = raw if isinstance(raw, list) else require_mapping(
        raw, "evidence.json"
    ).get("evidence", [])
    if not isinstance(evidence, list) or not evidence:
        raise VerificationError(
            "evidence.json must contain at least one repository evidence entry"
        )
    return len(evidence)


def verify_method_facts(root: Path) -> int:
    classes_document = require_mapping(read_json(root / "classes.json"), "classes.json")
    classes = classes_document.get("classes", [])
    if not isinstance(classes, list):
        raise VerificationError("classes.json.classes must be an array")
    classes_with_method_facts = sum(
        1
        for item in classes
        if isinstance(item, dict)
        and isinstance(item.get("methodFacts"), list)
        and bool(item["methodFacts"])
    )
    if classes_with_method_facts == 0:
        raise VerificationError(
            "classes.json contains no methodFacts; Java extraction is too shallow"
        )
    return classes_with_method_facts


def verify_complexity(root: Path) -> tuple[int, int, float]:
    complexity = require_mapping(read_json(root / "complexity.json"), "complexity.json")
    code_complexity = require_mapping(
        complexity.get("codeComplexity"), "complexity.json.codeComplexity"
    )
    cost_drivers = require_mapping(
        complexity.get("aiCostDrivers"), "complexity.json.aiCostDrivers"
    )
    footprint = require_mapping(
        complexity.get("contextFootprint"), "complexity.json.contextFootprint"
    )

    if "maxMethodCognitiveComplexity" not in code_complexity:
        raise VerificationError(
            "complexity.json lacks codeComplexity.maxMethodCognitiveComplexity"
        )
    if "tokenCostDrivers" not in cost_drivers:
        raise VerificationError(
            "complexity.json lacks aiCostDrivers.tokenCostDrivers"
        )
    if footprint.get("schemaVersion") != 3:
        raise VerificationError("contextFootprint must use schemaVersion 3")
    if footprint.get("method") != EXPECTED_CONTEXT_METHOD:
        raise VerificationError(
            "contextFootprint contains an unexpected measurement method"
        )
    if footprint.get("measurementStatus") != "MEASURED":
        raise VerificationError(
            "contextFootprint must be measured from capability selectors"
        )

    capability_count = require_positive_integer(
        footprint.get("capabilityCount"), "contextFootprint.capabilityCount"
    )
    sample_count = require_positive_integer(
        footprint.get("capabilitySampleCount"),
        "contextFootprint.capabilitySampleCount",
    )

    for field in UNRESOLVED_REFERENCE_FIELDS:
        if footprint.get(field) != 0:
            raise VerificationError(
                f"contextFootprint.{field} must be 0, got {footprint.get(field)!r}"
            )

    sources = require_mapping(
        footprint.get("capabilityWorkingSetSources"),
        "contextFootprint.capabilityWorkingSetSources",
    )
    source_total = sum(
        value
        for value in sources.values()
        if isinstance(value, int) and not isinstance(value, bool) and value > 0
    )
    if source_total <= 0:
        raise VerificationError(
            "contextFootprint contains no working-set selector provenance"
        )

    normalized_debt = require_finite_number(
        complexity.get("aiContextDebt"), "complexity.json.aiContextDebt"
    )
    if not 0 <= normalized_debt < 100:
        raise VerificationError(
            "complexity.json.aiContextDebt must be normalized to [0, 100)"
        )
    footprint_debt = require_finite_number(
        footprint.get("normalizedContextDebt"),
        "contextFootprint.normalizedContextDebt",
    )
    if normalized_debt != footprint_debt:
        raise VerificationError(
            "aiContextDebt differs from contextFootprint.normalizedContextDebt"
        )

    return capability_count, sample_count, normalized_debt


def verify(root: Path) -> None:
    if not root.is_dir():
        raise VerificationError(f"AI-knowledge output directory does not exist: {root}")
    verify_required_files(root)
    evidence_count = verify_evidence(root)
    classes_with_method_facts = verify_method_facts(root)
    capability_count, sample_count, normalized_debt = verify_complexity(root)
    print(
        "AI-knowledge artifacts verified: "
        f"evidence={evidence_count}, "
        f"classesWithMethodFacts={classes_with_method_facts}, "
        f"capabilities={capability_count}, "
        f"samples={sample_count}, "
        f"normalizedContextDebt={normalized_debt:.2f}"
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Verify generated AI-knowledge artifacts."
    )
    parser.add_argument(
        "--root",
        type=Path,
        default=Path("build/ai-knowledge"),
        help="AI-knowledge output directory (default: build/ai-knowledge)",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        verify(args.root.resolve())
    except VerificationError as error:
        print(f"AI-knowledge verification failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
