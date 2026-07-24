#!/usr/bin/env python3
"""Independently verify exact-one-candidate paired downstream utility."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import re
from collections import Counter
from pathlib import Path
from typing import Any

import jsonschema

SCHEMA = "regelsuche.paired-task-utility/v1"
BENCHMARK = "regelsuche-candidate-independent-autonomous-discovery-2026-07/v1"
CHALLENGE = "reusable-search-macros"
PROFILE = "macro-primitives/v1"
COMPARISON = "IDENTICAL_INPUT_TARGET_INVENTORY_STRATEGY_AND_BUDGET"
SELECTION = "MAX_TRAIN_SUPPORT_THEN_CANONICAL_HASH_THEN_MACRO_ID"
INVENTORY = "BASELINE_PLUS_EXACTLY_ONE_SELECTED_CANDIDATE"
OUTCOMES = [
    "IMPROVED",
    "REACHABILITY_GAIN",
    "NO_IMPROVEMENT",
    "NO_RESULT",
    "CORRECTNESS_REGRESSION",
    "CANDIDATE_NOT_FORMED",
]
EXPECTED_RUNNER_ARGUMENTS = {
    "--benchmark-source",
    "--corpus",
    "--profile",
    "--freeze-receipt",
    "--downstream-stream",
    "--output",
    "--repository-revision",
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


def verify_source_boundaries(runner_path: Path, selector_path: Path) -> list[str]:
    runner = runner_path.read_text(encoding="utf-8")
    selector = selector_path.read_text(encoding="utf-8")
    flags = re.findall(r'"(--[a-z-]+)"', runner)
    counts = Counter(flags)
    discovered = set(flags)
    if discovered != EXPECTED_RUNNER_ARGUMENTS:
        fail(
            "runner CLI allowlist drift: "
            f"missing={sorted(EXPECTED_RUNNER_ARGUMENTS - discovered)}, "
            f"unexpected={sorted(discovered - EXPECTED_RUNNER_ARGUMENTS)}"
        )
    duplicates = sorted(flag for flag, count in counts.items() if count < 2)
    if duplicates:
        fail(
            "runner arguments must be declared and consumed explicitly: "
            f"{duplicates}"
        )
    formation_position = runner.find("adapter.form(traces)")
    selection_position = runner.find(".select(formation)")
    stream_read_position = runner.find("readObject(arguments.downstreamStream())")
    if not (
        0 <= formation_position < selection_position < stream_read_position
    ):
        fail("runner does not freeze exact-one selection before stream reading")
    if "selection.exactOneFormation()" not in runner:
        fail("runner does not evaluate the exact-one formation result")
    if "adapter.evaluate(\n                binding.task(), formation)" in runner:
        fail("runner evaluates with the full formed-candidate inventory")

    required_selector_fragments = [
        "select(FormationResult formation)",
        "candidate.supportingTraceIds().size()",
        ".reversed()",
        "candidate.rule().canonicalHash()",
        ".thenComparing(MacroCandidate::macroId)",
        "List.of(selected)",
    ]
    missing = [fragment for fragment in required_selector_fragments
               if fragment not in selector]
    if missing:
        fail(f"selector policy implementation drift: {missing}")
    forbidden_selector_imports = [
        "PairedEvaluation",
        "EvaluationTask",
        "SearchRun",
        "UtilityOutcome",
    ]
    leaked = [value for value in forbidden_selector_imports if value in selector]
    if leaked:
        fail(f"selector imports held-out evaluation data: {leaked}")
    return sorted(discovered)


def macro_cases(corpus: dict[str, Any]) -> list[dict[str, Any]]:
    cases = [
        case
        for case in corpus["cases"]
        if case["challengeId"] == CHALLENGE
    ]
    if [case["caseId"] for case in cases] != [
        "case-13",
        "case-14",
        "case-15",
        "case-16",
        "case-17",
        "case-18",
    ]:
        fail("macro case identities or order changed")
    for case in cases:
        require_hash(case, f"case {case['caseId']}")
        policy = case["exposurePolicy"]
        if policy["candidateFormationMustNotRead"] != ["evaluationInput"]:
            fail(f"formation prohibition drift for {case['caseId']}")
        if case["split"] == "TRAIN":
            if policy["candidateFormationMayRead"] != ["formationInput"]:
                fail(f"TRAIN formation access drift for {case['caseId']}")
        elif policy["candidateFormationMayRead"]:
            fail(f"held-out formation access drift for {case['caseId']}")
    return cases


def train_trace_ids(cases: list[dict[str, Any]]) -> list[str]:
    result: list[str] = []
    for case in cases:
        if case["split"] != "TRAIN":
            continue
        formation = case["formationInput"]
        if formation["heldOutTargetsVisible"]:
            fail(f"held-out targets visible in {case['caseId']}")
        if formation["primitiveInventoryProfile"] != PROFILE:
            fail(f"TRAIN profile drift for {case['caseId']}")
        result.extend(item["traceId"] for item in formation["replayTraces"])
    if result != [
        "case-13-trace-1",
        "case-13-trace-2",
        "case-14-trace-1",
        "case-14-trace-2",
    ]:
        fail("TRAIN trace identities or order changed")
    return result


def verify_frozen_inputs(
    benchmark: dict[str, Any],
    corpus: dict[str, Any],
    profile: dict[str, Any],
    receipt: dict[str, Any],
    stream: dict[str, Any],
) -> list[str]:
    require_hash(corpus, "case corpus")
    require_hash(profile, "baseline inventory profile")
    require_hash(receipt, "freeze receipt")
    require_hash(stream, "downstream task stream")
    if semantic_hash(benchmark) != receipt["benchmarkSourceContentHash"]:
        fail("benchmark source/freeze receipt binding drift")
    if corpus["contentHash"] != receipt["caseCorpusContentHash"]:
        fail("case corpus/freeze receipt binding drift")
    if (
        receipt["formationInventoryContentHashes"].get(PROFILE)
        != profile["contentHash"]
    ):
        fail("baseline inventory/freeze receipt binding drift")
    if benchmark.get("executionStatus") != "NOT_STARTED":
        fail("benchmark was not frozen before execution")
    if receipt.get("executionStatusAtFreeze") != "NOT_STARTED":
        fail("receipt execution status drift")
    if receipt.get("executedCampaignsAtFreeze") != 0:
        fail("receipt already contains campaigns")
    if receipt.get("executedEvaluationsAtFreeze") != 0:
        fail("receipt already contains evaluations")
    if receipt.get("resultInspectionStatus") != "NO_EVALUATED_RESULTS_EXIST":
        fail("receipt result-inspection boundary drift")
    if receipt.get("publicationAuthorized") is not False:
        fail("freeze receipt unexpectedly authorizes publication")

    if stream.get("schema") != "regelsuche.downstream-task-stream/v1":
        fail("downstream stream schema drift")
    if stream.get("benchmarkId") != BENCHMARK:
        fail("downstream stream benchmark drift")
    if stream.get("challengeId") != CHALLENGE:
        fail("downstream stream challenge drift")
    if stream.get("sourceCaseCorpusContentHash") != corpus["contentHash"]:
        fail("downstream stream/corpus binding drift")
    if stream.get("freezeReceiptContentHash") != receipt["contentHash"]:
        fail("downstream stream/receipt binding drift")
    if stream.get("baselineInventoryContentHash") != profile["contentHash"]:
        fail("downstream stream/baseline binding drift")
    if (
        stream.get("combinedPreregistrationHash")
        != receipt["combinedPreregistrationHash"]
    ):
        fail("downstream stream preregistration binding drift")
    if stream.get("comparisonPolicy") != COMPARISON:
        fail("downstream stream comparison policy drift")
    if stream.get("configuredTasks") != 12 or len(stream.get("tasks", [])) != 12:
        fail("downstream stream task count drift")
    if stream.get("evaluationStatus") != "NOT_EXECUTED_BY_STREAM_CONSTRUCTION":
        fail("downstream stream contains execution results")
    if stream.get("publicationAuthorized") is not False:
        fail("downstream stream unexpectedly authorizes publication")
    for index, task in enumerate(stream["tasks"], start=1):
        require_hash(task, f"stream task {index}")
        if task.get("index") != index:
            fail(f"downstream stream order drift at task {index}")
    return train_trace_ids(macro_cases(corpus))


def expected_selection(candidates: list[dict[str, Any]]) -> dict[str, Any]:
    if len(candidates) != 3:
        fail("formed candidate count drift")
    macro_ids: set[str] = set()
    hashes: set[str] = set()
    for candidate in candidates:
        require_hash(candidate, f"candidate {candidate.get('macroId')}")
        macro_id = candidate["macroId"]
        if macro_id in macro_ids:
            fail(f"duplicate formed macro ID: {macro_id}")
        macro_ids.add(macro_id)
        canonical_hash = candidate["canonicalHash"]
        if canonical_hash in hashes:
            fail(f"duplicate formed canonical hash: {canonical_hash}")
        hashes.add(canonical_hash)
        supporting = candidate["supportingTraceIds"]
        if len(supporting) != len(set(supporting)):
            fail(f"duplicate supporting trace for {macro_id}")
        if candidate["trainSupport"] != len(supporting):
            fail(f"TRAIN support count drift for {macro_id}")
        if candidate["supportingExamples"] != len(supporting):
            fail(f"supporting-example count drift for {macro_id}")
    return sorted(
        candidates,
        key=lambda candidate: (
            -candidate["trainSupport"],
            candidate["canonicalHash"],
            candidate["macroId"],
        ),
    )[0]


def recompute_outcome(
    baseline: dict[str, Any], candidate: dict[str, Any]
) -> str:
    if baseline["success"] and not candidate["success"]:
        return "CORRECTNESS_REGRESSION"
    if not baseline["success"] and candidate["success"]:
        return "REACHABILITY_GAIN"
    if not baseline["success"]:
        return "NO_RESULT"
    if (
        candidate["expandedStates"] < baseline["expandedStates"]
        or len(candidate["ruleIds"]) < len(baseline["ruleIds"])
    ):
        return "IMPROVED"
    return "NO_IMPROVEMENT"


def verify_search_run(
    run: dict[str, Any], context: str, max_states: int
) -> None:
    require_hash(run, context)
    if run["expandedStates"] > max_states:
        fail(f"{context} exceeds frozen state budget")
    if len(run["path"]) not in {0, len(run["ruleIds"]) + 1}:
        fail(f"{context} path/rule accounting drift")
    if run["success"] and not run["reachedExpression"]:
        fail(f"{context} successful run has no reached expression")


def verify_one(
    run: dict[str, Any],
    benchmark: dict[str, Any],
    corpus: dict[str, Any],
    profile: dict[str, Any],
    receipt: dict[str, Any],
    stream: dict[str, Any],
    train_traces: list[str],
) -> dict[str, Any]:
    require_hash(run, "paired-task utility run")
    if run["schema"] != SCHEMA:
        fail("paired-task utility schema drift")
    if run["benchmarkId"] != BENCHMARK or run["challengeId"] != CHALLENGE:
        fail("paired-task utility identity drift")
    expected_bindings = {
        "caseCorpusContentHash": corpus["contentHash"],
        "freezeReceiptContentHash": receipt["contentHash"],
        "combinedPreregistrationHash": receipt["combinedPreregistrationHash"],
        "downstreamTaskStreamContentHash": stream["contentHash"],
        "baselineInventoryContentHash": profile["contentHash"],
    }
    for field, expected in expected_bindings.items():
        if run[field] != expected:
            fail(f"paired-task utility binding drift: {field}")
    if run["baselineInventoryProfileId"] != PROFILE:
        fail("baseline inventory profile drift")
    if run["comparisonPolicy"] != COMPARISON:
        fail("comparison policy drift")
    if run["formationAccessPolicy"] != "TRAIN_ONLY":
        fail("formation access policy drift")
    if run["candidateSelectionPolicy"] != SELECTION:
        fail("candidate selection policy drift")
    if run["candidateInventoryPolicy"] != INVENTORY:
        fail("candidate inventory policy drift")
    if run["enabledCandidateCount"] != 1:
        fail("candidate-enabled inventory is not exact-one")
    if run["configuredTasks"] != 12 or run["executedTasks"] != 12:
        fail("paired-task execution did not retain all tasks")
    if run["publicationAuthorized"] is not False:
        fail("paired-task utility unexpectedly authorizes publication")
    if run["formalProofStatus"] != "NOT_EVALUATED":
        fail("paired-task utility inflated formal-proof status")
    if run["externalNoveltyStatus"] != "NOT_EVALUATED":
        fail("paired-task utility inflated external novelty status")

    evidence_ids: list[str] = []
    formation_states = 0
    formation_candidates = 0
    for evidence in run["formationReplayEvidence"]:
        require_hash(evidence, f"replay evidence {evidence.get('traceId')}")
        evidence_ids.append(evidence["traceId"])
        if evidence["reproduced"] is not True:
            fail("TRAIN replay was not reproduced")
        formation_states += evidence["exploredStates"]
        formation_candidates += len(evidence["actualRuleIds"])
    if evidence_ids != train_traces:
        fail("formation replay evidence does not match frozen TRAIN traces")

    candidates = run["formedCandidates"]
    if [item["macroId"] for item in candidates] != sorted(
        item["macroId"] for item in candidates
    ):
        fail("formed candidate ledger is not in stable macro-ID order")
    for candidate in candidates:
        if not set(candidate["supportingTraceIds"]).issubset(train_traces):
            fail("candidate support leaks outside frozen TRAIN traces")
    selected = expected_selection(candidates)
    selection = run["candidateSelection"]
    require_hash(selection, "candidate selection")
    if selection["policy"] != SELECTION:
        fail("candidate selection policy record drift")
    if selection["formedCandidateCount"] != len(candidates):
        fail("candidate selection formed-count drift")
    if selection["selectedCandidateId"] != selected["macroId"]:
        fail("selected candidate does not follow the frozen TRAIN-only policy")
    if selection["selectedCandidateContentHash"] != selected["contentHash"]:
        fail("selected candidate content binding drift")
    if selection["selectedTrainSupport"] != selected["trainSupport"]:
        fail("selected candidate TRAIN support drift")

    counts = {outcome: 0 for outcome in OUTCOMES}
    baseline_states = 0
    candidate_states = 0
    baseline_candidates = 0
    candidate_candidates = 0
    baseline_steps = 0
    candidate_steps = 0
    selected_was_used = False
    stream_tasks = stream["tasks"]
    tasks = run["tasks"]
    if len(tasks) != len(stream_tasks):
        fail("paired-task row count drift")
    for index, (task, frozen) in enumerate(zip(tasks, stream_tasks), start=1):
        require_hash(task, f"paired task {index}")
        if task["index"] != index:
            fail(f"paired-task order drift at row {index}")
        frozen_fields = {
            "taskId": frozen["taskId"],
            "caseId": frozen["caseId"],
            "split": frozen["split"],
            "structuralCluster": frozen["structuralCluster"],
            "streamTaskContentHash": frozen["contentHash"],
            "source": frozen["source"],
            "target": frozen["target"],
            "assumptions": frozen["assumptions"],
            "searchBudget": frozen["searchBudget"],
        }
        for field, expected in frozen_fields.items():
            if task[field] != expected:
                fail(f"paired task {index} drifted from stream field {field}")
        if task["comparisonPolicy"] != COMPARISON:
            fail(f"paired task {index} comparison policy drift")
        if task["candidateInventoryPolicy"] != INVENTORY:
            fail(f"paired task {index} inventory policy drift")
        if task["enabledCandidateIds"] != [selected["macroId"]]:
            fail(f"paired task {index} did not enable exactly the selected candidate")

        baseline = task["baseline"]
        candidate_run = task["candidateEnabled"]
        max_states = frozen["searchBudget"]["maxExpandedStates"]
        verify_search_run(baseline, f"task {index} baseline", max_states)
        verify_search_run(candidate_run, f"task {index} candidate", max_states)
        baseline_macro_ids = [
            rule for rule in baseline["ruleIds"]
            if rule.startswith("macro_candidate_independent_")
        ]
        if baseline_macro_ids:
            fail(f"baseline task {index} contains learned candidates")
        enabled_macro_ids = [
            rule for rule in candidate_run["ruleIds"]
            if rule.startswith("macro_candidate_independent_")
        ]
        if any(rule != selected["macroId"] for rule in enabled_macro_ids):
            fail(f"candidate task {index} used an unselected learned candidate")
        selected_was_used = selected_was_used or bool(enabled_macro_ids)

        outcome = recompute_outcome(baseline, candidate_run)
        if task["outcome"] != outcome:
            fail(f"paired task {index} outcome was not independently reproduced")
        regression = baseline["success"] and not candidate_run["success"]
        if task["correctnessRegression"] is not regression:
            fail(f"paired task {index} correctness regression drift")
        if regression:
            fail(f"paired task {index} contains a correctness regression")
        counts[outcome] += 1

        expected_delta = {
            "expandedStateSaving": (
                baseline["expandedStates"] - candidate_run["expandedStates"]
            ),
            "generatedCandidateSaving": (
                baseline["generatedCandidates"]
                - candidate_run["generatedCandidates"]
            ),
            "pathStepSaving": (
                len(baseline["ruleIds"]) - len(candidate_run["ruleIds"])
            ),
        }
        delta = task["resourceDelta"]
        require_hash(delta, f"task {index} resource delta")
        for field, expected in expected_delta.items():
            if delta[field] != expected:
                fail(f"paired task {index} resource delta drift: {field}")

        baseline_states += baseline["expandedStates"]
        candidate_states += candidate_run["expandedStates"]
        baseline_candidates += baseline["generatedCandidates"]
        candidate_candidates += candidate_run["generatedCandidates"]
        baseline_steps += len(baseline["ruleIds"])
        candidate_steps += len(candidate_run["ruleIds"])

    if not selected_was_used:
        fail("selected candidate was never exercised by the frozen stream")
    if counts != run["aggregateOutcomeCounts"]:
        fail("aggregate outcome counts were not independently reproduced")
    if sum(counts.values()) != 12:
        fail("aggregate outcome counts do not retain all tasks")
    if counts["CORRECTNESS_REGRESSION"] != 0:
        fail("aggregate result contains a correctness regression")
    if counts["CANDIDATE_NOT_FORMED"] != 0:
        fail("aggregate result silently omitted candidate formation")
    if run["correctnessRegressionCount"] != 0:
        fail("top-level correctness regression count drift")

    budgets = benchmark["budgets"]
    expected_resource = {
        "configuredStates": budgets["maxStatesPerCampaign"],
        "executedStates": formation_states + baseline_states + candidate_states,
        "remainingStates": (
            budgets["maxStatesPerCampaign"]
            - formation_states - baseline_states - candidate_states
        ),
        "configuredCandidateEvaluations": budgets["maxCandidateEvaluations"],
        "executedCandidateEvaluations": (
            formation_candidates + baseline_candidates + candidate_candidates
        ),
        "remainingCandidateEvaluations": (
            budgets["maxCandidateEvaluations"]
            - formation_candidates - baseline_candidates - candidate_candidates
        ),
        "configuredProofAttempts": budgets["maxProofAttempts"],
        "executedProofAttempts": 0,
        "remainingProofAttempts": budgets["maxProofAttempts"],
        "formationStates": formation_states,
        "formationCandidateEvaluations": formation_candidates,
        "baselineExpandedStates": baseline_states,
        "candidateExpandedStates": candidate_states,
        "expandedStateSaving": baseline_states - candidate_states,
        "baselineGeneratedCandidates": baseline_candidates,
        "candidateGeneratedCandidates": candidate_candidates,
        "generatedCandidateSaving": baseline_candidates - candidate_candidates,
        "baselinePathSteps": baseline_steps,
        "candidatePathSteps": candidate_steps,
        "pathStepSaving": baseline_steps - candidate_steps,
    }
    resource = run["resourceUse"]
    require_hash(resource, "aggregate resource use")
    for field, expected in expected_resource.items():
        if resource[field] != expected:
            fail(f"aggregate resource drift: {field}")
    if resource["remainingStates"] < 0:
        fail("paired utility exceeded configured state budget")
    if resource["remainingCandidateEvaluations"] < 0:
        fail("paired utility exceeded configured candidate budget")
    return {
        "selectedCandidateId": selected["macroId"],
        "selectedCandidateContentHash": selected["contentHash"],
        "outcomeCounts": counts,
        "resourceUse": expected_resource,
    }


def rehash(value: dict[str, Any]) -> None:
    value.pop("contentHash", None)
    value["contentHash"] = semantic_hash(value)


def expect_failure(
    mutated: dict[str, Any],
    benchmark: dict[str, Any],
    corpus: dict[str, Any],
    profile: dict[str, Any],
    receipt: dict[str, Any],
    stream: dict[str, Any],
    train_traces: list[str],
    label: str,
) -> None:
    try:
        verify_one(
            mutated,
            benchmark,
            corpus,
            profile,
            receipt,
            stream,
            train_traces,
        )
    except RuntimeError:
        return
    fail(f"mutation survived independent verification: {label}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runner-source", required=True, type=Path)
    parser.add_argument("--selector-source", required=True, type=Path)
    parser.add_argument("--first", required=True, type=Path)
    parser.add_argument("--second", required=True, type=Path)
    parser.add_argument("--benchmark-source", required=True, type=Path)
    parser.add_argument("--corpus", required=True, type=Path)
    parser.add_argument("--profile", required=True, type=Path)
    parser.add_argument("--freeze-receipt", required=True, type=Path)
    parser.add_argument("--downstream-stream", required=True, type=Path)
    parser.add_argument("--schema", required=True, type=Path)
    parser.add_argument("--report-directory", required=True, type=Path)
    arguments = parser.parse_args()

    verified_runner_arguments = verify_source_boundaries(
        arguments.runner_source, arguments.selector_source
    )
    schema = load(arguments.schema)
    jsonschema.Draft202012Validator.check_schema(schema)
    validator = jsonschema.Draft202012Validator(schema)
    first = load(arguments.first)
    second = load(arguments.second)
    benchmark = load(arguments.benchmark_source)
    corpus = load(arguments.corpus)
    profile = load(arguments.profile)
    receipt = load(arguments.freeze_receipt)
    stream = load(arguments.downstream_stream)
    train_traces = verify_frozen_inputs(
        benchmark, corpus, profile, receipt, stream
    )

    for run in (first, second):
        validator.validate(run)
    first_summary = verify_one(
        first,
        benchmark,
        corpus,
        profile,
        receipt,
        stream,
        train_traces,
    )
    second_summary = verify_one(
        second,
        benchmark,
        corpus,
        profile,
        receipt,
        stream,
        train_traces,
    )
    if first_summary != second_summary:
        fail("clean exact-one run summaries differ")
    if arguments.first.read_bytes() != arguments.second.read_bytes():
        fail("clean exact-one paired utility runs are not byte-identical")

    extra_candidate = copy.deepcopy(first)
    task = extra_candidate["tasks"][0]
    task["enabledCandidateIds"].append(
        extra_candidate["formedCandidates"][1]["macroId"]
    )
    rehash(task)
    rehash(extra_candidate)
    expect_failure(
        extra_candidate,
        benchmark,
        corpus,
        profile,
        receipt,
        stream,
        train_traces,
        "extra enabled candidate",
    )

    outcome_drift = copy.deepcopy(first)
    task = outcome_drift["tasks"][0]
    task["outcome"] = "NO_IMPROVEMENT"
    rehash(task)
    counts = Counter(item["outcome"] for item in outcome_drift["tasks"])
    outcome_drift["aggregateOutcomeCounts"] = {
        outcome: counts.get(outcome, 0) for outcome in OUTCOMES
    }
    rehash(outcome_drift)
    expect_failure(
        outcome_drift,
        benchmark,
        corpus,
        profile,
        receipt,
        stream,
        train_traces,
        "outcome inflation",
    )

    selection_drift = copy.deepcopy(first)
    selected_id = selection_drift["candidateSelection"]["selectedCandidateId"]
    alternative = next(
        item
        for item in selection_drift["formedCandidates"]
        if item["macroId"] != selected_id
    )
    selection = selection_drift["candidateSelection"]
    selection["selectedCandidateId"] = alternative["macroId"]
    selection["selectedCandidateContentHash"] = alternative["contentHash"]
    selection["selectedTrainSupport"] = alternative["trainSupport"]
    rehash(selection)
    for task in selection_drift["tasks"]:
        task["enabledCandidateIds"] = [alternative["macroId"]]
        rehash(task)
    rehash(selection_drift)
    expect_failure(
        selection_drift,
        benchmark,
        corpus,
        profile,
        receipt,
        stream,
        train_traces,
        "post-hoc candidate substitution",
    )

    reordered = copy.deepcopy(first)
    reordered["tasks"][0], reordered["tasks"][1] = (
        reordered["tasks"][1],
        reordered["tasks"][0],
    )
    reordered["tasks"][0]["index"] = 1
    reordered["tasks"][1]["index"] = 2
    rehash(reordered["tasks"][0])
    rehash(reordered["tasks"][1])
    rehash(reordered)
    expect_failure(
        reordered,
        benchmark,
        corpus,
        profile,
        receipt,
        stream,
        train_traces,
        "task reordering",
    )

    publication = copy.deepcopy(first)
    publication["publicationAuthorized"] = True
    rehash(publication)
    try:
        validator.validate(publication)
    except jsonschema.ValidationError:
        pass
    else:
        fail("schema accepted paired utility publication authorization")

    report = {
        "schema": "regelsuche.paired-task-utility-verification/v1",
        "pairedTaskUtilityContentHash": first["contentHash"],
        "downstreamTaskStreamContentHash": stream["contentHash"],
        "byteIdenticalCleanRuns": True,
        "verifiedRunnerArguments": verified_runner_arguments,
        "verifiedCandidateSelectionPolicy": SELECTION,
        "verifiedCandidateInventoryPolicy": INVENTORY,
        "selectedCandidateId": first_summary["selectedCandidateId"],
        "selectedCandidateContentHash": first_summary[
            "selectedCandidateContentHash"
        ],
        "verifiedTaskCount": 12,
        "verifiedOutcomeCounts": first_summary["outcomeCounts"],
        "verifiedResourceUse": first_summary["resourceUse"],
        "verifiedMutations": [
            "extra-enabled-candidate",
            "outcome-inflation",
            "post-hoc-candidate-substitution",
            "task-reordering",
            "publication-authorization",
        ],
        "formalProofStatus": "NOT_EVALUATED",
        "externalNoveltyStatus": "NOT_EVALUATED",
        "publicationAuthorized": False,
    }
    arguments.report_directory.mkdir(parents=True, exist_ok=True)
    (arguments.report_directory / "verification.json").write_text(
        json.dumps(report, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
        encoding="utf-8",
    )
    print("paired-task-utility=VERIFIED")
    print(f"selected-candidate={first_summary['selectedCandidateId']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
