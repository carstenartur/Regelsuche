#!/usr/bin/env python3
"""Generate a vector-only discovery-cost ledger from canonical #383 runs."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any

SCHEMA = "regelsuche.discovery-cost-ledger/v1"
BENCHMARK = "regelsuche-candidate-independent-autonomous-discovery-2026-07/v1"


def load(path: Path) -> dict[str, Any]:
    def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"duplicate field {key!r}")
            result[key] = value
        return result

    value = json.loads(
        path.read_text(encoding="utf-8"), object_pairs_hook=unique_object
    )
    if not isinstance(value, dict):
        raise ValueError(f"expected object: {path}")
    return value


def semantic_hash(value: Any) -> str:
    encoded = json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return "sha256:" + hashlib.sha256(encoded).hexdigest()


def resource(
    dimension: str,
    unit: str,
    configured: int | None,
    executed: int,
    status: str,
    sources: list[str],
) -> dict[str, Any]:
    remaining = None if configured is None else configured - executed
    if remaining is not None and remaining < 0:
        raise ValueError(f"resource exceeds configured budget: {dimension}")
    return {
        "dimension": dimension,
        "unit": unit,
        "configured": configured,
        "executed": executed,
        "remaining": remaining,
        "status": status,
        "sourcePaths": sources,
    }


def stage(
    name: str,
    states: int,
    candidates: int,
    proofs: int,
    status: str,
) -> dict[str, Any]:
    return {
        "stage": name,
        "exploredStates": states,
        "candidateEvaluations": candidates,
        "proofAttempts": proofs,
        "status": status,
    }


def sequence_resources(run: dict[str, Any]) -> dict[str, int]:
    total = {
        "exploredStates": 0,
        "generatedSuccessors": 0,
        "candidateAttempts": 0,
        "proofAttempts": 0,
    }
    for campaign in run["campaigns"]:
        for evaluation in campaign["evaluations"]:
            source = evaluation["resourceUse"]
            for field in total:
                total[field] += source[field]
    return total


def rational_resources(run: dict[str, Any]) -> dict[str, int]:
    total = {
        "states": 0,
        "candidates": 0,
        "proofs": 0,
        "formationStates": 0,
        "formationCandidates": 0,
        "evaluationStates": 0,
        "evaluationCandidates": 0,
    }
    for campaign in run["campaigns"]:
        source = campaign["resourceUse"]
        evaluation_states = sum(
            item["resourceUse"]["executedStates"]
            for item in campaign["taskEvaluations"]
        )
        evaluation_candidates = sum(
            item["resourceUse"]["executedCandidateEvaluations"]
            for item in campaign["taskEvaluations"]
        )
        total["states"] += source["executedStates"]
        total["candidates"] += source["executedCandidateEvaluations"]
        total["proofs"] += source["executedProofAttempts"]
        total["formationStates"] += source["executedStates"] - evaluation_states
        total["formationCandidates"] += (
            source["executedCandidateEvaluations"] - evaluation_candidates
        )
        total["evaluationStates"] += evaluation_states
        total["evaluationCandidates"] += evaluation_candidates
    return total


def macro_resources(run: dict[str, Any]) -> dict[str, int]:
    total = {
        "states": 0,
        "candidates": 0,
        "proofs": 0,
        "formationStates": 0,
        "formationCandidates": 0,
        "evaluationStates": 0,
        "evaluationCandidates": 0,
    }
    for campaign in run["campaigns"]:
        source = campaign["resourceUse"]
        total["states"] += source["executedStates"]
        total["candidates"] += source["executedCandidateEvaluations"]
        total["proofs"] += source["executedProofAttempts"]
        total["formationStates"] += source["formationStates"]
        total["formationCandidates"] += source["formationCandidateEvaluations"]
        total["evaluationStates"] += source["pairedSearchStates"]
        total["evaluationCandidates"] += source[
            "pairedSearchCandidateEvaluations"
        ]
    return total


def macro_amortization_reference(run: dict[str, Any]) -> dict[str, Any]:
    reference: tuple[int, int, list[dict[str, Any]]] | None = None
    for campaign in run["campaigns"]:
        cost_states = campaign["resourceUse"]["formationStates"]
        cost_candidates = campaign["resourceUse"][
            "formationCandidateEvaluations"
        ]
        cumulative_states = 0
        cumulative_candidates = 0
        tasks: list[dict[str, Any]] = []
        for index, evaluation in enumerate(campaign["pairedEvaluations"], 1):
            state_saving = (
                evaluation["baseline"]["expandedStates"]
                - evaluation["macroEnabled"]["expandedStates"]
            )
            candidate_saving = (
                evaluation["baseline"]["generatedCandidates"]
                - evaluation["macroEnabled"]["generatedCandidates"]
            )
            cumulative_states += state_saving
            cumulative_candidates += candidate_saving
            tasks.append(
                {
                    "index": index,
                    "taskId": evaluation["taskId"],
                    "split": evaluation["split"],
                    "outcome": evaluation["outcome"],
                    "exploredStateSaving": state_saving,
                    "candidateEvaluationSaving": candidate_saving,
                    "cumulativeExploredStateSaving": cumulative_states,
                    "cumulativeCandidateEvaluationSaving": cumulative_candidates,
                }
            )
        signature = (cost_states, cost_candidates, tasks)
        if reference is None:
            reference = signature
        elif signature != reference:
            raise ValueError("macro campaign amortization evidence drift")
    if reference is None:
        raise ValueError("macro run contains no campaigns")
    cost_states, cost_candidates, tasks = reference
    state_index = next(
        (
            row["index"]
            for row in tasks
            if row["cumulativeExploredStateSaving"] >= cost_states
        ),
        None,
    )
    candidate_index = next(
        (
            row["index"]
            for row in tasks
            if row["cumulativeCandidateEvaluationSaving"] >= cost_candidates
        ),
        None,
    )
    return {
        "status": "PARTIAL_VECTOR_RESULT_INCOMPLETE_LIFECYCLE_COST",
        "campaignsWithIdenticalResult": len(run["campaigns"]),
        "frozenTaskCount": len(tasks),
        "formationCost": {
            "exploredStates": cost_states,
            "candidateEvaluations": cost_candidates,
        },
        "tasks": tasks,
        "breakEven": {
            "exploredStates": {
                "decision": (
                    "BREAK_EVEN_OBSERVED"
                    if state_index is not None
                    else "NO_BREAK_EVEN_OBSERVED"
                ),
                "firstTaskIndex": state_index,
                "finalNetSaving": tasks[-1]["cumulativeExploredStateSaving"]
                - cost_states,
            },
            "candidateEvaluations": {
                "decision": (
                    "BREAK_EVEN_OBSERVED"
                    if candidate_index is not None
                    else "NO_BREAK_EVEN_OBSERVED"
                ),
                "firstTaskIndex": candidate_index,
                "finalNetSaving": tasks[-1][
                    "cumulativeCandidateEvaluationSaving"
                ]
                - cost_candidates,
            },
        },
        "totalLifecycleBreakEvenStatus": (
            "NOT_ESTABLISHED_UNEXECUTED_LIFECYCLE_STAGES"
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    for name in [
        "benchmark",
        "finite",
        "recurrence",
        "rational",
        "macro",
        "output",
    ]:
        parser.add_argument(f"--{name}", required=True, type=Path)
    parser.add_argument("--repository-revision", required=True)
    arguments = parser.parse_args()

    benchmark = load(arguments.benchmark)
    finite = load(arguments.finite)
    recurrence = load(arguments.recurrence)
    rational = load(arguments.rational)
    macro = load(arguments.macro)
    if (
        benchmark["benchmarkId"] != BENCHMARK
        or benchmark["benchmarkStatus"]
        != "COMPLETE_FROZEN_CHALLENGE_EXECUTION"
    ):
        raise ValueError("candidate-independent benchmark is incomplete")

    finite_resources = sequence_resources(finite)
    recurrence_resources = sequence_resources(recurrence)
    sequence = {
        field: finite_resources[field] + recurrence_resources[field]
        for field in finite_resources
    }
    rational_cost = rational_resources(rational)
    macro_cost = macro_resources(macro)

    challenge_ledgers = [
        {
            "challengeId": "finite-difference-recurrences",
            "sourceContentHashes": [
                finite["contentHash"],
                recurrence["contentHash"],
            ],
            "resourceVector": [
                resource(
                    "EXPLORED_STATES",
                    "state",
                    12000,
                    sequence["exploredStates"],
                    "EXECUTED",
                    [
                        "finite.evaluations.resourceUse",
                        "recurrence.evaluations.resourceUse",
                    ],
                ),
                resource(
                    "GENERATED_SUCCESSORS",
                    "successor",
                    None,
                    sequence["generatedSuccessors"],
                    "EXECUTED_WITHOUT_CONFIGURED_LIMIT",
                    [
                        "finite.evaluations.resourceUse",
                        "recurrence.evaluations.resourceUse",
                    ],
                ),
                resource(
                    "CANDIDATE_EVALUATIONS",
                    "candidate",
                    2400,
                    sequence["candidateAttempts"],
                    "EXECUTED",
                    [
                        "finite.evaluations.resourceUse",
                        "recurrence.evaluations.resourceUse",
                    ],
                ),
                resource(
                    "PROOF_ATTEMPTS",
                    "attempt",
                    400,
                    sequence["proofAttempts"],
                    "CONFIGURED_NOT_EXECUTED",
                    ["benchmark budget", "sequence evaluations"],
                ),
            ],
            "stageBreakdown": [
                stage(
                    "FORMATION_AND_HELD_OUT_EVALUATION",
                    sequence["exploredStates"],
                    sequence["candidateAttempts"],
                    0,
                    "COMBINED_NOT_SEPARATELY_METERED",
                )
            ],
        },
        {
            "challengeId": "rational-assumption-rewrites",
            "sourceContentHashes": [rational["contentHash"]],
            "resourceVector": [
                resource(
                    "EXPLORED_STATES",
                    "state",
                    12000,
                    rational_cost["states"],
                    "EXECUTED",
                    ["campaign.resourceUse"],
                ),
                resource(
                    "CANDIDATE_EVALUATIONS",
                    "candidate",
                    2400,
                    rational_cost["candidates"],
                    "EXECUTED",
                    ["campaign.resourceUse"],
                ),
                resource(
                    "PROOF_ATTEMPTS",
                    "attempt",
                    400,
                    rational_cost["proofs"],
                    "CONFIGURED_NOT_EXECUTED",
                    ["campaign.resourceUse"],
                ),
            ],
            "stageBreakdown": [
                stage(
                    "FORMATION",
                    rational_cost["formationStates"],
                    rational_cost["formationCandidates"],
                    0,
                    "RECONSTRUCTED_FROM_BALANCED_CAMPAIGN_AND_TASK_LEDGERS",
                ),
                stage(
                    "HELD_OUT_EVALUATION",
                    rational_cost["evaluationStates"],
                    rational_cost["evaluationCandidates"],
                    0,
                    "EXECUTED",
                ),
            ],
        },
        {
            "challengeId": "reusable-search-macros",
            "sourceContentHashes": [macro["contentHash"]],
            "resourceVector": [
                resource(
                    "EXPLORED_STATES",
                    "state",
                    12000,
                    macro_cost["states"],
                    "EXECUTED",
                    ["campaign.resourceUse"],
                ),
                resource(
                    "CANDIDATE_EVALUATIONS",
                    "candidate",
                    2400,
                    macro_cost["candidates"],
                    "EXECUTED",
                    ["campaign.resourceUse"],
                ),
                resource(
                    "PROOF_ATTEMPTS",
                    "attempt",
                    400,
                    macro_cost["proofs"],
                    "CONFIGURED_NOT_EXECUTED",
                    ["campaign.resourceUse"],
                ),
            ],
            "stageBreakdown": [
                stage(
                    "FORMATION",
                    macro_cost["formationStates"],
                    macro_cost["formationCandidates"],
                    0,
                    "EXECUTED",
                ),
                stage(
                    "PAIRED_HELD_OUT_EVALUATION",
                    macro_cost["evaluationStates"],
                    macro_cost["evaluationCandidates"],
                    0,
                    "EXECUTED",
                ),
            ],
        },
    ]

    total_states = (
        sequence["exploredStates"]
        + rational_cost["states"]
        + macro_cost["states"]
    )
    total_candidates = (
        sequence["candidateAttempts"]
        + rational_cost["candidates"]
        + macro_cost["candidates"]
    )
    total_proofs = (
        sequence["proofAttempts"]
        + rational_cost["proofs"]
        + macro_cost["proofs"]
    )
    output = {
        "schema": SCHEMA,
        "benchmarkId": BENCHMARK,
        "repositoryRevision": arguments.repository_revision,
        "benchmarkExecutionContentHash": benchmark["contentHash"],
        "accountingPolicy": (
            "VECTOR_RESOURCES_NO_IMPLICIT_CONVERSION_OR_DOUBLE_COUNTING"
        ),
        "configuredCampaigns": 12,
        "executedCampaigns": 12,
        "challengeLedgers": challenge_ledgers,
        "aggregateResourceVector": [
            resource(
                "EXPLORED_STATES",
                "state",
                36000,
                total_states,
                "EXECUTED",
                [],
            ),
            resource(
                "CANDIDATE_EVALUATIONS",
                "candidate",
                7200,
                total_candidates,
                "EXECUTED",
                [],
            ),
            resource(
                "PROOF_ATTEMPTS",
                "attempt",
                1200,
                total_proofs,
                "CONFIGURED_NOT_EXECUTED",
                [],
            ),
        ],
        "lifecycleCoverage": [
            {
                "stage": "VALIDATION",
                "status": "EMBEDDED_NOT_SEPARATELY_METERED",
            },
            {
                "stage": "COUNTEREXAMPLE",
                "status": "NOT_EXECUTED_IN_BENCHMARK",
            },
            {
                "stage": "PROJECT_NOVELTY",
                "status": "NOT_EXECUTED_IN_BENCHMARK",
            },
            {
                "stage": "FORMAL_PROOF",
                "status": "CONFIGURED_NOT_EXECUTED",
            },
            {
                "stage": "QUALIFICATION",
                "status": "NOT_EXECUTED_IN_BENCHMARK",
            },
        ],
        "macroAmortizationReference": macro_amortization_reference(macro),
        "overallAmortizationStatus": (
            "NOT_ESTABLISHED_INCOMPLETE_LIFECYCLE_COST_AND_SINGLE_CANDIDATE_STREAM"
        ),
        "formalProofStatus": "NOT_EVALUATED",
        "externalNoveltyStatus": "NOT_EVALUATED",
        "expertInterestingnessStatus": "NOT_EVALUATED",
        "publicationAuthorized": False,
    }
    output["contentHash"] = semantic_hash(output)
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(
        json.dumps(output, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"discoveryCostLedger={output['contentHash']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
