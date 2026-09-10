#!/usr/bin/env python3
"""Independently verify learned-pattern authorization schemas and cross-artifact policy."""

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
EXPECTED_FILES = {
    "authorization-bundle.json",
    "split-manifest.json",
    "validation-selection.json",
    "final-test-evaluation.json",
    "counterexample-evidence.json",
    "authorization-receipt.json",
}
SCHEMAS = {
    "authorization-bundle.json":
        "regelsuche-learned-pattern-rule-authorization-bundle-v1.schema.json",
    "split-manifest.json":
        "regelsuche-evolution-split-manifest-v1.schema.json",
    "validation-selection.json":
        "evolution-validation-selection-v1.schema.json",
    "final-test-evaluation.json":
        "evolution-final-test-evaluation-v1.schema.json",
    "counterexample-evidence.json":
        "regelsuche-learned-pattern-rule-counterexample-evidence-v1.schema.json",
    "authorization-receipt.json":
        "regelsuche-learned-pattern-rule-authorization-receipt-v1.schema.json",
}
EXPECTED_COUNTEREXAMPLE_BUDGET = {
    "numericRandomSamples": 64,
    "includeEdgeCases": True,
    "includeMatrixAssignments": False,
    "randomSeed": 745,
    "includeComplexAssignments": True,
    "includeRationalAssignments": True,
    "maxMatrixDimension": 0,
    "timeoutMillis": 0,
}
SHA_PREFIX = "sha256:"


def fail(message: str) -> None:
    raise SystemExit(f"learned-pattern authorization verification failed: {message}")


def strict_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            fail(f"duplicate JSON field {key!r}")
        result[key] = value
    return result


def load(path: Path) -> dict[str, Any]:
    try:
        with path.open("r", encoding="utf-8") as handle:
            value = json.load(handle, object_pairs_hook=strict_object)
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
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


def verify_self_hash(document: dict[str, Any], name: str) -> None:
    retained = document.get("contentHash")
    payload = dict(document)
    payload.pop("contentHash", None)
    actual = sha256_text(canonical(payload))
    if retained != actual:
        fail(f"{name} contentHash mismatch: retained={retained}, computed={actual}")


def validate_schema(document: dict[str, Any], schema_path: Path, name: str) -> None:
    schema = load(schema_path)
    validator = Draft202012Validator(schema, format_checker=FormatChecker())
    errors = sorted(validator.iter_errors(document), key=lambda item: list(item.path))
    if errors:
        first = errors[0]
        location = "/".join(str(part) for part in first.path) or "<root>"
        fail(f"{name} violates schema at {location}: {first.message}")


def require_equal(actual: Any, expected: Any, message: str) -> None:
    if actual != expected:
        fail(f"{message}: expected={expected!r}, actual={actual!r}")


def require_zero(document: dict[str, Any], fields: tuple[str, ...], name: str) -> None:
    for field in fields:
        require_equal(document.get(field), 0, f"{name}.{field}")


def parse_instant(value: str, name: str) -> datetime:
    try:
        instant = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except (AttributeError, ValueError) as error:
        fail(f"{name} is not a valid ISO instant: {error}")
    if instant.tzinfo is None:
        fail(f"{name} must be offset-aware")
    return instant


def case_map(cases: list[dict[str, Any]], family_field: str) -> dict[str, str]:
    result: dict[str, str] = {}
    for case in cases:
        case_id = case["caseId"]
        if case_id in result:
            fail(f"duplicate case ID {case_id!r}")
        result[case_id] = case[family_field]
    return result


def verify_split_disjointness(split: dict[str, Any]) -> None:
    if split.get("heldOutMaterialization") == "DEFERRED_TO_PUBLIC_RANDOMNESS":
        fail("authorization requires concrete VALIDATION and FINAL TEST partitions")

    partitions = [
        ("TRAIN", split["trainCases"]),
        ("VALIDATION", split["validationCases"]),
        ("FINAL_TEST", split["finalTestCases"]),
    ]
    if not all(cases for _, cases in partitions):
        fail("all authorization split partitions must be non-empty")

    identity_fields = (
        "caseId",
        "familyId",
        "exactSignatureHash",
        "alphaSignatureHash",
        "inputHash",
        "hiddenTargetHash",
    )
    for field in identity_fields:
        owner: dict[str, str] = {}
        for partition, cases in partitions:
            for case in cases:
                identity = case[field]
                previous = owner.get(identity)
                if previous is not None and previous != partition:
                    fail(
                        f"split leakage: {field} {identity!r} occurs in "
                        f"{previous} and {partition}"
                    )
                owner[identity] = partition


def verify_validation(
    split: dict[str, Any],
    validation: dict[str, Any],
    bundle: dict[str, Any],
) -> None:
    require_equal(
        validation["splitManifestHash"],
        split["contentHash"],
        "VALIDATION split binding",
    )
    require_equal(validation["evaluationSplit"], "VALIDATION", "VALIDATION split label")
    require_equal(validation["selectionOutcome"], "SELECTED", "VALIDATION selection outcome")
    require_equal(
        validation["selectedGenomeHash"],
        bundle["genomeHash"],
        "VALIDATION selected genome",
    )

    matches = [
        candidate
        for candidate in validation["candidates"]
        if candidate["genomeHash"] == bundle["genomeHash"]
        and candidate["configurationHash"] == validation["selectedConfigurationHash"]
    ]
    require_equal(len(matches), 1, "selected VALIDATION candidate cardinality")
    selected = matches[0]
    require_zero(
        selected,
        ("reachabilityRegressions", "correctnessFailures", "correctnessRegressions"),
        "selected VALIDATION candidate",
    )
    require_equal(selected["blockers"], [], "selected VALIDATION blockers")

    expected = case_map(split["validationCases"], "familyId")
    actual = case_map(selected["cases"], "family")
    require_equal(actual, expected, "VALIDATION case/family partition")
    require_equal(
        set(validation["validationCaseIds"]),
        set(expected),
        "VALIDATION case ID set",
    )


def verify_final_test(
    split: dict[str, Any],
    validation: dict[str, Any],
    final_test: dict[str, Any],
    bundle: dict[str, Any],
) -> None:
    require_equal(
        final_test["splitManifestHash"],
        split["contentHash"],
        "FINAL TEST split binding",
    )
    require_equal(
        final_test["validationSelectionHash"],
        validation["contentHash"],
        "FINAL TEST VALIDATION binding",
    )
    require_equal(
        final_test["selectedGenomeHash"],
        bundle["genomeHash"],
        "FINAL TEST selected genome",
    )
    require_equal(
        final_test["selectedConfigurationHash"],
        validation["selectedConfigurationHash"],
        "FINAL TEST selected configuration",
    )
    require_equal(final_test["evaluationSplit"], "FINAL_TEST", "FINAL TEST split label")
    require_equal(final_test["executionOutcome"], "COMPLETED", "FINAL TEST outcome")
    require_equal(final_test["finalTestStatus"], "COMPLETED", "FINAL TEST status")
    require_zero(
        final_test,
        (
            "reachabilityRegressions",
            "correctnessFailures",
            "correctnessRegressions",
            "failedCaseEvaluations",
        ),
        "FINAL TEST",
    )

    expected = case_map(split["finalTestCases"], "familyId")
    actual = case_map(final_test["cases"], "family")
    require_equal(actual, expected, "FINAL TEST case/family partition")
    require_equal(
        set(final_test["finalTestCaseIds"]),
        set(expected),
        "FINAL TEST case ID set",
    )


def java_utf16_length(value: str) -> int:
    return len(value.encode("utf-16-le")) // 2


def append_java(material: list[str], value: str) -> None:
    material.append(f"{java_utf16_length(value)}:{value}")


def append_java_list(material: list[str], values: list[str]) -> None:
    append_java(material, str(len(values)))
    for value in values:
        append_java(material, value)


def verify_counterexample(
    counterexample: dict[str, Any],
    bundle: dict[str, Any],
) -> None:
    require_equal(
        counterexample["genomeHash"], bundle["genomeHash"],
        "counterexample genome",
    )
    require_equal(counterexample["geneId"], bundle["geneId"], "counterexample gene")
    require_equal(
        counterexample["repositoryRevision"],
        bundle["repositoryRevision"],
        "counterexample repository revision",
    )
    require_equal(
        counterexample["budget"],
        EXPECTED_COUNTEREXAMPLE_BUDGET,
        "counterexample replay budget",
    )
    require_equal(
        counterexample["status"],
        "NO_COUNTEREXAMPLE_FOUND",
        "counterexample terminal status",
    )
    if not counterexample["attemptedSources"]:
        fail("counterexample evidence must retain at least one attempted source")
    require_equal(
        counterexample["inferredAssumptions"],
        [],
        "counterexample inferred assumptions",
    )

    material: list[str] = []
    append_java(material, counterexample["status"])
    append_java_list(material, counterexample["inferredAssumptions"])
    append_java_list(material, counterexample["attemptedSources"])
    append_java(material, counterexample["explanation"])
    append_java(material, "NO_COUNTEREXAMPLE")
    expected_hash = sha256_text("".join(material))
    require_equal(
        counterexample["resultHash"],
        expected_hash,
        "counterexample retained result hash",
    )


def verify_bundle(
    bundle: dict[str, Any],
    split: dict[str, Any],
    validation: dict[str, Any],
    final_test: dict[str, Any],
    counterexample: dict[str, Any],
) -> None:
    require_equal(bundle["splitManifestHash"], split["contentHash"], "bundle split hash")
    require_equal(
        bundle["validationSelectionHash"],
        validation["contentHash"],
        "bundle VALIDATION hash",
    )
    require_equal(
        bundle["finalTestEvaluationHash"],
        final_test["contentHash"],
        "bundle FINAL TEST hash",
    )
    require_equal(
        bundle["counterexampleEvidenceHash"],
        counterexample["contentHash"],
        "bundle counterexample hash",
    )
    issued = parse_instant(bundle["issuedAt"], "bundle.issuedAt")
    expires = parse_instant(bundle["expiresAt"], "bundle.expiresAt")
    if not issued < expires:
        fail("authorization bundle must expire after it is issued")


def verify_receipt(
    receipt: dict[str, Any],
    bundle: dict[str, Any],
    split: dict[str, Any],
    validation: dict[str, Any],
    final_test: dict[str, Any],
    counterexample: dict[str, Any],
) -> None:
    for field in ("genomeHash", "geneId", "repositoryRevision"):
        require_equal(receipt[field], bundle[field], f"receipt {field}")
    require_equal(
        receipt["evidenceBundleHash"], bundle["contentHash"],
        "receipt bundle hash",
    )
    require_equal(
        receipt["semanticValidationHash"], validation["contentHash"],
        "receipt VALIDATION hash",
    )
    require_equal(
        receipt["counterexampleSearchHash"], counterexample["contentHash"],
        "receipt counterexample hash",
    )
    require_equal(
        receipt["holdoutEvaluationHash"], final_test["contentHash"],
        "receipt FINAL TEST hash",
    )
    require_equal(
        receipt["leakageAuditHash"], split["contentHash"],
        "receipt leakage/split hash",
    )
    require_equal(receipt["validUntil"], bundle["expiresAt"], "receipt expiry")

    issued = parse_instant(bundle["issuedAt"], "bundle.issuedAt")
    authorized = parse_instant(receipt["authorizedAt"], "receipt.authorizedAt")
    expires = parse_instant(bundle["expiresAt"], "bundle.expiresAt")
    if not issued <= authorized < expires:
        fail("receipt authorizedAt must be inside the bundle validity interval")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--schemas", required=True, type=Path)
    args = parser.parse_args()

    version = importlib.metadata.version("jsonschema")
    require_equal(version, EXPECTED_JSONSCHEMA_VERSION, "jsonschema version")

    root = args.root.resolve()
    schemas = args.schemas.resolve()
    if not root.is_dir():
        fail(f"evidence root does not exist: {root}")
    actual_files = {path.name for path in root.iterdir() if path.is_file()}
    require_equal(actual_files, EXPECTED_FILES, "authorization evidence file set")

    documents: dict[str, dict[str, Any]] = {}
    for filename in sorted(EXPECTED_FILES):
        document = load(root / filename)
        schema_path = schemas / SCHEMAS[filename]
        validate_schema(document, schema_path, filename)
        documents[filename] = document

    for filename in (
        "authorization-bundle.json",
        "counterexample-evidence.json",
        "authorization-receipt.json",
    ):
        verify_self_hash(documents[filename], filename)
        expected_bytes = canonical(documents[filename])
        actual_bytes = (root / filename).read_text(encoding="utf-8")
        require_equal(actual_bytes, expected_bytes, f"canonical bytes for {filename}")

    bundle = documents["authorization-bundle.json"]
    split = documents["split-manifest.json"]
    validation = documents["validation-selection.json"]
    final_test = documents["final-test-evaluation.json"]
    counterexample = documents["counterexample-evidence.json"]
    receipt = documents["authorization-receipt.json"]

    verify_split_disjointness(split)
    verify_bundle(bundle, split, validation, final_test, counterexample)
    verify_validation(split, validation, bundle)
    verify_final_test(split, validation, final_test, bundle)
    verify_counterexample(counterexample, bundle)
    verify_receipt(receipt, bundle, split, validation, final_test, counterexample)

    print(
        "Verified learned-pattern authorization: schemas, canonical hashes, "
        "split isolation, VALIDATION/FINAL-TEST qualification, counterexample "
        "evidence and receipt bindings."
    )


if __name__ == "__main__":
    main()
