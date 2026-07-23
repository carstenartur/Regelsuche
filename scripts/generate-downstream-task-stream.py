#!/usr/bin/env python3
"""Generate a candidate-independent downstream stream from pre-execution frozen inputs."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any

SCHEMA = "regelsuche.downstream-task-stream/v1"
BENCHMARK = "regelsuche-candidate-independent-autonomous-discovery-2026-07/v1"
STREAM = "reusable-search-macros-held-out-task-stream/v1"
CHALLENGE = "reusable-search-macros"
PROFILE = "macro-primitives/v1"
COMPARISON = "IDENTICAL_INPUT_TARGET_INVENTORY_STRATEGY_AND_BUDGET"
REQUIRED_EVIDENCE = [
    "primitive-step-semantics",
    "baseline-search",
    "macro-enabled-search",
    "correctness-regression",
]
OUTCOMES = [
    "IMPROVED",
    "REACHABILITY_GAIN",
    "NO_IMPROVEMENT",
    "NO_RESULT",
    "CORRECTNESS_REGRESSION",
    "CANDIDATE_NOT_FORMED",
]


def load(path: Path) -> dict[str, Any]:
    def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"duplicate field {key!r}: {path}")
            result[key] = value
        return result

    value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_object)
    if not isinstance(value, dict):
        raise ValueError(f"expected JSON object: {path}")
    return value


def semantic_hash(value: Any) -> str:
    encoded = json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return "sha256:" + hashlib.sha256(encoded).hexdigest()


def require_hash(value: dict[str, Any], context: str) -> str:
    retained = value.get("contentHash")
    material = dict(value)
    material.pop("contentHash", None)
    calculated = semantic_hash(material)
    if retained != calculated:
        raise ValueError(f"{context} contentHash mismatch: {retained} != {calculated}")
    return retained


def hashed(value: dict[str, Any]) -> dict[str, Any]:
    result = dict(value)
    result["contentHash"] = semantic_hash(result)
    return result


def build_stream(
    corpus: dict[str, Any],
    receipt: dict[str, Any],
    profile: dict[str, Any],
    repository_revision: str,
) -> dict[str, Any]:
    require_hash(corpus, "case corpus")
    require_hash(receipt, "freeze receipt")
    require_hash(profile, "formation profile")

    if corpus.get("benchmarkId") != BENCHMARK:
        raise ValueError("case corpus benchmark identity changed")
    if corpus.get("freezeStatus") != "FROZEN_BEFORE_EVALUATED_EXECUTION":
        raise ValueError("case corpus is not pre-execution frozen")
    if corpus.get("executionStatusAtFreeze") != "NOT_STARTED":
        raise ValueError("case corpus had execution at freeze time")
    if receipt.get("caseCorpusContentHash") != corpus.get("contentHash"):
        raise ValueError("freeze receipt does not bind the case corpus")
    if receipt.get("executionStatusAtFreeze") != "NOT_STARTED":
        raise ValueError("freeze receipt does not predate execution")
    if receipt.get("resultInspectionStatus") != "NO_EVALUATED_RESULTS_EXIST":
        raise ValueError("freeze receipt permits evaluated-result inspection")
    if profile.get("profileId") != PROFILE:
        raise ValueError("unexpected baseline inventory profile")
    expected_profile_hash = receipt.get("formationInventoryContentHashes", {}).get(PROFILE)
    if expected_profile_hash != profile.get("contentHash"):
        raise ValueError("freeze receipt does not bind the baseline inventory")

    tasks: list[dict[str, Any]] = []
    split_counts = {"TRAIN": 0, "VALIDATION": 0, "TEST": 0}
    for case in corpus.get("cases", []):
        if case.get("challengeId") != CHALLENGE:
            continue
        exposure = case.get("exposurePolicy", {})
        if exposure.get("candidateFormationMustNotRead") != ["evaluationInput"]:
            raise ValueError(f"evaluation input is not hidden for {case.get('caseId')}")
        evaluation = case.get("evaluationInput")
        if not isinstance(evaluation, dict):
            raise ValueError(f"missing evaluation input for {case.get('caseId')}")
        if evaluation.get("comparisonPolicy") != COMPARISON:
            raise ValueError(f"comparison policy drift for {case.get('caseId')}")
        if evaluation.get("requiredEvidence") != REQUIRED_EVIDENCE:
            raise ValueError(f"required evidence drift for {case.get('caseId')}")
        split = case.get("split")
        if split not in split_counts:
            raise ValueError(f"invalid split for {case.get('caseId')}: {split}")
        for task in evaluation.get("tasks", []):
            row = hashed(
                {
                    "index": len(tasks) + 1,
                    "taskId": task["taskId"],
                    "caseId": case["caseId"],
                    "split": split,
                    "structuralCluster": case["structuralCluster"],
                    "caseContentHash": case["contentHash"],
                    "source": task["source"],
                    "target": task["target"],
                    "assumptions": task["assumptions"],
                    "searchBudget": task["searchBudget"],
                    "comparisonPolicy": COMPARISON,
                    "requiredEvidence": REQUIRED_EVIDENCE,
                }
            )
            tasks.append(row)
            split_counts[split] += 1

    if len(tasks) != 12 or split_counts != {"TRAIN": 4, "VALIDATION": 4, "TEST": 4}:
        raise ValueError(f"unexpected downstream stream shape: {len(tasks)} / {split_counts}")
    task_ids = [row["taskId"] for row in tasks]
    if len(set(task_ids)) != len(task_ids):
        raise ValueError("duplicate task identity in downstream stream")

    return hashed(
        {
            "schema": SCHEMA,
            "benchmarkId": BENCHMARK,
            "streamId": STREAM,
            "challengeId": CHALLENGE,
            "repositoryRevision": repository_revision,
            "freezeStatus": "DERIVED_ONLY_FROM_PRE_EXECUTION_FROZEN_INPUTS",
            "sourceCaseCorpusContentHash": corpus["contentHash"],
            "freezeReceiptContentHash": receipt["contentHash"],
            "formationProfileId": PROFILE,
            "baselineInventoryContentHash": profile["contentHash"],
            "combinedPreregistrationHash": receipt["combinedPreregistrationHash"],
            "orderingPolicy": "CASE_CORPUS_ORDER_THEN_TASK_ORDER",
            "comparisonPolicy": COMPARISON,
            "formationAccessPolicy": "EVALUATION_INPUT_HIDDEN_DURING_CANDIDATE_FORMATION",
            "configuredTasks": 12,
            "splitCounts": split_counts,
            "retainedOutcomeClasses": OUTCOMES,
            "evaluationStatus": "NOT_EXECUTED_BY_STREAM_CONSTRUCTION",
            "publicationAuthorized": False,
            "tasks": tasks,
        }
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--corpus", required=True, type=Path)
    parser.add_argument("--receipt", required=True, type=Path)
    parser.add_argument("--profile", required=True, type=Path)
    parser.add_argument("--repository-revision", required=True)
    parser.add_argument("--output", required=True, type=Path)
    arguments = parser.parse_args()

    stream = build_stream(
        load(arguments.corpus),
        load(arguments.receipt),
        load(arguments.profile),
        arguments.repository_revision,
    )
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(
        json.dumps(stream, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"downstreamTaskStream={stream['contentHash']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
