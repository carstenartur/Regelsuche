#!/usr/bin/env python3
"""Fail-closed verification for retained Autopilot production campaign evidence."""

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
ARTIFACT_TYPE_BY_FILE = {
    "brief-v2.json": "research-brief",
    "seeds.json": "seed-catalog",
    "observations.json": "observation-bundle",
    "generation-receipt.json": "generation-receipt",
    "discovery-report.json": "discovery-report",
    "generation-run.json": "generation-run",
    "plan-v2.json": "initial-plan",
    "full-decision.json": "full-decision",
    "full-mining-evidence.json": "full-mining-evidence",
    "full-binding.json": "full-binding",
    "full-receipt.json": "full-aggregate-receipt",
    "full-execution-v2.json": "full-execution",
    "full-lineage-v2.json": "full-lineage",
    "rejection-decision.json": "rejection-decision",
    "rejection-mining-evidence.json": "rejection-mining-evidence",
    "rejection-binding.json": "rejection-binding",
    "rejection-receipt.json": "rejection-aggregate-receipt",
    "rejection-execution-v2.json": "rejection-execution",
    "rejection-lineage-v2.json": "rejection-lineage",
    "candidate-formation-receipt.json": "candidate-formation-receipt",
    "evidence-dag.json": "evidence-dag",
    "production-mining-run.json": "mining-run",
    "validation-report.json": "validation-report",
    "counterexample-report.json": "counterexample-report",
    "project-novelty-report.json": "project-novelty-report",
    "proof-report.json": "proof-report",
    "solver-obligation.json": "solver-obligation",
    "solver-result.json": "solver-result",
    "lifecycle-candidate.json": "lifecycle-candidate",
    "lifecycle-decision.json": "lifecycle-decision",
    "stage-resource-ledger.json": "stage-resource-ledger",
    "production-lifecycle-run.json": "lifecycle-run",
    "next-plan-v2.json": "next-plan",
    "campaign-round-v2.json": "campaign-round",
    "feedback-reallocation.json": "feedback-reallocation",
    "campaign-resource-ledger.json": "campaign-resource-ledger",
}
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
MANIFEST_BINDINGS = {
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
LIFECYCLE_BINDINGS = {
    "briefHash": "brief-v2.json",
    "miningRunHash": "production-mining-run.json",
    "validationHash": "validation-report.json",
    "counterexampleHash": "counterexample-report.json",
    "projectNoveltyHash": "project-novelty-report.json",
    "proofEvidenceHash": "proof-report.json",
    "solverObligationHash": "solver-obligation.json",
    "solverResultHash": "solver-result.json",
    "lifecycleCandidateHash": "lifecycle-candidate.json",
    "lifecycleDecisionHash": "lifecycle-decision.json",
    "stageResourceLedgerHash": "stage-resource-ledger.json",
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


def sha256_bytes(payload: bytes) -> str:
    return "sha256:" + hashlib.sha256(payload).hexdigest()


def require_sha256(value: Any, label: str) -> str:
    require(
        isinstance(value, str)
        and value.startswith("sha256:")
        and len(value) == 71
        and all(character in "0123456789abcdef" for character in value[7:]),
        f"{label} is not a canonical SHA-256 identity",
    )
    return value


def artifact_identity(
    name: str,
    path: Path,
    document: dict[str, Any],
) -> str:
    if "contentHash" in document:
        return require_sha256(document["contentHash"], f"{name}.contentHash")
    if "evidenceHash" in document:
        return require_sha256(document["evidenceHash"], f"{name}.evidenceHash")
    return sha256_bytes(path.read_bytes())


def verify_bindings(
    owner: dict[str, Any],
    bindings: dict[str, str],
    identities: dict[str, str],
    label: str,
) -> None:
    for property_name, artifact_name in bindings.items():
        require(
            owner.get(property_name) == identities[artifact_name],
            f"{label} {property_name} binding drift",
        )


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
    identities: dict[str, str] = {}
    for name in REQUIRED_FILES:
        path = root / name
        require(
            path.is_file() and not path.is_symlink(),
            f"missing, non-regular or symbolic required artifact: {name}",
        )
        require(path.stat().st_size > 0, f"empty required artifact: {name}")
        document = load_unique(path)
        require(isinstance(document, dict), f"artifact must be an object: {name}")
        documents[name] = document
        identities[name] = artifact_identity(name, path, document)

    require(
        not (root / "proof-obligation.json").exists(),
        "deprecated proof-obligation.json must not be emitted",
    )

    for artifact_name, schema_name in SCHEMA_BINDINGS.items():
        schema_path = schema_root / schema_name
        require(
            schema_path.is_file() and not schema_path.is_symlink(),
            f"missing, non-regular or symbolic schema: {schema_name}",
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

    obligation_hash = identities["solver-obligation.json"]
    result_hash = identities["solver-result.json"]
    require(result.get("obligationHash") == obligation_hash, "solver result obligation binding drift")
    require(proof.get("solverObligationHash") == obligation_hash, "proof obligation binding drift")
    require(proof.get("solverResultHash") == result_hash, "proof result binding drift")
    require(lifecycle.get("solverObligationHash") == obligation_hash, "lifecycle obligation binding drift")
    require(lifecycle.get("solverResultHash") == result_hash, "lifecycle result binding drift")
    require(result.get("status") == "CONFIRMED", "solver result is not confirmed")
    require(result.get("translationStatus") == "LOSSLESS", "solver translation is not lossless")

    verify_bindings(manifest, MANIFEST_BINDINGS, identities, "campaign manifest")
    verify_bindings(lifecycle, LIFECYCLE_BINDINGS, identities, "lifecycle manifest")

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
    require(isinstance(artifacts, list), "campaign manifest artifacts are not a list")
    by_type: dict[str, str] = {}
    for index, item in enumerate(artifacts):
        require(isinstance(item, dict), f"manifest artifact {index} is not an object")
        artifact_type = item.get("artifactType")
        require(isinstance(artifact_type, str) and artifact_type, f"manifest artifact {index} lacks type")
        require(artifact_type not in by_type, f"campaign artifact type is duplicated: {artifact_type}")
        by_type[artifact_type] = require_sha256(
            item.get("contentHash"),
            f"manifest artifact {index} contentHash",
        )

    expected_types = set(ARTIFACT_TYPE_BY_FILE.values())
    require(set(by_type) == expected_types, "campaign manifest artifact type set drift")
    for file_name, artifact_type in ARTIFACT_TYPE_BY_FILE.items():
        require(
            by_type[artifact_type] == identities[file_name],
            f"campaign manifest artifact root drift: {artifact_type}",
        )

    print(f"jsonschema={installed}")
    print("autopilot-production-campaign=VERIFIED")
    print(f"required-artifacts={len(REQUIRED_FILES)}")
    print(f"manifest-artifact-roots={len(by_type)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
