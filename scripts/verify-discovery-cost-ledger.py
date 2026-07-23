#!/usr/bin/env python3
"""Independently verify the vector discovery-cost ledger and partial amortization."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any

import jsonschema


def fail(message: str) -> None:
    raise RuntimeError(message)


def load(path: Path) -> dict[str, Any]:
    if not path.is_file() or path.is_symlink():
        fail(f"expected regular non-symbolic file: {path}")

    def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                fail(f"duplicate field {key!r}")
            result[key] = value
        return result

    value = json.loads(
        path.read_text(encoding="utf-8"), object_pairs_hook=unique_object
    )
    if not isinstance(value, dict):
        fail(f"expected JSON object: {path}")
    return value


def canonical(value: Any) -> bytes:
    return json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")


def semantic_hash(value: Any) -> str:
    return "sha256:" + hashlib.sha256(canonical(value)).hexdigest()


def require_content_hash(value: dict[str, Any], context: str) -> str:
    retained = value.get("contentHash")
    material = dict(value)
    material.pop("contentHash", None)
    if retained != semantic_hash(material):
        fail(f"{context} contentHash mismatch")
    return retained


def sequence_resources(
    finite: dict[str, Any], recurrence: dict[str, Any]
) -> dict[str, int]:
    total = {
        "exploredStates": 0,
        "generatedSuccessors": 0,
        "candidateAttempts": 0,
        "proofAttempts": 0,
    }
    for run in (finite, recurrence):
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
        resource = campaign["resourceUse"]
        evaluation_states = sum(
            item["resourceUse"]["executedStates"]
            for item in campaign["taskEvaluations"]
        )
        evaluation_candidates = sum(
            item["resourceUse"]["executedCandidateEvaluations"]
            for item in campaign["taskEvaluations"]
        )
        total["states"] += resource["executedStates"]
        total["candidates"] += resource["executedCandidateEvaluations"]
        total["proofs"] += resource["executedProofAttempts"]
        total["formationStates"] += resource["executedStates"] - evaluation_states
        total["formationCandidates"] += (
            resource["executedCandidateEvaluations"] - evaluation_candidates
        )
        total["evaluationStates"] += evaluation_states
        total["evaluationCandidates"] += evaluation_candidates
    return total


def macro_resources(
    run: dict[str, Any],
) -> tuple[dict[str, int], tuple[int, int, list[dict[str, Any]]]]:
    total = {
        "states": 0,
        "candidates": 0,
        "proofs": 0,
        "formationStates": 0,
        "formationCandidates": 0,
        "evaluationStates": 0,
        "evaluationCandidates": 0,
    }
    signatures: list[tuple[int, int, list[dict[str, Any]]]] = []
    for campaign in run["campaigns"]:
        resource = campaign["resourceUse"]
        total["states"] += resource["executedStates"]
        total["candidates"] += resource["executedCandidateEvaluations"]
        total["proofs"] += resource["executedProofAttempts"]
        total["formationStates"] += resource["formationStates"]
        total["formationCandidates"] += resource[
            "formationCandidateEvaluations"
        ]
        total["evaluationStates"] += resource["pairedSearchStates"]
        total["evaluationCandidates"] += resource[
            "pairedSearchCandidateEvaluations"
        ]
        cumulative_states = 0
        cumulative_candidates = 0
        rows: list[dict[str, Any]] = []
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
            rows.append(
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
        signatures.append(
            (
                resource["formationStates"],
                resource["formationCandidateEvaluations"],
                rows,
            )
        )
    if len({repr(item) for item in signatures}) != 1:
        fail("macro campaigns do not have identical amortization evidence")
    return total, signatures[0]


def resource_index(rows: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    return {row["dimension"]: row for row in rows}


def verify_one(
    output: dict[str, Any],
    benchmark: dict[str, Any],
    finite: dict[str, Any],
    recurrence: dict[str, Any],
    rational: dict[str, Any],
    macro: dict[str, Any],
) -> None:
    for context, value in [
        ("ledger", output),
        ("benchmark", benchmark),
        ("finite", finite),
        ("recurrence", recurrence),
        ("rational", rational),
        ("macro", macro),
    ]:
        require_content_hash(value, context)
    if output["benchmarkExecutionContentHash"] != benchmark["contentHash"]:
        fail("benchmark binding mismatch")
    if benchmark["benchmarkStatus"] != "COMPLETE_FROZEN_CHALLENGE_EXECUTION":
        fail("benchmark is incomplete")

    sequence = sequence_resources(finite, recurrence)
    rational_cost = rational_resources(rational)
    macro_cost, macro_signature = macro_resources(macro)
    challenges = {
        row["challengeId"]: row for row in output["challengeLedgers"]
    }
    if list(challenges) != [
        "finite-difference-recurrences",
        "rational-assumption-rewrites",
        "reusable-search-macros",
    ]:
        fail("challenge order changed")
    expected_hashes = {
        "finite-difference-recurrences": [
            finite["contentHash"],
            recurrence["contentHash"],
        ],
        "rational-assumption-rewrites": [rational["contentHash"]],
        "reusable-search-macros": [macro["contentHash"]],
    }
    for challenge, hashes in expected_hashes.items():
        if challenges[challenge]["sourceContentHashes"] != hashes:
            fail(f"source hash drift: {challenge}")

    vectors = {
        key: resource_index(value["resourceVector"])
        for key, value in challenges.items()
    }
    expected = {
        "finite-difference-recurrences": {
            "EXPLORED_STATES": (12000, sequence["exploredStates"]),
            "GENERATED_SUCCESSORS": (
                None,
                sequence["generatedSuccessors"],
            ),
            "CANDIDATE_EVALUATIONS": (
                2400,
                sequence["candidateAttempts"],
            ),
            "PROOF_ATTEMPTS": (400, sequence["proofAttempts"]),
        },
        "rational-assumption-rewrites": {
            "EXPLORED_STATES": (12000, rational_cost["states"]),
            "CANDIDATE_EVALUATIONS": (
                2400,
                rational_cost["candidates"],
            ),
            "PROOF_ATTEMPTS": (400, rational_cost["proofs"]),
        },
        "reusable-search-macros": {
            "EXPLORED_STATES": (12000, macro_cost["states"]),
            "CANDIDATE_EVALUATIONS": (2400, macro_cost["candidates"]),
            "PROOF_ATTEMPTS": (400, macro_cost["proofs"]),
        },
    }
    for challenge, dimensions in expected.items():
        for dimension, (configured, executed) in dimensions.items():
            row = vectors[challenge][dimension]
            if row["configured"] != configured or row["executed"] != executed:
                fail(f"resource mismatch: {challenge}/{dimension}")
            expected_remaining = (
                None if configured is None else configured - executed
            )
            if row["remaining"] != expected_remaining:
                fail(f"remaining mismatch: {challenge}/{dimension}")

    stages = {
        key: {row["stage"]: row for row in value["stageBreakdown"]}
        for key, value in challenges.items()
    }
    if (
        stages["rational-assumption-rewrites"]["FORMATION"][
            "exploredStates"
        ]
        != rational_cost["formationStates"]
        or stages["rational-assumption-rewrites"]["HELD_OUT_EVALUATION"][
            "candidateEvaluations"
        ]
        != rational_cost["evaluationCandidates"]
    ):
        fail("rational stage split mismatch")
    if (
        stages["reusable-search-macros"]["FORMATION"]["exploredStates"]
        != macro_cost["formationStates"]
        or stages["reusable-search-macros"]["PAIRED_HELD_OUT_EVALUATION"][
            "candidateEvaluations"
        ]
        != macro_cost["evaluationCandidates"]
    ):
        fail("macro stage split mismatch")

    aggregates = resource_index(output["aggregateResourceVector"])
    totals = {
        "EXPLORED_STATES": sequence["exploredStates"]
        + rational_cost["states"]
        + macro_cost["states"],
        "CANDIDATE_EVALUATIONS": sequence["candidateAttempts"]
        + rational_cost["candidates"]
        + macro_cost["candidates"],
        "PROOF_ATTEMPTS": sequence["proofAttempts"]
        + rational_cost["proofs"]
        + macro_cost["proofs"],
    }
    configured = {
        "EXPLORED_STATES": 36000,
        "CANDIDATE_EVALUATIONS": 7200,
        "PROOF_ATTEMPTS": 1200,
    }
    for dimension, executed in totals.items():
        row = aggregates[dimension]
        expected_row = (
            configured[dimension],
            executed,
            configured[dimension] - executed,
        )
        actual_row = (row["configured"], row["executed"], row["remaining"])
        if actual_row != expected_row:
            fail(f"aggregate mismatch: {dimension}")

    cost_states, cost_candidates, expected_tasks = macro_signature
    amortization = output["macroAmortizationReference"]
    if amortization["formationCost"] != {
        "exploredStates": cost_states,
        "candidateEvaluations": cost_candidates,
    }:
        fail("macro formation cost mismatch")
    if amortization["tasks"] != expected_tasks:
        fail("macro downstream task stream mismatch")
    state_index = next(
        (
            row["index"]
            for row in expected_tasks
            if row["cumulativeExploredStateSaving"] >= cost_states
        ),
        None,
    )
    candidate_index = next(
        (
            row["index"]
            for row in expected_tasks
            if row["cumulativeCandidateEvaluationSaving"] >= cost_candidates
        ),
        None,
    )
    expected_break_even = {
        "exploredStates": {
            "decision": (
                "BREAK_EVEN_OBSERVED"
                if state_index is not None
                else "NO_BREAK_EVEN_OBSERVED"
            ),
            "firstTaskIndex": state_index,
            "finalNetSaving": expected_tasks[-1][
                "cumulativeExploredStateSaving"
            ]
            - cost_states,
        },
        "candidateEvaluations": {
            "decision": (
                "BREAK_EVEN_OBSERVED"
                if candidate_index is not None
                else "NO_BREAK_EVEN_OBSERVED"
            ),
            "firstTaskIndex": candidate_index,
            "finalNetSaving": expected_tasks[-1][
                "cumulativeCandidateEvaluationSaving"
            ]
            - cost_candidates,
        },
    }
    if amortization["breakEven"] != expected_break_even:
        fail("macro break-even mismatch")
    if (
        output["overallAmortizationStatus"]
        != "NOT_ESTABLISHED_INCOMPLETE_LIFECYCLE_COST_AND_SINGLE_CANDIDATE_STREAM"
        or output["publicationAuthorized"] is not False
    ):
        fail("claim boundary drift")


def main() -> int:
    parser = argparse.ArgumentParser()
    for side in ["first", "second"]:
        for name in [
            "ledger",
            "benchmark",
            "finite",
            "recurrence",
            "rational",
            "macro",
        ]:
            parser.add_argument(
                f"--{side}-{name}", required=True, type=Path
            )
    parser.add_argument("--schema", required=True, type=Path)
    parser.add_argument("--report-directory", required=True, type=Path)
    arguments = parser.parse_args()

    schema = load(arguments.schema)
    jsonschema.Draft202012Validator.check_schema(schema)
    outputs: list[dict[str, Any]] = []
    for side in ["first", "second"]:
        values = {
            name: load(getattr(arguments, f"{side}_{name}"))
            for name in [
                "ledger",
                "benchmark",
                "finite",
                "recurrence",
                "rational",
                "macro",
            ]
        }
        jsonschema.validate(values["ledger"], schema)
        verify_one(
            values["ledger"],
            values["benchmark"],
            values["finite"],
            values["recurrence"],
            values["rational"],
            values["macro"],
        )
        outputs.append(values["ledger"])
    if arguments.first_ledger.read_bytes() != arguments.second_ledger.read_bytes():
        fail("clean ledger runs are not byte-identical")

    mutations: list[str] = []
    for field, value in [
        ("publicationAuthorized", True),
        ("overallAmortizationStatus", "BREAK_EVEN_OBSERVED"),
    ]:
        mutated = json.loads(arguments.first_ledger.read_text(encoding="utf-8"))
        mutated[field] = value
        try:
            jsonschema.validate(mutated, schema)
        except jsonschema.ValidationError:
            mutations.append(field)
        else:
            fail(f"mutation survived: {field}")

    report = {
        "schema": "regelsuche.discovery-cost-ledger-verification/v1",
        "runContentHash": outputs[0]["contentHash"],
        "byteIdenticalCleanRuns": True,
        "verifiedConfiguredCampaigns": 12,
        "verifiedExecutedCampaigns": 12,
        "verifiedDimensions": [
            "EXPLORED_STATES",
            "CANDIDATE_EVALUATIONS",
            "PROOF_ATTEMPTS",
        ],
        "verifiedMutations": mutations,
        "overallAmortizationStatus": outputs[0][
            "overallAmortizationStatus"
        ],
        "publicationAuthorized": False,
    }
    arguments.report_directory.mkdir(parents=True, exist_ok=True)
    (arguments.report_directory / "verification.json").write_text(
        json.dumps(report, sort_keys=True, indent=2) + "\n",
        encoding="utf-8",
    )
    print("discovery-cost-ledger=VERIFIED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
