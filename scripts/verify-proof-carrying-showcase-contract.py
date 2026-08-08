#!/usr/bin/env python3
"""Fail-closed verifier for the unexecuted proof-carrying showcase contract."""

from __future__ import annotations

import hashlib
import importlib.util
import json
import sys
from pathlib import Path
from typing import Any

sys.dont_write_bytecode = True

PLAN_SCHEMA = "regelsuche.proof-carrying-self-improvement-showcase-plan/v1"
EXPECTED_SHOWCASE_ID = "proof-carrying-self-improvement-2026-08/v1"
EXPECTED_CLAIM_POLICY = (
    "SHOWCASE_CONFIRMED_DOES_NOT_IMPLY_EXPERT_REVIEW_OR_EXTERNAL_NOVELTY"
)
EXPECTED_CHAIN_HASH = (
    "8990e7a9aaed2ffed73dbd7092123d6f289930540d7651336225dc172e51b2ce"
)
EXPECTED_FAMILIES = [
    "nested-rational-cancellation",
    "factor-cancel-collect",
    "multi-stage-rational-polynomial",
]
EXPECTED_CONFIGURATIONS = [
    "primitive-best-first",
    "preregistered-handwritten-program",
    "random-valid-program",
    "no-composition-ablation",
    "no-decision-ablation",
    "learned-program",
]
EXPECTED_METRICS = [
    "reachedCases",
    "newlyReachedCases",
    "distinctImprovedFamilies",
    "canonicalPrimitiveWork",
    "canonicalTotalWork",
    "exploredStates",
    "generatedCandidates",
    "pathPrimitiveSteps",
    "correctnessRegressions",
    "hiddenAssumptionRegressions",
    "technicalFailures",
]
EXPECTED_ARTIFACTS = [
    "showcase-plan.json",
    "candidate-freeze.json",
    "public-randomness-receipt.json",
    "showcase-seed-receipt.json",
    "generated-final-test.json",
    "baseline-results.json",
    "learned-program-results.json",
    "showcase-result-card.json",
    "showcase-run.json",
]
EXPECTED_SCHEMAS = {
    "regelsuche-proof-carrying-showcase-plan-v1.schema.json": PLAN_SCHEMA,
    "regelsuche-proof-carrying-showcase-candidate-freeze-v1.schema.json":
        "regelsuche.proof-carrying-showcase-candidate-freeze/v1",
    "regelsuche-proof-carrying-showcase-public-randomness-receipt-v1.schema.json":
        "regelsuche.proof-carrying-showcase-public-randomness-receipt/v1",
    "regelsuche-proof-carrying-showcase-seed-receipt-v1.schema.json":
        "regelsuche.proof-carrying-showcase-seed-receipt/v1",
}


class VerificationError(ValueError):
    """Raised when the frozen showcase contract is inconsistent."""


def fail(message: str) -> None:
    raise VerificationError(message)


def load_unique_json(path: Path) -> dict[str, Any]:
    def hook(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                fail(f"duplicate JSON field {key!r} in {path}")
            result[key] = value
        return result

    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=hook)
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        fail(f"unable to read strict JSON {path}: {exc}")
    if not isinstance(value, dict):
        fail(f"top-level JSON must be an object: {path}")
    return value


def require_exact_fields(value: dict[str, Any], expected: set[str], context: str) -> None:
    actual = set(value)
    unknown = sorted(actual - expected)
    missing = sorted(expected - actual)
    if unknown or missing:
        fail(f"{context} fields differ: unknown={unknown}, missing={missing}")


def canonical_hash(value: dict[str, Any]) -> str:
    payload = {key: item for key, item in value.items() if key != "contentHash"}
    canonical = json.dumps(
        payload,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    ).encode("utf-8")
    return "sha256:" + hashlib.sha256(canonical).hexdigest()


def verify_plan(plan: dict[str, Any]) -> None:
    require_exact_fields(
        plan,
        {
            "schema",
            "showcaseId",
            "issue",
            "status",
            "claimPolicy",
            "publicationGradeFlagship",
            "candidateFormation",
            "publicRandomness",
            "challengeGenerator",
            "comparison",
            "acceptance",
            "requiredArtifacts",
            "stageStates",
            "contentHash",
        },
        "showcase plan",
    )
    if plan["schema"] != PLAN_SCHEMA:
        fail("unexpected showcase-plan schema")
    if plan["showcaseId"] != EXPECTED_SHOWCASE_ID:
        fail("showcase identity drift")
    if plan["issue"] != 597:
        fail("showcase issue binding drift")
    if plan["status"] != "CONTRACT_FROZEN_NOT_RUN":
        fail("the committed contract must remain unexecuted")
    if plan["claimPolicy"] != EXPECTED_CLAIM_POLICY:
        fail("showcase claim policy drift")
    if plan["publicationGradeFlagship"] != "DEFERRED_PENDING_INDEPENDENT_REVIEW":
        fail("publication-grade flagship boundary drift")
    expected_hash = canonical_hash(plan)
    if plan["contentHash"] != expected_hash:
        fail(
            "showcase plan contentHash mismatch: "
            f"declared={plan['contentHash']}, expected={expected_hash}"
        )

    formation = plan["candidateFormation"]
    require_exact_fields(
        formation,
        {
            "visibleSplits",
            "prohibitedInformation",
            "candidateFreezeRequiredBeforeRandomnessRound",
            "requiredCandidateProperties",
        },
        "candidateFormation",
    )
    if formation["visibleSplits"] != ["TRAIN"]:
        fail("candidate formation is not TRAIN-only")
    if formation["prohibitedInformation"] != [
        "FINAL_TEST_SEED",
        "FINAL_TEST_CASES",
        "DRAND_RANDOMNESS",
        "EXPERT_LABELS",
        "EXTERNAL_NOVELTY_RESULTS",
    ]:
        fail("candidate-formation prohibited-information contract drift")
    if formation["candidateFreezeRequiredBeforeRandomnessRound"] is not True:
        fail("candidate freeze must precede public randomness")
    if formation["requiredCandidateProperties"] != {
        "notSeedEquivalent": True,
        "compositionTopology": True,
        "decisionTopology": True,
        "minimumPrimitiveStepsOnSuccessfulPath": 3,
    }:
        fail("required candidate properties drift")

    randomness = plan["publicRandomness"]
    require_exact_fields(
        randomness,
        {
            "provider",
            "network",
            "chainHash",
            "apiVersion",
            "roundEndpointTemplate",
            "roundSelection",
            "signatureVerification",
            "seedDerivation",
            "minimumDelaySecondsAfterCandidateFreeze",
        },
        "publicRandomness",
    )
    if randomness != {
        "provider": "DRAND_LEAGUE_OF_ENTROPY",
        "network": "default",
        "chainHash": EXPECTED_CHAIN_HASH,
        "apiVersion": "v1",
        "roundEndpointTemplate": "/{chainHash}/public/{round}",
        "roundSelection": "FIRST_VERIFIED_ROUND_STRICTLY_AFTER_CANDIDATE_NOT_BEFORE",
        "signatureVerification": "PINNED_DRAND_CLIENT_AND_CHAIN_INFO_REQUIRED",
        "seedDerivation": "SHA256_DOMAIN_SEPARATED_V1",
        "minimumDelaySecondsAfterCandidateFreeze": 300,
    }:
        fail("public-randomness contract drift")

    generator = plan["challengeGenerator"]
    require_exact_fields(
        generator,
        {
            "generatorId",
            "caseCount",
            "families",
            "sameGeneratedCasesForAllConfigurations",
            "assumptionsRetained",
            "caseIdentity",
            "manualReplacementOrPruning",
        },
        "challengeGenerator",
    )
    if generator["generatorId"] != "proof-carrying-symbolic-stress-ladders/v1":
        fail("challenge-generator identity drift")
    if generator["caseCount"] != 24:
        fail("showcase must freeze exactly 24 generated cases")
    families = generator["families"]
    if not isinstance(families, list) or len(families) != 3:
        fail("showcase must freeze exactly three structural families")
    if [family.get("familyId") for family in families] != EXPECTED_FAMILIES:
        fail("challenge-family identity or ordering drift")
    if sum(family.get("caseCount", 0) for family in families) != 24:
        fail("family case counts do not balance to the frozen case count")
    for family in families:
        require_exact_fields(
            family,
            {"familyId", "caseCount", "difficultyLevels"},
            f"family {family.get('familyId')}",
        )
        if family["caseCount"] != 8 or family["difficultyLevels"] != [3, 4, 5, 6]:
            fail(f"family stress ladder drift: {family['familyId']}")
    if generator["sameGeneratedCasesForAllConfigurations"] is not True:
        fail("all configurations must receive the same generated cases")
    if generator["assumptionsRetained"] is not True:
        fail("generated assumptions must remain retained")
    if generator["caseIdentity"] != "SHA256_CANONICAL_CASE_V1":
        fail("case identity contract drift")
    if generator["manualReplacementOrPruning"] != "FORBIDDEN":
        fail("manual case replacement or pruning must remain forbidden")

    comparison = plan["comparison"]
    require_exact_fields(
        comparison,
        {
            "matchedInformation",
            "matchedMechanicalWork",
            "configurations",
            "authoritativeMetrics",
            "elapsedTimeRole",
        },
        "comparison",
    )
    if comparison["matchedInformation"] is not True:
        fail("comparison must preserve information parity")
    if comparison["matchedMechanicalWork"] is not True:
        fail("comparison must preserve matched mechanical work")
    if comparison["configurations"] != EXPECTED_CONFIGURATIONS:
        fail("comparison configuration contract drift")
    if comparison["authoritativeMetrics"] != EXPECTED_METRICS:
        fail("authoritative metric contract drift")
    if comparison["elapsedTimeRole"] != "ENVIRONMENT_QUALIFIED_DIAGNOSTIC_ONLY":
        fail("elapsed time must not replace the canonical work ledger")

    acceptance = plan["acceptance"]
    require_exact_fields(
        acceptance,
        {
            "minimumImprovedCases",
            "minimumDistinctImprovedFamilies",
            "minimumNewlyReachedCases",
            "minimumMedianCanonicalWorkReductionPermille",
            "maximumCorrectnessRegressions",
            "maximumHiddenAssumptionRegressions",
            "maximumTechnicalFailures",
            "positiveRoute",
            "stretchRoute",
            "nullResultPolicy",
            "requiredCleanReproductions",
            "requirePinnedContainerReproduction",
        },
        "acceptance",
    )
    if acceptance != {
        "minimumImprovedCases": 4,
        "minimumDistinctImprovedFamilies": 2,
        "minimumNewlyReachedCases": 2,
        "minimumMedianCanonicalWorkReductionPermille": 900,
        "maximumCorrectnessRegressions": 0,
        "maximumHiddenAssumptionRegressions": 0,
        "maximumTechnicalFailures": 0,
        "positiveRoute": "NEW_REACHABILITY_OR_TEN_X_MEDIAN_CANONICAL_WORK_REDUCTION",
        "stretchRoute": "HUNDRED_X_WORK_REDUCTION_OR_TWO_ADDITIONAL_DIFFICULTY_LEVELS",
        "nullResultPolicy": "COMPLETE_SHOWCASE_NULL_RESULT_WITHOUT_THRESHOLD_OR_CASE_REPAIR",
        "requiredCleanReproductions": 2,
        "requirePinnedContainerReproduction": True,
    }:
        fail("showcase acceptance thresholds drift")

    if plan["requiredArtifacts"] != EXPECTED_ARTIFACTS:
        fail("required artifact contract drift")
    if plan["stageStates"] != {
        "candidateFreeze": "NOT_CREATED",
        "publicRandomness": "NOT_AVAILABLE",
        "generatedFinalTest": "NOT_CREATED",
        "execution": "NOT_RUN",
        "resultCard": "NOT_CREATED",
        "expertReview": "DEFERRED",
        "externalNovelty": "NOT_EVALUATED",
    }:
        fail("pre-execution stage states drift")


def verify_schemas(repository_root: Path) -> None:
    schema_root = repository_root / "docs" / "schemas"
    for filename, runtime_schema in EXPECTED_SCHEMAS.items():
        path = schema_root / filename
        schema = load_unique_json(path)
        if schema.get("$schema") != "https://json-schema.org/draft/2020-12/schema":
            fail(f"{filename} is not Draft 2020-12")
        if schema.get("$id") != runtime_schema:
            fail(f"{filename} runtime schema identity drift")
        if schema.get("type") != "object" or schema.get("additionalProperties") is not False:
            fail(f"{filename} must fail closed on top-level fields")
        required = schema.get("required")
        properties = schema.get("properties")
        if not isinstance(required, list) or not isinstance(properties, dict):
            fail(f"{filename} lacks required/properties contracts")
        if set(required) != set(properties):
            fail(f"{filename} required fields differ from declared properties")


def run_seed_derivation_self_test(repository_root: Path, plan_path: Path) -> None:
    script = repository_root / "scripts" / "derive-proof-carrying-showcase-seed.py"
    spec = importlib.util.spec_from_file_location("showcase_seed_deriver", script)
    if spec is None or spec.loader is None:
        fail("unable to load showcase seed derivation module")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    try:
        module.self_test(plan_path)
    except Exception as exc:
        fail(f"showcase seed derivation self-test failed: {exc}")


def main() -> None:
    plan_path = Path(
        sys.argv[1]
        if len(sys.argv) > 1
        else "research/showcase/proof-carrying-self-improvement/showcase-plan.json"
    ).resolve()
    repository_root = plan_path.parents[3]
    plan = load_unique_json(plan_path)
    verify_plan(plan)
    verify_schemas(repository_root)
    run_seed_derivation_self_test(repository_root, plan_path)
    print(f"verifiedShowcaseId={plan['showcaseId']}")
    print(f"showcasePlanContentHash={plan['contentHash']}")
    print("showcaseClaimBoundary=SHOWCASE_ONLY")
    print("publicationGradeFlagship=DEFERRED_PENDING_INDEPENDENT_REVIEW")
    print("publicRandomnessOrdering=VERIFIED")
    print("showcaseContractStatus=CONTRACT_FROZEN_NOT_RUN")


if __name__ == "__main__":
    try:
        main()
    except VerificationError as exc:
        raise SystemExit(f"proof-carrying showcase contract invalid: {exc}") from exc
