#!/usr/bin/env python3
"""Independently verify the frozen reusable-macro four-campaign batch."""

from __future__ import annotations

import argparse
import copy
from collections import Counter
import hashlib
import json
from pathlib import Path
import shutil
import sys
from typing import Any, Callable

import jsonschema

RUN_SCHEMA = "regelsuche.candidate-independent-reusable-macro-batch/v1"
VERIFY_SCHEMA = (
    "regelsuche.candidate-independent-reusable-macro-batch-verification/v1"
)
BENCHMARK_ID = "regelsuche-candidate-independent-autonomous-discovery-2026-07/v1"
CHALLENGE = "reusable-search-macros"
PROFILE_ID = "macro-primitives/v1"
EXPECTED_CAMPAIGNS = [
    f"{CHALLENGE}-campaign-{index:02d}" for index in range(1, 5)
]
EXPECTED_CASES = [f"case-{index:02d}" for index in range(13, 19)]
EXPECTED_TASKS = [
    f"case-{case_index:02d}-task-{task_index}"
    for case_index in range(13, 19)
    for task_index in range(1, 3)
]
EXPECTED_AGGREGATE = {
    "IMPROVED": 8,
    "REACHABILITY_GAIN": 0,
    "NO_IMPROVEMENT": 24,
    "NO_RESULT": 16,
    "CORRECTNESS_REGRESSION": 0,
    "CANDIDATE_NOT_FORMED": 0,
}
EXPECTED_PER_CAMPAIGN = {
    key: value // 4 for key, value in EXPECTED_AGGREGATE.items()
}
EXPECTED_BINOMIAL = (
    "(A + B) ^ 2",
    "2 * A * B + A ^ 2 + B * B",
    ["case-13-trace-1", "case-13-trace-2"],
)


class VerificationError(RuntimeError):
    pass


def fail(message: str) -> None:
    raise VerificationError(message)


def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            fail(f"duplicate JSON field {key!r}")
        result[key] = value
    return result


def load_json(path: Path) -> dict[str, Any]:
    if not path.is_file() or path.is_symlink():
        fail(f"expected regular non-symbolic JSON file: {path}")
    try:
        value = json.loads(
            path.read_text(encoding="utf-8"), object_pairs_hook=unique_object
        )
    except (OSError, json.JSONDecodeError) as error:
        fail(f"cannot read {path}: {error}")
    if not isinstance(value, dict):
        fail(f"expected JSON object in {path}")
    return value


def canonical_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def semantic_hash(value: Any) -> str:
    return "sha256:" + hashlib.sha256(canonical_bytes(value)).hexdigest()


def exact_hash(path: Path) -> str:
    return "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()


def require_content_hash(value: dict[str, Any], context: str) -> None:
    retained = value.get("contentHash")
    if not isinstance(retained, str):
        fail(f"{context} has no contentHash")
    material = dict(value)
    material.pop("contentHash", None)
    expected = semantic_hash(material)
    if retained != expected:
        fail(f"{context} contentHash mismatch: {retained} != {expected}")


def require_nested_hashes(run: dict[str, Any]) -> None:
    for campaign_index, campaign in enumerate(run.get("campaigns", [])):
        if not isinstance(campaign, dict):
            fail(f"campaign {campaign_index} is not an object")
        formation = campaign.get("formation")
        if not isinstance(formation, dict):
            fail(f"campaign {campaign_index} has no formation evidence")
        for replay_index, replay in enumerate(
            formation.get("replayEvidence", [])
        ):
            if not isinstance(replay, dict):
                fail("replay evidence is not an object")
            require_content_hash(
                replay,
                f"campaign {campaign_index} replay {replay_index}",
            )
        for macro_index, macro in enumerate(formation.get("macros", [])):
            if not isinstance(macro, dict):
                fail("macro evidence is not an object")
            for step_index, step in enumerate(macro.get("atomicSteps", [])):
                if not isinstance(step, dict):
                    fail("macro atomic step is not an object")
                require_content_hash(
                    step,
                    f"campaign {campaign_index} macro {macro_index} "
                    f"step {step_index}",
                )
            require_content_hash(
                macro, f"campaign {campaign_index} macro {macro_index}"
            )
        require_content_hash(formation, f"campaign {campaign_index} formation")
        for evaluation_index, evaluation in enumerate(
            campaign.get("pairedEvaluations", [])
        ):
            if not isinstance(evaluation, dict):
                fail("paired evaluation is not an object")
            for role in ("baseline", "macroEnabled"):
                search = evaluation.get(role)
                if not isinstance(search, dict):
                    fail(f"paired evaluation has no {role} search")
                require_content_hash(
                    search,
                    f"campaign {campaign_index} evaluation "
                    f"{evaluation_index} {role}",
                )
            require_content_hash(
                evaluation,
                f"campaign {campaign_index} evaluation {evaluation_index}",
            )
        require_content_hash(campaign, f"campaign {campaign_index}")
    require_content_hash(run, "reusable-macro batch run")


def validate_frozen_inputs(
    benchmark: dict[str, Any],
    corpus: dict[str, Any],
    profile: dict[str, Any],
    receipt: dict[str, Any],
) -> tuple[dict[str, dict[str, Any]], dict[str, list[str]]]:
    require_content_hash(corpus, "case corpus")
    require_content_hash(profile, "macro primitive profile")
    require_content_hash(receipt, "freeze receipt")
    if semantic_hash(benchmark) != receipt.get("benchmarkSourceContentHash"):
        fail("benchmark source is not bound by the freeze receipt")
    if corpus.get("contentHash") != receipt.get("caseCorpusContentHash"):
        fail("case corpus is not bound by the freeze receipt")
    inventory_roots = receipt.get("formationInventoryContentHashes")
    if not isinstance(inventory_roots, dict):
        fail("freeze receipt has no formation inventory roots")
    if profile.get("contentHash") != inventory_roots.get(PROFILE_ID):
        fail("macro profile is not bound by the freeze receipt")
    if benchmark.get("executionStatus") != "NOT_STARTED":
        fail("benchmark source no longer retains NOT_STARTED")
    if receipt.get("executionStatusAtFreeze") != "NOT_STARTED":
        fail("corpus was not frozen before evaluated execution")
    if receipt.get("executedCampaignsAtFreeze") != 0:
        fail("freeze receipt already contains campaigns")
    if receipt.get("executedEvaluationsAtFreeze") != 0:
        fail("freeze receipt already contains evaluations")
    if receipt.get("publicationAuthorized") is not False:
        fail("freeze receipt unexpectedly authorizes publication")
    if profile.get("claimPolicy") != (
        "PROFILE_MAPPING_DOES_NOT_ESTABLISH_MACRO_UTILITY_OR_CASE_SUCCESS"
    ):
        fail("macro profile claim boundary changed")

    operations: dict[str, list[str]] = {}
    for item in profile.get("operations", []):
        if not isinstance(item, dict):
            fail("macro profile operation is not an object")
        operation_id = item.get("operationId")
        rules = item.get("implementationRuleIds")
        if not isinstance(operation_id, str) or not isinstance(rules, list):
            fail("macro profile operation is incomplete")
        if not rules or any(not isinstance(rule, str) for rule in rules):
            fail(f"macro operation {operation_id} has invalid rules")
        operations[operation_id] = rules
    if len(operations) != 6:
        fail(f"macro operation inventory changed: {sorted(operations)}")

    cases = {
        item.get("caseId"): item
        for item in corpus.get("cases", [])
        if isinstance(item, dict) and item.get("challengeId") == CHALLENGE
    }
    if sorted(cases) != EXPECTED_CASES:
        fail(f"macro case identities changed: {sorted(cases)}")
    for case_id, case in cases.items():
        require_content_hash(case, f"macro case {case_id}")
        exposure = case.get("exposurePolicy")
        if not isinstance(exposure, dict):
            fail(f"case {case_id} has no exposure policy")
        if exposure.get("candidateFormationMustNotRead") != ["evaluationInput"]:
            fail(f"case {case_id} does not prohibit evaluation input")
        if case.get("split") == "TRAIN":
            if exposure.get("candidateFormationMayRead") != ["formationInput"]:
                fail(f"TRAIN formation surface changed: {case_id}")
            formation = case.get("formationInput")
            if not isinstance(formation, dict):
                fail(f"TRAIN case {case_id} has no formation payload")
            if formation.get("heldOutTargetsVisible") is not False:
                fail(f"TRAIN case {case_id} exposes held-out targets")
        else:
            if exposure.get("candidateFormationMayRead") != []:
                fail(f"held-out case {case_id} exposes formation input")
            if case.get("formationInput") is not None:
                fail(f"held-out case {case_id} has formation payload")
    return cases, operations


def expected_tasks(
    cases: dict[str, dict[str, Any]]
) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for case_id in EXPECTED_CASES:
        case = cases[case_id]
        evaluation = case.get("evaluationInput")
        if not isinstance(evaluation, dict):
            fail(f"case {case_id} has no evaluation input")
        if evaluation.get("comparisonPolicy") != (
            "IDENTICAL_INPUT_TARGET_INVENTORY_STRATEGY_AND_BUDGET"
        ):
            fail(f"case {case_id} comparison policy changed")
        tasks = evaluation.get("tasks")
        if not isinstance(tasks, list) or len(tasks) != 2:
            fail(f"case {case_id} task count changed")
        for task in tasks:
            if not isinstance(task, dict):
                fail("macro task is not an object")
            task_id = task.get("taskId")
            if not isinstance(task_id, str):
                fail("macro task has no taskId")
            result[task_id] = {
                "caseId": case_id,
                "split": case.get("split"),
                "structuralCluster": case.get("structuralCluster"),
                "caseContentHash": case.get("contentHash"),
                **task,
            }
    if list(result) != EXPECTED_TASKS:
        fail(f"macro task inventory changed: {list(result)}")
    return result


def classify_pair(evaluation: dict[str, Any]) -> str:
    baseline = evaluation.get("baseline")
    macro = evaluation.get("macroEnabled")
    if not isinstance(baseline, dict) or not isinstance(macro, dict):
        fail("paired search evidence is missing")
    baseline_success = baseline.get("success") is True
    macro_success = macro.get("success") is True
    if baseline_success and not macro_success:
        return "CORRECTNESS_REGRESSION"
    if not baseline_success and macro_success:
        return "REACHABILITY_GAIN"
    if not baseline_success:
        return "NO_RESULT"
    if (
        macro.get("expandedStates", 0) < baseline.get("expandedStates", 0)
        or len(macro.get("ruleIds", [])) < len(baseline.get("ruleIds", []))
    ):
        return "IMPROVED"
    return "NO_IMPROVEMENT"


def validate_search(
    search: dict[str, Any], max_states: int, context: str
) -> None:
    expanded = search.get("expandedStates")
    generated = search.get("generatedCandidates")
    if not isinstance(expanded, int) or not 0 <= expanded <= max_states:
        fail(f"{context} expanded-state accounting is invalid")
    if not isinstance(generated, int) or generated < 0:
        fail(f"{context} generated-candidate accounting is invalid")
    path = search.get("path")
    rules = search.get("ruleIds")
    if not isinstance(path, list) or not isinstance(rules, list):
        fail(f"{context} path or rules are missing")
    if search.get("success") is True:
        if not path:
            fail(f"{context} successful search has no path")
        if search.get("reachedExpression") == "":
            fail(f"{context} successful search has no reached expression")


def validate_macro_inventory(formation: dict[str, Any]) -> None:
    replays = formation.get("replayEvidence")
    macros = formation.get("macros")
    if not isinstance(replays, list) or len(replays) != 4:
        fail("campaign does not retain all four TRAIN replays")
    trace_ids = [item.get("traceId") for item in replays if isinstance(item, dict)]
    if trace_ids != [
        "case-13-trace-1",
        "case-13-trace-2",
        "case-14-trace-1",
        "case-14-trace-2",
    ]:
        fail(f"TRAIN replay inventory changed: {trace_ids}")
    for replay in replays:
        if replay.get("reproduced") is not True:
            fail(f"TRAIN replay was not reproduced: {replay.get('traceId')}")
        actual = replay.get("actualRuleIds")
        compressed = replay.get("compressedOperationIds")
        if not isinstance(actual, list) or not actual:
            fail("replay has no concrete production rules")
        if not isinstance(compressed, list) or not compressed:
            fail("replay has no compressed operation sequence")

    if not isinstance(macros, list) or len(macros) != 3:
        fail("campaign does not retain exactly three formed macros")
    identities = set()
    binomial_seen = False
    support_counts = Counter()
    for macro in macros:
        if not isinstance(macro, dict):
            fail("formed macro is not an object")
        macro_id = macro.get("macroId")
        if not isinstance(macro_id, str) or macro_id in identities:
            fail("formed macro identities are missing or duplicated")
        identities.add(macro_id)
        if macro.get("proofStatus") != "SYMBOLICALLY_VERIFIED":
            fail(f"macro {macro_id} is not symbolically verified")
        if macro.get("confidenceScore") != 1.0:
            fail(f"macro {macro_id} confidence changed")
        if not macro.get("atomicSteps"):
            fail(f"macro {macro_id} has no atomic replay expansion")
        support = macro.get("supportingTraceIds")
        if not isinstance(support, list) or not support:
            fail(f"macro {macro_id} has no supporting TRAIN traces")
        support_counts.update(support)
        if (
            macro.get("leftPattern") == EXPECTED_BINOMIAL[0]
            and macro.get("rightPattern") == EXPECTED_BINOMIAL[1]
            and support == EXPECTED_BINOMIAL[2]
        ):
            binomial_seen = True
        if "3 * B - 2" in str(macro.get("rightPattern")):
            fail("numerically overfitted binomial schema survived formation")
    if not binomial_seen:
        fail("validated TRAIN-derived binomial macro is missing")
    if support_counts != Counter(
        {
            "case-13-trace-1": 1,
            "case-13-trace-2": 1,
            "case-14-trace-1": 1,
            "case-14-trace-2": 1,
        }
    ):
        fail(f"macro support accounting changed: {support_counts}")


def validate_resource_use(resource: dict[str, Any], context: str) -> None:
    fields = [
        ("configuredStates", "executedStates", "remainingStates"),
        (
            "configuredCandidateEvaluations",
            "executedCandidateEvaluations",
            "remainingCandidateEvaluations",
        ),
        (
            "configuredProofAttempts",
            "executedProofAttempts",
            "remainingProofAttempts",
        ),
    ]
    for configured, executed, remaining in fields:
        values = [resource.get(configured), resource.get(executed), resource.get(remaining)]
        if any(not isinstance(value, int) or value < 0 for value in values):
            fail(f"{context} resource values are invalid for {configured}")
        if values[1] + values[2] != values[0]:
            fail(f"{context} resource accounting does not balance for {configured}")
    if resource.get("configuredStates") != 3000:
        fail(f"{context} state budget changed")
    if resource.get("configuredCandidateEvaluations") != 600:
        fail(f"{context} candidate budget changed")
    if resource.get("configuredProofAttempts") != 100:
        fail(f"{context} proof budget changed")
    if resource.get("executedProofAttempts") != 0:
        fail(f"{context} unexpectedly claims proof execution")
    if resource.get("formationStates", 0) + resource.get("pairedSearchStates", 0) != resource.get("executedStates"):
        fail(f"{context} state sub-ledgers do not reconstruct total")
    if resource.get("formationCandidateEvaluations", 0) + resource.get("pairedSearchCandidateEvaluations", 0) != resource.get("executedCandidateEvaluations"):
        fail(f"{context} candidate sub-ledgers do not reconstruct total")


def validate_run(
    run: dict[str, Any],
    tasks: dict[str, dict[str, Any]],
) -> tuple[int, int]:
    require_nested_hashes(run)
    if run.get("schema") != RUN_SCHEMA:
        fail("unexpected macro batch schema")
    if run.get("benchmarkId") != BENCHMARK_ID:
        fail("unexpected benchmark identity")
    if run.get("challengeId") != CHALLENGE:
        fail("unexpected challenge identity")
    if run.get("publicationAuthorized") is not False:
        fail("macro batch unexpectedly authorizes publication")
    if run.get("formalProofStatus") != "NOT_EVALUATED":
        fail("macro batch overclaims formal proof")
    if run.get("externalNoveltyStatus") != "NOT_EVALUATED":
        fail("macro batch overclaims external novelty")
    if run.get("aggregateOutcomeCounts") != EXPECTED_AGGREGATE:
        fail(f"macro aggregate changed: {run.get('aggregateOutcomeCounts')}")

    campaigns = run.get("campaigns")
    if not isinstance(campaigns, list) or len(campaigns) != 4:
        fail("macro batch does not contain four campaigns")
    campaign_ids = [campaign.get("campaignId") for campaign in campaigns]
    if campaign_ids != EXPECTED_CAMPAIGNS:
        fail(f"campaign inventory changed: {campaign_ids}")

    task_roots: dict[str, str] = {}
    campaign_roots = set()
    executed_states = 0
    executed_candidates = 0
    aggregate = Counter()
    for campaign in campaigns:
        if campaign.get("contentHash") in campaign_roots:
            fail("duplicate campaign root")
        campaign_roots.add(campaign.get("contentHash"))
        if campaign.get("formationVisibility") != "TRAIN_ONLY":
            fail("campaign formation visibility changed")
        if campaign.get("heldOutInputAccess") != "EVALUATION_ONLY":
            fail("campaign held-out access changed")
        if campaign.get("formedMacroCount") != 3:
            fail("campaign macro count changed")
        formation = campaign.get("formation")
        if not isinstance(formation, dict) or formation.get("status") != "SELECTED":
            fail("campaign has no selected macro formation")
        validate_macro_inventory(formation)
        evaluations = campaign.get("pairedEvaluations")
        if not isinstance(evaluations, list) or len(evaluations) != 12:
            fail("campaign does not retain twelve paired evaluations")
        if [item.get("taskId") for item in evaluations] != EXPECTED_TASKS:
            fail("campaign task order or membership changed")
        counts = Counter()
        for evaluation in evaluations:
            task_id = evaluation.get("taskId")
            expected = tasks.get(task_id)
            if expected is None:
                fail(f"unexpected task {task_id}")
            for field in (
                "caseId",
                "split",
                "structuralCluster",
                "caseContentHash",
                "source",
                "target",
                "assumptions",
                "searchBudget",
            ):
                if evaluation.get(field) != expected.get(field):
                    fail(f"task {task_id} field {field} drifted")
            baseline = evaluation.get("baseline")
            macro = evaluation.get("macroEnabled")
            max_states = expected["searchBudget"]["maxExpandedStates"]
            validate_search(baseline, max_states, f"{task_id}/baseline")
            validate_search(macro, max_states, f"{task_id}/macro")
            recomputed = classify_pair(evaluation)
            if evaluation.get("outcome") != recomputed:
                fail(
                    f"task {task_id} outcome mismatch: "
                    f"{evaluation.get('outcome')} != {recomputed}"
                )
            if evaluation.get("correctnessRegression") is not False:
                fail(f"task {task_id} retains a correctness regression")
            counts[recomputed] += 1
            root = evaluation.get("contentHash")
            prior = task_roots.setdefault(task_id, root)
            if prior != root:
                fail(f"task {task_id} root changed across campaigns")
        normalized_counts = {
            outcome: counts.get(outcome, 0) for outcome in EXPECTED_PER_CAMPAIGN
        }
        if normalized_counts != EXPECTED_PER_CAMPAIGN:
            fail(f"campaign outcome counts changed: {normalized_counts}")
        if campaign.get("outcomeCounts") != EXPECTED_PER_CAMPAIGN:
            fail("retained campaign outcome counts do not match raw tasks")
        aggregate.update(counts)
        resource = campaign.get("resourceUse")
        if not isinstance(resource, dict):
            fail("campaign has no resource ledger")
        validate_resource_use(resource, campaign.get("campaignId", "campaign"))
        executed_states += resource["executedStates"]
        executed_candidates += resource["executedCandidateEvaluations"]
    if {outcome: aggregate.get(outcome, 0) for outcome in EXPECTED_AGGREGATE} != EXPECTED_AGGREGATE:
        fail("raw campaign aggregate does not match expected macro frontier")
    if list(task_roots) != EXPECTED_TASKS:
        fail("task root inventory changed")
    if run.get("executedStates") != executed_states:
        fail("run state total does not match campaigns")
    if run.get("executedCandidateEvaluations") != executed_candidates:
        fail("run candidate total does not match campaigns")
    return executed_states, executed_candidates


def expect_failure(action: Callable[[], None], label: str) -> dict[str, Any]:
    try:
        action()
    except (VerificationError, jsonschema.ValidationError) as error:
        return {"mutationId": label, "rejected": True, "detail": str(error)}
    fail(f"mutation {label} was accepted")


def mutation_results(
    run: dict[str, Any],
    schema: dict[str, Any],
    tasks: dict[str, dict[str, Any]],
) -> list[dict[str, Any]]:
    results: list[dict[str, Any]] = []

    leaked = copy.deepcopy(run)
    leaked["campaigns"][0]["formationVisibility"] = "ALL_SPLITS"
    results.append(expect_failure(
        lambda: validate_run(leaked, tasks),
        "held-out-formation-leak",
    ))

    hidden = copy.deepcopy(run)
    hidden["campaigns"][0]["pairedEvaluations"].pop()
    results.append(expect_failure(
        lambda: validate_run(hidden, tasks),
        "missing-task-row",
    ))

    regression = copy.deepcopy(run)
    regression["campaigns"][0]["pairedEvaluations"][0][
        "correctnessRegression"
    ] = True
    results.append(expect_failure(
        lambda: validate_run(regression, tasks),
        "hidden-correctness-regression",
    ))

    overclaim = copy.deepcopy(run)
    overclaim["publicationAuthorized"] = True
    results.append(expect_failure(
        lambda: validate_run(overclaim, tasks),
        "publication-overclaim",
    ))

    unbalanced = copy.deepcopy(run)
    unbalanced["campaigns"][0]["resourceUse"]["remainingStates"] += 1
    results.append(expect_failure(
        lambda: validate_run(unbalanced, tasks),
        "unbalanced-resource-ledger",
    ))

    schema_extra = copy.deepcopy(run)
    schema_extra["unexpected"] = "field"
    results.append(expect_failure(
        lambda: jsonschema.Draft202012Validator(schema).validate(schema_extra),
        "unknown-schema-field",
    ))
    return results


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository-root", type=Path, required=True)
    parser.add_argument("--first", type=Path, required=True)
    parser.add_argument("--second", type=Path, required=True)
    parser.add_argument("--report-directory", type=Path, required=True)
    args = parser.parse_args()

    root = args.repository_root.resolve()
    first = args.first.resolve()
    second = args.second.resolve()
    report_directory = args.report_directory.resolve()
    schema_path = root / "docs/schemas/regelsuche-candidate-independent-reusable-macro-batch-v1.schema.json"
    benchmark_path = root / "research/benchmarks/candidate-independent/benchmark-source.json"
    corpus_path = root / "research/benchmarks/candidate-independent/case-corpus.json"
    profile_path = root / "research/benchmarks/candidate-independent/macro-primitives.json"
    receipt_path = root / "research/benchmarks/candidate-independent/corpus-freeze-receipt.json"

    schema = load_json(schema_path)
    benchmark = load_json(benchmark_path)
    corpus = load_json(corpus_path)
    profile = load_json(profile_path)
    receipt = load_json(receipt_path)
    cases, _ = validate_frozen_inputs(benchmark, corpus, profile, receipt)
    tasks = expected_tasks(cases)
    first_run = load_json(first)
    second_run = load_json(second)

    validator = jsonschema.Draft202012Validator(schema)
    validator.validate(first_run)
    validator.validate(second_run)
    if first.read_bytes() != second.read_bytes():
        fail("two macro batch runs are not byte-identical")
    if first_run != second_run:
        fail("two macro batch runs are not semantically identical")

    states, candidates = validate_run(first_run, tasks)
    validate_run(second_run, tasks)
    mutations = mutation_results(first_run, schema, tasks)

    report = {
        "schema": VERIFY_SCHEMA,
        "benchmarkId": BENCHMARK_ID,
        "challengeId": CHALLENGE,
        "firstRunContentHash": first_run["contentHash"],
        "secondRunContentHash": second_run["contentHash"],
        "firstRunExactHash": exact_hash(first),
        "secondRunExactHash": exact_hash(second),
        "byteIdentical": True,
        "configuredCampaigns": 4,
        "executedCampaigns": 4,
        "executedPairedEvaluations": 48,
        "aggregateOutcomeCounts": EXPECTED_AGGREGATE,
        "executedStates": states,
        "executedCandidateEvaluations": candidates,
        "mutationTests": mutations,
        "publicationAuthorized": False,
        "formalProofStatus": "NOT_EVALUATED",
        "externalNoveltyStatus": "NOT_EVALUATED",
    }
    report["contentHash"] = semantic_hash(report)
    report_directory.mkdir(parents=True, exist_ok=True)
    output = report_directory / "verification.json"
    output.write_text(
        json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    shutil.copy2(first, report_directory / "first-run.json")
    shutil.copy2(second, report_directory / "second-run.json")
    print(f"candidateIndependentReusableMacroVerification={output}")
    print(f"contentHash={report['contentHash']}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (VerificationError, jsonschema.ValidationError) as error:
        print(f"verification failed: {error}", file=sys.stderr)
        raise SystemExit(1)
