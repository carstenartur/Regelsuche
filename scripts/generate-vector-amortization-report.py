#!/usr/bin/env python3
"""Generate the authoritative vector-only amortization report for #384."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any, Callable

REPORT_SCHEMA = "regelsuche.amortization-report/v1"
RUN_SCHEMA = "regelsuche.amortization-run/v1"
PROFILE_SCHEMA = "regelsuche.amortization-profile/v1"
LEDGER_SCHEMA = "regelsuche.discovery-cost-ledger/v1"
UTILITY_SCHEMA = "regelsuche.paired-task-utility/v1"
OVERALL = "NOT_ESTABLISHED_INCOMPLETE_LIFECYCLE_COST"
INCOMPLETE_LEDGER = (
    "NOT_ESTABLISHED_INCOMPLETE_LIFECYCLE_COST_AND_SINGLE_CANDIDATE_STREAM"
)
INCOMPLETE_LIFECYCLE_STATUSES = {
    "EMBEDDED_NOT_SEPARATELY_METERED",
    "NOT_EXECUTED_IN_BENCHMARK",
    "CONFIGURED_NOT_EXECUTED",
}


def fail(message: str) -> None:
    raise RuntimeError(message)


def load(path: Path) -> dict[str, Any]:
    if not path.is_file() or path.is_symlink():
        fail(f"expected regular non-symbolic file: {path}")

    def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                fail(f"duplicate field {key!r}: {path}")
            result[key] = value
        return result

    value = json.loads(
        path.read_text(encoding="utf-8"), object_pairs_hook=unique_object
    )
    if not isinstance(value, dict):
        fail(f"expected JSON object: {path}")
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
        fail(f"{context} contentHash mismatch: {retained} != {calculated}")
    return retained


def add_hash(value: dict[str, Any]) -> dict[str, Any]:
    if "contentHash" in value:
        fail("contentHash already present")
    value["contentHash"] = semantic_hash(value)
    return value


def write(path: Path, value: dict[str, Any]) -> None:
    path = path.resolve()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
        encoding="utf-8",
    )


def decision(discovery_cost: int, ordered: list[tuple[str, int]]) -> dict[str, Any]:
    cumulative = 0
    first: int | None = None
    for index, (_, saving) in enumerate(ordered, start=1):
        cumulative += saving
        if first is None and cumulative >= discovery_cost:
            first = index
    result = {
        "decision": (
            "BREAK_EVEN_OBSERVED"
            if first is not None
            else "NO_BREAK_EVEN_OBSERVED"
        ),
        "firstTaskIndex": first,
        "discoveryCost": discovery_cost,
        "finalCumulativeSaving": cumulative,
        "finalNetSaving": cumulative - discovery_cost,
    }
    return add_hash(result)


def ordered_rows(
    tasks: list[dict[str, Any]],
    discovery_cost: int,
    saving: Callable[[dict[str, Any]], int],
) -> list[dict[str, Any]]:
    cumulative = 0
    result: list[dict[str, Any]] = []
    for task in tasks:
        retained_saving = saving(task)
        cumulative += retained_saving
        result.append(add_hash({
            "index": task["index"],
            "taskId": task["taskId"],
            "split": task["split"],
            "saving": retained_saving,
            "cumulativeSaving": cumulative,
            "netSavingAfterDiscoveryCost": cumulative - discovery_cost,
            "breakEvenReached": cumulative >= discovery_cost,
        }))
    return result


def sensitivity(
    tasks: list[dict[str, Any]],
    discovery_cost: int,
    saving: Callable[[dict[str, Any]], int],
) -> dict[str, Any]:
    pairs = [(task["taskId"], saving(task)) for task in tasks]
    best = sorted(pairs, key=lambda item: (-item[1], item[0]))
    worst = sorted(pairs, key=lambda item: (item[1], item[0]))
    best_decision = decision(discovery_cost, best)
    best_decision["taskOrder"] = [task_id for task_id, _ in best]
    rehash(best_decision)
    worst_decision = decision(discovery_cost, worst)
    worst_decision["taskOrder"] = [task_id for task_id, _ in worst]
    rehash(worst_decision)
    return add_hash({
        "policy": (
            "BEST_AND_WORST_CASE_PERMUTATION_BOUNDS_WITHOUT_REPLACING_"
            "FROZEN_ORDER"
        ),
        "bestCase": best_decision,
        "worstCase": worst_decision,
        "finalSavingOrderInvariant": sum(value for _, value in pairs),
    })


def rehash(value: dict[str, Any]) -> None:
    value.pop("contentHash", None)
    value["contentHash"] = semantic_hash(value)


def dimension(
    name: str,
    unit: str,
    discovery_cost: int,
    tasks: list[dict[str, Any]],
    saving: Callable[[dict[str, Any]], int],
    source_policy: str,
) -> dict[str, Any]:
    rows = ordered_rows(tasks, discovery_cost, saving)
    observed = decision(
        discovery_cost,
        [(row["taskId"], row["saving"]) for row in rows],
    )
    return add_hash({
        "dimension": name,
        "unit": unit,
        "sourcePolicy": source_policy,
        "discoveryCost": discovery_cost,
        "lifecycleCostStatus": "PARTIAL_FORMATION_COST_ONLY",
        "tasks": rows,
        "observedOrderDecision": observed,
        "orderingSensitivity": sensitivity(
            tasks, discovery_cost, saving
        ),
        "finalCumulativeSaving": rows[-1]["cumulativeSaving"],
        "finalNetSaving": rows[-1]["netSavingAfterDiscoveryCost"],
    })


def validate_sources(
    ledger: dict[str, Any],
    utility: dict[str, Any],
    profile: dict[str, Any],
) -> None:
    require_hash(ledger, "discovery cost ledger")
    require_hash(utility, "paired task utility")
    require_hash(profile, "amortization profile")
    if ledger.get("schema") != LEDGER_SCHEMA:
        fail("unexpected discovery cost ledger schema")
    if utility.get("schema") != UTILITY_SCHEMA:
        fail("unexpected paired utility schema")
    if profile.get("schema") != PROFILE_SCHEMA:
        fail("unexpected amortization profile schema")
    if profile.get("mode") != "VECTOR_ONLY":
        fail("amortization profile is not vector-only")
    if profile.get("conversionWeights") != []:
        fail("vector-only profile contains scalar conversion weights")
    if ledger.get("overallAmortizationStatus") != INCOMPLETE_LEDGER:
        fail("discovery ledger lifecycle boundary drift")
    if ledger.get("publicationAuthorized") is not False:
        fail("discovery ledger unexpectedly authorizes publication")
    if utility.get("publicationAuthorized") is not False:
        fail("paired utility unexpectedly authorizes publication")
    if utility.get("correctnessRegressionCount") != 0:
        fail("paired utility contains correctness regressions")
    if utility.get("enabledCandidateCount") != 1:
        fail("paired utility is not exact-one candidate")
    if utility.get("executedTasks") != 12:
        fail("paired utility did not execute the complete frozen stream")
    for task in utility["tasks"]:
        require_hash(task, f"paired utility task {task.get('taskId')}")
        require_hash(task["resourceDelta"], "paired utility task delta")


def incomplete_lifecycle_stages(
    lifecycle_coverage: list[dict[str, Any]],
) -> list[str]:
    stages: list[str] = []
    for item in lifecycle_coverage:
        status = item.get("status")
        if status not in INCOMPLETE_LIFECYCLE_STATUSES:
            fail(f"unexpected lifecycle coverage status: {status}")
        stages.append(item["stage"])
    return stages


def generate(
    ledger: dict[str, Any],
    utility: dict[str, Any],
    profile: dict[str, Any],
    repository_revision: str,
) -> tuple[dict[str, Any], dict[str, Any]]:
    validate_sources(ledger, utility, profile)
    resource = utility["resourceUse"]
    require_hash(resource, "paired utility resource use")
    macro_reference = ledger["macroAmortizationReference"]
    if macro_reference["formationCost"]["exploredStates"] != resource[
        "formationStates"
    ]:
        fail("Phase-1 and exact-one formation-state costs differ")
    if macro_reference["formationCost"]["candidateEvaluations"] != resource[
        "formationCandidateEvaluations"
    ]:
        fail("Phase-1 and exact-one formation candidate costs differ")

    tasks = utility["tasks"]
    explored = dimension(
        "EXPLORED_STATES",
        "state",
        resource["formationStates"],
        tasks,
        lambda task: task["resourceDelta"]["expandedStateSaving"],
        "FORMATION_STATES_VERSUS_PAIRED_EXPANDED_STATE_SAVING",
    )
    candidates = dimension(
        "CANDIDATE_EVALUATIONS",
        "candidate",
        resource["formationCandidateEvaluations"],
        tasks,
        lambda task: task["resourceDelta"]["generatedCandidateSaving"],
        (
            "FORMATION_CANDIDATE_EVALUATIONS_VERSUS_BEST_FIRST_GENERATED_"
            "TRANSFORMATIONS_PER_PHASE1_LEDGER"
        ),
    )
    path_saving = sum(
        task["resourceDelta"]["pathStepSaving"] for task in tasks
    )
    selected = utility["candidateSelection"]
    require_hash(selected, "paired utility candidate selection")
    lifecycle_coverage = ledger["lifecycleCoverage"]
    incomplete_stages = incomplete_lifecycle_stages(lifecycle_coverage)

    report = add_hash({
        "schema": REPORT_SCHEMA,
        "benchmarkId": utility["benchmarkId"],
        "challengeId": utility["challengeId"],
        "repositoryRevision": repository_revision,
        "discoveryCostLedgerContentHash": ledger["contentHash"],
        "pairedTaskUtilityContentHash": utility["contentHash"],
        "downstreamTaskStreamContentHash": utility[
            "downstreamTaskStreamContentHash"
        ],
        "amortizationProfileContentHash": profile["contentHash"],
        "profileId": profile["profileId"],
        "accountingMode": "VECTOR_ONLY_NO_IMPLICIT_CONVERSION",
        "candidateId": selected["selectedCandidateId"],
        "candidateContentHash": selected["selectedCandidateContentHash"],
        "configuredTasks": 12,
        "executedTasks": len(tasks),
        "correctnessRegressionCount": utility["correctnessRegressionCount"],
        "lifecycleCoverage": lifecycle_coverage,
        "incompleteLifecycleStages": incomplete_stages,
        "dimensions": [explored, candidates],
        "pathStepDiagnostic": add_hash({
            "status": "DIAGNOSTIC_NOT_USED_FOR_DISCOVERY_BREAK_EVEN",
            "finalCumulativeSaving": path_saving,
        }),
        "scalarDecisionStatus": profile["scalarDecisionStatus"],
        "overallDecision": OVERALL,
        "overallDecisionReason": (
            "VALIDATION_COUNTEREXAMPLE_PROJECT_NOVELTY_FORMAL_PROOF_AND_"
            "QUALIFICATION_COSTS_NOT_ALL_SEPARATELY_EXECUTED"
        ),
        "formalProofStatus": "NOT_EVALUATED",
        "externalNoveltyStatus": "NOT_EVALUATED",
        "publicationAuthorized": False,
    })

    run = add_hash({
        "schema": RUN_SCHEMA,
        "repositoryRevision": repository_revision,
        "amortizationReportContentHash": report["contentHash"],
        "discoveryCostLedgerContentHash": ledger["contentHash"],
        "pairedTaskUtilityContentHash": utility["contentHash"],
        "downstreamTaskStreamContentHash": utility[
            "downstreamTaskStreamContentHash"
        ],
        "amortizationProfileContentHash": profile["contentHash"],
        "candidateId": selected["selectedCandidateId"],
        "executionMode": "CHECKOUT_LOCAL_DETERMINISTIC_DOUBLE_RUN",
        "containerReproductionStatus": (
            "NOT_YET_EXECUTED_FOR_COMBINED_AMORTIZATION_REPORT"
        ),
        "runtimeTelemetryStatus": "NON_CANONICAL_NOT_RETAINED_IN_REPORT",
        "overallDecision": OVERALL,
        "publicationAuthorized": False,
    })
    return report, run


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ledger", required=True, type=Path)
    parser.add_argument("--paired-utility", required=True, type=Path)
    parser.add_argument("--profile", required=True, type=Path)
    parser.add_argument("--repository-revision", required=True)
    parser.add_argument("--report-output", required=True, type=Path)
    parser.add_argument("--run-output", required=True, type=Path)
    arguments = parser.parse_args()

    report, run = generate(
        load(arguments.ledger),
        load(arguments.paired_utility),
        load(arguments.profile),
        arguments.repository_revision,
    )
    write(arguments.report_output, report)
    write(arguments.run_output, run)
    print(f"amortization-report={arguments.report_output}")
    print(f"contentHash={report['contentHash']}")
    print(f"overallDecision={report['overallDecision']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
