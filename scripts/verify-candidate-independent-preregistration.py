#!/usr/bin/env python3
"""Fail-closed verification for the candidate-independent benchmark preregistration."""

from __future__ import annotations

import hashlib
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

EXPECTED_SCHEMA = "regelsuche.candidate-independent-benchmark-source/v1"
EXPECTED_PORTFOLIO = "regelsuche-evaluator-backed-challenges-2026-07/v1"
EXPECTED_PORTFOLIO_HASH = "sha256:b1b8caa2eacab13ad859506ce1a6c409a97262cf868c9ed6a5f5ad89b1ccb2e9"
EXPECTED_SPLITS = {"TRAIN", "VALIDATION", "TEST"}
EXPECTED_CHALLENGES = {
    "rational-assumption-rewrites",
    "finite-difference-recurrences",
    "reusable-search-macros",
}
EXPECTED_OUTCOMES = {
    "ACCEPTED", "REJECTED", "DISPROVED", "NO_RESULT",
    "TIMEOUT", "UNSUPPORTED", "INCOMPLETE",
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


def main() -> None:
    path = Path(sys.argv[1] if len(sys.argv) > 1 else
                "research/benchmarks/candidate-independent/benchmark-source.json")
    document = load_unique_json(path)

    required = {
        "schema", "benchmarkId", "portfolioId", "portfolioContentHash",
        "claimPolicy", "publicationAuthorized", "executionStatus",
        "formationVisibility", "budgets", "metrics", "cases",
    }
    unknown = set(document) - required
    missing = required - set(document)
    if unknown or missing:
        fail(f"unknown={sorted(unknown)} missing={sorted(missing)}")
    if document["schema"] != EXPECTED_SCHEMA:
        fail("unexpected schema")
    if document["portfolioId"] != EXPECTED_PORTFOLIO:
        fail("portfolio identity drift")
    if document["portfolioContentHash"] != EXPECTED_PORTFOLIO_HASH:
        fail("portfolio content hash drift")
    if document["executionStatus"] != "NOT_STARTED":
        fail("evaluated execution must not start in the preregistration")
    if document["publicationAuthorized"] is not False:
        fail("publication must remain unauthorized")
    if document["formationVisibility"]["allowedSplits"] != ["TRAIN"]:
        fail("candidate formation visibility is not TRAIN-only")

    cases = document["cases"]
    if len(cases) != 18:
        fail(f"expected 18 cases, found {len(cases)}")
    ids = [case["caseId"] for case in cases]
    if len(ids) != len(set(ids)):
        fail("duplicate caseId")

    split_counts = Counter(case["split"] for case in cases)
    if split_counts != Counter({"TRAIN": 6, "VALIDATION": 6, "TEST": 6}):
        fail(f"unexpected split counts: {dict(split_counts)}")
    challenges = {case["challengeId"] for case in cases}
    if challenges != EXPECTED_CHALLENGES:
        fail(f"unexpected challenges: {sorted(challenges)}")

    cluster_splits: dict[tuple[str, str], set[str]] = defaultdict(set)
    for case in cases:
        if case["split"] not in EXPECTED_SPLITS:
            fail(f"unknown split in {case['caseId']}")
        expected_visibility = case["split"] == "TRAIN"
        if case["formationVisible"] is not expected_visibility:
            fail(f"formation visibility leak in {case['caseId']}")
        if case["expectedAnswerVisible"] is not False:
            fail(f"expected answer leak in {case['caseId']}")
        if case["targetVisibleDuringFormation"] is not False:
            fail(f"target leak in {case['caseId']}")
        if set(case["outcomePolicy"]) != EXPECTED_OUTCOMES:
            fail(f"incomplete outcome accounting in {case['caseId']}")
        cluster_splits[(case["challengeId"], case["structuralCluster"])].add(case["split"])

    for cluster, assigned_splits in cluster_splits.items():
        if assigned_splits != EXPECTED_SPLITS:
            fail(f"cluster {cluster} is not represented in all frozen splits")

    canonical = json.dumps(document, sort_keys=True, separators=(",", ":")).encode()
    digest = hashlib.sha256(canonical).hexdigest()
    print(f"verifiedBenchmarkId={document['benchmarkId']}")
    print(f"canonicalSourceHash=sha256:{digest}")
    print("splitCounts=TRAIN:6,VALIDATION:6,TEST:6")
    print("executionStatus=NOT_STARTED")


if __name__ == "__main__":
    main()
