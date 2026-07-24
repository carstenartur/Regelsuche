#!/usr/bin/env python3
"""Independently recompute the vector-only amortization report and decisions."""

from __future__ import annotations

import argparse
import ast
import copy
import hashlib
import json
from collections import Counter
from pathlib import Path
from typing import Any, Callable

import jsonschema

REPORT_SCHEMA = "regelsuche.amortization-report/v1"
RUN_SCHEMA = "regelsuche.amortization-run/v1"
PROFILE_SCHEMA = "regelsuche.amortization-profile/v1"
LEDGER_SCHEMA = "regelsuche.discovery-cost-ledger/v1"
UTILITY_SCHEMA = "regelsuche.paired-task-utility/v1"
OVERALL = "NOT_ESTABLISHED_INCOMPLETE_LIFECYCLE_COST"
LEDGER_BOUNDARY = (
    "NOT_ESTABLISHED_INCOMPLETE_LIFECYCLE_COST_AND_SINGLE_CANDIDATE_STREAM"
)
INCOMPLETE_LIFECYCLE_STATUSES = {
    "EMBEDDED_NOT_SEPARATELY_METERED",
    "NOT_EXECUTED_IN_BENCHMARK",
    "CONFIGURED_NOT_EXECUTED",
}
EXPECTED_GENERATOR_ARGUMENTS = {
    "--ledger",
    "--paired-utility",
    "--profile",
    "--repository-revision",
    "--report-output",
    "--run-output",
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
    value["contentHash"] = semantic_hash(value)
    return value


def rehash(value: dict[str, Any]) -> None:
    value.pop("contentHash", None)
    value["contentHash"] = semantic_hash(value)


def verify_generator_source(path: Path) -> list[str]:
    source = path.read_text(encoding="utf-8")
    tree = ast.parse(source, filename=str(path))
    arguments: list[str] = []
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call) or not isinstance(node.func, ast.Attribute):
            continue
        if node.func.attr != "add_argument" or not node.args:
            continue
        for argument in node.args:
            if not (
                isinstance(argument, ast.Constant)
                and isinstance(argument.value, str)
            ):
                fail("amortization generator CLI names must be string literals")
            arguments.append(argument.value)

    counts = Counter(arguments)
    duplicates = sorted(argument for argument, count in counts.items() if count > 1)
    if duplicates:
        fail(f"amortization generator CLI contains duplicates: {duplicates}")
    actual = set(arguments)
    if actual != EXPECTED_GENERATOR_ARGUMENTS:
        fail(
            "amortization generator CLI allowlist drift: "
            f"missing={sorted(EXPECTED_GENERATOR_ARGUMENTS - actual)}, "
            f"unexpected={sorted(actual - EXPECTED_GENERATOR_ARGUMENTS)}"
        )
    required_fragments = [
        'OVERALL = "NOT_ESTABLISHED_INCOMPLETE_LIFECYCLE_COST"',
        '"accountingMode": "VECTOR_ONLY_NO_IMPLICIT_CONVERSION"',
        '"lifecycleCostStatus": "PARTIAL_FORMATION_COST_ONLY"',
        'profile.get("conversionWeights") != []',
        '"publicationAuthorized": False',
        "INCOMPLETE_LIFECYCLE_STATUSES",
    ]
    missing = [fragment for fragment in required_fragments if fragment not in source]
    if missing:
        fail(f"amortization generator claim boundary drift: {missing}")
    if "verify-vector-amortization-report" in source:
        fail("generator imports or invokes its independent verifier")
    return sorted(actual)


def decision(cost: int, pairs: list[tuple[str, int]]) -> dict[str, Any]:
    cumulative = 0
    first: int | None = None
    for index, (_, saving) in enumerate(pairs, start=1):
        cumulative += saving
        if first is None and cumulative >= cost:
            first = index
    return add_hash({
        "decision": (
            "BREAK_EVEN_OBSERVED"
            if first is not None
            else "NO_BREAK_EVEN_OBSERVED"
        ),
        "firstTaskIndex": first,
        "discoveryCost": cost,
        "finalCumulativeSaving": cumulative,
        "finalNetSaving": cumulative - cost,
    })


def rows(
    tasks: list[dict[str, Any]],
    cost: int,
    saving: Callable[[dict[str, Any]], int],
) -> list[dict[str, Any]]:
    cumulative = 0
    result: list[dict[str, Any]] = []
    for task in tasks:
        value = saving(task)
        cumulative += value
        result.append(add_hash({
            "index": task["index"],
            "taskId": task["taskId"],
            "split": task["split"],
            "saving": value,
            "cumulativeSaving": cumulative,
            "netSavingAfterDiscoveryCost": cumulative - cost,
            "breakEvenReached": cumulative >= cost,
        }))
    return result


def permutation(
    cost: int,
    pairs: list[tuple[str, int]],
) -> dict[str, Any]:
    value = decision(cost, pairs)
    value["taskOrder"] = [task_id for task_id, _ in pairs]
    rehash(value)
    return value


def sensitivity(
    tasks: list[dict[str, Any]],
    cost: int,
    saving: Callable[[dict[str, Any]], int],
) -> dict[str, Any]:
    pairs = [(task["taskId"], saving(task)) for task in tasks]
    best = sorted(pairs, key=lambda item: (-item[1], item[0]))
    worst = sorted(pairs, key=lambda item: (item[1], item[0]))
    return add_hash({
        "policy": (
            "BEST_AND_WORST_CASE_PERMUTATION_BOUNDS_WITHOUT_REPLACING_"
            "FROZEN_ORDER"
        ),
        "bestCase": permutation(cost, best),
        "worstCase": permutation(cost, worst),
        "finalSavingOrderInvariant": sum(value for _, value in pairs),
    })


def dimension(
    name: str,
    unit: str,
    source_policy: str,
    cost: int,
    tasks: list[dict[str, Any]],
    saving: Callable[[dict[str, Any]], int],
) -> dict[str, Any]:
    task_rows = rows(tasks, cost, saving)
    observed = decision(
        cost,
        [(item["taskId"], item["saving"]) for item in task_rows],
    )
    return add_hash({
        "dimension": name,
        "unit": unit,
        "sourcePolicy": source_policy,
        "discoveryCost": cost,
        "lifecycleCostStatus": "PARTIAL_FORMATION_COST_ONLY",
        "tasks": task_rows,
        "observedOrderDecision": observed,
        "orderingSensitivity": sensitivity(tasks, cost, saving),
        "finalCumulativeSaving": task_rows[-1]["cumulativeSaving"],
        "finalNetSaving": task_rows[-1]["netSavingAfterDiscoveryCost"],
    })


def validate_inputs(
    ledger: dict[str, Any],
    utility: dict[str, Any],
    profile: dict[str, Any],
) -> None:
    require_hash(ledger, "discovery cost ledger")
    require_hash(utility, "paired task utility")
    require_hash(profile, "amortization profile")
    if ledger.get("schema") != LEDGER_SCHEMA:
        fail("discovery cost ledger schema drift")
    if utility.get("schema") != UTILITY_SCHEMA:
        fail("paired task utility schema drift")
    if profile.get("schema") != PROFILE_SCHEMA:
        fail("amortization profile schema drift")
    if profile.get("mode") != "VECTOR_ONLY":
        fail("amortization profile is not vector-only")
    if profile.get("conversionWeights") != []:
        fail("vector-only profile contains scalar weights")
    if profile.get("scalarDecisionStatus") != (
        "NOT_APPLICABLE_NO_SCALAR_CONVERSION"
    ):
        fail("vector-only scalar decision status drift")
    if ledger.get("overallAmortizationStatus") != LEDGER_BOUNDARY:
        fail("discovery ledger lifecycle boundary drift")
    if utility.get("enabledCandidateCount") != 1:
        fail("paired utility is not exact-one")
    if utility.get("correctnessRegressionCount") != 0:
        fail("paired utility contains correctness regressions")
    if utility.get("executedTasks") != 12:
        fail("paired utility task count drift")
    if ledger.get("publicationAuthorized") is not False:
        fail("discovery ledger publication boundary drift")
    if utility.get("publicationAuthorized") is not False:
        fail("paired utility publication boundary drift")
    if profile.get("publicationAuthorized") is not False:
        fail("amortization profile publication boundary drift")
    require_hash(utility["resourceUse"], "paired utility resource use")
    require_hash(utility["candidateSelection"], "paired utility selection")
    for index, task in enumerate(utility["tasks"], start=1):
        require_hash(task, f"paired utility task {index}")
        require_hash(task["resourceDelta"], f"paired utility delta {index}")
        if task["index"] != index:
            fail(f"paired utility task order drift at {index}")


def incomplete_lifecycle_stages(
    coverage: list[dict[str, Any]],
) -> list[str]:
    stages: list[str] = []
    for item in coverage:
        status = item.get("status")
        if status not in INCOMPLETE_LIFECYCLE_STATUSES:
            fail(f"unexpected lifecycle coverage status: {status}")
        stages.append(item["stage"])
    return stages


def expected(
    ledger: dict[str, Any],
    utility: dict[str, Any],
    profile: dict[str, Any],
    repository_revision: str,
) -> tuple[dict[str, Any], dict[str, Any]]:
    validate_inputs(ledger, utility, profile)
    resource = utility["resourceUse"]
    reference = ledger["macroAmortizationReference"]["formationCost"]
    if reference["exploredStates"] != resource["formationStates"]:
        fail("Phase-1/exact-one explored-state formation costs differ")
    if (
        reference["candidateEvaluations"]
        != resource["formationCandidateEvaluations"]
    ):
        fail("Phase-1/exact-one candidate formation costs differ")

    tasks = utility["tasks"]
    explored = dimension(
        "EXPLORED_STATES",
        "state",
        "FORMATION_STATES_VERSUS_PAIRED_EXPANDED_STATE_SAVING",
        resource["formationStates"],
        tasks,
        lambda task: task["resourceDelta"]["expandedStateSaving"],
    )
    candidate = dimension(
        "CANDIDATE_EVALUATIONS",
        "candidate",
        (
            "FORMATION_CANDIDATE_EVALUATIONS_VERSUS_BEST_FIRST_GENERATED_"
            "TRANSFORMATIONS_PER_PHASE1_LEDGER"
        ),
        resource["formationCandidateEvaluations"],
        tasks,
        lambda task: task["resourceDelta"]["generatedCandidateSaving"],
    )
    coverage = ledger["lifecycleCoverage"]
    incomplete = incomplete_lifecycle_stages(coverage)
    selection = utility["candidateSelection"]
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
        "candidateId": selection["selectedCandidateId"],
        "candidateContentHash": selection["selectedCandidateContentHash"],
        "configuredTasks": 12,
        "executedTasks": len(tasks),
        "correctnessRegressionCount": utility["correctnessRegressionCount"],
        "lifecycleCoverage": coverage,
        "incompleteLifecycleStages": incomplete,
        "dimensions": [explored, candidate],
        "pathStepDiagnostic": add_hash({
            "status": "DIAGNOSTIC_NOT_USED_FOR_DISCOVERY_BREAK_EVEN",
            "finalCumulativeSaving": sum(
                task["resourceDelta"]["pathStepSaving"] for task in tasks
            ),
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
        "candidateId": selection["selectedCandidateId"],
        "executionMode": "CHECKOUT_LOCAL_DETERMINISTIC_DOUBLE_RUN",
        "containerReproductionStatus": (
            "NOT_YET_EXECUTED_FOR_COMBINED_AMORTIZATION_REPORT"
        ),
        "runtimeTelemetryStatus": "NON_CANONICAL_NOT_RETAINED_IN_REPORT",
        "overallDecision": OVERALL,
        "publicationAuthorized": False,
    })
    return report, run


def expect_mismatch(
    report: dict[str, Any], expected_report: dict[str, Any], label: str
) -> None:
    if report == expected_report:
        fail(f"mutation survived independent recomputation: {label}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--generator", required=True, type=Path)
    parser.add_argument("--first-report", required=True, type=Path)
    parser.add_argument("--second-report", required=True, type=Path)
    parser.add_argument("--first-run", required=True, type=Path)
    parser.add_argument("--second-run", required=True, type=Path)
    parser.add_argument("--ledger", required=True, type=Path)
    parser.add_argument("--paired-utility", required=True, type=Path)
    parser.add_argument("--profile", required=True, type=Path)
    parser.add_argument("--profile-schema", required=True, type=Path)
    parser.add_argument("--report-schema", required=True, type=Path)
    parser.add_argument("--run-schema", required=True, type=Path)
    parser.add_argument("--repository-revision", required=True)
    parser.add_argument("--report-directory", required=True, type=Path)
    arguments = parser.parse_args()

    verified_generator_arguments = verify_generator_source(arguments.generator)
    profile_schema = load(arguments.profile_schema)
    report_schema = load(arguments.report_schema)
    run_schema = load(arguments.run_schema)
    for schema in (profile_schema, report_schema, run_schema):
        jsonschema.Draft202012Validator.check_schema(schema)
    profile_validator = jsonschema.Draft202012Validator(profile_schema)
    report_validator = jsonschema.Draft202012Validator(report_schema)
    run_validator = jsonschema.Draft202012Validator(run_schema)

    ledger = load(arguments.ledger)
    utility = load(arguments.paired_utility)
    profile = load(arguments.profile)
    first_report = load(arguments.first_report)
    second_report = load(arguments.second_report)
    first_run = load(arguments.first_run)
    second_run = load(arguments.second_run)
    profile_validator.validate(profile)
    for report in (first_report, second_report):
        report_validator.validate(report)
        require_hash(report, "amortization report")
    for run in (first_run, second_run):
        run_validator.validate(run)
        require_hash(run, "amortization run")

    expected_report, expected_run = expected(
        ledger, utility, profile, arguments.repository_revision
    )
    if first_report != expected_report or second_report != expected_report:
        fail("amortization report differs from independent recomputation")
    if first_run != expected_run or second_run != expected_run:
        fail("amortization run differs from independent recomputation")
    if arguments.first_report.read_bytes() != arguments.second_report.read_bytes():
        fail("clean amortization reports are not byte-identical")
    if arguments.first_run.read_bytes() != arguments.second_run.read_bytes():
        fail("clean amortization runs are not byte-identical")

    saving_drift = copy.deepcopy(first_report)
    row = saving_drift["dimensions"][0]["tasks"][0]
    row["saving"] += 1
    rehash(row)
    rehash(saving_drift["dimensions"][0])
    rehash(saving_drift)
    expect_mismatch(saving_drift, expected_report, "task saving")

    reordered = copy.deepcopy(first_report)
    rows_value = reordered["dimensions"][0]["tasks"]
    rows_value[0], rows_value[1] = rows_value[1], rows_value[0]
    rehash(reordered["dimensions"][0])
    rehash(reordered)
    expect_mismatch(reordered, expected_report, "frozen task order")

    lifecycle_drift = copy.deepcopy(first_report)
    lifecycle_drift["incompleteLifecycleStages"].remove("VALIDATION")
    rehash(lifecycle_drift)
    expect_mismatch(lifecycle_drift, expected_report, "lifecycle coverage")
    try:
        report_validator.validate(lifecycle_drift)
    except jsonschema.ValidationError:
        pass
    else:
        fail("schema accepted omission of an incomplete lifecycle stage")

    inflated = copy.deepcopy(first_report)
    inflated["overallDecision"] = "BREAK_EVEN_OBSERVED"
    rehash(inflated)
    try:
        report_validator.validate(inflated)
    except jsonschema.ValidationError:
        pass
    else:
        fail("schema accepted an inflated complete-lifecycle break-even")

    weighted = copy.deepcopy(profile)
    weighted["conversionWeights"] = [
        {"dimension": "EXPLORED_STATES", "weight": 1.0}
    ]
    rehash(weighted)
    try:
        profile_validator.validate(weighted)
    except jsonschema.ValidationError:
        pass
    else:
        fail("vector-only profile schema accepted scalar weights")

    publication = copy.deepcopy(first_report)
    publication["publicationAuthorized"] = True
    rehash(publication)
    try:
        report_validator.validate(publication)
    except jsonschema.ValidationError:
        pass
    else:
        fail("schema accepted amortization publication authorization")

    verification = {
        "schema": "regelsuche.amortization-report-verification/v1",
        "amortizationReportContentHash": first_report["contentHash"],
        "amortizationRunContentHash": first_run["contentHash"],
        "profileContentHash": profile["contentHash"],
        "byteIdenticalCleanReports": True,
        "byteIdenticalCleanRuns": True,
        "verifiedGeneratorArguments": verified_generator_arguments,
        "verifiedAccountingMode": "VECTOR_ONLY_NO_IMPLICIT_CONVERSION",
        "verifiedDimensions": [
            item["dimension"] for item in first_report["dimensions"]
        ],
        "verifiedIncompleteLifecycleStages": first_report[
            "incompleteLifecycleStages"
        ],
        "verifiedOverallDecision": OVERALL,
        "verifiedMutations": [
            "task-saving",
            "frozen-task-order",
            "lifecycle-coverage",
            "inflated-overall-break-even",
            "scalar-weight-in-vector-profile",
            "publication-authorization",
        ],
        "containerReproductionStatus": (
            "NOT_YET_EXECUTED_FOR_COMBINED_AMORTIZATION_REPORT"
        ),
        "publicationAuthorized": False,
    }
    verification["contentHash"] = semantic_hash(verification)
    arguments.report_directory.mkdir(parents=True, exist_ok=True)
    (arguments.report_directory / "verification.json").write_text(
        json.dumps(
            verification, ensure_ascii=False, sort_keys=True, indent=2
        ) + "\n",
        encoding="utf-8",
    )
    print("vector-amortization-report=VERIFIED")
    print(f"overall-decision={OVERALL}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
