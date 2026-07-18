#!/usr/bin/env python3
"""Fail-closed verification for the candidate-independent benchmark preregistration."""

from __future__ import annotations

import hashlib
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

EXPECTED_SCHEMA = "regelsuche.candidate-independent-benchmark-source/v1"
EXPECTED_BENCHMARK_ID = "regelsuche-candidate-independent-autonomous-discovery-2026-07/v1"
EXPECTED_PORTFOLIO = "regelsuche-evaluator-backed-challenges-2026-07/v1"
EXPECTED_PORTFOLIO_HASH = "sha256:b1b8caa2eacab13ad859506ce1a6c409a97262cf868c9ed6a5f5ad89b1ccb2e9"
EXPECTED_CLAIM_POLICY = "BENCHMARK_SUCCESS_DOES_NOT_IMPLY_EXTERNAL_MATHEMATICAL_NOVELTY"
EXPECTED_SPLITS = {"TRAIN", "VALIDATION", "TEST"}
EXPECTED_CHALLENGES = {
    "rational-assumption-rewrites",
    "finite-difference-recurrences",
    "reusable-search-macros",
}
EXPECTED_OUTCOMES = [
    "ACCEPTED", "REJECTED", "DISPROVED", "NO_RESULT",
    "TIMEOUT", "UNSUPPORTED", "INCOMPLETE",
]
EXPECTED_BUDGETS = {
    "campaignsPerChallenge": 4,
    "maxCandidateEvaluations": 600,
    "maxProofAttempts": 100,
    "maxStatesPerCampaign": 3000,
}
EXPECTED_METRICS = [
    "configuredCampaigns", "executedCampaigns", "zeroOutputCampaigns",
    "acceptedCandidates", "rejectedCandidates", "disprovedCandidates",
    "heldOutReachability", "exploredStateDelta", "correctnessRegressions",
    "resourceUsage", "structuralSupportDiversity",
]
EXPECTED_PROHIBITED_FIELDS = [
    "target", "expectedAnswer", "hiddenReference", "testLabel",
    "postHocFamilyAnnotation",
]
EXPECTED_CASE_FIELDS = {
    "caseId", "challengeId", "structuralCluster", "split",
    "formationVisible", "expectedAnswerVisible",
    "targetVisibleDuringFormation", "outcomePolicy",
}


def fail(message: str) -> None:
    raise SystemExit(f"candidate-independent preregistration invalid: {message}")


def load_unique_json(path: Path) -> dict:
    def hook(pairs: list[tuple[str, object]]) -> dict:
        result: dict[str, object] = {}
        for key, value in pairs:
            if key in result:
                fail(f"duplicate JSON key {key!r}")
            result[key] = value
        return result

    try:
        return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=hook)
    except (OSError, json.JSONDecodeError) as exc:
        fail(str(exc))


def require_exact_fields(value: dict, expected: set[str], context: str) -> None:
    unknown = set(value) - expected
    missing = expected - set(value)
    if unknown or missing:
        fail(f"{context} unknown={sorted(unknown)} missing={sorted(missing)}")


def main() -> None:
    path = Path(sys.argv[1] if len(sys.argv) > 1 else
                "research/benchmarks/candidate-independent/benchmark-source.json")
    document = load_unique_json(path)

    required = {
        "schema", "benchmarkId", "portfolioId", "portfolioContentHash",
        "claimPolicy", "publicationAuthorized", "executionStatus",
        "formationVisibility", "budgets", "metrics", "cases",
    }
    require_exact_fields(document, required, "top-level")

    if document["schema"] != EXPECTED_SCHEMA:
        fail("unexpected schema")
    if document["benchmarkId"] != EXPECTED_BENCHMARK_ID:
        fail("benchmark identity drift")
    if document["portfolioId"] != EXPECTED_PORTFOLIO:
        fail("portfolio identity drift")
    if document["portfolioContentHash"] != EXPECTED_PORTFOLIO_HASH:
        fail("portfolio content hash drift")
    if document["claimPolicy"] != EXPECTED_CLAIM_POLICY:
        fail("claim policy drift")
    if document["executionStatus"] != "NOT_STARTED":
        fail("evaluated execution must not start in the preregistration")
    if document["publicationAuthorized"] is not False:
        fail("publication must remain unauthorized")
    if document["budgets"] != EXPECTED_BUDGETS:
        fail("budget drift")
    if document["metrics"] != EXPECTED_METRICS:
        fail("metric contract drift")

    visibility = document["formationVisibility"]
    require_exact_fields(visibility, {"allowedSplits", "prohibitedFields"}, "formationVisibility")
    if visibility["allowedSplits"] != ["TRAIN"]:
        fail("candidate formation visibility is not TRAIN-only")
    if visibility["prohibitedFields"] != EXPECTED_PROHIBITED_FIELDS:
        fail("prohibited formation fields drift")

    cases = document["cases"]
    if not isinstance(cases, list):
        fail("cases must be an array")
    if len(cases) != 18:
        fail(f"expected 18 cases, found {len(cases)}")

    ids = [case.get("caseId") for case in cases if isinstance(case, dict)]
    if len(ids) != len(cases):
        fail("every case must be an object")
    if len(ids) != len(set(ids)):
        fail("duplicate caseId")

    split_counts = Counter(case["split"] for case in cases)
    if split_counts != Counter({"TRAIN": 6, "VALIDATION": 6, "TEST": 6}):
        fail(f"unexpected split counts: {dict(split_counts)}")
    challenges = {case["challengeId"] for case in cases}
    if challenges != EXPECTED_CHALLENGES:
        fail(f"unexpected challenges: {sorted(challenges)}")

    challenge_split_counts: dict[str, Counter] = defaultdict(Counter)
    cluster_owner: dict[tuple[str, str], str] = {}
    for case in cases:
        require_exact_fields(case, EXPECTED_CASE_FIELDS, f"case {case.get('caseId')}")
        split = case["split"]
        if split not in EXPECTED_SPLITS:
            fail(f"unknown split in {case['caseId']}")
        challenge_split_counts[case["challengeId"]][split] += 1

        expected_visibility = split == "TRAIN"
        if case["formationVisible"] is not expected_visibility:
            fail(f"formation visibility leak in {case['caseId']}")
        if case["expectedAnswerVisible"] is not False:
            fail(f"expected answer leak in {case['caseId']}")
        if case["targetVisibleDuringFormation"] is not False:
            fail(f"target leak in {case['caseId']}")

        policy = case["outcomePolicy"]
        if not isinstance(policy, list):
            fail(f"outcome policy is not an array in {case['caseId']}")
        if len(policy) != len(set(policy)):
            fail(f"duplicate outcome in {case['caseId']}")
        if policy != EXPECTED_OUTCOMES:
            fail(f"outcome policy drift in {case['caseId']}")

        cluster_key = (case["challengeId"], case["structuralCluster"])
        previous_split = cluster_owner.setdefault(cluster_key, split)
        if previous_split != split:
            fail(
                f"structural cluster {cluster_key} crosses splits "
                f"({previous_split}, {split})"
            )

    expected_per_challenge = Counter({"TRAIN": 2, "VALIDATION": 2, "TEST": 2})
    for challenge in EXPECTED_CHALLENGES:
        if challenge_split_counts[challenge] != expected_per_challenge:
            fail(
                f"challenge {challenge} has unexpected split counts: "
                f"{dict(challenge_split_counts[challenge])}"
            )

    canonical = json.dumps(document, sort_keys=True, separators=(",", ":")).encode()
    digest = hashlib.sha256(canonical).hexdigest()
    print(f"verifiedBenchmarkId={document['benchmarkId']}")
    print(f"canonicalSourceHash=sha256:{digest}")
    print("splitCounts=TRAIN:6,VALIDATION:6,TEST:6")
    print("splitUnitIsolation=VERIFIED")
    print("executionStatus=NOT_STARTED")


if __name__ == "__main__":
    main()
