#!/usr/bin/env python3
"""Independently verify matched-work rewrite-program TRAIN evidence."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path
from typing import Any, Iterable

from jsonschema import Draft202012Validator

TRANSFORMATION_FIELDS = (
    "engineInvocations",
    "programNodeVisits",
    "sourceInvocations",
    "sourceCandidates",
    "composedCandidates",
    "requirementEvaluations",
    "requirementRejections",
    "priorityCandidatesOrdered",
    "prunedCandidates",
    "repeatIterations",
    "repeatEndpoints",
    "alternativeSelections",
    "alternativesSkipped",
    "duplicateCandidatesDropped",
)

SEARCH_FIELDS = (
    "exploredStates",
    "expandedStates",
    "generatedTransformations",
    "enqueuedStates",
    "duplicatePrunes",
    "repeatedApplicationPrunes",
    "sameExpressionPrunes",
    "expansionBudgetPrunes",
    "primitiveBudgetPrunes",
    "candidateBudgetPrunes",
    "statesWithoutTransformations",
    "engineBatches",
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--schemas", required=True, type=Path)
    return parser.parse_args()


def load(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        value = json.load(handle)
    if not isinstance(value, dict):
        raise ValueError(f"{path}: root must be an object")
    return value


def validate_schema(
    document: dict[str, Any],
    schema_path: Path,
    label: str,
) -> None:
    schema = load(schema_path)
    errors = sorted(
        Draft202012Validator(schema).iter_errors(document),
        key=lambda error: list(error.absolute_path),
    )
    if errors:
        details = "\n".join(
            f"  {list(error.absolute_path)}: {error.message}"
            for error in errors
        )
        raise ValueError(f"{label} schema validation failed:\n{details}")


def canonical_without_content_hash(document: dict[str, Any]) -> str:
    payload = copy.deepcopy(document)
    content_hash = payload.pop("contentHash", None)
    if content_hash is None:
        raise ValueError("contentHash is missing")
    return json.dumps(
        payload,
        ensure_ascii=False,
        separators=(",", ":"),
    )


def verify_content_hash(document: dict[str, Any], label: str) -> None:
    canonical = canonical_without_content_hash(document)
    actual = "sha256:" + hashlib.sha256(
        canonical.encode("utf-8")
    ).hexdigest()
    expected = document["contentHash"]
    if actual != expected:
        raise ValueError(
            f"{label} contentHash mismatch: {actual} != {expected}"
        )


def sum_fields(value: dict[str, Any], fields: Iterable[str]) -> int:
    return sum(require_non_negative_int(value, field) for field in fields)


def require_non_negative_int(value: dict[str, Any], field: str) -> int:
    item = value[field]
    if isinstance(item, bool) or not isinstance(item, int) or item < 0:
        raise ValueError(f"{field} must be a non-negative integer")
    return item


def verify_vector(
    value: dict[str, Any],
    fields: tuple[str, ...],
    label: str,
) -> int:
    recomputed = sum_fields(value, fields)
    retained = require_non_negative_int(value, "totalWorkUnits")
    if recomputed != retained:
        raise ValueError(
            f"{label}.totalWorkUnits mismatch: {retained} != {recomputed}"
        )
    return retained


def java_divide(numerator: int, denominator: int) -> int:
    if denominator <= 0:
        return 0
    sign = -1 if numerator < 0 else 1
    return sign * (abs(numerator) // denominator)


def clamp_permille(value: int) -> int:
    return max(-1000, min(1000, value))


def permille(numerator: int, denominator: int) -> int:
    if denominator <= 0:
        return 0
    return clamp_permille(java_divide(numerator * 1000, denominator))


def normalized_delta(delta: int, baseline: int) -> int:
    if baseline <= 0:
        return 0
    return clamp_permille(java_divide(delta * 1000, baseline))


def verify_case(case: dict[str, Any], budget: dict[str, Any]) -> None:
    case_id = case["caseId"]
    baseline_transformation = verify_vector(
        case["baselineTransformationWork"],
        TRANSFORMATION_FIELDS,
        f"{case_id}.baselineTransformationWork",
    )
    candidate_transformation = verify_vector(
        case["candidateTransformationWork"],
        TRANSFORMATION_FIELDS,
        f"{case_id}.candidateTransformationWork",
    )
    baseline_search = verify_vector(
        case["baselineOuterSearchWork"],
        SEARCH_FIELDS,
        f"{case_id}.baselineOuterSearchWork",
    )
    candidate_search = verify_vector(
        case["candidateOuterSearchWork"],
        SEARCH_FIELDS,
        f"{case_id}.candidateOuterSearchWork",
    )

    if case["baselineOuterSearchWorkUnits"] != baseline_search:
        raise ValueError(f"{case_id}: baseline outer-search scalar mismatch")
    if case["candidateOuterSearchWorkUnits"] != candidate_search:
        raise ValueError(f"{case_id}: candidate outer-search scalar mismatch")
    if case["baselineExploredStates"] != case["baselineOuterSearchWork"]["exploredStates"]:
        raise ValueError(f"{case_id}: baseline explored-state mismatch")
    if case["candidateExploredStates"] != case["candidateOuterSearchWork"]["exploredStates"]:
        raise ValueError(f"{case_id}: candidate explored-state mismatch")
    if case["baselineGeneratedTransformations"] != case["baselineOuterSearchWork"]["generatedTransformations"]:
        raise ValueError(f"{case_id}: baseline generated-transformation mismatch")
    if case["candidateGeneratedTransformations"] != case["candidateOuterSearchWork"]["generatedTransformations"]:
        raise ValueError(f"{case_id}: candidate generated-transformation mismatch")

    baseline_audits = require_non_negative_int(case, "baselinePathAuditCalls")
    candidate_audits = require_non_negative_int(case, "candidatePathAuditCalls")
    baseline_total = baseline_transformation + baseline_search + baseline_audits
    candidate_total = candidate_transformation + candidate_search + candidate_audits
    if case["baselineTotalWorkUnits"] != baseline_total:
        raise ValueError(f"{case_id}: baseline total-work mismatch")
    if case["candidateTotalWorkUnits"] != candidate_total:
        raise ValueError(f"{case_id}: candidate total-work mismatch")

    max_primitive = require_non_negative_int(budget, "maxPrimitiveSteps")
    max_work = require_non_negative_int(budget, "maxWorkUnits")
    for side in ("baseline", "candidate"):
        reached = case[f"{side}Reached"]
        path_length = case[f"{side}PathLength"]
        primitive_steps = case[f"{side}PrimitiveSteps"]
        audit_calls = case[f"{side}PathAuditCalls"]
        total_work = case[f"{side}TotalWorkUnits"]
        correctness = case[f"{side}PathCorrectness"]
        if primitive_steps > max_primitive:
            raise ValueError(f"{case_id}: {side} primitive budget exceeded")
        if reached:
            if path_length < 0 or audit_calls != path_length:
                raise ValueError(f"{case_id}: {side} audit/path mismatch")
            if total_work > max_work:
                raise ValueError(f"{case_id}: {side} reached above total budget")
        else:
            if correctness != "NOT_EVALUATED" or audit_calls != 0:
                raise ValueError(f"{case_id}: unreached {side} path was audited")

    expected_newly_solved = (
        not case["baselineReached"]
        and case["candidateReached"]
        and case["programUsed"]
        and case["candidatePathCorrectness"] == "CONFIRMED"
    )
    if case["newlySolved"] != expected_newly_solved:
        raise ValueError(f"{case_id}: newlySolved mismatch")


def comparable(case: dict[str, Any]) -> bool:
    return (
        case["programUsed"]
        and case["baselineReached"]
        and case["candidateReached"]
        and case["baselinePathCorrectness"] == "CONFIRMED"
        and case["candidatePathCorrectness"] == "CONFIRMED"
        and case["candidateTotalWorkUnits"]
        <= case["baselineTotalWorkUnits"]
    )


def verify_raw_components(evidence: dict[str, Any]) -> None:
    cases = evidence["cases"]
    components = evidence["rawComponents"]
    case_count = len(cases)
    expected = {
        "SUPPORT": permille(
            sum(
                case["candidateReached"]
                and case["candidatePathCorrectness"] == "CONFIRMED"
                for case in cases
            ),
            case_count,
        ),
        "TRAIN_CASES_NEWLY_SOLVED": permille(
            sum(case["newlySolved"] for case in cases),
            case_count,
        ),
    }

    comparable_cases = [case for case in cases if comparable(case)]
    primitive_baseline = sum(
        max(1, case["baselinePrimitiveSteps"])
        for case in comparable_cases
    )
    primitive_delta = sum(
        case["baselinePrimitiveSteps"] - case["candidatePrimitiveSteps"]
        for case in comparable_cases
    )
    state_baseline = sum(
        max(1, case["baselineExploredStates"])
        for case in comparable_cases
    )
    state_delta = sum(
        case["baselineExploredStates"] - case["candidateExploredStates"]
        for case in comparable_cases
    )
    expected["TRAIN_PATH_LENGTH_REDUCTION"] = normalized_delta(
        primitive_delta, primitive_baseline
    )
    expected["TRAIN_EXPLORED_STATE_REDUCTION"] = normalized_delta(
        state_delta, state_baseline
    )

    for component, value in expected.items():
        if components.get(component) != value:
            raise ValueError(
                f"raw component {component} mismatch: "
                f"{components.get(component)} != {value}"
            )


def main() -> None:
    args = parse_args()
    root = args.root.resolve()
    schemas = args.schemas.resolve()
    documents = {
        "suite": load(root / "suite.json"),
        "protocol": load(root / "protocol.json"),
        "candidate": load(root / "candidate.json"),
        "evidence": load(root / "evidence.json"),
    }
    schema_files = {
        "suite": "regelsuche-evolution-rewrite-program-train-suite-v1.schema.json",
        "protocol": "regelsuche-evolution-rewrite-program-evaluation-protocol-v1.schema.json",
        "candidate": "regelsuche-evolution-rewrite-program-candidate-v1.schema.json",
        "evidence": "regelsuche-evolution-rewrite-program-train-fitness-v1.schema.json",
    }
    for label, document in documents.items():
        validate_schema(document, schemas / schema_files[label], label)
        verify_content_hash(document, label)

    suite = documents["suite"]
    protocol = documents["protocol"]
    candidate = documents["candidate"]
    evidence = documents["evidence"]
    if evidence["suiteHash"] != suite["contentHash"]:
        raise ValueError("evidence does not bind the exact suite")
    if evidence["evaluationProtocolHash"] != protocol["contentHash"]:
        raise ValueError("evidence does not bind the exact protocol")
    if evidence["candidateHash"] != candidate["contentHash"]:
        raise ValueError("evidence does not bind the exact candidate")
    if evidence["genomeHash"] != candidate["genomeHash"]:
        raise ValueError("evidence genome hash differs from candidate")
    if evidence["planHash"] != candidate["planHash"]:
        raise ValueError("evidence plan hash differs from candidate")

    budget = suite["primitiveWorkBudget"]
    for case in evidence["cases"]:
        verify_case(case, budget)
    verify_raw_components(evidence)
    print(
        "OK: rewrite-program work evidence is schema-valid, content-addressed, "
        "cross-bound and independently recomputable"
    )


if __name__ == "__main__":
    main()
