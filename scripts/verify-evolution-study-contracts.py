#!/usr/bin/env python3
"""Validate preregistered evolution split and study-plan artifacts."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from importlib.metadata import PackageNotFoundError, version
from pathlib import Path

try:
    from jsonschema import Draft202012Validator
except ImportError as error:
    raise SystemExit("jsonschema is required; run ./gradlew prepareVerificationEnvironment") from error

EXPECTED_JSONSCHEMA_VERSION = "4.25.1"
SCHEMA_ROOT = Path("docs/schemas")


def fail(message: str) -> None:
    raise SystemExit(f"evolution study contracts invalid: {message}")


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def load(path: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"cannot read {path}: {error}")
    require(isinstance(value, dict), f"{path} must contain a JSON object")
    return value


def canonical_hash(document: dict) -> str:
    payload = dict(document)
    payload.pop("contentHash", None)
    encoded = json.dumps(
        payload,
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")
    return "sha256:" + hashlib.sha256(encoded).hexdigest()


def validate(schema_name: str, artifact: dict) -> None:
    schema = load(SCHEMA_ROOT / schema_name)
    Draft202012Validator.check_schema(schema)
    require(schema.get("additionalProperties") is False,
            f"schema must fail closed: {schema_name}")
    Draft202012Validator(schema).validate(artifact)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--root",
        type=Path,
        default=Path("regelsuche-learning/build/reports/evolution-study-plan"),
    )
    arguments = parser.parse_args()

    try:
        installed = version("jsonschema")
    except PackageNotFoundError:
        fail("jsonschema is not installed")
    require(
        installed == EXPECTED_JSONSCHEMA_VERSION,
        f"jsonschema version drift: expected {EXPECTED_JSONSCHEMA_VERSION}, found {installed}",
    )

    manifest_path = arguments.root / "evolution-split-manifest.json"
    plan_path = arguments.root / "evolution-study-plan.json"
    manifest = load(manifest_path)
    plan = load(plan_path)

    validate("regelsuche-evolution-split-manifest-v1.schema.json", manifest)
    validate("regelsuche-evolution-study-plan-v1.schema.json", plan)

    require(manifest["contentHash"] == canonical_hash(manifest),
            "split-manifest content hash mismatch")
    require(plan["contentHash"] == canonical_hash(plan),
            "study-plan content hash mismatch")
    require(plan["splitManifestHash"] == manifest["contentHash"],
            "study plan is not bound to the split manifest")
    require(plan["status"] == "NOT_STARTED", "study status is not NOT_STARTED")
    require(
        plan["finalTestPolicy"]
        == "ONE_TIME_AFTER_FROZEN_VALIDATION_SELECTION",
        "final-test policy drift",
    )
    for field in (
        "proofStatus",
        "externalNoveltyStatus",
        "promotionStatus",
        "publicEvidenceStatus",
    ):
        require(plan[field] == "NOT_EVALUATED", f"{field} must remain NOT_EVALUATED")

    require(len(manifest["trainCases"]) >= 1, "TRAIN split is empty")
    require(len(manifest["validationCases"]) >= 1, "VALIDATION split is empty")
    require(len(manifest["finalTestCases"]) >= 1, "FINAL TEST split is empty")
    require("selectedConfigurationHash" not in plan,
            "selection evidence leaked into preregistration")
    require("finalTestOutcome" not in plan,
            "FINAL TEST outcome leaked into preregistration")

    print(f"jsonschema={installed}")
    print(f"evolution-split-manifest={manifest['contentHash']}")
    print(f"evolution-study-plan={plan['contentHash']}")
    print("evolution-study-status=NOT_STARTED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
