#!/usr/bin/env python3
"""Verify and normalize the checkout-owned CycloneDX dependency inventory."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any

POLICY_SCHEMA = "regelsuche.supply-chain-policy/v1"
EVIDENCE_SCHEMA = "regelsuche.supply-chain-evidence/v1"
INVENTORY_SCHEMA = "regelsuche.dependency-inventory/v1"
EXPECTED_FORMAT = "CycloneDX"
SUPPORTED_SPEC_VERSIONS = {"1.6"}
EXPECTED_VULNERABILITY_STATUS = "DEFERRED_UNTIL_CONTENT_ADDRESSED_DATABASE"


class VerificationError(RuntimeError):
    pass


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise VerificationError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _lexical_absolute(path: Path, label: str) -> Path:
    candidate = path if path.is_absolute() else Path.cwd() / path
    if ".." in candidate.parts:
        raise VerificationError(f"{label} must not contain '..': {path}")
    return candidate


def _repository_roots(path: Path) -> tuple[Path, Path]:
    lexical = _lexical_absolute(path, "repository root")
    if lexical.is_symlink():
        raise VerificationError(f"repository root must not be symbolic: {path}")
    if not lexical.is_dir():
        raise VerificationError(f"repository root must be a directory: {path}")
    try:
        return lexical, lexical.resolve(strict=True)
    except OSError as exc:
        raise VerificationError(f"cannot resolve repository root {path}: {exc}") from exc


def _checkout_path(
    repository_lexical: Path,
    repository_resolved: Path,
    path: Path,
    label: str,
) -> Path:
    absolute = _lexical_absolute(path, label)
    base: Path | None = None
    relative: Path | None = None
    for candidate_base in (repository_lexical, repository_resolved):
        try:
            relative = absolute.relative_to(candidate_base)
            base = candidate_base
            break
        except ValueError:
            continue
    if base is None or relative is None or not relative.parts:
        raise VerificationError(f"{label} must be inside the repository root: {path}")

    current = base
    for component in relative.parts:
        current = current / component
        if current.is_symlink():
            raise VerificationError(
                f"{label} contains a symbolic path component: {current}"
            )
    return absolute


def _require_inside_repository(
    repository_root: Path,
    resolved: Path,
    label: str,
) -> None:
    if repository_root not in resolved.parents:
        raise VerificationError(
            f"{label} resolves outside the repository root: {resolved}"
        )


def require_regular_checkout_file(
    repository_lexical: Path,
    repository_resolved: Path,
    path: Path,
    label: str,
) -> Path:
    absolute = _checkout_path(
        repository_lexical, repository_resolved, path, label
    )
    if not absolute.is_file():
        raise VerificationError(f"{label} must be a regular file: {path}")
    try:
        resolved = absolute.resolve(strict=True)
    except OSError as exc:
        raise VerificationError(f"cannot resolve {label} {path}: {exc}") from exc
    _require_inside_repository(repository_resolved, resolved, label)
    return resolved


def prepare_checkout_output_directory(
    repository_lexical: Path,
    repository_resolved: Path,
    path: Path,
) -> Path:
    label = "output directory"
    absolute = _checkout_path(
        repository_lexical, repository_resolved, path, label
    )
    if absolute.exists() and not absolute.is_dir():
        raise VerificationError(f"{label} must be a directory: {path}")
    try:
        absolute.mkdir(parents=True, exist_ok=True)
    except OSError as exc:
        raise VerificationError(f"cannot create {label} {path}: {exc}") from exc
    absolute = _checkout_path(
        repository_lexical, repository_resolved, absolute, label
    )
    if not absolute.is_dir():
        raise VerificationError(f"{label} must be a directory: {path}")
    try:
        resolved = absolute.resolve(strict=True)
    except OSError as exc:
        raise VerificationError(f"cannot resolve {label} {path}: {exc}") from exc
    _require_inside_repository(repository_resolved, resolved, label)
    return resolved


def _require_writable_output_file(path: Path) -> None:
    if path.is_symlink():
        raise VerificationError(f"output file must not be symbolic: {path}")
    if path.exists() and not path.is_file():
        raise VerificationError(f"output path must be a regular file: {path}")


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(
            path.read_text(encoding="utf-8"),
            object_pairs_hook=_reject_duplicate_keys,
        )
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise VerificationError(f"cannot read strict JSON {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise VerificationError(f"top-level JSON value must be an object: {path}")
    return value


def canonical_bytes(value: Any) -> bytes:
    return (
        json.dumps(
            value,
            sort_keys=True,
            separators=(",", ":"),
            ensure_ascii=False,
        )
        + "\n"
    ).encode("utf-8")


def sha256_bytes(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def write_json(path: Path, value: Any) -> None:
    _require_writable_output_file(path)
    try:
        path.write_bytes(canonical_bytes(value))
    except OSError as exc:
        raise VerificationError(f"cannot write output file {path}: {exc}") from exc


def write_text(path: Path, value: str) -> None:
    _require_writable_output_file(path)
    try:
        path.write_text(value, encoding="utf-8")
    except OSError as exc:
        raise VerificationError(f"cannot write output file {path}: {exc}") from exc


def require_string(value: Any, name: str, *, allow_blank: bool = False) -> str:
    if not isinstance(value, str):
        raise VerificationError(f"{name} must be a string")
    if not allow_blank and not value.strip():
        raise VerificationError(f"{name} must not be blank")
    return value


def normalize_license(entry: Any) -> dict[str, str]:
    if not isinstance(entry, dict):
        raise VerificationError("component license entry must be an object")
    payload = entry.get("license") if "license" in entry else entry.get("expression")
    if isinstance(payload, str):
        return {"expression": payload}
    if not isinstance(payload, dict):
        raise VerificationError("component license must contain license or expression")
    result: dict[str, str] = {}
    for key in ("id", "name", "url"):
        value = payload.get(key)
        if value is not None:
            result[key] = require_string(value, f"license.{key}")
    if not result:
        raise VerificationError("component license contains no stable identity")
    return result


def normalize_hash(entry: Any) -> dict[str, str]:
    if not isinstance(entry, dict):
        raise VerificationError("component hash entry must be an object")
    algorithm = require_string(entry.get("alg"), "hash.alg")
    content = require_string(entry.get("content"), "hash.content")
    return {"algorithm": algorithm, "content": content.lower()}


def normalize_component(component: Any) -> dict[str, Any]:
    if not isinstance(component, dict):
        raise VerificationError("CycloneDX component must be an object")
    bom_ref = require_string(component.get("bom-ref"), "component.bom-ref")
    name = require_string(component.get("name"), f"component[{bom_ref}].name")
    version = require_string(component.get("version"), f"component[{bom_ref}].version")
    component_type = require_string(component.get("type"), f"component[{bom_ref}].type")
    group = component.get("group", "")
    if group is None:
        group = ""
    group = require_string(group, f"component[{bom_ref}].group", allow_blank=True)
    purl = component.get("purl", "")
    if purl is None:
        purl = ""
    purl = require_string(purl, f"component[{bom_ref}].purl", allow_blank=True)
    scope = component.get("scope", "")
    if scope is None:
        scope = ""
    scope = require_string(scope, f"component[{bom_ref}].scope", allow_blank=True)

    licenses = [normalize_license(entry) for entry in component.get("licenses", [])]
    licenses.sort(key=lambda entry: json.dumps(entry, sort_keys=True, ensure_ascii=False))
    hashes = [normalize_hash(entry) for entry in component.get("hashes", [])]
    hashes.sort(key=lambda entry: (entry["algorithm"], entry["content"]))

    return {
        "bomRef": bom_ref,
        "type": component_type,
        "group": group,
        "name": name,
        "version": version,
        "purl": purl,
        "scope": scope,
        "licenses": licenses,
        "hashes": hashes,
    }


def normalize_dependencies(entries: Any) -> list[dict[str, Any]]:
    if not isinstance(entries, list):
        raise VerificationError("CycloneDX dependencies must be an array")
    normalized: list[dict[str, Any]] = []
    seen: set[str] = set()
    for entry in entries:
        if not isinstance(entry, dict):
            raise VerificationError("CycloneDX dependency entry must be an object")
        ref = require_string(entry.get("ref"), "dependency.ref")
        if ref in seen:
            raise VerificationError(f"duplicate dependency ref: {ref}")
        seen.add(ref)
        depends_on = entry.get("dependsOn", [])
        if not isinstance(depends_on, list) or not all(
            isinstance(item, str) for item in depends_on
        ):
            raise VerificationError(
                f"dependency[{ref}].dependsOn must be a string array"
            )
        if len(depends_on) != len(set(depends_on)):
            raise VerificationError(f"dependency[{ref}] contains duplicate edges")
        normalized.append({"ref": ref, "dependsOn": sorted(depends_on)})
    normalized.sort(key=lambda entry: entry["ref"])
    return normalized


def validate_policy(policy: dict[str, Any]) -> None:
    if policy.get("schema") != POLICY_SCHEMA:
        raise VerificationError(
            f"unsupported supply-chain policy schema: {policy.get('schema')}"
        )
    inventory = policy.get("inventory")
    if not isinstance(inventory, dict):
        raise VerificationError("policy.inventory must be an object")
    if inventory.get("format") != EXPECTED_FORMAT:
        raise VerificationError("policy inventory format must be CycloneDX")
    if inventory.get("specVersion") not in SUPPORTED_SPEC_VERSIONS:
        raise VerificationError("policy inventory specVersion is unsupported")
    if inventory.get("includeBomSerialNumber") is not False:
        raise VerificationError(
            "policy must disable the non-deterministic BOM serial number"
        )
    if inventory.get("includeBuildSystem") is not False:
        raise VerificationError(
            "policy must disable environment-specific build-system references"
        )
    if inventory.get("rawTimestampTreatment") != "EXCLUDED_FROM_SEMANTIC_IDENTITY":
        raise VerificationError("policy must define timestamp identity treatment")

    vulnerability = policy.get("vulnerabilityPolicy")
    if not isinstance(vulnerability, dict):
        raise VerificationError("policy.vulnerabilityPolicy must be an object")
    if vulnerability.get("status") != EXPECTED_VULNERABILITY_STATUS:
        raise VerificationError(
            "vulnerability scan must remain explicitly deferred in this tranche"
        )
    threshold = vulnerability.get("failOnCvssAtOrAbove")
    if not isinstance(threshold, (int, float)) or not 0.0 <= float(threshold) <= 10.0:
        raise VerificationError("failOnCvssAtOrAbove must be within 0..10")
    if vulnerability.get("unknownSeverity") != "FAIL_CLOSED":
        raise VerificationError("unknown vulnerability severity must fail closed")
    if vulnerability.get("scannerFailure") != "FAIL_CLOSED":
        raise VerificationError("scanner failure must fail closed")
    required = vulnerability.get("requiredDatabaseProperties")
    if not isinstance(required, list) or not required or not all(
        isinstance(item, str) for item in required
    ):
        raise VerificationError(
            "requiredDatabaseProperties must be a non-empty string array"
        )


def verify_and_render(
    repository_root: Path,
    bom_path: Path,
    output_directory: Path,
) -> None:
    repository_lexical, repository_resolved = _repository_roots(repository_root)
    policy_path = require_regular_checkout_file(
        repository_lexical,
        repository_resolved,
        repository_lexical / "config" / "quality" / "supply-chain-policy.json",
        "supply-chain policy",
    )
    bom_path = require_regular_checkout_file(
        repository_lexical,
        repository_resolved,
        bom_path,
        "CycloneDX BOM",
    )
    output_directory = prepare_checkout_output_directory(
        repository_lexical,
        repository_resolved,
        output_directory,
    )
    policy = load_json(policy_path)
    validate_policy(policy)
    bom = load_json(bom_path)

    if bom.get("bomFormat") != EXPECTED_FORMAT:
        raise VerificationError(f"unexpected BOM format: {bom.get('bomFormat')}")
    spec_version = require_string(bom.get("specVersion"), "bom.specVersion")
    if spec_version != policy["inventory"]["specVersion"]:
        raise VerificationError(
            f"BOM specVersion {spec_version} does not match policy "
            f"{policy['inventory']['specVersion']}"
        )
    if "serialNumber" in bom:
        raise VerificationError(
            "BOM serialNumber is forbidden by the deterministic policy"
        )
    if not isinstance(bom.get("version"), int) or bom["version"] < 1:
        raise VerificationError("BOM version must be a positive integer")

    components_value = bom.get("components", [])
    if not isinstance(components_value, list):
        raise VerificationError("CycloneDX components must be an array")
    components = [normalize_component(component) for component in components_value]
    components.sort(key=lambda component: (component["purl"], component["bomRef"]))

    identities: set[str] = set()
    for component in components:
        identity = component["purl"] or component["bomRef"]
        if identity in identities:
            raise VerificationError(f"duplicate component identity: {identity}")
        identities.add(identity)

    dependencies = normalize_dependencies(bom.get("dependencies", []))
    known_refs = {component["bomRef"] for component in components}
    metadata = bom.get("metadata")
    if isinstance(metadata, dict) and isinstance(metadata.get("component"), dict):
        root_component = normalize_component(metadata["component"])
        known_refs.add(root_component["bomRef"])
    else:
        root_component = None

    for dependency in dependencies:
        if dependency["ref"] not in known_refs:
            raise VerificationError(
                f"dependency graph references unknown source: {dependency['ref']}"
            )
        missing = [
            ref for ref in dependency["dependsOn"] if ref not in known_refs
        ]
        if missing:
            raise VerificationError(
                f"dependency graph source {dependency['ref']} references "
                f"unknown targets: {missing}"
            )

    inventory = {
        "schema": INVENTORY_SCHEMA,
        "format": EXPECTED_FORMAT,
        "specVersion": spec_version,
        "rootComponent": root_component,
        "components": components,
        "dependencies": dependencies,
    }
    inventory_bytes = canonical_bytes(inventory)
    policy_bytes = canonical_bytes(policy)
    evidence = {
        "schema": EVIDENCE_SCHEMA,
        "policyHash": sha256_bytes(policy_bytes),
        "inventoryHash": sha256_bytes(inventory_bytes),
        "componentCount": len(components),
        "dependencyNodeCount": len(dependencies),
        "dependencyEdgeCount": sum(
            len(entry["dependsOn"]) for entry in dependencies
        ),
        "rawBomTimestampPresent": bool(
            isinstance(metadata, dict) and metadata.get("timestamp")
        ),
        "rawBomTimestampSemantic": False,
        "vulnerabilityDatabaseStatus": "NOT_BOUND",
        "vulnerabilityScanStatus": "NOT_EVALUATED",
        "vulnerabilityPolicyStatus": EXPECTED_VULNERABILITY_STATUS,
        "claimBoundary": require_string(
            policy.get("claimBoundary"), "policy.claimBoundary"
        ),
    }

    write_json(output_directory / "dependency-inventory.json", inventory)
    write_json(output_directory / "supply-chain-evidence.json", evidence)
    markdown = "\n".join(
        [
            "# Supply-chain evidence",
            "",
            f"- Components: **{evidence['componentCount']}**",
            f"- Dependency graph nodes: **{evidence['dependencyNodeCount']}**",
            f"- Dependency graph edges: **{evidence['dependencyEdgeCount']}**",
            f"- Inventory hash: `{evidence['inventoryHash']}`",
            f"- Policy hash: `{evidence['policyHash']}`",
            "- Vulnerability database: **NOT BOUND**",
            "- Vulnerability scan: **NOT EVALUATED**",
            "",
            "> This tranche establishes deterministic resolved-dependency "
            "inventory only. It does not claim that dependencies are "
            "vulnerability-free.",
            "",
        ]
    )
    write_text(output_directory / "supply-chain-evidence.md", markdown)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository-root", type=Path, required=True)
    parser.add_argument("--bom", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        verify_and_render(
            args.repository_root,
            args.bom,
            args.output,
        )
    except VerificationError as exc:
        parser.error(str(exc))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
