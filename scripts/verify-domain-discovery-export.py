#!/usr/bin/env python3
"""Validate retained domain-discovery export bytes, identities and receipts."""

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
DOCUMENTATION = Path("docs/domain-discovery-export-verification.md")
REQUIRED_DOCUMENTATION_HEADINGS = (
    "## Trust Boundary und Sicherheitsmodell",
    "## Consumer-Prüfverfahren",
)
EXPECTED_ARTIFACT_ROLES = {
    "DOMAIN_DESCRIPTOR",
    "DISCOVERY_EVIDENCE",
    "LIFECYCLE_HANDOFF",
}
EXPECTED_FILES = {
    "domain.json",
    "evidence.json",
    "lifecycle-handoff.json",
    "export-manifest.json",
    "export-verification.json",
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
    raise SystemExit(f"domain discovery export invalid: {message}")


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def load(path: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"cannot read {path}: {error}")
    require(isinstance(value, dict), f"{path} must contain an object")
    return value


def sha256(value: bytes) -> str:
    return "sha256:" + hashlib.sha256(value).hexdigest()


def java_bool(value: bool) -> str:
    return "true" if value else "false"


def load_validator(file_name: str) -> Draft202012Validator:
    path = SCHEMA_ROOT / file_name
    schema = load(path)
    Draft202012Validator.check_schema(schema)
    require(schema.get("additionalProperties") is False,
            f"schema must fail closed: {path}")
    return Draft202012Validator(schema)


def nested_keys(value):
    if isinstance(value, dict):
        for key, nested in value.items():
            yield key
            yield from nested_keys(nested)
    elif isinstance(value, list):
        for nested in value:
            yield from nested_keys(nested)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(
            "regelsuche-discovery/build/reports/domain-discovery-export/export"
        ),
    )
    parser.add_argument(
        "--receipt",
        type=Path,
        default=Path(
            "regelsuche-discovery/build/reports/domain-discovery-export/"
            "export-verification.json"
        ),
    )
    arguments = parser.parse_args()

    try:
        installed = version("jsonschema")
    except PackageNotFoundError:
        fail("jsonschema is not installed")
    require(
        installed == EXPECTED_JSONSCHEMA_VERSION,
        f"jsonschema version drift: expected {EXPECTED_JSONSCHEMA_VERSION}, "
        f"found {installed}",
    )

    documentation = DOCUMENTATION.read_text(encoding="utf-8")
    for heading in REQUIRED_DOCUMENTATION_HEADINGS:
        require(heading in documentation,
                f"documentation is missing normative section: {heading}")

    validators = {
        "domain": load_validator(
            "regelsuche-discovery-domain-descriptor-v1.schema.json"
        ),
        "evidence": load_validator(
            "regelsuche-domain-discovery-evidence-v1.schema.json"
        ),
        "handoff": load_validator(
            "regelsuche-discovery-lifecycle-handoff-v1.schema.json"
        ),
        "export": load_validator(
            "regelsuche-domain-discovery-export-v1.schema.json"
        ),
        "verification": load_validator(
            "regelsuche-domain-discovery-export-verification-v1.schema.json"
        ),
    }

    root = arguments.root
    receipt_path = arguments.receipt
    require(root.is_dir(), f"missing export root: {root}")
    for file_name in EXPECTED_FILES - {"export-verification.json"}:
        path = root / file_name
        require(path.is_file() and path.stat().st_size > 0,
                f"missing or empty export artifact: {path}")
    require(receipt_path.is_file() and receipt_path.stat().st_size > 0,
            f"missing or empty verification receipt: {receipt_path}")
    require(not list(root.glob("*.tmp")), "temporary export files were retained")

    manifest_path = root / "export-manifest.json"
    manifest_bytes = manifest_path.read_bytes()
    manifest = json.loads(manifest_bytes)
    receipt = load(receipt_path)
    domain = load(root / "domain.json")
    evidence = load(root / "evidence.json")
    handoff = load(root / "lifecycle-handoff.json")

    validators["export"].validate(manifest)
    validators["verification"].validate(receipt)
    validators["domain"].validate(domain)
    validators["evidence"].validate(evidence)
    validators["handoff"].validate(handoff)

    require(manifest["schema"] == "regelsuche.domain-discovery-export/v1",
            "manifest schema drift")
    require(manifest["commitProtocol"] == "MANIFEST_LAST_ATOMIC_RENAME",
            "manifest commit protocol drift")
    require(
        manifest["domainId"] == domain["domainId"] == evidence["domain"]["domainId"],
        "domain identity mismatch",
    )
    require(
        manifest["domainRevision"]
        == domain["domainRevision"]
        == evidence["domain"]["domainRevision"],
        "domain revision mismatch",
    )
    require(manifest["domainDescriptorHash"] == domain["contentHash"],
            "descriptor hash mismatch")
    require(manifest["discoveryEvidenceHash"] == evidence["contentHash"],
            "evidence hash mismatch")
    require(manifest["lifecycleHandoffHash"] == handoff["contentHash"],
            "handoff hash mismatch")
    require(manifest["campaignId"] == evidence["campaignId"],
            "campaign identity mismatch")
    require(manifest["seedId"] == evidence["seed"]["seedId"],
            "seed identity mismatch")
    require(
        handoff["sourceEvidenceHash"] == evidence["contentHash"],
        "handoff source-evidence binding mismatch",
    )
    require(manifest["proofStatus"] == "NOT_EVALUATED",
            "manifest proof status drift")
    require(manifest["externalNoveltyStatus"] == "NOT_EVALUATED",
            "manifest novelty status drift")
    require(manifest["promotionStatus"] == "NOT_EVALUATED",
            "manifest promotion status drift")
    require(manifest["publicEvidenceStatus"] == "NOT_EVALUATED",
            "manifest public-evidence status drift")

    artifacts = manifest["artifacts"]
    require(len(artifacts) == 3, "artifact count drift")
    by_role: dict[str, dict] = {}
    file_names: set[str] = set()
    materials: list[str] = []
    artifact_snapshot_lines: list[str] = []
    total_bytes = 0
    for artifact in artifacts:
        role = artifact["role"]
        file_name = artifact["fileName"]
        require(role not in by_role, f"duplicate artifact role: {role}")
        require(file_name not in file_names,
                f"duplicate artifact file name: {file_name}")
        by_role[role] = artifact
        file_names.add(file_name)
        path = (root / file_name).resolve()
        require(path.parent == root.resolve(),
                f"artifact escapes export root: {file_name}")
        require(not path.is_symlink(), f"artifact is a symbolic link: {file_name}")
        data = path.read_bytes()
        require(len(data) == artifact["byteLength"],
                f"byte length mismatch: {file_name}")
        require(sha256(data) == artifact["byteHash"],
                f"byte hash mismatch: {file_name}")
        total_bytes += len(data)
        materials.append(
            file_name
            + "|"
            + role
            + "|"
            + artifact["sourceContentHash"]
            + "|"
            + artifact["byteHash"]
            + "|"
            + str(artifact["byteLength"])
        )
        artifact_snapshot_lines.append(
            file_name
            + "|"
            + artifact["byteHash"]
            + "|"
            + str(artifact["byteLength"])
        )

    require(set(by_role) == EXPECTED_ARTIFACT_ROLES,
            "artifact role set drift")
    require(file_names == {"domain.json", "evidence.json", "lifecycle-handoff.json"},
            "artifact file set drift")
    require(by_role["DOMAIN_DESCRIPTOR"]["sourceContentHash"]
            == domain["contentHash"], "descriptor role binding mismatch")
    require(by_role["DISCOVERY_EVIDENCE"]["sourceContentHash"]
            == evidence["contentHash"], "evidence role binding mismatch")
    require(by_role["LIFECYCLE_HANDOFF"]["sourceContentHash"]
            == handoff["contentHash"], "handoff role binding mismatch")

    manifest_material = (
        manifest["schema"]
        + "\nexportId="
        + manifest["exportId"]
        + "\ncampaignId="
        + manifest["campaignId"]
        + "\ndomainId="
        + manifest["domainId"]
        + "\ndomainRevision="
        + manifest["domainRevision"]
        + "\nseedId="
        + manifest["seedId"]
        + "\ndomainDescriptorHash="
        + manifest["domainDescriptorHash"]
        + "\ndiscoveryEvidenceHash="
        + manifest["discoveryEvidenceHash"]
        + "\nlifecycleHandoffHash="
        + manifest["lifecycleHandoffHash"]
        + "\nartifacts=["
        + ", ".join(materials)
        + "]"
        + "\ncommitProtocol="
        + manifest["commitProtocol"]
        + "\nproofStatus="
        + manifest["proofStatus"]
        + "\nexternalNoveltyStatus="
        + manifest["externalNoveltyStatus"]
        + "\npromotionStatus="
        + manifest["promotionStatus"]
        + "\npublicEvidenceStatus="
        + manifest["publicEvidenceStatus"]
    ).encode("utf-8")
    require(sha256(manifest_material) == manifest["contentHash"],
            "manifest content hash mismatch")

    snapshot_material = (
        receipt["schema"]
        + "\nmanifestByteHash="
        + sha256(manifest_bytes)
        + "\nartifacts=["
        + ", ".join(sorted(artifact_snapshot_lines))
        + "]"
    ).encode("utf-8")
    expected_snapshot_hash = sha256(snapshot_material)
    require(receipt["manifestContentHash"] == manifest["contentHash"],
            "receipt manifest-content binding mismatch")
    require(receipt["verifiedArtifactCount"] == len(artifacts),
            "receipt artifact count mismatch")
    require(receipt["verifiedByteLength"] == total_bytes,
            "receipt byte length mismatch")
    require(receipt["verifiedBytesSnapshotHash"] == expected_snapshot_hash,
            "receipt byte snapshot mismatch")
    require(receipt["identityBindingStatus"] == "VERIFIED",
            "receipt identity status drift")
    require(receipt["mathematicalValidationStatus"] == "NOT_EVALUATED",
            "receipt mathematical-validation status drift")
    require(receipt["proofStatus"] == "NOT_EVALUATED",
            "receipt proof status drift")
    require(receipt["externalNoveltyStatus"] == "NOT_EVALUATED",
            "receipt novelty status drift")
    require(receipt["promotionStatus"] == "NOT_EVALUATED",
            "receipt promotion status drift")
    require(receipt["publicEvidenceStatus"] == "NOT_EVALUATED",
            "receipt public-evidence status drift")

    receipt_material = (
        receipt["schema"]
        + "\nexportId="
        + receipt["exportId"]
        + "\ncampaignId="
        + receipt["campaignId"]
        + "\ndomainId="
        + receipt["domainId"]
        + "\ndomainRevision="
        + receipt["domainRevision"]
        + "\nseedId="
        + receipt["seedId"]
        + "\nmanifestContentHash="
        + receipt["manifestContentHash"]
        + "\nverifiedBytesSnapshotHash="
        + receipt["verifiedBytesSnapshotHash"]
        + "\nverifiedArtifactCount="
        + str(receipt["verifiedArtifactCount"])
        + "\nverifiedByteLength="
        + str(receipt["verifiedByteLength"])
        + "\nsymlinkAncestryChecked="
        + java_bool(receipt["symlinkAncestryChecked"])
        + "\nidentityBindingStatus="
        + receipt["identityBindingStatus"]
        + "\nmathematicalValidationStatus="
        + receipt["mathematicalValidationStatus"]
        + "\nproofStatus="
        + receipt["proofStatus"]
        + "\nexternalNoveltyStatus="
        + receipt["externalNoveltyStatus"]
        + "\npromotionStatus="
        + receipt["promotionStatus"]
        + "\npublicEvidenceStatus="
        + receipt["publicEvidenceStatus"]
    ).encode("utf-8")
    require(sha256(receipt_material) == receipt["contentHash"],
            "receipt content hash mismatch")

    require(FORBIDDEN_KEYS.isdisjoint(set(nested_keys(manifest))),
            "representation payload leaked into export manifest")
    require(FORBIDDEN_KEYS.isdisjoint(set(nested_keys(receipt))),
            "representation payload leaked into verification receipt")

    root_entries = {path.name for path in root.iterdir()}
    require(root_entries == EXPECTED_FILES - {"export-verification.json"},
            f"unexpected direct export entries: {sorted(root_entries)}")

    print(f"jsonschema={installed}")
    print(f"domain-discovery-export={manifest['contentHash']}")
    print(f"domain-discovery-export-verification={receipt['contentHash']}")
    print("domain-discovery-export-contract=valid")
    return 0


if __name__ == "__main__":
    sys.exit(main())
