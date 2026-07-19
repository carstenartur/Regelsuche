#!/usr/bin/env python3
"""Validate generic and autonomous-production lifecycle handoff evidence."""

from __future__ import annotations

import hashlib
import json
import sys
from importlib.metadata import PackageNotFoundError, version
from pathlib import Path

try:
    from jsonschema.validators import validator_for
except ImportError as error:
    raise SystemExit("jsonschema is required; run ./gradlew prepareVerificationEnvironment") from error

DISCOVERY_ROOT = Path("regelsuche-discovery/build/reports/domain-lifecycle-handoff")
AUTOPILOT_ROOT = Path("regelsuche-autopilot/build/reports/domain-lifecycle-handoff")
EXPORT_ROOT = AUTOPILOT_ROOT / "production-generation-export"
SCHEMA_ROOT = Path("docs/schemas")
EXPECTED_JSONSCHEMA_VERSION = "4.25.1"
EXPECTED_EXPORT_FILES = {
    "brief-v2.json",
    "seeds.json",
    "observations.json",
    "generation-receipt.json",
    "discovery-report.json",
    "generation-run.json",
    "lifecycle-handoff.json",
}
FORBIDDEN_KEYS = {
    "payload",
    "expression",
    "seedExpression",
    "selectedExpression",
    "states",
    "path",
    "sequenceTerms",
}


def fail(message: str) -> None:
    raise SystemExit(f"domain lifecycle handoff invalid: {message}")


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def load(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"cannot read {path}: {error}")


def sha256(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def load_validator(path: Path):
    schema = load(path)
    validator_class = validator_for(schema)
    validator_class.check_schema(schema)
    require(schema.get("additionalProperties") is False, f"schema must fail closed: {path}")
    return validator_class(schema)


def nested_keys(value):
    if isinstance(value, dict):
        for key, nested in value.items():
            yield key
            yield from nested_keys(nested)
    elif isinstance(value, list):
        for nested in value:
            yield from nested_keys(nested)


def require_nonempty(path: Path) -> None:
    require(path.is_file(), f"missing file: {path}")
    require(path.stat().st_size > 0, f"empty file: {path}")


def main() -> int:
    try:
        installed = version("jsonschema")
    except PackageNotFoundError:
        fail("jsonschema is not installed")
    require(
        installed == EXPECTED_JSONSCHEMA_VERSION,
        f"jsonschema version drift: expected {EXPECTED_JSONSCHEMA_VERSION}, found {installed}",
    )

    expression_path = DISCOVERY_ROOT / "expression-handoff.json"
    sequence_path = DISCOVERY_ROOT / "sequence-handoff.json"
    production_path = AUTOPILOT_ROOT / "production-generation-handoff.json"
    for path in (expression_path, sequence_path, production_path):
        require_nonempty(path)
    require_nonempty(EXPORT_ROOT / "export-manifest.json")
    for file_name in EXPECTED_EXPORT_FILES:
        require_nonempty(EXPORT_ROOT / file_name)

    handoff_validator = load_validator(
        SCHEMA_ROOT / "regelsuche-discovery-lifecycle-handoff-v1.schema.json"
    )
    export_validator = load_validator(
        SCHEMA_ROOT / "regelsuche-autonomous-production-generation-export-v1.schema.json"
    )

    handoff_paths = (
        expression_path,
        sequence_path,
        production_path,
        EXPORT_ROOT / "lifecycle-handoff.json",
    )
    reports = [load(path) for path in handoff_paths]
    for path, report in zip(handoff_paths, reports):
        handoff_validator.validate(report)
        for status_name in (
            "proofStatus",
            "externalNoveltyStatus",
            "promotionStatus",
            "publicEvidenceStatus",
        ):
            require(report.get(status_name) == "NOT_EVALUATED", f"{path}: {status_name} drift")
        for resource in report.get("resources", []):
            require(
                resource.get("configured")
                == resource.get("executed", 0)
                + resource.get("skipped", 0)
                + resource.get("remaining", 0),
                f"{path}: unbalanced resource {resource.get('resource')}",
            )

    expression, sequence, production, exported = reports
    for name, report in (("expression", expression), ("sequence", sequence)):
        require(report.get("sourceKind") == "DOMAIN_DISCOVERY_EVIDENCE", f"{name}: source kind drift")
        require(report.get("stage") == "DISCOVERY_VALIDATION", f"{name}: stage drift")
        require(report.get("disposition") == "CONFIRMED", f"{name}: disposition drift")

    for name, report in (("production", production), ("exported", exported)):
        require(report.get("sourceKind") == "PRODUCTION_GENERATION_RUN", f"{name}: source kind drift")
        require(report.get("stage") == "GENERATION", f"{name}: stage drift")
        require(report.get("disposition") == "COMPLETED", f"{name}: disposition drift")
        require(report.get("selectedCandidateHash") == "", f"{name}: candidate hash must be empty")
        require(report.get("certificateHash") == "", f"{name}: certificate hash must be empty")
    require(production == exported, "production handoff differs from exported handoff")

    manifest_path = EXPORT_ROOT / "export-manifest.json"
    manifest = load(manifest_path)
    export_validator.validate(manifest)
    require(manifest.get("commitProtocol") == "MANIFEST_LAST_ATOMIC_RENAME", "export commit protocol drift")
    artifacts = manifest.get("artifacts", [])
    require(len(artifacts) == 7, "export artifact count drift")
    require(not list(EXPORT_ROOT.glob("*.tmp")), "temporary export files were retained")

    roles: dict[str, dict] = {}
    materials: list[str] = []
    retained_names: set[str] = set()
    for artifact in artifacts:
        role = artifact.get("role")
        file_name = artifact.get("fileName")
        require(role not in roles, f"duplicate export role: {role}")
        require(file_name not in retained_names, f"duplicate export file: {file_name}")
        roles[role] = artifact
        retained_names.add(file_name)
        path = (EXPORT_ROOT / file_name).resolve()
        require(path.parent == EXPORT_ROOT.resolve(), f"export path escapes root: {file_name}")
        data = path.read_bytes()
        require(len(data) == artifact.get("byteLength"), f"byte length drift: {file_name}")
        require(sha256(data) == artifact.get("byteHash"), f"byte hash drift: {file_name}")
        materials.append(
            f"{file_name}|{role}|{artifact.get('sourceContentHash')}|"
            f"{artifact.get('byteHash')}|{artifact.get('byteLength')}"
        )
    require(retained_names == EXPECTED_EXPORT_FILES, "export file set drift")

    generation_run = load(EXPORT_ROOT / "generation-run.json")
    lifecycle_handoff = load(EXPORT_ROOT / "lifecycle-handoff.json")
    require(generation_run.get("contentHash") == manifest.get("generationRunHash"), "generation-run hash mismatch")
    require(lifecycle_handoff.get("contentHash") == manifest.get("lifecycleHandoffHash"), "lifecycle-handoff hash mismatch")
    require(
        lifecycle_handoff.get("sourceEvidenceHash") == manifest.get("generationRunHash"),
        "handoff source-evidence hash mismatch",
    )
    require(
        roles.get("GENERATION_RUN", {}).get("sourceContentHash") == manifest.get("generationRunHash"),
        "GENERATION_RUN manifest binding mismatch",
    )
    require(
        roles.get("LIFECYCLE_HANDOFF", {}).get("sourceContentHash") == manifest.get("lifecycleHandoffHash"),
        "LIFECYCLE_HANDOFF manifest binding mismatch",
    )

    manifest_material = (
        manifest["schema"]
        + "\nexportId="
        + manifest["exportId"]
        + "\ngenerationRunHash="
        + manifest["generationRunHash"]
        + "\nlifecycleHandoffHash="
        + manifest["lifecycleHandoffHash"]
        + "\nartifacts=["
        + ", ".join(materials)
        + "]"
        + "\ncommitProtocol="
        + manifest["commitProtocol"]
    ).encode("utf-8")
    require(sha256(manifest_material) == manifest.get("contentHash"), "export manifest content hash drift")

    for path, report in zip(handoff_paths, reports):
        leaked = FORBIDDEN_KEYS & set(nested_keys(report))
        require(not leaked, f"{path}: representation payload leaked: {sorted(leaked)}")
    manifest_leaks = FORBIDDEN_KEYS & set(nested_keys(manifest))
    require(not manifest_leaks, f"manifest representation payload leaked: {sorted(manifest_leaks)}")

    print(f"jsonschema={installed}")
    print("domain-lifecycle-handoff-contract=valid")
    return 0


if __name__ == "__main__":
    sys.exit(main())
