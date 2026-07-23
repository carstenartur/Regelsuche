#!/usr/bin/env python3
"""Independently verify the frozen candidate-independent downstream task stream."""

from __future__ import annotations

import argparse
import ast
import copy
import hashlib
import json
from pathlib import Path
from typing import Any

import jsonschema

BENCHMARK = "regelsuche-candidate-independent-autonomous-discovery-2026-07/v1"
CHALLENGE = "reusable-search-macros"
PROFILE = "macro-primitives/v1"
COMPARISON = "IDENTICAL_INPUT_TARGET_INVENTORY_STRATEGY_AND_BUDGET"
REQUIRED_EVIDENCE = [
    "primitive-step-semantics",
    "baseline-search",
    "macro-enabled-search",
    "correctness-regression",
]


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

    value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_object)
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
    if retained != semantic_hash(material):
        fail(f"{context} contentHash mismatch")
    return retained


def verify_generator_input_allowlist(path: Path) -> list[str]:
    source = path.read_text(encoding="utf-8")
    tree = ast.parse(source, filename=str(path))
    arguments: list[str] = []
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call) or not isinstance(node.func, ast.Attribute):
            continue
        if node.func.attr != "add_argument" or not node.args:
            continue
        first = node.args[0]
        if isinstance(first, ast.Constant) and isinstance(first.value, str):
            arguments.append(first.value)
    expected = [
        "--corpus",
        "--receipt",
        "--profile",
        "--repository-revision",
        "--output",
    ]
    if arguments != expected:
        fail(f"generator CLI input allowlist drift: {arguments}")
    forbidden_literals = [
        "pairedEvaluations",
        "macroEnabled",
        "candidateId",
        "macroId",
        "build/reports",
        "candidate-independent-reusable-macro-batch",
    ]
    leaked = [value for value in forbidden_literals if value in source]
    if leaked:
        fail(f"generator references post-execution or candidate data: {leaked}")
    return arguments


def expected_tasks(corpus: dict[str, Any]) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for case in corpus["cases"]:
        if case["challengeId"] != CHALLENGE:
            continue
        if (
            case["exposurePolicy"]["candidateFormationMustNotRead"]
            != ["evaluationInput"]
        ):
            fail(f"formation exposure drift for {case['caseId']}")
        evaluation = case["evaluationInput"]
        if evaluation["comparisonPolicy"] != COMPARISON:
            fail(f"comparison policy drift for {case['caseId']}")
        if evaluation["requiredEvidence"] != REQUIRED_EVIDENCE:
            fail(f"required evidence drift for {case['caseId']}")
        for task in evaluation["tasks"]:
            material = {
                "index": len(rows) + 1,
                "taskId": task["taskId"],
                "caseId": case["caseId"],
                "split": case["split"],
                "structuralCluster": case["structuralCluster"],
                "caseContentHash": case["contentHash"],
                "source": task["source"],
                "target": task["target"],
                "assumptions": task["assumptions"],
                "searchBudget": task["searchBudget"],
                "comparisonPolicy": COMPARISON,
                "requiredEvidence": REQUIRED_EVIDENCE,
            }
            rows.append({**material, "contentHash": semantic_hash(material)})
    return rows


def verify_one(
    stream: dict[str, Any],
    corpus: dict[str, Any],
    receipt: dict[str, Any],
    profile: dict[str, Any],
) -> None:
    for context, value in [
        ("stream", stream),
        ("case corpus", corpus),
        ("freeze receipt", receipt),
        ("formation profile", profile),
    ]:
        require_hash(value, context)
    if corpus["benchmarkId"] != BENCHMARK:
        fail("benchmark identity drift")
    if corpus["freezeStatus"] != "FROZEN_BEFORE_EVALUATED_EXECUTION":
        fail("case corpus is not pre-execution frozen")
    if corpus["executionStatusAtFreeze"] != "NOT_STARTED":
        fail("case corpus execution status drift")
    if receipt["caseCorpusContentHash"] != corpus["contentHash"]:
        fail("receipt/corpus binding drift")
    if receipt["resultInspectionStatus"] != "NO_EVALUATED_RESULTS_EXIST":
        fail("receipt result-inspection boundary drift")
    if profile["profileId"] != PROFILE:
        fail("baseline inventory identity drift")
    if receipt["formationInventoryContentHashes"][PROFILE] != profile["contentHash"]:
        fail("receipt/inventory binding drift")

    if stream["sourceCaseCorpusContentHash"] != corpus["contentHash"]:
        fail("stream/corpus binding drift")
    if stream["freezeReceiptContentHash"] != receipt["contentHash"]:
        fail("stream/receipt binding drift")
    if stream["baselineInventoryContentHash"] != profile["contentHash"]:
        fail("stream/baseline inventory binding drift")
    if stream["combinedPreregistrationHash"] != receipt["combinedPreregistrationHash"]:
        fail("stream preregistration binding drift")
    if stream["tasks"] != expected_tasks(corpus):
        fail("downstream tasks or their order drifted from the frozen corpus")
    if stream["splitCounts"] != {"TRAIN": 4, "VALIDATION": 4, "TEST": 4}:
        fail("downstream split accounting drift")
    forbidden = {"outcome", "baseline", "macroEnabled", "candidateId", "macroId"}
    for task in stream["tasks"]:
        overlap = forbidden.intersection(task)
        if overlap:
            fail(f"stream leaked evaluation or candidate fields: {sorted(overlap)}")


def expect_verification_failure(
    value: dict[str, Any],
    corpus: dict[str, Any],
    receipt: dict[str, Any],
    profile: dict[str, Any],
    label: str,
) -> None:
    try:
        verify_one(value, corpus, receipt, profile)
    except RuntimeError:
        return
    fail(f"mutation survived independent verification: {label}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--generator", required=True, type=Path)
    parser.add_argument("--first-stream", required=True, type=Path)
    parser.add_argument("--second-stream", required=True, type=Path)
    parser.add_argument("--corpus", required=True, type=Path)
    parser.add_argument("--receipt", required=True, type=Path)
    parser.add_argument("--profile", required=True, type=Path)
    parser.add_argument("--schema", required=True, type=Path)
    parser.add_argument("--report-directory", required=True, type=Path)
    arguments = parser.parse_args()

    verified_generator_arguments = verify_generator_input_allowlist(arguments.generator)
    schema = load(arguments.schema)
    jsonschema.Draft202012Validator.check_schema(schema)
    first = load(arguments.first_stream)
    second = load(arguments.second_stream)
    corpus = load(arguments.corpus)
    receipt = load(arguments.receipt)
    profile = load(arguments.profile)
    for stream in (first, second):
        jsonschema.validate(stream, schema)
        verify_one(stream, corpus, receipt, profile)
    if arguments.first_stream.read_bytes() != arguments.second_stream.read_bytes():
        fail("clean downstream streams are not byte-identical")

    reordered = copy.deepcopy(first)
    reordered["tasks"][0], reordered["tasks"][1] = (
        reordered["tasks"][1],
        reordered["tasks"][0],
    )
    reordered.pop("contentHash", None)
    reordered["contentHash"] = semantic_hash(reordered)
    expect_verification_failure(reordered, corpus, receipt, profile, "task order")

    inventory_drift = copy.deepcopy(first)
    inventory_drift["baselineInventoryContentHash"] = "sha256:" + "0" * 64
    inventory_drift.pop("contentHash", None)
    inventory_drift["contentHash"] = semantic_hash(inventory_drift)
    expect_verification_failure(inventory_drift, corpus, receipt, profile, "inventory binding")

    leaked = copy.deepcopy(first)
    leaked["tasks"][0]["outcome"] = "IMPROVED"
    try:
        jsonschema.validate(leaked, schema)
    except jsonschema.ValidationError:
        pass
    else:
        fail("schema accepted leaked evaluation outcome")

    report = {
        "schema": "regelsuche.downstream-task-stream-verification/v1",
        "streamContentHash": first["contentHash"],
        "byteIdenticalCleanRuns": True,
        "verifiedTaskCount": 12,
        "verifiedSplitCounts": first["splitCounts"],
        "verifiedOrderingPolicy": first["orderingPolicy"],
        "verifiedGeneratorArguments": verified_generator_arguments,
        "verifiedMutations": [
            "task-order",
            "baseline-inventory",
            "evaluation-outcome-leak",
        ],
        "evaluationStatus": "NOT_EXECUTED_BY_STREAM_CONSTRUCTION",
        "publicationAuthorized": False,
    }
    arguments.report_directory.mkdir(parents=True, exist_ok=True)
    (arguments.report_directory / "verification.json").write_text(
        json.dumps(report, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
        encoding="utf-8",
    )
    print("downstream-task-stream=VERIFIED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
