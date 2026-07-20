#!/usr/bin/env python3
"""Verify frozen independent-interestingness review protocol artifacts."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from importlib.metadata import PackageNotFoundError, version
from pathlib import Path
from typing import Any

try:
    from jsonschema import Draft202012Validator
    from jsonschema.exceptions import ValidationError
except ImportError as error:
    raise SystemExit(
        "jsonschema is required; run ./gradlew prepareVerificationEnvironment"
    ) from error

EXPECTED_JSONSCHEMA_VERSION = "4.25.1"
PLAN_SCHEMA_NAME = "regelsuche-independent-review-study-plan-v1.schema.json"
INTAKE_SCHEMA_NAME = "regelsuche-independent-review-intake-v1.schema.json"


def fail(message: str) -> None:
    raise SystemExit(f"independent interestingness review invalid: {message}")


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def load_unique(path: Path) -> Any:
    def pairs_hook(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"duplicate field {key!r}")
            result[key] = value
        return result

    try:
        with path.open("r", encoding="utf-8") as handle:
            return json.load(handle, object_pairs_hook=pairs_hook)
    except (OSError, json.JSONDecodeError, ValueError) as error:
        fail(f"cannot parse {path}: {error}")


def hash_payload(payload: Any) -> str:
    encoded = json.dumps(
        payload,
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")
    return "sha256:" + hashlib.sha256(encoded).hexdigest()


def require_hash(value: Any, label: str) -> str:
    require(
        isinstance(value, str)
        and value.startswith("sha256:")
        and len(value) == 71
        and all(character in "0123456789abcdef" for character in value[7:]),
        f"{label} is not a canonical SHA-256 identity",
    )
    return value


def validate_schema(path: Path) -> tuple[dict[str, Any], Draft202012Validator]:
    require(
        path.is_file() and not path.is_symlink(),
        f"schema is missing, non-regular or symbolic: {path}",
    )
    schema = load_unique(path)
    require(isinstance(schema, dict), f"schema is not an object: {path}")
    require(
        schema.get("additionalProperties") is False,
        f"schema must fail closed: {path}",
    )
    Draft202012Validator.check_schema(schema)
    return schema, Draft202012Validator(schema)


def validate_document(
    path: Path,
    validator: Draft202012Validator,
) -> dict[str, Any]:
    require(
        path.is_file() and not path.is_symlink(),
        f"artifact is missing, non-regular or symbolic: {path}",
    )
    require(path.stat().st_size > 0, f"artifact is empty: {path}")
    value = load_unique(path)
    require(isinstance(value, dict), f"artifact is not an object: {path}")
    try:
        validator.validate(value)
    except ValidationError as error:
        fail(f"schema validation failed for {path.name}: {error.message}")
    return value


def verify_plan(plan: dict[str, Any]) -> None:
    cases = plan["cases"]
    require(len(cases) >= 4, "study plan requires at least four cases")
    require(
        len({case["caseId"] for case in cases}) == len(cases),
        "study plan contains duplicate case IDs",
    )
    require(
        len({case["candidateId"] for case in cases}) == len(cases),
        "study plan contains duplicate candidate IDs",
    )
    require(
        len({case["candidateArtifactHash"] for case in cases}) == len(cases),
        "study plan contains duplicate candidate artifacts",
    )

    calibration = [case for case in cases if case["split"] == "CALIBRATION"]
    test = [case for case in cases if case["split"] == "TEST"]
    require(len(calibration) >= 2, "CALIBRATION requires at least two cases")
    require(len(test) >= 2, "TEST requires at least two cases")
    require(
        not ({case["candidateFamily"] for case in calibration}
             & {case["candidateFamily"] for case in test}),
        "candidate families overlap CALIBRATION and TEST",
    )
    require(
        not ({case["structuralSignatureHash"] for case in calibration}
             & {case["structuralSignatureHash"] for case in test}),
        "structural signatures overlap CALIBRATION and TEST",
    )

    exposed = {
        exposure["candidateArtifactHash"]
        for exposure in plan["historicalExposures"]
    }
    reused = sorted(
        case["caseId"]
        for case in test
        if case["candidateArtifactHash"] in exposed
    )
    require(not reused, f"historically exposed artifacts reused in TEST: {reused}")

    predictive = {
        "schema": "regelsuche.independent-review-predictive-corpus/v1",
        "minimumCasesPerSplit": 2,
        "cases": cases,
    }
    require(
        plan["predictiveCorpusHash"] == hash_payload(predictive),
        "predictive corpus hash drift",
    )
    require(
        plan["thresholdLockHash"] == hash_payload(plan["acceptanceThresholds"]),
        "threshold lock hash drift",
    )
    without_hash = dict(plan)
    content_hash = without_hash.pop("contentHash")
    require_hash(content_hash, "study-plan contentHash")
    require(content_hash == hash_payload(without_hash), "study-plan content hash drift")

    protocol = plan["reviewProtocol"]
    require(protocol["blindReviewRequired"] is True, "blind review is not required")
    require(
        protocol["oneReviewPerReviewerAndCandidate"] is True,
        "one-review-per-reviewer-and-candidate is not required",
    )
    require(
        protocol["reviewerIdentitiesStoredAsHashesOnly"] is True,
        "reviewer identity hashing is not required",
    )
    require(
        protocol["testLabelsExcludedFromSelection"] is True,
        "TEST labels are not excluded from selection",
    )
    require(
        protocol["profiles"] == ["THEORY_DISCOVERY", "SEARCH_REUSE"],
        "interestingness profile set or order drift",
    )
    require(plan["labelsStatus"] == "NOT_COLLECTED", "plan contains collected labels")
    require(plan["promotionStatus"] == "NOT_EVALUATED", "plan promotion status inflated")
    require(
        plan["publicEvidenceStatus"] == "NOT_EVALUATED",
        "plan Public Evidence status inflated",
    )


def verify_intake(plan: dict[str, Any], intake: dict[str, Any]) -> None:
    require(intake["studyPlanHash"] == plan["contentHash"], "intake study-plan binding drift")
    require(
        intake["predictiveCorpusHash"] == plan["predictiveCorpusHash"],
        "intake predictive-corpus binding drift",
    )
    require(
        intake["thresholdLockHash"] == plan["thresholdLockHash"],
        "intake threshold-lock binding drift",
    )
    require(
        intake["minimumIndependentExpertReviews"]
        == plan["reviewProtocol"]["minimumIndependentExpertReviews"],
        "minimum independent review count drift",
    )
    require(intake["revision"] == 1, "development intake must be revision 1")
    require(
        intake["priorLabeledEvaluationHash"] == "",
        "revision 1 intake must not claim a predecessor",
    )

    cases_by_id = {case["caseId"]: case for case in plan["cases"]}
    decisions = intake["decisions"]
    require(
        len({decision["reviewId"] for decision in decisions}) == len(decisions),
        "intake contains duplicate review IDs",
    )
    for decision in decisions:
        candidate_case = cases_by_id.get(decision["caseId"])
        require(candidate_case is not None, f"decision references unknown case: {decision['caseId']}")
        require(
            decision["candidateId"] == candidate_case["candidateId"],
            f"decision candidate/case binding drift: {decision['reviewId']}",
        )
        require(
            decision["studyPlanHash"] == plan["contentHash"],
            f"decision study-plan binding drift: {decision['reviewId']}",
        )
        without_hash = dict(decision)
        content_hash = without_hash.pop("contentHash")
        require_hash(content_hash, f"decision {decision['reviewId']} contentHash")
        require(
            content_hash == hash_payload(without_hash),
            f"decision content hash drift: {decision['reviewId']}",
        )
        require(
            decision["origin"] == "DEVELOPMENT_FIXTURE",
            f"reference intake contains non-development origin: {decision['reviewId']}",
        )
        require(
            decision["outcome"] == "DEVELOPMENT_ONLY",
            f"development fixture outcome inflated: {decision['reviewId']}",
        )
        require(decision["blindReview"] is True, f"review is not blind: {decision['reviewId']}")
        require(not decision["blockers"], f"accepted development review has blockers: {decision['reviewId']}")

    expected_statuses = {
        (case["caseId"], case["candidateId"], case["split"])
        for case in plan["cases"]
    }
    status_rows = [
        (status["caseId"], status["candidateId"], status["split"])
        for status in intake["candidateStatuses"]
    ]
    actual_statuses = set(status_rows)
    require(
        len(status_rows) == len(actual_statuses),
        "candidate-status identities are duplicated",
    )
    require(
        len(status_rows) == len(plan["cases"]),
        "candidate-status row count differs from the frozen plan",
    )
    require(actual_statuses == expected_statuses, "candidate-status identity set drift")
    for status in intake["candidateStatuses"]:
        require(status["countedExpertReviews"] == 0, "development intake counts expert reviews")
        require(status["blindExpertReviews"] == 0, "development intake counts blind experts")
        require(status["rejectedReviews"] == 0, "reference development intake has rejected reviews")
        require(status["status"] == "DEVELOPMENT_ONLY", "candidate status is not DEVELOPMENT_ONLY")
        require(status["developmentFixtureReviews"] > 0, "candidate lacks its development fixture")

    labeled_payload = {
        "schema": "regelsuche.independent-review-labeled-evaluation/v1",
        "studyPlanHash": intake["studyPlanHash"],
        "predictiveCorpusHash": intake["predictiveCorpusHash"],
        "thresholdLockHash": intake["thresholdLockHash"],
        "minimumIndependentExpertReviews": intake["minimumIndependentExpertReviews"],
        "revision": intake["revision"],
        "priorLabeledEvaluationHash": intake["priorLabeledEvaluationHash"],
        "evidenceStatus": intake["evidenceStatus"],
        "decisions": decisions,
        "candidateStatuses": intake["candidateStatuses"],
    }
    require(
        intake["labeledEvaluationHash"] == hash_payload(labeled_payload),
        "labeled evaluation hash drift",
    )
    without_hash = dict(intake)
    content_hash = without_hash.pop("contentHash")
    require_hash(content_hash, "intake contentHash")
    require(content_hash == hash_payload(without_hash), "intake content hash drift")

    require(intake["evidenceStatus"] == "DEVELOPMENT_ONLY", "intake evidence status inflated")
    require(intake["promotionStatus"] == "NOT_EVALUATED", "intake promotion status inflated")
    require(
        intake["publicEvidenceStatus"] == "NOT_EVALUATED",
        "intake Public Evidence status inflated",
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--schemas", type=Path, default=Path("docs/schemas"))
    arguments = parser.parse_args()
    root = arguments.root.resolve()
    schema_root = arguments.schemas.resolve()

    try:
        installed = version("jsonschema")
    except PackageNotFoundError:
        fail("jsonschema is not installed")
    require(
        installed == EXPECTED_JSONSCHEMA_VERSION,
        f"jsonschema version drift: expected {EXPECTED_JSONSCHEMA_VERSION}, found {installed}",
    )

    _, plan_validator = validate_schema(schema_root / PLAN_SCHEMA_NAME)
    _, intake_validator = validate_schema(schema_root / INTAKE_SCHEMA_NAME)
    plan = validate_document(root / "study-plan.json", plan_validator)
    intake = validate_document(root / "development-intake.json", intake_validator)
    verify_plan(plan)
    verify_intake(plan, intake)

    print(f"jsonschema={installed}")
    print("independent-interestingness-review=DEVELOPMENT_PROTOCOL_VERIFIED")
    print(f"study-cases={len(plan['cases'])}")
    print(f"development-decisions={len(intake['decisions'])}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
