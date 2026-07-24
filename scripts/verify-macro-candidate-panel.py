#!/usr/bin/env python3
"""Independently verify the canonical all-candidate reusable-macro panel."""

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

SCHEMA = "regelsuche.macro-candidate-panel/v1"
BENCHMARK = "regelsuche-candidate-independent-autonomous-discovery-2026-07/v1"
CHALLENGE = "reusable-search-macros"
PROFILE = "macro-primitives/v1"
PANEL_POLICY = "ALL_TRAIN_FORMED_CANDIDATES_BASELINE_PLUS_EXACTLY_ONE"
BASELINE_POLICY = "REUSE_VERIFIED_PAIRED_UTILITY_BASELINE_PER_TASK"
DECISION_POLICY = "DESCRIPTIVE_PANEL_DOES_NOT_RESELECT_PRODUCTION_CANDIDATE"
OUTCOMES = [
    "IMPROVED",
    "REACHABILITY_GAIN",
    "NO_IMPROVEMENT",
    "NO_RESULT",
    "CORRECTNESS_REGRESSION",
    "CANDIDATE_NOT_FORMED",
]
EXPECTED_RUNNER_ARGUMENTS = {
    "--corpus",
    "--profile",
    "--downstream-stream",
    "--paired-utility",
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


def rehash(value: dict[str, Any]) -> None:
    value.pop("contentHash", None)
    value["contentHash"] = semantic_hash(value)


def verify_runner_source(path: Path) -> list[str]:
    if not path.is_file() or path.is_symlink():
        fail(f"expected regular runner source: {path}")
    source = path.read_text(encoding="utf-8")
    flags = re.findall(r'"(--[a-z-]+)"', source)
    actual = set(flags)
    if actual != EXPECTED_RUNNER_ARGUMENTS:
        fail(
            "candidate-panel runner CLI allowlist drift: "
            f"missing={sorted(EXPECTED_RUNNER_ARGUMENTS - actual)}, "
            f"unexpected={sorted(actual - EXPECTED_RUNNER_ARGUMENTS)}"
        )
    counts = Counter(flags)
    missing_consumption = sorted(
        flag for flag in EXPECTED_RUNNER_ARGUMENTS if counts[flag] < 2
    )
    if missing_consumption:
        fail(
            "candidate-panel runner arguments are not declared and consumed: "
            f"{missing_consumption}"
        )

    formation = source.find("adapter.form(traces)")
    stream_read = source.find("readObject(arguments.downstreamStream())")
    utility_read = source.find("readObject(arguments.pairedUtility())")
    if not (0 <= formation < stream_read and formation < utility_read):
        fail("candidate formation is not completed before evaluated inputs are read")

    required = [
        "CandidateIndependentMacroCandidatePanelRunner",
        "new BaselineEvaluation(task.task(), baseline.run())",
        "List.of(candidate)",
        'run.put("productionSelectionPreserved", true)',
        'run.put("exercisedCandidateCount", exercisedCandidateCount)',
        'candidateResult.put("macroUsageCount", macroUsageCount)',
        "DESCRIPTIVE_PANEL_DOES_NOT_RESELECT_PRODUCTION_CANDIDATE",
        'run.put("publicationAuthorized", false)',
    ]
    missing = [fragment for fragment in required if fragment not in source]
    if missing:
        fail(f"candidate-panel runner contract drift: {missing}")
    if "CandidateIndependentExactOneMacroSelector" in source:
        fail("candidate panel attempts to reselect the production candidate")
    return sorted(actual)


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


def validate_inputs(
    corpus: dict[str, Any],
    profile: dict[str, Any],
    stream: dict[str, Any],
    utility: dict[str, Any],
) -> None:
    for context, value in [
        ("case corpus", corpus),
        ("baseline profile", profile),
        ("downstream stream", stream),
        ("paired utility", utility),
    ]:
        require_hash(value, context)
    if corpus.get("benchmarkId") != BENCHMARK:
        fail("case corpus benchmark identity drift")
    if corpus.get("freezeStatus") != "FROZEN_BEFORE_EVALUATED_EXECUTION":
        fail("case corpus is not pre-execution frozen")
    if corpus.get("executionStatusAtFreeze") != "NOT_STARTED":
        fail("case corpus execution status drift")
    if profile.get("profileId") != PROFILE:
        fail("baseline profile identity drift")
    if stream.get("schema") != "regelsuche.downstream-task-stream/v1":
        fail("downstream task stream schema drift")
    if utility.get("schema") != "regelsuche.paired-task-utility/v1":
        fail("paired utility schema drift")
    if stream.get("sourceCaseCorpusContentHash") != corpus["contentHash"]:
        fail("stream/corpus binding drift")
    if stream.get("baselineInventoryContentHash") != profile["contentHash"]:
        fail("stream/profile binding drift")
    if utility.get("caseCorpusContentHash") != corpus["contentHash"]:
        fail("paired utility/corpus binding drift")
    if utility.get("baselineInventoryContentHash") != profile["contentHash"]:
        fail("paired utility/profile binding drift")
    if utility.get("downstreamTaskStreamContentHash") != stream["contentHash"]:
        fail("paired utility/stream binding drift")
    if utility.get("enabledCandidateCount") != 1:
        fail("paired utility is not exact-one candidate")
    if utility.get("executedTasks") != 12:
        fail("paired utility task count drift")
    if utility.get("correctnessRegressionCount") != 0:
        fail("production-selected paired utility contains regressions")
    if utility.get("publicationAuthorized") is not False:
        fail("paired utility unexpectedly authorizes publication")

    require_hash(utility["candidateSelection"], "paired utility selection")
    require_hash(utility["resourceUse"], "paired utility resource use")
    for index, evidence in enumerate(
        utility["formationReplayEvidence"], start=1
    ):
        require_hash(evidence, f"paired utility replay evidence {index}")
    for candidate in utility["formedCandidates"]:
        require_hash(candidate, f"paired utility candidate {candidate.get('macroId')}")
        for step in candidate["atomicSteps"]:
            require_hash(step, "paired utility candidate atomic step")


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


def expected_delta(
    baseline: dict[str, Any], candidate: dict[str, Any]
) -> dict[str, int]:
    return {
        "expandedStateSaving": (
            baseline["expandedStates"] - candidate["expandedStates"]
        ),
        "generatedCandidateSaving": (
            baseline["generatedCandidates"] - candidate["generatedCandidates"]
        ),
        "pathStepSaving": (
            len(baseline["ruleIds"]) - len(candidate["ruleIds"])
        ),
    }


def baseline_bindings(
    stream: dict[str, Any], utility: dict[str, Any]
) -> list[dict[str, Any]]:
    stream_tasks = stream["tasks"]
    utility_tasks = utility["tasks"]
    if len(stream_tasks) != 12 or len(utility_tasks) != 12:
        fail("frozen or paired task count drift")

    result: list[dict[str, Any]] = []
    for index, (frozen, paired) in enumerate(
        zip(stream_tasks, utility_tasks), start=1
    ):
        require_hash(frozen, f"stream task {index}")
        require_hash(paired, f"paired utility task {index}")
        if frozen["index"] != index or paired["index"] != index:
            fail(f"task order drift at index {index}")
        if paired["taskId"] != frozen["taskId"]:
            fail(f"paired task identity drift at index {index}")
        if paired["streamTaskContentHash"] != frozen["contentHash"]:
            fail(f"paired task stream binding drift at index {index}")

        baseline = paired["baseline"]
        selected_run = paired["candidateEnabled"]
        delta = paired["resourceDelta"]
        max_states = frozen["searchBudget"]["maxExpandedStates"]
        verify_search_run(baseline, f"paired baseline {index}", max_states)
        verify_search_run(
            selected_run, f"paired selected candidate {index}", max_states
        )
        require_hash(delta, f"paired utility resource delta {index}")
        if any(
            rule.startswith("macro_candidate_independent_")
            for rule in baseline["ruleIds"]
        ):
            fail(f"paired baseline {index} contains a learned candidate")
        for field, expected in expected_delta(baseline, selected_run).items():
            if delta[field] != expected:
                fail(f"paired utility resource delta drift at {index}: {field}")
        result.append(
            {"stream": frozen, "paired": paired, "baseline": baseline}
        )
    return result


def candidate_resource(
    baselines: list[dict[str, Any]], tasks: list[dict[str, Any]]
) -> dict[str, Any]:
    baseline_states = sum(
        item["baseline"]["expandedStates"] for item in baselines
    )
    baseline_generated = sum(
        item["baseline"]["generatedCandidates"] for item in baselines
    )
    baseline_steps = sum(
        len(item["baseline"]["ruleIds"]) for item in baselines
    )
    candidate_states = sum(
        item["candidateEnabled"]["expandedStates"] for item in tasks
    )
    candidate_generated = sum(
        item["candidateEnabled"]["generatedCandidates"] for item in tasks
    )
    candidate_steps = sum(
        len(item["candidateEnabled"]["ruleIds"]) for item in tasks
    )
    result = {
        "baselineExecutionPolicy": BASELINE_POLICY,
        "sharedBaselineExpandedStates": baseline_states,
        "sharedBaselineGeneratedCandidates": baseline_generated,
        "sharedBaselinePathSteps": baseline_steps,
        "candidateExpandedStates": candidate_states,
        "candidateGeneratedCandidates": candidate_generated,
        "candidatePathSteps": candidate_steps,
        "expandedStateSaving": baseline_states - candidate_states,
        "generatedCandidateSaving": baseline_generated - candidate_generated,
        "pathStepSaving": baseline_steps - candidate_steps,
    }
    result["contentHash"] = semantic_hash(result)
    return result


def physical_resource(
    utility: dict[str, Any],
    baselines: list[dict[str, Any]],
    candidate_results: list[dict[str, Any]],
) -> dict[str, Any]:
    formation_states = sum(
        item["exploredStates"] for item in utility["formationReplayEvidence"]
    )
    formation_candidates = sum(
        len(item["actualRuleIds"])
        for item in utility["formationReplayEvidence"]
    )
    baseline_states = sum(
        item["baseline"]["expandedStates"] for item in baselines
    )
    baseline_generated = sum(
        item["baseline"]["generatedCandidates"] for item in baselines
    )
    baseline_steps = sum(
        len(item["baseline"]["ruleIds"]) for item in baselines
    )
    panel_states = sum(
        task["candidateEnabled"]["expandedStates"]
        for candidate in candidate_results
        for task in candidate["tasks"]
    )
    panel_generated = sum(
        task["candidateEnabled"]["generatedCandidates"]
        for candidate in candidate_results
        for task in candidate["tasks"]
    )
    panel_steps = sum(
        len(task["candidateEnabled"]["ruleIds"])
        for candidate in candidate_results
        for task in candidate["tasks"]
    )
    result = {
        "policy": (
            "FORMATION_ONCE_SHARED_BASELINES_ONCE_EACH_CANDIDATE_RUN_ONCE"
        ),
        "formationStates": formation_states,
        "formationCandidateEvaluations": formation_candidates,
        "sharedBaselineExpandedStates": baseline_states,
        "sharedBaselineGeneratedCandidates": baseline_generated,
        "sharedBaselinePathSteps": baseline_steps,
        "candidatePanelExpandedStates": panel_states,
        "candidatePanelGeneratedCandidates": panel_generated,
        "candidatePanelPathSteps": panel_steps,
        "totalPhysicalStates": formation_states + baseline_states + panel_states,
        "totalPhysicalCandidateEvaluations": (
            formation_candidates + baseline_generated + panel_generated
        ),
    }
    result["contentHash"] = semantic_hash(result)
    return result


def verify_one(
    panel: dict[str, Any],
    corpus: dict[str, Any],
    profile: dict[str, Any],
    stream: dict[str, Any],
    utility: dict[str, Any],
) -> dict[str, Any]:
    require_hash(panel, "macro candidate panel")
    expected_top = {
        "schema": SCHEMA,
        "benchmarkId": BENCHMARK,
        "challengeId": CHALLENGE,
        "caseCorpusContentHash": corpus["contentHash"],
        "baselineInventoryProfileId": PROFILE,
        "baselineInventoryContentHash": profile["contentHash"],
        "downstreamTaskStreamContentHash": stream["contentHash"],
        "pairedTaskUtilityContentHash": utility["contentHash"],
        "formationAccessPolicy": "TRAIN_ONLY",
        "candidatePanelPolicy": PANEL_POLICY,
        "sharedBaselinePolicy": BASELINE_POLICY,
        "decisionPolicy": DECISION_POLICY,
        "formedCandidateCount": 3,
        "evaluatedCandidateCount": 3,
        "tasksPerCandidate": 12,
        "totalCandidateTaskEvaluations": 36,
        "productionSelectionPreserved": True,
        "formalProofStatus": "NOT_EVALUATED",
        "externalNoveltyStatus": "NOT_EVALUATED",
        "publicationAuthorized": False,
    }
    for field, expected in expected_top.items():
        if panel.get(field) != expected:
            fail(f"candidate panel top-level drift: {field}")

    formed = panel["formedCandidates"]
    if formed != utility["formedCandidates"]:
        fail("candidate panel formation differs from paired utility")
    if len(formed) != 3:
        fail("formed candidate count drift")

    formed_by_id: dict[str, dict[str, Any]] = {}
    for candidate in formed:
        require_hash(candidate, f"formed candidate {candidate.get('macroId')}")
        for step in candidate["atomicSteps"]:
            require_hash(step, "formed candidate atomic step")
        candidate_id = candidate["macroId"]
        if candidate_id in formed_by_id:
            fail(f"duplicate formed candidate: {candidate_id}")
        formed_by_id[candidate_id] = candidate
    if list(formed_by_id) != sorted(formed_by_id):
        fail("formed candidates are not in stable macro-ID order")

    selection = utility["candidateSelection"]
    selected_id = selection["selectedCandidateId"]
    selected_hash = selection["selectedCandidateContentHash"]
    if panel["selectedCandidateId"] != selected_id:
        fail("candidate panel changed the production-selected candidate")
    if panel["selectedCandidateContentHash"] != selected_hash:
        fail("candidate panel selected-candidate content binding drift")
    if formed_by_id[selected_id]["contentHash"] != selected_hash:
        fail("selected candidate is not bound to the formed-candidate ledger")

    baselines = baseline_bindings(stream, utility)
    results = panel["candidates"]
    if len(results) != 3:
        fail("candidate panel result count drift")
    if [item["candidateId"] for item in results] != list(formed_by_id):
        fail("candidate panel result identities or order drift")

    aggregate = {outcome: 0 for outcome in OUTCOMES}
    selected_count = 0
    exercised_candidate_count = 0
    candidates_with_regression = 0
    for result in results:
        candidate_id = result["candidateId"]
        formed_candidate = formed_by_id[candidate_id]
        require_hash(result, f"candidate result {candidate_id}")
        if result["candidateContentHash"] != formed_candidate["contentHash"]:
            fail(f"candidate content binding drift: {candidate_id}")
        if result["trainSupport"] != formed_candidate["trainSupport"]:
            fail(f"candidate TRAIN support drift: {candidate_id}")

        production_selected = candidate_id == selected_id
        if result["productionSelected"] is not production_selected:
            fail(f"production selection flag drift: {candidate_id}")
        selected_count += int(production_selected)

        counts = {outcome: 0 for outcome in OUTCOMES}
        regressions = 0
        usage_count = 0
        tasks = result["tasks"]
        if len(tasks) != 12:
            fail(f"candidate task count drift: {candidate_id}")
        for index, (task, binding) in enumerate(
            zip(tasks, baselines), start=1
        ):
            frozen = binding["stream"]
            paired = binding["paired"]
            baseline = binding["baseline"]
            require_hash(task, f"candidate {candidate_id} task {index}")
            expected_fields = {
                "index": index,
                "taskId": frozen["taskId"],
                "caseId": frozen["caseId"],
                "split": frozen["split"],
                "structuralCluster": frozen["structuralCluster"],
                "streamTaskContentHash": frozen["contentHash"],
                "baselineContentHash": baseline["contentHash"],
                "enabledCandidateId": candidate_id,
                "productionSelectedRunParity": production_selected,
            }
            for field, expected in expected_fields.items():
                if task[field] != expected:
                    fail(
                        f"candidate {candidate_id} task {index} drift: {field}"
                    )

            candidate_run = task["candidateEnabled"]
            delta = task["resourceDelta"]
            max_states = frozen["searchBudget"]["maxExpandedStates"]
            verify_search_run(
                candidate_run,
                f"candidate {candidate_id} search {index}",
                max_states,
            )
            require_hash(delta, f"candidate {candidate_id} delta {index}")
            learned = [
                rule
                for rule in candidate_run["ruleIds"]
                if rule.startswith("macro_candidate_independent_")
            ]
            if any(rule != candidate_id for rule in learned):
                fail(f"candidate {candidate_id} task {index} used another macro")
            usage_count += len(learned)

            outcome = recompute_outcome(baseline, candidate_run)
            if task["outcome"] != outcome:
                fail(f"candidate {candidate_id} task {index} outcome drift")
            regression = baseline["success"] and not candidate_run["success"]
            if task["correctnessRegression"] is not regression:
                fail(f"candidate {candidate_id} task {index} regression drift")
            counts[outcome] += 1
            aggregate[outcome] += 1
            regressions += int(regression)

            for field, expected in expected_delta(
                baseline, candidate_run
            ).items():
                if delta[field] != expected:
                    fail(
                        f"candidate {candidate_id} task {index} delta drift: "
                        f"{field}"
                    )

            if production_selected:
                if candidate_run != paired["candidateEnabled"]:
                    fail(
                        f"selected candidate task {index} differs from paired utility"
                    )
                if task["outcome"] != paired["outcome"]:
                    fail(f"selected candidate task {index} outcome parity drift")
                if delta != paired["resourceDelta"]:
                    fail(f"selected candidate task {index} delta parity drift")
                if regression is not paired["correctnessRegression"]:
                    fail(
                        f"selected candidate task {index} regression parity drift"
                    )

        exercised = usage_count > 0
        if result["macroUsageCount"] != usage_count:
            fail(f"candidate macro usage count drift: {candidate_id}")
        if result["exercised"] is not exercised:
            fail(f"candidate exercised flag drift: {candidate_id}")
        if production_selected and not exercised:
            fail("production-selected candidate was not exercised")
        exercised_candidate_count += int(exercised)

        if counts != result["outcomeCounts"]:
            fail(f"candidate outcome counts drift: {candidate_id}")
        if counts["CANDIDATE_NOT_FORMED"] != 0:
            fail(f"candidate silently reported not formed: {candidate_id}")
        if regressions != result["correctnessRegressionCount"]:
            fail(f"candidate regression count drift: {candidate_id}")
        expected_resource = candidate_resource(baselines, tasks)
        require_hash(result["resourceUse"], f"candidate resource use {candidate_id}")
        if result["resourceUse"] != expected_resource:
            fail(f"candidate resource accounting drift: {candidate_id}")
        candidates_with_regression += int(regressions > 0)

    if selected_count != 1:
        fail("candidate panel does not preserve exactly one production selection")
    if panel["exercisedCandidateCount"] != exercised_candidate_count:
        fail("candidate panel exercised-candidate count drift")
    if panel["unexercisedCandidateCount"] != 3 - exercised_candidate_count:
        fail("candidate panel unexercised-candidate count drift")
    if exercised_candidate_count < 1:
        fail("candidate panel did not exercise the production candidate")
    if aggregate != panel["aggregatePanelOutcomeCounts"]:
        fail("candidate panel aggregate outcome drift")
    if sum(aggregate.values()) != 36:
        fail("candidate panel aggregate lost task rows")
    if aggregate["CANDIDATE_NOT_FORMED"] != 0:
        fail("candidate panel contains candidate-not-formed outcomes")
    if (
        panel["candidatesWithCorrectnessRegression"]
        != candidates_with_regression
    ):
        fail("candidate panel regression-candidate count drift")

    expected_physical = physical_resource(utility, baselines, results)
    require_hash(panel["physicalResourceUse"], "panel physical resource use")
    if panel["physicalResourceUse"] != expected_physical:
        fail("candidate panel physical resource accounting drift")
    return {
        "selectedCandidateId": selected_id,
        "evaluatedCandidateIds": list(formed_by_id),
        "exercisedCandidateCount": exercised_candidate_count,
        "unexercisedCandidateCount": 3 - exercised_candidate_count,
        "aggregateOutcomeCounts": aggregate,
        "candidatesWithCorrectnessRegression": candidates_with_regression,
    }


def expect_verification_failure(
    value: dict[str, Any],
    corpus: dict[str, Any],
    profile: dict[str, Any],
    stream: dict[str, Any],
    utility: dict[str, Any],
    label: str,
) -> None:
    try:
        verify_one(value, corpus, profile, stream, utility)
    except RuntimeError:
        return
    fail(f"mutation survived independent verification: {label}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runner-source", required=True, type=Path)
    parser.add_argument("--first", required=True, type=Path)
    parser.add_argument("--second", required=True, type=Path)
    parser.add_argument("--corpus", required=True, type=Path)
    parser.add_argument("--profile", required=True, type=Path)
    parser.add_argument("--downstream-stream", required=True, type=Path)
    parser.add_argument("--paired-utility", required=True, type=Path)
    parser.add_argument("--schema", required=True, type=Path)
    parser.add_argument("--report-directory", required=True, type=Path)
    arguments = parser.parse_args()

    verified_runner_arguments = verify_runner_source(arguments.runner_source)
    schema = load(arguments.schema)
    jsonschema.Draft202012Validator.check_schema(schema)
    validator = jsonschema.Draft202012Validator(schema)
    corpus = load(arguments.corpus)
    profile = load(arguments.profile)
    stream = load(arguments.downstream_stream)
    utility = load(arguments.paired_utility)
    validate_inputs(corpus, profile, stream, utility)
    first = load(arguments.first)
    second = load(arguments.second)
    for panel in (first, second):
        validator.validate(panel)

    summary = verify_one(first, corpus, profile, stream, utility)
    second_summary = verify_one(second, corpus, profile, stream, utility)
    if summary != second_summary:
        fail("clean candidate-panel summaries differ")
    if arguments.first.read_bytes() != arguments.second.read_bytes():
        fail("clean candidate-panel outputs are not byte-identical")

    omitted = copy.deepcopy(first)
    omitted["candidates"].pop()
    rehash(omitted)
    try:
        validator.validate(omitted)
    except jsonschema.ValidationError:
        pass
    else:
        fail("schema accepted an omitted panel candidate")

    reordered = copy.deepcopy(first)
    reordered["candidates"][0], reordered["candidates"][1] = (
        reordered["candidates"][1],
        reordered["candidates"][0],
    )
    rehash(reordered)
    validator.validate(reordered)
    expect_verification_failure(
        reordered, corpus, profile, stream, utility, "candidate order"
    )

    substituted = copy.deepcopy(first)
    candidate = substituted["candidates"][0]
    task = candidate["tasks"][0]
    task["enabledCandidateId"] = substituted["candidates"][1]["candidateId"]
    rehash(task)
    rehash(candidate)
    rehash(substituted)
    validator.validate(substituted)
    expect_verification_failure(
        substituted,
        corpus,
        profile,
        stream,
        utility,
        "enabled candidate substitution",
    )

    outcome_drift = copy.deepcopy(first)
    candidate = outcome_drift["candidates"][0]
    task = candidate["tasks"][0]
    task["outcome"] = (
        "NO_RESULT" if task["outcome"] != "NO_RESULT" else "IMPROVED"
    )
    rehash(task)
    rehash(candidate)
    rehash(outcome_drift)
    validator.validate(outcome_drift)
    expect_verification_failure(
        outcome_drift, corpus, profile, stream, utility, "outcome inflation"
    )

    baseline_drift = copy.deepcopy(first)
    candidate = baseline_drift["candidates"][0]
    task = candidate["tasks"][0]
    task["baselineContentHash"] = "sha256:" + "0" * 64
    rehash(task)
    rehash(candidate)
    rehash(baseline_drift)
    validator.validate(baseline_drift)
    expect_verification_failure(
        baseline_drift, corpus, profile, stream, utility, "baseline binding"
    )

    usage_drift = copy.deepcopy(first)
    candidate = usage_drift["candidates"][0]
    if candidate["exercised"]:
        candidate["macroUsageCount"] += 1
    else:
        candidate["exercised"] = True
        candidate["macroUsageCount"] = 1
    rehash(candidate)
    rehash(usage_drift)
    validator.validate(usage_drift)
    expect_verification_failure(
        usage_drift, corpus, profile, stream, utility, "candidate usage"
    )

    count_drift = copy.deepcopy(first)
    count_drift["exercisedCandidateCount"] = (
        2 if first["exercisedCandidateCount"] != 2 else 1
    )
    count_drift["unexercisedCandidateCount"] = (
        3 - count_drift["exercisedCandidateCount"]
    )
    rehash(count_drift)
    validator.validate(count_drift)
    expect_verification_failure(
        count_drift, corpus, profile, stream, utility, "usage totals"
    )

    parity_drift = copy.deepcopy(first)
    selected = next(
        item for item in parity_drift["candidates"] if item["productionSelected"]
    )
    selected_task = selected["tasks"][0]
    selected_task["productionSelectedRunParity"] = False
    rehash(selected_task)
    rehash(selected)
    rehash(parity_drift)
    validator.validate(parity_drift)
    expect_verification_failure(
        parity_drift,
        corpus,
        profile,
        stream,
        utility,
        "production selected parity",
    )

    reselection = copy.deepcopy(first)
    reselection["decisionPolicy"] = "POST_HOC_BEST_PANEL_CANDIDATE"
    rehash(reselection)
    try:
        validator.validate(reselection)
    except jsonschema.ValidationError:
        pass
    else:
        fail("schema accepted post-hoc production-candidate reselection")

    publication = copy.deepcopy(first)
    publication["publicationAuthorized"] = True
    rehash(publication)
    try:
        validator.validate(publication)
    except jsonschema.ValidationError:
        pass
    else:
        fail("schema accepted candidate-panel publication authorization")

    verification = {
        "schema": "regelsuche.macro-candidate-panel-verification/v1",
        "panelContentHash": first["contentHash"],
        "pairedTaskUtilityContentHash": utility["contentHash"],
        "downstreamTaskStreamContentHash": stream["contentHash"],
        "byteIdenticalCleanRuns": True,
        "verifiedRunnerArguments": verified_runner_arguments,
        "verifiedCandidateIds": summary["evaluatedCandidateIds"],
        "verifiedSelectedCandidateId": summary["selectedCandidateId"],
        "verifiedExercisedCandidateCount": summary[
            "exercisedCandidateCount"
        ],
        "verifiedUnexercisedCandidateCount": summary[
            "unexercisedCandidateCount"
        ],
        "verifiedAggregateOutcomeCounts": summary["aggregateOutcomeCounts"],
        "verifiedCandidatesWithCorrectnessRegression": summary[
            "candidatesWithCorrectnessRegression"
        ],
        "verifiedMutations": [
            "omitted-candidate",
            "candidate-order",
            "enabled-candidate-substitution",
            "outcome-inflation",
            "baseline-binding",
            "candidate-usage",
            "usage-totals",
            "production-selected-parity",
            "post-hoc-reselection",
            "publication-authorization",
        ],
        "decisionPolicy": DECISION_POLICY,
        "publicationAuthorized": False,
    }
    verification["contentHash"] = semantic_hash(verification)
    arguments.report_directory.mkdir(parents=True, exist_ok=True)
    (arguments.report_directory / "verification.json").write_text(
        json.dumps(
            verification, ensure_ascii=False, sort_keys=True, indent=2
        )
        + "\n",
        encoding="utf-8",
    )
    print("macro-candidate-panel=VERIFIED")
    print(f"selected-candidate={summary['selectedCandidateId']}")
    print(
        "exercised-candidates="
        f"{summary['exercisedCandidateCount']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
