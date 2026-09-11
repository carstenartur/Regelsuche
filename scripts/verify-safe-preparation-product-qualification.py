#!/usr/bin/env python3
"""Independent verifier for the frozen #745 safe-preparation qualification."""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator, FormatChecker

EXPECTED_SCHEMA = "regelsuche.safe-preparation-product-qualification/v1"
EXPECTED_CONFIGURATION = "direct-v1-vs-safe-preparation-v2-matched-work-v1"
EXPECTED_DIRECT = "DIRECT_V1"
EXPECTED_SAFE = "SAFE_PREPARATION_V2"
EXPECTED_WORK_REVISION = "regelsuche.safe-preparation-work/analyze-plus-verify/v1"
EXPECTED_BLOCKERS = [
    "APPLICABILITY_SCHEMA_COVERAGE_INCOMPLETE",
    "TYPED_REPRESENTATION_PREPARATION_OUTSIDE_PROFILE",
    "OCCURRENCE_LOCAL_GUARD_BINDINGS_NOT_PRODUCT_QUALIFIED",
    "WORKBENCH_CLI_RUNTIME_ADAPTER_NOT_PRODUCT_QUALIFIED",
]
PERFECT_SQUARE_CASE = "perfect-square-native-exact-preparation"
GUARD_CASE = "telescoping-missing-guard-control"
PERFECT_SQUARE_PRIMITIVES = [
    "prepare_exact_monomial_square_structure",
    "ast_square_difference_factor",
]


def fail(message: str) -> None:
    raise SystemExit(f"safe-preparation qualification verification failed: {message}")


def no_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            fail(f"duplicate JSON key {key!r}")
        result[key] = value
    return result


def load_json(path: Path) -> dict[str, Any]:
    try:
        with path.open("r", encoding="utf-8") as stream:
            value = json.load(stream, object_pairs_hook=no_duplicates)
    except (OSError, json.JSONDecodeError) as exc:
        fail(f"cannot read {path}: {exc}")
    if not isinstance(value, dict):
        fail(f"{path} must contain a JSON object")
    return value


def validate_schema(report: dict[str, Any], schema: dict[str, Any]) -> None:
    validator = Draft202012Validator(schema, format_checker=FormatChecker())
    errors = sorted(validator.iter_errors(report), key=lambda error: list(error.path))
    if errors:
        first = errors[0]
        location = "/".join(str(part) for part in first.path) or "<root>"
        fail(f"schema violation at {location}: {first.message}")


def assert_route(route: dict[str, Any], expected_profile: str, inventory: str) -> None:
    if route["profileId"] != expected_profile:
        fail(f"route profile mismatch: {route['profileId']} != {expected_profile}")
    if route["visibleInventoryFingerprint"] != inventory:
        fail("route visible inventory differs from frozen report inventory")
    if route["technicalFailure"]:
        if route["searchStatus"] != "TECHNICAL_FAILURE":
            fail("technical failure route does not retain TECHNICAL_FAILURE status")
        if route["syntacticallyReached"] or route["semanticReached"]:
            fail("technical failure route claims target reachability")
        if route["edgeDepth"] != -1 or route["primitiveDepth"] != 0:
            fail("technical failure route retains a non-empty path depth")
    if route["semanticReached"]:
        if not route["syntacticallyReached"]:
            fail("semantic success without syntactic target reachability")
        if not route["constructionSafe"]:
            fail("semantic success uses a non-construction-safe path")
        if route["missingAssumptions"]:
            fail("semantic success retains missing assumptions")
    if route["syntacticallyReached"]:
        if route["edgeDepth"] < 0:
            fail("reached route has negative edge depth")
        if route["primitiveDepth"] != len(route["primitiveRuleIds"]):
            fail("primitive depth differs from retained primitive lineage")
    else:
        if route["edgeDepth"] != -1 or route["primitiveDepth"] != 0:
            fail("unreached route retains a non-empty path depth")


def assert_telemetry(case: dict[str, Any]) -> None:
    direct = case["direct"]["safeTelemetry"]
    if any(value != 0 for value in direct.values()):
        fail(f"DIRECT route unexpectedly contains SAFE telemetry in {case['id']}")

    safe = case["safe"]["safeTelemetry"]
    if safe["coordinatorCalls"] != safe["verificationCalls"]:
        fail(f"SAFE analyze/verification call mismatch in {case['id']}")
    if safe["analyzeMechanicalWork"] != safe["verificationReplayMechanicalWork"]:
        fail(f"verification replay is not charged as a full deterministic recomputation in {case['id']}")
    if safe["chargedCoordinatorMechanicalWork"] != (
        safe["analyzeMechanicalWork"] + safe["verificationReplayMechanicalWork"]
    ):
        fail(f"charged coordinator work is inconsistent in {case['id']}")
    if safe["verificationFailures"] != 0:
        fail(f"coordinator replay failed in {case['id']}")


def recompute_case(case: dict[str, Any], inventory: str) -> tuple[bool, bool, bool, bool, bool]:
    direct = case["direct"]
    safe = case["safe"]
    assert_route(direct, EXPECTED_DIRECT, inventory)
    assert_route(safe, EXPECTED_SAFE, inventory)
    assert_telemetry(case)

    technical_failure = bool(direct["technicalFailure"] or safe["technicalFailure"])
    expectations = (
        not technical_failure
        and direct["semanticReached"] == case["expectDirectSemanticReachability"]
        and safe["semanticReached"] == case["expectSafeSemanticReachability"]
    )
    reachability_regression = direct["semanticReached"] and not safe["semanticReached"]
    correctness_regression = (
        safe["syntacticallyReached"]
        and not safe["semanticReached"]
        and direct["semanticReached"]
    )
    assumption_regression = (
        direct["semanticReached"]
        and safe["semanticReached"]
        and direct["effectiveAssumptions"] != safe["effectiveAssumptions"]
    )
    newly_reached = (
        not technical_failure
        and not direct["semanticReached"]
        and safe["semanticReached"]
    )

    expected_flags = {
        "expectationsSatisfied": expectations,
        "reachabilityRegression": reachability_regression,
        "correctnessRegression": correctness_regression,
        "assumptionRegression": assumption_regression,
        "newlyReachedBySafe": newly_reached,
    }
    for key, expected in expected_flags.items():
        if case[key] != expected:
            fail(f"case {case['id']} has inconsistent {key}")
    return (
        expectations,
        reachability_regression,
        correctness_regression,
        assumption_regression,
        newly_reached,
    )


def verify(report: dict[str, Any]) -> None:
    if report["schema"] != EXPECTED_SCHEMA:
        fail("unexpected report schema")
    if report["configurationId"] != EXPECTED_CONFIGURATION:
        fail("unexpected configuration identity")
    if report["directProfileId"] != EXPECTED_DIRECT or report["safeProfileId"] != EXPECTED_SAFE:
        fail("unexpected paired profile identities")
    if report["safeWorkAccountingRevision"] != EXPECTED_WORK_REVISION:
        fail("unexpected SAFE work-accounting revision")
    if not report["verificationReplayCharged"]:
        fail("SAFE verification replay is not charged")
    if not report["directRouteIsNoPreparationAblation"]:
        fail("DIRECT route is not declared as the no-preparation ablation")
    if report["productBlockers"] != EXPECTED_BLOCKERS:
        fail("frozen product-blocker list changed without a schema revision")

    cases = report["cases"]
    ids = [case["id"] for case in cases]
    if len(ids) != len(set(ids)):
        fail("duplicate case ID")

    flags = [recompute_case(case, report["visibleInventoryFingerprint"]) for case in cases]
    newly_reached = sum(1 for flag in flags if flag[4])
    common = [case for case in cases if case["direct"]["semanticReached"] and case["safe"]["semanticReached"]]
    common_direct_work = sum(case["direct"]["chargedTotalWork"] for case in common)
    common_safe_work = sum(case["safe"]["chargedTotalWork"] for case in common)
    direct_semantic_rejects = sum(
        1 for case in cases
        if case["direct"]["syntacticallyReached"] and not case["direct"]["semanticReached"]
    )
    safe_semantic_rejects = sum(
        1 for case in cases
        if case["safe"]["syntacticallyReached"] and not case["safe"]["semanticReached"]
    )

    aggregates = {
        "newlyReachedCases": newly_reached,
        "commonSolvedCases": len(common),
        "commonDirectWork": common_direct_work,
        "commonSafeWork": common_safe_work,
        "directSyntacticButSemanticallyRejectedCases": direct_semantic_rejects,
        "safeSyntacticButSemanticallyRejectedCases": safe_semantic_rejects,
    }
    for key, expected in aggregates.items():
        if report[key] != expected:
            fail(f"aggregate {key} does not match case material")

    recomputed_qualified = (
        all(flag[0] for flag in flags)
        and not any(flag[1] for flag in flags)
        and not any(flag[2] for flag in flags)
        and not any(flag[3] for flag in flags)
        and newly_reached >= 1
        and all(
            not case["direct"]["technicalFailure"]
            and not case["safe"]["technicalFailure"]
            for case in cases
        )
    )
    if report["evidenceQualified"] != recomputed_qualified:
        fail("evidenceQualified differs from independently recomputed decision")
    if not recomputed_qualified:
        fail("frozen qualification evidence is not green")

    expected_decision = (
        "KEEP_OPT_IN_PENDING_PRODUCT_COVERAGE"
        if report["productBlockers"]
        else "SELECT_SAFE_PREPARATION_V2"
    )
    if report["productDecision"] != expected_decision:
        fail("product decision does not follow retained blockers")

    by_id = {case["id"]: case for case in cases}
    exact = by_id.get(PERFECT_SQUARE_CASE)
    if exact is None:
        fail("missing perfect-square capability case")
    if exact["direct"]["semanticReached"] or not exact["safe"]["semanticReached"]:
        fail("perfect-square case is not newly reachable through SAFE")
    if exact["safe"]["primitiveRuleIds"] != PERFECT_SQUARE_PRIMITIVES:
        fail("perfect-square SAFE path does not retain the exact two-step primitive proof")

    guard = by_id.get(GUARD_CASE)
    if guard is None:
        fail("missing guard negative-control case")
    if not guard["direct"]["syntacticallyReached"] or guard["direct"]["semanticReached"]:
        fail("DIRECT guard control no longer distinguishes syntactic from semantic reachability")
    if guard["direct"]["missingAssumptions"] != guard["requiredAssumptions"]:
        fail("DIRECT guard control does not retain the exact missing assumptions")
    if guard["safe"]["semanticReached"]:
        fail("SAFE admitted the missing-guard control")


def main(argv: list[str]) -> int:
    if sys.flags.optimize != 0:
        fail("optimized Python mode is not permitted for evidence verification")
    if len(argv) != 3:
        fail("usage: verify-safe-preparation-product-qualification.py REPORT SCHEMA")
    report_path = Path(argv[1])
    schema_path = Path(argv[2])
    report = load_json(report_path)
    schema = load_json(schema_path)
    validate_schema(report, schema)
    verify(report)
    print(
        "safe-preparation-product-qualification=VERIFIED "
        f"cases={len(report['cases'])} "
        f"newlyReached={report['newlyReachedCases']} "
        f"decision={report['productDecision']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
