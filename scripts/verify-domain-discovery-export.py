#!/usr/bin/env python3
"""Independently verify retained generic domain-discovery exports."""

from __future__ import annotations

import argparse
import copy
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
SCHEMAS = {
    "descriptor": "regelsuche-discovery-domain-descriptor-v1.schema.json",
    "evidence": "regelsuche-domain-discovery-evidence-v1.schema.json",
    "handoff": "regelsuche-discovery-lifecycle-handoff-v1.schema.json",
    "manifest": "regelsuche-domain-discovery-export-v1.schema.json",
    "verification": (
        "regelsuche-domain-discovery-export-verification-v1.schema.json"
    ),
}
EXPECTED_DOMAINS = {
    "expression": "expression-rewrite",
    "sequence": "integer-sequence-finite-difference",
}
ROLE_ORDER = [
    "DOMAIN_DESCRIPTOR",
    "DISCOVERY_EVIDENCE",
    "LIFECYCLE_HANDOFF",
]
FORBIDDEN_KEYS = {
    "payload",
    "canonicalState",
    "seedExpression",
    "selectedExpression",
    "sequenceTerms",
    "states",
    "path",
}


def fail(message: str) -> None:
    raise SystemExit(f"domain discovery export invalid: {message}")


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


def sha256(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def canonical_map(values: dict[str, str]) -> str:
    return "|".join(
        f"{len(key)}:{key}={len(value)}:{value}"
        for key, value in sorted(values.items())
    )


def canonical_list(values: list[str]) -> str:
    return "|".join(f"{len(value)}:{value}" for value in values)


def nested_keys(value: Any):
    if isinstance(value, dict):
        for key, nested in value.items():
            yield key
            yield from nested_keys(nested)
    elif isinstance(value, list):
        for nested in value:
            yield from nested_keys(nested)


def expect_schema_rejection(
    validator: Draft202012Validator,
    value: Any,
    label: str,
) -> None:
    try:
        validator.validate(value)
    except ValidationError:
        return
    fail(f"{label} unexpectedly passed schema validation")


def load_validators(schema_root: Path) -> dict[str, Draft202012Validator]:
    validators: dict[str, Draft202012Validator] = {}
    for name, file_name in SCHEMAS.items():
        schema_path = schema_root / file_name
        schema = load_unique(schema_path)
        require(isinstance(schema, dict), f"schema is not an object: {schema_path}")
        Draft202012Validator.check_schema(schema)
        require(
            schema.get("additionalProperties") is False,
            f"schema must fail closed: {schema_path}",
        )
        validators[name] = Draft202012Validator(schema)
    return validators


def validate_export(
    directory: Path,
    expected_domain: str,
    validators: dict[str, Draft202012Validator],
) -> tuple[dict[str, Path], dict[str, Any], dict[str, dict[str, Any]]]:
    paths = {
        "descriptor": directory / "domain.json",
        "evidence": directory / "evidence.json",
        "handoff": directory / "lifecycle-handoff.json",
        "manifest": directory / "export-manifest.json",
    }
    documents: dict[str, Any] = {}
    for name, path in paths.items():
        require(path.is_file(), f"missing {name} artifact: {path}")
        documents[name] = load_unique(path)
        validators[name].validate(documents[name])

    descriptor = documents["descriptor"]
    evidence = documents["evidence"]
    handoff = documents["handoff"]
    manifest = documents["manifest"]
    require(descriptor == evidence["domain"], f"descriptor/evidence drift: {directory}")
    require(descriptor["domainId"] == expected_domain, f"unexpected domain: {directory}")
    require(evidence["campaignId"] == manifest["campaignId"], "campaign binding drift")
    require(descriptor["domainId"] == manifest["domainId"], "domain binding drift")
    require(descriptor["revision"] == manifest["domainRevision"], "revision drift")
    require(
        descriptor["contentHash"] == manifest["domainDescriptorHash"],
        "descriptor root drift",
    )
    require(
        evidence["contentHash"] == manifest["discoveryEvidenceHash"],
        "evidence root drift",
    )
    require(
        handoff["contentHash"] == manifest["lifecycleHandoffHash"],
        "handoff root drift",
    )
    require(handoff["sourceKind"] == "DOMAIN_DISCOVERY_EVIDENCE", "source kind drift")
    require(handoff["stage"] == "DISCOVERY_VALIDATION", "handoff stage drift")
    require(handoff["campaignId"] == evidence["campaignId"], "handoff campaign drift")
    require(handoff["domainId"] == descriptor["domainId"], "handoff domain drift")
    require(
        handoff["domainRevision"] == descriptor["revision"],
        "handoff revision drift",
    )
    require(
        handoff["domainContractHash"] == descriptor["contentHash"],
        "handoff contract drift",
    )
    require(handoff["inputHash"] == evidence["seed"]["contentHash"], "input root drift")
    require(
        handoff["sourceEvidenceHash"] == evidence["contentHash"],
        "source evidence drift",
    )
    for report in (evidence, handoff):
        for status in (
            "proofStatus",
            "externalNoveltyStatus",
            "promotionStatus",
            "publicEvidenceStatus",
        ):
            require(report[status] == "NOT_EVALUATED", f"inflated {status}")

    artifacts = manifest["artifacts"]
    require(
        [item["fileName"] for item in artifacts]
        == sorted(item["fileName"] for item in artifacts),
        "manifest artifacts are not canonically ordered",
    )
    by_role = {item["role"]: item for item in artifacts}
    require(set(by_role) == set(ROLE_ORDER), "manifest role set is incomplete")
    expected = {
        "DOMAIN_DESCRIPTOR": (paths["descriptor"], descriptor["contentHash"]),
        "DISCOVERY_EVIDENCE": (paths["evidence"], evidence["contentHash"]),
        "LIFECYCLE_HANDOFF": (paths["handoff"], handoff["contentHash"]),
    }
    for role, (path, source_hash) in expected.items():
        artifact = by_role[role]
        retained = path.read_bytes()
        require(artifact["fileName"] == path.name, f"file name drift for {role}")
        require(artifact["sourceContentHash"] == source_hash, f"source hash drift for {role}")
        require(artifact["byteHash"] == sha256(retained), f"byte hash drift for {role}")
        require(artifact["byteLength"] == len(retained), f"byte length drift for {role}")

    artifact_material = "[" + ", ".join(
        f"{item['fileName']}|{item['role']}|{item['sourceContentHash']}|"
        f"{item['byteHash']}|{item['byteLength']}"
        for item in artifacts
    ) + "]"
    manifest_material = (
        "regelsuche.domain-discovery-export/v1"
        + "\ncampaignId=" + manifest["campaignId"]
        + "\ndomainId=" + manifest["domainId"]
        + "\ndomainRevision=" + manifest["domainRevision"]
        + "\ndomainDescriptorHash=" + manifest["domainDescriptorHash"]
        + "\ndiscoveryEvidenceHash=" + manifest["discoveryEvidenceHash"]
        + "\nlifecycleHandoffHash=" + manifest["lifecycleHandoffHash"]
        + "\nartifacts=" + artifact_material
        + "\ncommitProtocol=" + manifest["commitProtocol"]
    ).encode("utf-8")
    require(manifest["contentHash"] == sha256(manifest_material), "manifest hash drift")
    require(
        manifest["commitProtocol"] == "MANIFEST_LAST_ATOMIC_RENAME",
        "commit protocol drift",
    )
    require(FORBIDDEN_KEYS.isdisjoint(set(nested_keys(handoff))), "handoff leaks representation")
    require(FORBIDDEN_KEYS.isdisjoint(set(nested_keys(manifest))), "manifest leaks representation")

    invalid_manifest = copy.deepcopy(manifest)
    invalid_manifest["artifacts"][0] = copy.deepcopy(invalid_manifest["artifacts"][1])
    expect_schema_rejection(
        validators["manifest"],
        invalid_manifest,
        "manifest with duplicate/missing role",
    )
    invalid_handoff = copy.deepcopy(handoff)
    invalid_handoff["payload"] = "representation leak"
    expect_schema_rejection(
        validators["handoff"],
        invalid_handoff,
        "handoff with representation payload",
    )
    return paths, manifest, by_role


def verify_receipt(
    path: Path,
    paths: dict[str, Path],
    manifest: dict[str, Any],
    by_role: dict[str, dict[str, Any]],
    validator: Draft202012Validator,
) -> None:
    require(path.is_file(), f"missing verification receipt: {path}")
    receipt = load_unique(path)
    validator.validate(receipt)
    require(receipt["manifestContentHash"] == manifest["contentHash"], "receipt manifest drift")
    require(receipt["manifestByteHash"] == sha256(paths["manifest"].read_bytes()), "receipt byte drift")
    for key in (
        "campaignId",
        "domainId",
        "domainRevision",
        "domainDescriptorHash",
        "discoveryEvidenceHash",
        "lifecycleHandoffHash",
    ):
        require(receipt[key] == manifest[key], f"receipt {key} drift")
    snapshot_material = [
        f"{role}|{by_role[role]['byteHash']}|{by_role[role]['byteLength']}"
        for role in ROLE_ORDER
    ]
    require(
        receipt["artifactSetHash"]
        == sha256(canonical_list(snapshot_material).encode("utf-8")),
        "receipt artifact-set drift",
    )
    require(receipt["verifiedArtifactCount"] == 3, "receipt artifact count drift")
    require(receipt["identityBindingStatus"] == "VERIFIED", "identity not verified")
    require(
        receipt["mathematicalValidationStatus"] == "NOT_EVALUATED",
        "mathematical validation boundary inflated",
    )
    values = {
        key: str(value).lower() if isinstance(value, bool) else str(value)
        for key, value in receipt.items()
        if key != "contentHash"
    }
    require(
        receipt["contentHash"] == sha256(canonical_map(values).encode("utf-8")),
        "verification receipt hash drift",
    )
    require(FORBIDDEN_KEYS.isdisjoint(set(nested_keys(receipt))), "receipt leaks representation")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository-root", type=Path, default=Path("."))
    arguments = parser.parse_args()
    root = arguments.repository_root.resolve()

    try:
        installed = version("jsonschema")
    except PackageNotFoundError:
        fail("jsonschema is not installed")
    require(
        installed == EXPECTED_JSONSCHEMA_VERSION,
        f"jsonschema version drift: expected {EXPECTED_JSONSCHEMA_VERSION}, found {installed}",
    )

    validators = load_validators(root / "docs" / "schemas")
    writer_root = root / "regelsuche-discovery/build/reports/domain-discovery-export"
    verification_root = (
        root
        / "regelsuche-discovery/build/reports/domain-discovery-export-verification"
    )
    for directory_name, expected_domain in EXPECTED_DOMAINS.items():
        validate_export(writer_root / directory_name, expected_domain, validators)
        paths, manifest, by_role = validate_export(
            verification_root / directory_name,
            expected_domain,
            validators,
        )
        verify_receipt(
            verification_root / directory_name / "verification.json",
            paths,
            manifest,
            by_role,
            validators["verification"],
        )

    print(f"jsonschema={installed}")
    print("domain-discovery-export=VERIFIED")
    print("domains=expression-rewrite,integer-sequence-finite-difference")
    return 0


if __name__ == "__main__":
    sys.exit(main())
