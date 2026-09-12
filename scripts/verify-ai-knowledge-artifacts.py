#!/usr/bin/env python3
"""Fail-closed verification for generated AI knowledge artifacts."""

from __future__ import annotations

import argparse
import json
import math
import re
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

EXPECTED_CONTEXT_FOOTPRINT_METHOD = (
    "line-weighted-prioritized-capability-selector-working-set-proxy"
)
CAPABILITY_COVERAGE_CONTRACT = (
    Path(__file__).resolve().parents[1]
    / "config/ai-knowledge-capability-coverage.json"
)


def load_unique_json(path: Path) -> Any:
    def object_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"duplicate field {key!r}")
            result[key] = value
        return result

    try:
        with path.open("r", encoding="utf-8") as handle:
            return json.load(handle, object_pairs_hook=object_pairs)
    except (OSError, json.JSONDecodeError, ValueError) as error:
        raise ValueError(f"cannot parse {path}: {error}") from error


def positive_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value > 0


def finite_number(value: Any) -> bool:
    return (
        isinstance(value, (int, float))
        and not isinstance(value, bool)
        and math.isfinite(float(value))
    )


def verify_capability_coverage(
    root: Path, documents: dict[str, Any], errors: list[str]
) -> None:
    """Check semantic membership independently of selector resolution counts."""
    try:
        contract = load_unique_json(CAPABILITY_COVERAGE_CONTRACT)
    except ValueError as error:
        errors.append(str(error))
        return
    if not isinstance(contract, dict) or contract.get("schema") != (
        "regelsuche.ai-knowledge-capability-coverage/v1"
    ):
        errors.append("invalid AI knowledge capability coverage contract")
        return

    def indexed(document: Any, field: str, label: str) -> dict[str, Any]:
        rows = document.get(field) if isinstance(document, dict) else None
        if not isinstance(rows, list) or not rows:
            errors.append(f"{label}: expected nonempty {field} array")
            return {}
        result: dict[str, Any] = {}
        for row in rows:
            identity = row.get("id") if isinstance(row, dict) else None
            if not isinstance(identity, str) or not re.fullmatch(r"[a-z][a-z0-9-]*", identity):
                errors.append(f"{label}: invalid capability identity {identity!r}")
            elif identity in result:
                errors.append(f"{label}: duplicate capability {identity}")
            else:
                result[identity] = row
        return result

    required = indexed(contract, "capabilities", "coverage contract")
    capabilities = indexed(documents.get("capabilities.json"), "capabilities", "capabilities.json")
    packs = indexed(documents.get("context-packs/index.json"), "contextPacks", "context-pack index")

    def names(document: Any, field: str, name: str) -> set[str]:
        rows = document.get(field) if isinstance(document, dict) else None
        if not isinstance(rows, list):
            errors.append(f"{field} inventory must contain an array")
            return set()
        return {row[name] for row in rows
                if isinstance(row, dict) and isinstance(row.get(name), str)}

    extracted_types = names(documents.get("classes.json"), "classes", "class")
    extracted_tests = names(documents.get("tests.json"), "tests", "testClass")
    for identity, boundary in required.items():
        capability = capabilities.get(identity, {})
        entry = packs.get(identity, {})
        expected_file = f"context-packs/{identity}.json"
        if entry.get("file") != expected_file:
            errors.append(f"CONTEXT_IDENTITY_INVALID: {identity}: expected {expected_file}")
            continue
        try:
            pack = load_unique_json(root / expected_file)
        except ValueError as error:
            errors.append(str(error))
            continue
        if not isinstance(pack, dict) or pack.get("id") != identity:
            errors.append(f"CONTEXT_IDENTITY_INVALID: {identity}")
            continue
        for required_field, matched_field, pack_field, extracted in (
            ("requiredTypes", "matchedTypes", "types", extracted_types),
            ("requiredTests", "matchedTests", "tests", extracted_tests),
        ):
            expected = boundary.get(required_field)
            if (not isinstance(expected, list) or not expected
                    or any(not isinstance(name, str) or not name for name in expected)
                    or len(set(expected)) != len(expected)):
                errors.append(f"coverage contract: invalid {identity}.{required_field}")
                continue
            matched = capability.get(matched_field)
            context = pack.get(pack_field)
            if not isinstance(matched, list):
                errors.append(f"{identity}.{matched_field} must be an array")
                matched = []
            if not isinstance(context, list):
                errors.append(f"{identity} context {pack_field} must be an array")
                context = []
            for name in expected:
                if name not in extracted:
                    errors.append(f"BOUNDARY_NOT_EXTRACTED: {identity}: {name}")
                if name not in matched:
                    errors.append(f"CAPABILITY_MEMBERSHIP_MISSING: {identity}: {name}")
                if name not in context:
                    errors.append(f"CONTEXT_MEMBERSHIP_MISSING: {identity}: {name}")


def verify(root: Path) -> tuple[list[str], list[str]]:
    errors: list[str] = []
    warnings: list[str] = []
    root = root.resolve()

    if not root.is_dir():
        return [f"artifact root does not exist: {root}"], warnings

    documents: dict[str, Any] = {}
    for relative in REQUIRED_FILES:
        path = root / relative
        if not path.is_file():
            errors.append(f"missing required artifact: {relative}")
            continue
        if path.stat().st_size <= 0:
            errors.append(f"required artifact is empty: {relative}")
            continue
        if path.suffix == ".json":
            try:
                documents[relative] = load_unique_json(path)
            except ValueError as error:
                errors.append(str(error))

    evidence_document = documents.get("evidence.json")
    if evidence_document is not None:
        if isinstance(evidence_document, list):
            evidence = evidence_document
        elif isinstance(evidence_document, dict):
            evidence = evidence_document.get("evidence", [])
        else:
            evidence = []
        if not isinstance(evidence, list) or not evidence:
            errors.append(
                "evidence.json is empty; expected repository evidence artifacts"
            )

    classes_document = documents.get("classes.json")
    if classes_document is not None:
        classes = (
            classes_document.get("classes", [])
            if isinstance(classes_document, dict)
            else []
        )
        if not isinstance(classes, list):
            errors.append("classes.json field 'classes' must be an array")
        elif not any(
            isinstance(candidate, dict) and candidate.get("methodFacts")
            for candidate in classes
        ):
            warnings.append(
                "classes.json contains no methodFacts; Java extraction may be too shallow"
            )

    complexity = documents.get("complexity.json")
    if complexity is not None:
        if not isinstance(complexity, dict):
            errors.append("complexity.json must contain an object")
        else:
            code_complexity = complexity.get("codeComplexity", {})
            cost_drivers = complexity.get("aiCostDrivers", {})
            footprint = complexity.get("contextFootprint", {})

            if not isinstance(code_complexity, dict) or (
                "maxMethodCognitiveComplexity" not in code_complexity
            ):
                errors.append(
                    "complexity.json lacks "
                    "codeComplexity.maxMethodCognitiveComplexity"
                )
            if not isinstance(cost_drivers, dict) or (
                "tokenCostDrivers" not in cost_drivers
            ):
                errors.append(
                    "complexity.json lacks aiCostDrivers.tokenCostDrivers"
                )
            if not isinstance(footprint, dict):
                errors.append("complexity.json contextFootprint must be an object")
                footprint = {}

            if footprint.get("schemaVersion") != 3:
                errors.append("complexity.json lacks contextFootprint schema v3")
            if footprint.get("method") != EXPECTED_CONTEXT_FOOTPRINT_METHOD:
                errors.append(
                    "complexity.json contains an unexpected context-footprint method"
                )
            if footprint.get("measurementStatus") != "MEASURED":
                errors.append(
                    "context footprint is not measured from capability selectors"
                )
            if not positive_int(footprint.get("capabilityCount")):
                errors.append("context footprint contains no capabilities")
            if not positive_int(footprint.get("capabilitySampleCount")):
                errors.append(
                    "context footprint contains no capability working-set samples"
                )

            for field in (
                "unresolvedCapabilityTypeReferences",
                "unresolvedCapabilityModuleReferences",
                "unresolvedCapabilityPackageReferences",
            ):
                unresolved = footprint.get(field)
                if unresolved != 0:
                    errors.append(
                        f"context footprint has {unresolved!r} in {field}"
                    )

            sources = footprint.get("capabilityWorkingSetSources", {})
            source_count = 0
            if isinstance(sources, dict):
                source_count = sum(
                    value
                    for value in sources.values()
                    if isinstance(value, int) and not isinstance(value, bool)
                )
            if source_count <= 0:
                errors.append(
                    "context footprint contains no working-set selector provenance"
                )

            normalized = complexity.get("aiContextDebt")
            if not finite_number(normalized) or not 0 <= float(normalized) < 100:
                errors.append(
                    "complexity.json aiContextDebt is not a measured normalized "
                    "value below 100"
                )
            elif normalized != footprint.get("normalizedContextDebt"):
                errors.append(
                    "top-level aiContextDebt differs from "
                    "contextFootprint.normalizedContextDebt"
                )

    context_pack_index = documents.get("context-packs/index.json")
    if context_pack_index is not None:
        context_packs = (
            context_pack_index.get("contextPacks", [])
            if isinstance(context_pack_index, dict)
            else []
        )
        if not isinstance(context_packs, list) or not context_packs:
            errors.append("context-packs/index.json contains no context packs")

    verify_capability_coverage(root, documents, errors)
    return errors, warnings


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--root",
        type=Path,
        default=Path("build/ai-knowledge"),
        help="generated AI knowledge artifact directory",
    )
    arguments = parser.parse_args()

    errors, warnings = verify(arguments.root)
    for warning in warnings:
        print(f"WARNING: {warning}")
    for error in errors:
        print(f"ERROR: {error}", file=sys.stderr)
    if errors:
        return 1

    print(
        "AI knowledge artifacts passed required-file, evidence and "
        "schema-v3 and semantic capability-coverage checks."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
