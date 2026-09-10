#!/usr/bin/env python3
"""Independently verify learned RewriteProgram authorization and replay evidence."""

from __future__ import annotations

import argparse
from datetime import datetime
import hashlib
import importlib.metadata
import json
from pathlib import Path
from typing import Any

try:
    from jsonschema import Draft202012Validator, FormatChecker
except ImportError as error:
    raise SystemExit(
        "jsonschema is required; run ./gradlew prepareVerificationEnvironment"
    ) from error

EXPECTED_JSONSCHEMA_VERSION = "4.25.1"
SHA_PREFIX = "sha256:"
APPLICABILITY_SEMANTICS = "CANONICAL_PROGRAM_RETURNS_AT_LEAST_ONE_CANDIDATE/v1"
FILES = {
    "genome": "genome.json",
    "plan": "program-plan.json",
    "candidate": "program-candidate.json",
    "replay": "program-replay-evidence.json",
    "receipt": "program-authorization-receipt.json",
    "mul_leaf": "leaf-mul-one-authorization-receipt.json",
    "add_leaf": "leaf-add-zero-authorization-receipt.json",
}
SCHEMAS = {
    "genome": "regelsuche-evolution-genome-v1.schema.json",
    "plan": "regelsuche-evolution-rewrite-program-plan-v1.schema.json",
    "candidate": "regelsuche-evolution-rewrite-program-candidate-v1.schema.json",
    "replay": "regelsuche-learned-rewrite-program-replay-evidence-v1.schema.json",
    "receipt": "regelsuche-learned-rewrite-program-authorization-receipt-v1.schema.json",
    "mul_leaf": "regelsuche-learned-pattern-rule-authorization-receipt-v1.schema.json",
    "add_leaf": "regelsuche-learned-pattern-rule-authorization-receipt-v1.schema.json",
}


def fail(message: str) -> None:
    raise SystemExit(f"learned RewriteProgram authorization verification failed: {message}")


def strict_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            fail(f"duplicate JSON field {key!r}")
        result[key] = value
    return result


def read_text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as error:
        fail(f"cannot read {path}: {error}")


def load(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(read_text(path), object_pairs_hook=strict_object)
    except json.JSONDecodeError as error:
        fail(f"cannot parse {path}: {error}")
    if not isinstance(value, dict):
        fail(f"{path.name} must contain one JSON object")
    return value


def canonical(value: Any) -> str:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ) + "\n"


def sha256_text(value: str) -> str:
    return SHA_PREFIX + hashlib.sha256(value.encode("utf-8")).hexdigest()


def require_equal(actual: Any, expected: Any, message: str) -> None:
    if actual != expected:
        fail(f"{message}: expected={expected!r}, actual={actual!r}")


def require_positive(value: Any, message: str) -> None:
    if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
        fail(f"{message} must be a positive integer, got {value!r}")


def require_boolean(value: Any, message: str) -> None:
    if not isinstance(value, bool):
        fail(f"{message} must be a boolean, got {value!r}")


def validate_schema(document: dict[str, Any], schema_path: Path, name: str) -> None:
    schema = load(schema_path)
    validator = Draft202012Validator(schema, format_checker=FormatChecker())
    errors = sorted(validator.iter_errors(document), key=lambda item: list(item.path))
    if errors:
        first = errors[0]
        location = "/".join(str(part) for part in first.path) or "<root>"
        fail(f"{name} violates schema at {location}: {first.message}")


def verify_canonical_file(path: Path, document: dict[str, Any]) -> None:
    require_equal(read_text(path), canonical(document), f"{path.name} canonical encoding")


def verify_self_hash(document: dict[str, Any], name: str) -> None:
    retained = document.get("contentHash")
    payload = dict(document)
    payload.pop("contentHash", None)
    require_equal(
        retained,
        sha256_text(canonical(payload)),
        f"{name} contentHash",
    )


def parse_instant(value: str, name: str) -> datetime:
    try:
        result = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except (AttributeError, ValueError) as error:
        fail(f"{name} is not a valid ISO instant: {error}")
    if result.tzinfo is None:
        fail(f"{name} must be offset-aware")
    return result


def verify_work(work: dict[str, Any], name: str) -> int:
    primitive = work.get("primitiveRewrites")
    exact_steps = work.get("exactTheorySteps")
    exact_units = work.get("exactTheoryWorkUnits")
    for field, value in (
        ("primitiveRewrites", primitive),
        ("exactTheorySteps", exact_steps),
        ("exactTheoryWorkUnits", exact_units),
    ):
        if not isinstance(value, int) or isinstance(value, bool) or value < 0:
            fail(f"{name}.{field} must be a non-negative integer")
    if exact_units < exact_steps:
        fail(f"{name}.exactTheoryWorkUnits must cover exactTheorySteps")
    if exact_steps == 0 and exact_units != 0:
        fail(f"{name} has exact-theory work without an exact-theory step")
    return primitive + exact_units


def verify_replay(replay: dict[str, Any]) -> None:
    verify_self_hash(replay, "program replay")
    cases = replay["cases"]
    require_equal(len(cases), 1, "fixture replay case count")
    case = cases[0]
    verify_self_hash(case, "program replay case")
    require_equal(case["caseId"], "composite-normalization", "replay case ID")
    require_equal(case["inputExpression"], "(x * 1) + 0", "replay input")
    require_boolean(case.get("complete"), "replay completeness marker")
    # This fixture deliberately contains a Prune node. A faithful replay must
    # therefore retain an incomplete enumeration instead of silently upgrading
    # the pruned program to a completeness claim.
    require_equal(case["complete"], False,
                  "declared pruning must retain incomplete replay enumeration")
    require_equal(len(case["candidates"]), 1, "retained replay candidate count")

    candidate = case["candidates"][0]
    verify_self_hash(candidate, "retained replay candidate")
    require_equal(candidate["outputExpression"], "x", "retained replay output")
    candidate_work = verify_work(candidate["executionWork"], "candidate executionWork")
    require_equal(candidate["executionWork"]["primitiveRewrites"], 2,
                  "retained primitive rewrite count")
    require_equal(candidate["executionWork"]["exactTheorySteps"], 0,
                  "retained exact-theory step count")
    require_equal(len(candidate["primitiveRuleIds"]), 2,
                  "retained primitive lineage length")
    require_equal(len(candidate["ruleIds"]), 2,
                  "retained rule lineage length")

    metrics = case["workMetrics"]
    metric_work = verify_work(metrics["candidateWork"], "workMetrics.candidateWork")
    for field in (
        "composedCandidates",
        "requirementEvaluations",
        "priorityCandidatesOrdered",
        "prunedCandidates",
        "repeatIterations",
    ):
        require_positive(metrics[field], f"workMetrics.{field}")
    # Choice evaluates all alternatives; only FirstApplicable records a selected
    # alternative and skipped suffix. This fixture intentionally contains Choice
    # but no FirstApplicable, so both counters must remain zero.
    require_equal(metrics["alternativeSelections"], 0,
                  "choice-only fixture alternative selection count")
    require_equal(metrics["alternativesSkipped"], 0,
                  "choice-only fixture skipped-alternative count")
    if metrics["requirementRejections"] > metrics["requirementEvaluations"]:
        fail("requirementRejections exceeds requirementEvaluations")
    if metric_work < candidate_work:
        fail("aggregate mathematical candidate work is below retained candidate work")


def verify_bindings(documents: dict[str, dict[str, Any]]) -> None:
    genome = documents["genome"]
    plan = documents["plan"]
    candidate = documents["candidate"]
    replay = documents["replay"]
    receipt = documents["receipt"]
    mul_leaf = documents["mul_leaf"]
    add_leaf = documents["add_leaf"]

    genome_hash = genome["contentHash"]
    require_equal(plan["genomeHash"], genome_hash, "plan genome binding")
    require_equal(candidate["genomeHash"], genome_hash, "candidate genome binding")
    require_equal(replay["genomeHash"], genome_hash, "replay genome binding")
    require_equal(receipt["genomeHash"], genome_hash, "receipt genome binding")
    require_equal(mul_leaf["genomeHash"], genome_hash, "mul leaf genome binding")
    require_equal(add_leaf["genomeHash"], genome_hash, "add leaf genome binding")

    plan_hash = plan["contentHash"]
    require_equal(candidate["planHash"], plan_hash, "candidate plan binding")
    require_equal(replay["planHash"], plan_hash, "replay plan binding")
    require_equal(receipt["planHash"], plan_hash, "receipt plan binding")
    require_equal(
        candidate["planAlphaStructuralHash"],
        plan["alphaStructuralHash"],
        "candidate plan alpha binding",
    )
    require_equal(
        replay["planAlphaStructuralHash"],
        plan["alphaStructuralHash"],
        "replay plan alpha binding",
    )
    require_equal(
        receipt["planAlphaStructuralHash"],
        plan["alphaStructuralHash"],
        "receipt plan alpha binding",
    )

    require_equal(replay["candidateHash"], candidate["contentHash"],
                  "replay candidate binding")
    require_equal(receipt["candidateHash"], candidate["contentHash"],
                  "receipt candidate binding")
    require_equal(receipt["replayEvidenceHash"], replay["contentHash"],
                  "receipt replay binding")
    require_equal(receipt["applicabilitySemantics"], APPLICABILITY_SEMANTICS,
                  "program applicability semantics")

    require_equal(mul_leaf["geneId"], "mul-one", "mul leaf gene")
    require_equal(add_leaf["geneId"], "add-zero", "add leaf gene")
    require_equal(
        receipt["leafAuthorizationHashes"],
        {
            "add-zero": add_leaf["contentHash"],
            "mul-one": mul_leaf["contentHash"],
        },
        "program leaf authorization map",
    )
    require_equal(
        receipt["leafApplicabilitySchemaHashes"],
        {
            "add-zero": add_leaf["applicabilitySchemaHash"],
            "mul-one": mul_leaf["applicabilitySchemaHash"],
        },
        "program leaf applicability-schema map",
    )
    require_equal(
        set(receipt["leafAuthorizationHashes"]),
        set(receipt["leafApplicabilitySchemaHashes"]),
        "program authorization/applicability subject set",
    )
    promoted = [mul_leaf["promotedRuleId"], add_leaf["promotedRuleId"]]
    require_equal(replay["cases"][0]["candidates"][0]["ruleIds"], promoted,
                  "program uses promoted leaf rules in sequence order")

    revision = receipt["repositoryRevision"]
    require_equal(mul_leaf["repositoryRevision"], revision, "mul leaf revision")
    require_equal(add_leaf["repositoryRevision"], revision, "add leaf revision")

    authorized_at = parse_instant(receipt["authorizedAt"], "receipt.authorizedAt")
    valid_until = parse_instant(receipt["validUntil"], "receipt.validUntil")
    leaf_expiries = [
        parse_instant(mul_leaf["validUntil"], "mul leaf validUntil"),
        parse_instant(add_leaf["validUntil"], "add leaf validUntil"),
    ]
    if not authorized_at < valid_until:
        fail("program receipt must expire after authorization")
    require_equal(valid_until, min(leaf_expiries),
                  "program lifetime must be capped by earliest leaf expiry")
    for name, leaf in (("mul", mul_leaf), ("add", add_leaf)):
        leaf_start = parse_instant(leaf["authorizedAt"], f"{name} leaf authorizedAt")
        leaf_end = parse_instant(leaf["validUntil"], f"{name} leaf validUntil")
        if authorized_at < leaf_start or not authorized_at < leaf_end:
            fail(f"program authorization is outside {name} leaf validity")

    expected_revisions = sorted({case["workRevision"] for case in replay["cases"]})
    require_equal(receipt["workRevisions"], expected_revisions,
                  "program work-semantics revisions")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--schemas", type=Path, required=True)
    args = parser.parse_args()

    installed = importlib.metadata.version("jsonschema")
    require_equal(installed, EXPECTED_JSONSCHEMA_VERSION,
                  "jsonschema verifier version")

    root = args.root.resolve()
    schemas = args.schemas.resolve()
    documents: dict[str, dict[str, Any]] = {}
    for key, filename in FILES.items():
        path = root / filename
        if not path.is_file() or path.is_symlink():
            fail(f"required artifact must be a regular non-symlink file: {path}")
        document = load(path)
        validate_schema(document, schemas / SCHEMAS[key], filename)
        documents[key] = document

    for key in ("replay", "receipt", "mul_leaf", "add_leaf"):
        path = root / FILES[key]
        verify_canonical_file(path, documents[key])
        verify_self_hash(documents[key], FILES[key])

    verify_replay(documents["replay"])
    verify_bindings(documents)
    print("learned RewriteProgram authorization evidence: VERIFIED")


if __name__ == "__main__":
    main()
