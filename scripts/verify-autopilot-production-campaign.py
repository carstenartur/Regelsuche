#!/usr/bin/env python3
"""Fail-closed verification for retained Autopilot production campaign evidence."""

from __future__ import annotations

import argparse
import json
import sys
from importlib.metadata import PackageNotFoundError, version
from pathlib import Path
from typing import Any

try:
    from jsonschema import Draft202012Validator
except ImportError as error:
    raise SystemExit(
        "jsonschema is required; run ./gradlew prepareVerificationEnvironment"
    ) from error

EXPECTED_JSONSCHEMA_VERSION = "4.25.1"
REQUIRED_FILES = (
    "brief-v2.json",
    "seeds.json",
    "observations.json",
    "generation-receipt.json",
    "discovery-report.json",
    "generation-run.json",
    "plan-v2.json",
    "full-decision.json",
    "full-mining-evidence.json",
    "full-binding.json",
    "full-receipt.json",
    "full-execution-v2.json",
    "full-lineage-v2.json",
    "rejection-decision.json",
    "rejection-mining-evidence.json",
    "rejection-binding.json",
    "rejection-receipt.json",
    "rejection-execution-v2.json",
    "rejection-lineage-v2.json",
    "candidate-formation-receipt.json",
    "evidence-dag.json",
    "production-mining-run.json",
    "validation-report.json",
    "counterexample-report.json",
    "project-novelty-report.json",
    "proof-report.json",
    "solver-obligation.json",
    "solver-result.json",
    "lifecycle-candidate.json",
    "lifecycle-decision.json",
    "stage-resource-ledger.json",
    "production-lifecycle-run.json",
    "next-plan-v2.json",
    "campaign-round-v2.json",
    "feedback-reallocation.json",
    "campaign-resource-ledger.json",
    "production-campaign-manifest.json",
)
SCHEMA_BINDINGS = {
    "solver-obligation.json": "regelsuche-solver-obligation-v1.schema.json",
    "solver-result.json": "regelsuche-solver-result-v1.schema.json",
    "proof-report.json": "regelsuche-open-target-conjecture-proof-v2.schema.json",
    "production-lifecycle-run.json": (
        "regelsuche-autonomous-production-lifecycle-v3.schema.json"
    ),
}


def fail(message: str) -> None:
    raise SystemExit(f"autopilot production campaign invalid: {message}")


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


def require_sha256(value: Any, label: str) -> str:
    require(
        isinstance(value, str)
        and value.startswith("sha256:")
        and len(value) == 71
        and all(character in "0123456789abcdef" for character in value[7:]),
        f"{label} is not a canonical SHA-256 identity",
    )
    return value


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument(
        "--schemas",
        type=Path,
        default=Path("docs/schemas"),
    )
    arguments = parser.parse_args()
    root = arguments.root.resolve()
    schema_root = arguments.schemas.resolve()

    require(root.is_dir(), f"campaign directory does not exist: {root}")
    try:
        installed = version("jsonschema")
    except PackageNotFoundError:
        fail("jsonschema is not installed")
    require(
        installed == EXPECTED_JSONSCHEMA_VERSION,
        f"jsonschema version drift: expected {EXPECTED_JSONSCHEMA_VERSION}, found {installed}",
    )

    documents: dict[str, dict[str, Any]] = {}
    for name in REQUIRED_FILES:
        path = root / name
        require(path.is_file(), f"missing required artifact: {name}")
        require(path.stat().st_size > 0, f"empty required artifact: {name}")
        document = load_unique(path)
        require(isinstance(document, dict), f"artifact must be an object: {name}")
        if "contentHash" in document:
            require_sha256(document["contentHash"], f"{name}.contentHash")
        documents[name] = document

    require(
        not (root / "proof-obligation.json").exists(),
        "deprecated proof-obligation.json must not be emitted",
    )

    for artifact_name, schema_name in SCHEMA_BINDINGS.items():
        schema_path = schema_root / schema_name
        require(schema_path.is_file(), f"missing schema: {schema_name}")
        schema = load_unique(schema_path)
        require(isinstance(schema, dict), f"schema must be an object: {schema_name}")
        Draft202012Validator.check_schema(schema)
        Draft202012Validator(schema).validate(documents[artifact_name])

    obligation = documents["solver-obligation.json"]
    result = documents["solver-result.json"]
    proof = documents["proof-report.json"]
    lifecycle = documents["production-lifecycle-run.json"]
    manifest = documents["production-campaign-manifest.json"]

    obligation_hash = require_sha256(
        obligation.get("contentHash"), "solver obligation contentHash"
    )
    result_hash = require_sha256(result.get("contentHash"), "solver result contentHash")
    require(result.get("obligationHash") == obligation_hash, "solver result obligation binding drift")
    require(proof.get("solverObligationHash") == obligation_hash, "proof obligation binding drift")
    require(proof.get("solverResultHash") == result_hash, "proof result binding drift")
    require(lifecycle.get("solverObligationHash") == obligation_hash, "lifecycle obligation binding drift")
    require(lifecycle.get("solverResultHash") == result_hash, "lifecycle result binding drift")
    require(result.get("status") == "CONFIRMED", "solver result is not confirmed")
    require(result.get("translationStatus") == "LOSSLESS", "solver translation is not lossless")

    require(manifest.get("status") == "COMPLETED", "campaign manifest is not completed")
    require(manifest.get("targetProvided") is False, "campaign received a target")
    require(
        manifest.get("campaignCompletionIsMathematicalEvidence") is False,
        "campaign completion was inflated into mathematical evidence",
    )
    require(
        manifest.get("externalNoveltyEvaluated") is False,
        "campaign incorrectly claims external novelty evaluation",
    )
    require(manifest.get("promotionStatus") == "NOT_EVALUATED", "promotion status inflated")
    require(manifest.get("publicEvidenceStatus") == "NOT_EVALUATED", "public evidence status inflated")
    artifacts = manifest.get("artifacts")
    require(isinstance(artifacts, list) and len(artifacts) >= 30, "campaign manifest lacks artifact roots")
    artifact_types = [item.get("artifactType") for item in artifacts if isinstance(item, dict)]
    require(len(artifact_types) == len(set(artifact_types)), "campaign artifact types are duplicated")
    for index, item in enumerate(artifacts):
        require(isinstance(item, dict), f"manifest artifact {index} is not an object")
        require(isinstance(item.get("artifactType"), str) and item["artifactType"], f"manifest artifact {index} lacks type")
        require_sha256(item.get("contentHash"), f"manifest artifact {index} contentHash")

    print(f"jsonschema={installed}")
    print("autopilot-production-campaign=VERIFIED")
    print(f"required-artifacts={len(REQUIRED_FILES)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
