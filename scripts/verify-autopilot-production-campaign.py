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
    from jsonschema.exceptions import ValidationError
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
    "production-campaign-manifest.json": (
        "regelsuche-autonomous-production-campaign-v2.schema.json"
    ),
}
MANIFEST_ROOT_BINDINGS = {
    "briefHash": "brief-v2.json",
    "generationRunHash": "generation-run.json",
    "miningRunHash": "production-mining-run.json",
    "lifecycleRunHash": "production-lifecycle-run.json",
    "initialPlanHash": "plan-v2.json",
    "nextPlanHash": "next-plan-v2.json",
    "campaignRoundHash": "campaign-round-v2.json",
    "feedbackReallocationHash": "feedback-reallocation.json",
    "campaignResourceLedgerHash": "campaign-resource-ledger.json",
}
MANIFEST_ARTIFACT_BINDINGS = {
    "research-brief": "brief-v2.json",
    "seed-catalog": "seeds.json",
    "observation-bundle": "observations.json",
    "generation-receipt": "generation-receipt.json",
    "generation-run": "generation-run.json",
    "initial-plan": "plan-v2.json",
    "full-decision": "full-decision.json",
    "full-mining-evidence": "full-mining-evidence.json",
    "full-binding": "full-binding.json",
    "full-aggregate-receipt": "full-receipt.json",
    "full-execution": "full-execution-v2.json",
    "full-lineage": "full-lineage-v2.json",
    "rejection-decision": "rejection-decision.json",
    "rejection-mining-evidence": "rejection-mining-evidence.json",
    "rejection-binding": "rejection-binding.json",
    "rejection-aggregate-receipt": "rejection-receipt.json",
    "rejection-execution": "rejection-execution-v2.json",
    "rejection-lineage": "rejection-lineage-v2.json",
    "candidate-formation-receipt": "candidate-formation-receipt.json",
    "evidence-dag": "evidence-dag.json",
    "mining-run": "production-mining-run.json",
    "solver-obligation": "solver-obligation.json",
    "solver-result": "solver-result.json",
    "lifecycle-decision": "lifecycle-decision.json",
    "stage-resource-ledger": "stage-resource-ledger.json",
    "lifecycle-run": "production-lifecycle-run.json",
    "next-plan": "next-plan-v2.json",
    "campaign-round": "campaign-round-v2.json",
    "feedback-reallocation": "feedback-reallocation.json",
    "campaign-resource-ledger": "campaign-resource-ledger.json",
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


def document_hash(
    documents: dict[str, dict[str, Any]],
    file_name: str,
) -> str:
    document = documents[file_name]
    return require_sha256(document.get("contentHash"), f"{file_name}.contentHash")


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

    require(
        root.is_dir() and not root.is_symlink(),
        f"campaign directory does not exist or is symbolic: {root}",
    )
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
        require(
            path.is_file() and not path.is_symlink(),
            f"missing, non-regular or symbolic required artifact: {name}",
        )
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
        require(
            schema_path.is_file() and not schema_path.is_symlink(),
            f"missing or symbolic schema: {schema_name}",
        )
        schema = load_unique(schema_path)
        require(isinstance(schema, dict), f"schema must be an object: {schema_name}")
        require(
            schema.get("additionalProperties") is False,
            f"schema must fail closed: {schema_name}",
        )
        Draft202012Validator.check_schema(schema)
        try:
            Draft202012Validator(schema).validate(documents[artifact_name])
        except ValidationError as error:
            fail(f"schema validation failed for {artifact_name}: {error.message}")

    obligation = documents["solver-obligation.json"]
    result = documents["solver-result.json"]
    proof = documents["proof-report.json"]
    lifecycle = documents["production-lifecycle-run.json"]
    manifest = documents["production-campaign-manifest.json"]

    obligation_hash = document_hash(documents, "solver-obligation.json")
    result_hash = document_hash(documents, "solver-result.json")
    require(result.get("obligationHash") == obligation_hash, "solver result obligation binding drift")
    require(proof.get("solverObligationHash") == obligation_hash, "proof obligation binding drift")
    require(proof.get("solverResultHash") == result_hash, "proof result binding drift")
    require(lifecycle.get("solverObligationHash") == obligation_hash, "lifecycle obligation binding drift")
    require(lifecycle.get("solverResultHash") == result_hash, "lifecycle result binding drift")
    require(result.get("status") == "CONFIRMED", "solver result is not confirmed")
    require(result.get("translationStatus") == "LOSSLESS", "solver translation is not lossless")

    for manifest_field, file_name in MANIFEST_ROOT_BINDINGS.items():
        require(
            manifest.get(manifest_field) == document_hash(documents, file_name),
            f"manifest {manifest_field} binding drift: {file_name}",
        )

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
    artifact_roots: dict[str, str] = {}
    for index, item in enumerate(artifacts):
        require(isinstance(item, dict), f"manifest artifact {index} is not an object")
        artifact_type = item.get("artifactType")
        require(
            isinstance(artifact_type, str) and artifact_type,
            f"manifest artifact {index} lacks type",
        )
        require(artifact_type not in artifact_roots, f"duplicate campaign artifact type: {artifact_type}")
        artifact_roots[artifact_type] = require_sha256(
            item.get("contentHash"),
            f"manifest artifact {index} contentHash",
        )

    for artifact_type, file_name in MANIFEST_ARTIFACT_BINDINGS.items():
        require(
            artifact_roots.get(artifact_type) == document_hash(documents, file_name),
            f"manifest artifact root drift: {artifact_type} -> {file_name}",
        )

    print(f"jsonschema={installed}")
    print("autopilot-production-campaign=VERIFIED")
    print(f"required-artifacts={len(REQUIRED_FILES)}")
    print(f"manifest-root-bindings={len(MANIFEST_ROOT_BINDINGS)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
