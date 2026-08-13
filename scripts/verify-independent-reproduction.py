#!/usr/bin/env python3
"""Verify a frozen reproduction artifact and emit canonical reproduction receipts."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path, PurePosixPath
import platform
import re
import subprocess
import sys
import tarfile
from typing import Any, Iterable


MANIFEST_SCHEMA = "regelsuche.independent-reproduction-artifact/v1"
RECEIPT_SCHEMA = "regelsuche.independent-reproduction-receipt/v1"
CLAIM_POLICY = "REPRODUCTION_DOES_NOT_IMPLY_EXTERNAL_NOVELTY"
BASE_IMAGE = (
    "eclipse-temurin:21.0.11_10-jdk-noble@"
    "sha256:35685c7e23352983a48882d97cd9875f5284c228db71d1e2476e5e6c1bab1080"
)
BASE_IMAGE_INDEX_DIGEST = (
    "sha256:35685c7e23352983a48882d97cd9875f5284c228db71d1e2476e5e6c1bab1080"
)
GRADLE_DISTRIBUTION = "gradle-9.7.0-bin.zip"
GRADLE_DISTRIBUTION_SHA256 = (
    "84fbba45c7f4c64abc77460e1c00f541e9f960e3c7ed2538f1ede19eacd873ae"
)
SHA_RE = re.compile(r"^sha256:[0-9a-f]{64}$")
REVISION_RE = re.compile(r"^[0-9a-f]{40}$")
IDENTIFIER_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/-]{1,191}$")


def canonical_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")


def sha256_bytes(value: bytes) -> str:
    return "sha256:" + hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return "sha256:" + digest.hexdigest()


def load_unique(path: Path) -> Any:
    def hook(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"duplicate JSON field {key!r} in {path}")
            result[key] = value
        return result

    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle, object_pairs_hook=hook)


def safe_relative(value: str) -> PurePosixPath:
    path = PurePosixPath(value)
    if path.is_absolute() or not path.parts or ".." in path.parts:
        raise ValueError(f"unsafe relative path: {value}")
    if any(part in {"", "."} for part in path.parts):
        raise ValueError(f"non-canonical relative path: {value}")
    return path


def resolve(root: Path, value: str) -> Path:
    relative = safe_relative(value)
    candidate = root
    for part in relative.parts:
        candidate = candidate / part
        if candidate.is_symlink():
            raise ValueError(f"symlink is not permitted in artifact path: {value}")
    resolved_root = root.resolve()
    resolved = candidate.resolve()
    if resolved != resolved_root and resolved_root not in resolved.parents:
        raise ValueError(f"path escapes artifact root: {value}")
    return resolved


def schema_validate(schema_path: Path, value: Any) -> None:
    try:
        from jsonschema import Draft202012Validator, FormatChecker
    except ImportError as exc:  # pragma: no cover - operational failure
        raise RuntimeError("jsonschema==4.25.1 is required") from exc
    schema = load_unique(schema_path)
    Draft202012Validator.check_schema(schema)
    Draft202012Validator(
        schema, format_checker=FormatChecker()
    ).validate(value)


def schema_check(schema_path: Path) -> None:
    try:
        from jsonschema import Draft202012Validator
    except ImportError as exc:  # pragma: no cover - operational failure
        raise RuntimeError("jsonschema==4.25.1 is required") from exc
    schema = load_unique(schema_path)
    Draft202012Validator.check_schema(schema)


def expected_hash(value: dict[str, Any]) -> str:
    without = dict(value)
    without.pop("contentHash", None)
    return sha256_bytes(canonical_bytes(without))


def references_by_path(manifest: dict[str, Any]) -> dict[str, dict[str, Any]]:
    files = manifest.get("files", [])
    if not isinstance(files, list):
        raise ValueError("manifest files must be an array")
    result: dict[str, dict[str, Any]] = {}
    for item in files:
        if not isinstance(item, dict) or not isinstance(item.get("path"), str):
            raise ValueError("manifest contains an invalid file reference")
        path = item["path"]
        safe_relative(path)
        if path in result:
            raise ValueError(f"duplicate manifest file path: {path}")
        result[path] = item
    return result


def exact_root(items: Iterable[dict[str, str]]) -> str:
    material = [
        {"path": item["path"], "sha256": item["sha256"]}
        for item in sorted(items, key=lambda item: item["path"])
    ]
    return sha256_bytes(canonical_bytes(material))


def parse_properties_text(text: str, source: str) -> dict[str, str]:
    result: dict[str, str] = {}
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise ValueError(f"invalid property line in {source}: {raw!r}")
        key, value = line.split("=", 1)
        if key in result:
            raise ValueError(f"duplicate property {key!r} in {source}")
        result[key] = value
    return result


def inspect_source_archive(path: Path, manifest: dict[str, Any]) -> list[str]:
    failures: list[str] = []
    required_members = {
        "regelsuche-source/gradlew",
        "regelsuche-source/settings.gradle",
        "regelsuche-source/gradle/wrapper/gradle-wrapper.jar",
        "regelsuche-source/gradle/wrapper/gradle-wrapper.properties",
        "regelsuche-source/reproduction/Dockerfile.reproduction",
        "regelsuche-source/reproduction/reproduce.sh",
        "regelsuche-source/scripts/build-independent-reproduction-artifact.py",
        "regelsuche-source/scripts/verify-independent-reproduction.py",
        "regelsuche-source/regelsuche-release/src/main/java/de/regelsuche/release/AutonomousDiscoveryWalkthroughRunner.java",
    }
    try:
        with tarfile.open(path, "r:gz") as archive:
            members = archive.getmembers()
            if not members:
                return ["source archive is empty"]
            by_name = {member.name: member for member in members}
            if len(by_name) != len(members):
                failures.append("source archive contains duplicate member names")
            for member in members:
                name = PurePosixPath(member.name)
                if name.is_absolute() or ".." in name.parts:
                    failures.append(f"unsafe source archive member: {member.name}")
                if not name.parts or name.parts[0] != "regelsuche-source":
                    failures.append(
                        f"source archive member lacks fixed prefix: {member.name}"
                    )
                if member.issym() or member.islnk():
                    failures.append(f"source archive contains link: {member.name}")
            for required in sorted(required_members - set(by_name)):
                failures.append(f"source archive missing required member: {required}")

            properties_member = by_name.get(
                "regelsuche-source/gradle/wrapper/gradle-wrapper.properties"
            )
            if properties_member is not None:
                handle = archive.extractfile(properties_member)
                if handle is None:
                    failures.append("Gradle wrapper properties are unreadable")
                else:
                    properties = parse_properties_text(
                        handle.read().decode("utf-8"),
                        "source archive Gradle wrapper properties",
                    )
                    expected_url = (
                        "https\\://services.gradle.org/distributions/"
                        + GRADLE_DISTRIBUTION
                    )
                    if properties.get("distributionUrl") != expected_url:
                        failures.append("source archive Gradle URL is not frozen")
                    if (
                        properties.get("distributionSha256Sum")
                        != GRADLE_DISTRIBUTION_SHA256
                    ):
                        failures.append(
                            "source archive Gradle distribution checksum is not frozen"
                        )
                    declared = manifest.get("declaredEnvironment", {})
                    if declared.get("gradleDistributionSha256") != properties.get(
                        "distributionSha256Sum"
                    ):
                        failures.append(
                            "manifest and source archive Gradle checksums differ"
                        )
    except (OSError, tarfile.TarError, UnicodeError, ValueError) as exc:
        failures.append(f"source archive is unreadable: {exc}")
    return failures


def semantic_roots(card: dict[str, Any]) -> dict[str, str]:
    result: dict[str, str] = {}
    content_hash = card.get("contentHash")
    run_identity = card.get("runIdentity")
    if isinstance(content_hash, str) and SHA_RE.fullmatch(content_hash):
        result["resultCardContentHash"] = content_hash
    if isinstance(run_identity, str) and SHA_RE.fullmatch(run_identity):
        result["runIdentity"] = run_identity
    for item in card.get("artifacts", []):
        if not isinstance(item, dict):
            continue
        role = item.get("role")
        semantic = item.get("semanticHash")
        if (
            isinstance(role, str)
            and role
            and isinstance(semantic, str)
            and SHA_RE.fullmatch(semantic)
        ):
            if role in result:
                raise ValueError(f"duplicate semantic root role: {role}")
            result[role] = semantic
    return dict(sorted(result.items()))


def result_card_status_failures(
    card: dict[str, Any], manifest: dict[str, Any]
) -> list[str]:
    required = manifest.get("expectedEvidence", {}).get("requiredStatuses", {})
    failures: list[str] = []
    checks = {
        "autonomyClaimAuthorized": card.get("claimBoundaries", {}).get(
            "autonomyClaimAuthorized"
        ),
        "qualificationQualified": card.get("qualification", {}).get("qualified"),
        "targetAccess": card.get("researchBrief", {}).get(
            "targetOrExpectedAnswerAccess"
        ),
        "externalNoveltyStatus": card.get("claimBoundaries", {}).get(
            "externalNoveltyStatus"
        ),
        "promotionStatus": card.get("claimBoundaries", {}).get("promotionStatus"),
        "publicEvidenceStatus": card.get("claimBoundaries", {}).get(
            "publicEvidenceStatus"
        ),
        "correctnessRegressionCount": card.get("qualification", {}).get(
            "correctnessRegressionCount"
        ),
    }
    if not isinstance(required, dict):
        return ["manifest requiredStatuses is not an object"]
    for name, expected in required.items():
        if checks.get(name) != expected:
            failures.append(
                f"{name}: expected {expected!r}, observed {checks.get(name)!r}"
            )
    if card.get("claimBanner") != manifest.get("expectedEvidence", {}).get(
        "claimBanner"
    ):
        failures.append("claim banner differs from artifact manifest")
    if card.get("repositoryRevision") != manifest.get("source", {}).get("revision"):
        failures.append("result-card repository revision differs from artifact source")
    return sorted(set(failures))


def specialized_reference_failures(
    manifest: dict[str, Any], refs: dict[str, dict[str, Any]]
) -> list[str]:
    failures: list[str] = []
    specialized: list[tuple[str, Any]] = []
    schemas = manifest.get("schemas", [])
    if isinstance(schemas, list):
        specialized.extend(("schema", item) for item in schemas)
    metadata = manifest.get("metadata", {})
    if isinstance(metadata, dict):
        specialized.extend((f"metadata.{name}", item) for name, item in metadata.items())
    for label, item in specialized:
        if not isinstance(item, dict) or not isinstance(item.get("path"), str):
            failures.append(f"{label} is not a file reference")
            continue
        listed = refs.get(item["path"])
        if listed is None:
            failures.append(f"{label} path is absent from files: {item['path']}")
        elif listed != item:
            failures.append(f"{label} reference disagrees with files: {item['path']}")
    return failures


def container_definition_failures(root: Path, manifest: dict[str, Any]) -> list[str]:
    failures: list[str] = []
    environment = manifest.get("declaredEnvironment", {})
    value = environment.get("containerDefinitionPath", "")
    if not isinstance(value, str) or not value:
        return ["container definition path is missing"]
    try:
        path = resolve(root, value)
        text = path.read_text(encoding="utf-8")
    except (OSError, UnicodeError, ValueError) as exc:
        return [f"container definition is unreadable: {exc}"]
    expected = f"FROM {BASE_IMAGE} AS build"
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    if not lines or lines[0] != expected:
        failures.append("container definition does not use the frozen base digest")
    external = [
        line
        for line in lines
        if line.startswith("FROM ") and not line.startswith("FROM build ")
    ]
    if external != [expected]:
        failures.append(
            "container definition has an additional or unpinned external base"
        )
    if environment.get("javaImage") != BASE_IMAGE:
        failures.append("manifest Java image differs from the frozen base")
    if environment.get("javaImageIndexDigest") != BASE_IMAGE_INDEX_DIGEST:
        failures.append("manifest Java image digest differs from the frozen base")
    return failures


def identity_failures(
    root: Path, manifest: dict[str, Any], card: dict[str, Any]
) -> list[str]:
    failures: list[str] = []
    identities = manifest.get("inputIdentities", {})
    expected = {
        "resultCardHash": card.get("contentHash"),
        "runIdentity": card.get("runIdentity"),
        "researchBriefHash": card.get("researchBrief", {}).get("briefHash"),
        "inventoryHash": card.get("researchBrief", {}).get("inventoryHash"),
        "modelHash": card.get("researchBrief", {}).get("modelHash"),
        "candidateLineageHash": card.get("candidate", {}).get("lineageRoot"),
        "qualificationSuiteRevision": card.get("pairedUtility", {}).get(
            "suiteRevision"
        ),
    }
    for name, value in expected.items():
        if identities.get(name) != value:
            failures.append(f"input identity {name} differs from result card")
    qualification_path = root / (
        "expected/evidence/qualification/candidate-qualification-evidence.json"
    )
    try:
        qualification = load_unique(qualification_path)
        for name, field in (
            ("qualificationSuiteHash", "suiteHash"),
            ("qualificationSplitHash", "splitAuditHash"),
        ):
            if identities.get(name) != qualification.get(field):
                failures.append(f"input identity {name} differs from qualification")
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        failures.append(f"qualification identity evidence is unreadable: {exc}")
    return failures


def backend_identity_failures(root: Path, manifest: dict[str, Any]) -> list[str]:
    failures: list[str] = []
    declared = manifest.get("backendIdentities", [])
    if not isinstance(declared, list) or len(declared) != 1:
        return ["exactly one backend identity must be declared"]
    try:
        proof = load_unique(root / "expected/evidence/campaign/proof-report.json")
        result = load_unique(root / "expected/evidence/campaign/solver-result.json")
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return [f"backend identity evidence is unreadable: {exc}"]
    observed = {
        "backendId": result.get("backendId"),
        "backendVersion": result.get("backendVersion"),
        "status": result.get("status"),
        "translationStatus": result.get("translationStatus"),
        "resultHash": result.get("contentHash"),
        "invocationHash": result.get("invocationHash"),
    }
    if declared[0] != observed:
        failures.append("declared backend identity differs from solver evidence")
    if proof.get("solverResultHash") != result.get("contentHash"):
        failures.append("proof report is not bound to the solver result")
    return failures


def verify_artifact(root: Path, manifest: dict[str, Any]) -> list[str]:
    failures: list[str] = []
    if manifest.get("schema") != MANIFEST_SCHEMA:
        failures.append("unsupported artifact manifest schema")
    if expected_hash(manifest) != manifest.get("contentHash"):
        failures.append("artifact manifest contentHash mismatch")
    if manifest.get("claimPolicy") != CLAIM_POLICY:
        failures.append("artifact manifest claim policy is not conservative")
    if manifest.get("externalAttestationStatus") != "NOT_COLLECTED":
        failures.append("external attestation status is overstated")

    source = manifest.get("source", {})
    tag_status = source.get("releaseTagStatus")
    artifact_status = manifest.get("artifactStatus")
    if tag_status == "PUBLISHED" and artifact_status != "FROZEN_PUBLIC_RELEASE":
        failures.append("published tag requires FROZEN_PUBLIC_RELEASE")
    if (
        tag_status == "DEVELOPMENT_REVISION"
        and artifact_status != "DEVELOPMENT_READY_FOR_INDEPENDENT_EXECUTION"
    ):
        failures.append("development revision has an invalid artifact status")
    if not REVISION_RE.fullmatch(str(source.get("revision", ""))):
        failures.append("source revision is not a full commit SHA")

    try:
        refs = references_by_path(manifest)
    except ValueError as exc:
        failures.append(str(exc))
        refs = {}

    listed = set(refs)
    actual: set[str] = set()
    for path in root.rglob("*"):
        relative = path.relative_to(root).as_posix()
        if path.is_symlink():
            failures.append(f"artifact contains symlink: {relative}")
        elif path.is_file() and relative != "artifact-manifest.json":
            actual.add(relative)
    for value in sorted(actual - listed):
        failures.append(f"unlisted artifact file: {value}")
    for value in sorted(listed - actual):
        failures.append(f"missing artifact file: {value}")
    for value in sorted(listed & actual):
        try:
            path = resolve(root, value)
        except ValueError as exc:
            failures.append(str(exc))
            continue
        if path.is_symlink() or not path.is_file():
            failures.append(f"artifact file is not a regular file: {value}")
            continue
        item = refs[value]
        if path.stat().st_size != item.get("bytes"):
            failures.append(f"artifact byte length mismatch: {value}")
        if sha256_file(path) != item.get("sha256"):
            failures.append(f"artifact SHA-256 mismatch: {value}")

    failures.extend(specialized_reference_failures(manifest, refs))
    for item in manifest.get("schemas", []):
        if not isinstance(item, dict) or not isinstance(item.get("path"), str):
            continue
        try:
            schema_check(resolve(root, item["path"]))
        except Exception as exc:  # noqa: BLE001 - retained diagnostic
            failures.append(f"schema is invalid: {item.get('path')}: {exc}")

    source_path = source.get("archivePath", "")
    if isinstance(source_path, str) and source_path in refs:
        if refs[source_path].get("sha256") != source.get("archiveSha256"):
            failures.append("source archive hash disagrees with file inventory")
        try:
            archive_path = resolve(root, source_path)
            if archive_path.is_file():
                failures.extend(inspect_source_archive(archive_path, manifest))
        except ValueError as exc:
            failures.append(str(exc))

    environment = manifest.get("declaredEnvironment", {})
    container_path = environment.get("containerDefinitionPath", "")
    if isinstance(container_path, str) and container_path in refs:
        if refs[container_path].get("sha256") != environment.get(
            "containerDefinitionSha256"
        ):
            failures.append(
                "container definition hash disagrees with file inventory"
            )
    failures.extend(container_definition_failures(root, manifest))

    exact_paths = manifest.get("portabilityPolicy", {}).get("exactBytePaths", [])
    if not isinstance(exact_paths, list):
        failures.append("exactBytePaths must be an array")
        exact_paths = []
    if exact_paths != sorted(exact_paths) or len(exact_paths) != len(
        set(exact_paths)
    ):
        failures.append("exactBytePaths must be sorted and unique")
    exact_refs: list[dict[str, str]] = []
    for value in exact_paths:
        if not isinstance(value, str):
            failures.append("exact-byte path is not text")
        elif value not in refs:
            failures.append(f"exact-byte path is not listed in files: {value}")
        elif not value.startswith("expected/"):
            failures.append(f"exact-byte path is outside expected/: {value}")
        else:
            exact_refs.append({"path": value, "sha256": refs[value]["sha256"]})
    if exact_refs and exact_root(exact_refs) != manifest.get(
        "expectedEvidence", {}
    ).get("exactByteRoot"):
        failures.append("expected exact-byte root mismatch")

    try:
        card_path = resolve(root, "expected/result-card.json")
    except ValueError as exc:
        failures.append(str(exc))
        card_path = root / "expected/result-card.json"
    if not card_path.is_file():
        failures.append("expected result-card.json is missing")
    else:
        try:
            card = load_unique(card_path)
            schema_validate(root / "schemas/result-card.schema.json", card)
            if expected_hash(card) != card.get("contentHash"):
                failures.append("expected result-card contentHash mismatch")
            roots = semantic_roots(card)
            if roots != manifest.get("expectedEvidence", {}).get("semanticRoots"):
                failures.append(
                    "expected result-card semantic roots differ from manifest"
                )
            failures.extend(result_card_status_failures(card, manifest))
            failures.extend(identity_failures(root, manifest, card))
        except Exception as exc:  # noqa: BLE001 - retained diagnostic
            failures.append(f"expected result card is invalid: {exc}")
    failures.extend(backend_identity_failures(root, manifest))
    return sorted(set(failures))


def safe_expected_paths(manifest: dict[str, Any]) -> list[str]:
    values = manifest.get("portabilityPolicy", {}).get("exactBytePaths", [])
    if not isinstance(values, list):
        return []
    return sorted(
        value
        for value in values
        if isinstance(value, str) and value.startswith("expected/")
    )


def compare_exact(root: Path, observed: Path, manifest: dict[str, Any]) -> dict[str, Any]:
    expected_paths = safe_expected_paths(manifest)
    try:
        expected_refs = references_by_path(manifest)
    except ValueError:
        expected_refs = {}
    expected_relatives = {value.removeprefix("expected/") for value in expected_paths}
    expected_directories: set[str] = set()
    for relative in expected_relatives:
        parent = PurePosixPath(relative).parent
        while parent != PurePosixPath("."):
            expected_directories.add(parent.as_posix())
            parent = parent.parent
    observed_entries = (
        {
            path.relative_to(observed).as_posix()
            for path in observed.rglob("*")
        }
        if observed.is_dir() and not observed.is_symlink()
        else set()
    )

    missing: list[str] = []
    differing: list[str] = []
    matched = 0
    observed_root_items: list[dict[str, str]] = []
    for expected_path in expected_paths:
        relative = expected_path.removeprefix("expected/")
        try:
            path = resolve(observed, relative)
        except ValueError:
            differing.append(relative)
            continue
        if not path.is_file():
            missing.append(relative)
            continue
        actual = sha256_file(path)
        observed_root_items.append({"path": expected_path, "sha256": actual})
        expected_ref = expected_refs.get(expected_path, {})
        if actual == expected_ref.get("sha256"):
            matched += 1
        else:
            differing.append(relative)
    allowed_entries = expected_relatives | expected_directories
    unexpected = sorted(observed_entries - allowed_entries)
    observed_root = (
        exact_root(observed_root_items)
        if expected_paths and len(observed_root_items) == len(expected_paths)
        else "NOT_AVAILABLE"
    )
    expected_root = manifest.get("expectedEvidence", {}).get("exactByteRoot")
    if not isinstance(expected_root, str) or not SHA_RE.fullmatch(expected_root):
        expected_root = exact_root([])
    all_match = bool(expected_paths) and (
        not missing
        and not differing
        and not unexpected
        and matched == len(expected_paths)
    )
    return {
        "designatedFileCount": len(expected_paths),
        "matchedFileCount": matched,
        "expectedExactByteRoot": expected_root,
        "observedExactByteRoot": observed_root,
        "missingPaths": sorted(missing),
        "differingPaths": sorted(differing),
        "unexpectedPaths": unexpected,
        "allDesignatedBytesMatch": all_match,
    }


def compare_semantic(
    observed: Path,
    manifest: dict[str, Any],
    input_failures: list[str],
    execution_exit_code: int,
    result_card_schema: Path,
) -> dict[str, Any]:
    expected_value = manifest.get("expectedEvidence", {}).get("semanticRoots", {})
    expected = (
        dict(sorted(expected_value.items()))
        if isinstance(expected_value, dict)
        else {}
    )
    observed_roots: dict[str, str] = {}
    status_failures: list[str] = []
    try:
        path = resolve(observed, "result-card.json")
    except ValueError as exc:
        status_failures.append(str(exc))
        path = observed / "result-card.json"
    if not path.is_file():
        status_failures.append("observed result-card.json is missing")
    else:
        try:
            card = load_unique(path)
            schema_validate(result_card_schema, card)
            if expected_hash(card) != card.get("contentHash"):
                status_failures.append("observed result-card contentHash mismatch")
            observed_roots = semantic_roots(card)
            status_failures.extend(result_card_status_failures(card, manifest))
        except Exception as exc:  # noqa: BLE001 - retained diagnostic
            status_failures.append(f"observed result card is invalid: {exc}")
    missing = sorted(set(expected) - set(observed_roots))
    differing = sorted(
        name
        for name in set(expected) & set(observed_roots)
        if expected[name] != observed_roots[name]
    )
    if execution_exit_code != 0:
        status_failures.append(
            f"evaluated execution exited with {execution_exit_code}"
        )
    all_match = bool(expected) and (
        not input_failures
        and not missing
        and not differing
        and not status_failures
        and observed_roots == expected
    )
    return {
        "expectedRoots": dict(sorted(expected.items())),
        "observedRoots": dict(sorted(observed_roots.items())),
        "missingRoots": missing,
        "differingRoots": differing,
        "inputArtifactFailures": sorted(set(input_failures)),
        "requiredStatusFailures": sorted(set(status_failures)),
        "allSemanticRootsMatch": all_match,
    }


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def docker_version() -> str:
    try:
        return (
            subprocess.run(
                ["docker", "version", "--format", "{{.Server.Version}}"],
                check=True,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
            ).stdout.strip()
            or "UNKNOWN"
        )
    except (OSError, subprocess.CalledProcessError):
        return "UNKNOWN"


def valid_artifact_id(value: Any) -> str:
    if isinstance(value, str) and IDENTIFIER_RE.fullmatch(value):
        return value
    return "unresolved-reproduction-artifact"


def manifest_hash_binding(
    manifest: dict[str, Any], manifest_file_sha256: str
) -> tuple[str, str]:
    claimed = manifest.get("contentHash")
    if isinstance(claimed, str) and SHA_RE.fullmatch(claimed):
        return claimed, "CLAIMED_CONTENT_HASH"
    return manifest_file_sha256, "FILE_SHA256_FALLBACK"


def receipt_semantic_material(receipt: dict[str, Any]) -> dict[str, Any]:
    return {
        "schema": receipt["schema"],
        "artifactManifestHash": receipt["artifactManifestHash"],
        "artifactManifestHashSource": receipt["artifactManifestHashSource"],
        "artifactManifestFileSha256": receipt["artifactManifestFileSha256"],
        "artifactId": receipt["artifactId"],
        "environmentIdentity": receipt["environmentIdentity"],
        "executionExitCode": receipt["executionExitCode"],
        "exactComparison": receipt["exactComparison"],
        "semanticComparison": receipt["semanticComparison"],
        "reproductionStatus": receipt["reproductionStatus"],
        "claimPolicy": receipt["claimPolicy"],
        "externalAttestationStatus": receipt["externalAttestationStatus"],
    }


def attestation_material(receipt: dict[str, Any]) -> dict[str, Any]:
    return {
        "artifactManifestHash": receipt["artifactManifestHash"],
        "artifactManifestFileSha256": receipt["artifactManifestFileSha256"],
        "environmentIdentity": receipt["environmentIdentity"],
        "semanticReceiptHash": receipt["semanticReceiptHash"],
        "reproductionStatus": receipt["reproductionStatus"],
    }


def make_receipt(
    manifest: dict[str, Any],
    manifest_file_sha256: str,
    exact: dict[str, Any],
    semantic: dict[str, Any],
    environment_identity: str,
    started_at: str,
    finished_at: str,
    image_id: str,
    execution_exit_code: int,
    platform_value: str,
    docker_value: str,
) -> dict[str, Any]:
    if semantic["allSemanticRootsMatch"] and exact["allDesignatedBytesMatch"]:
        status = "EXACT_BYTE_REPRODUCED"
    elif semantic["allSemanticRootsMatch"]:
        status = "SEMANTICALLY_REPRODUCED"
    else:
        status = "NOT_REPRODUCED"
    manifest_hash, manifest_hash_source = manifest_hash_binding(
        manifest, manifest_file_sha256
    )
    receipt: dict[str, Any] = {
        "schema": RECEIPT_SCHEMA,
        "artifactManifestHash": manifest_hash,
        "artifactManifestHashSource": manifest_hash_source,
        "artifactManifestFileSha256": manifest_file_sha256,
        "artifactId": valid_artifact_id(manifest.get("artifactId")),
        "environmentIdentity": environment_identity,
        "executionStartedAt": started_at,
        "executionFinishedAt": finished_at,
        "containerImageId": image_id or "NOT_AVAILABLE",
        "environmentDiagnostics": {
            "platform": platform_value or "UNKNOWN",
            "dockerVersion": docker_value or "UNKNOWN",
            "pythonVersion": platform.python_version() or "UNKNOWN",
            "networkPolicy": "DISABLED_DURING_EVALUATED_RUN",
        },
        "executionExitCode": execution_exit_code,
        "exactComparison": exact,
        "semanticComparison": semantic,
        "reproductionStatus": status,
        "claimPolicy": CLAIM_POLICY,
        "externalAttestationStatus": "NOT_COLLECTED",
    }
    receipt["semanticReceiptHash"] = sha256_bytes(
        canonical_bytes(receipt_semantic_material(receipt))
    )
    receipt["reproducerAttestationHash"] = sha256_bytes(
        canonical_bytes(attestation_material(receipt))
    )
    receipt["contentHash"] = sha256_bytes(canonical_bytes(receipt))
    return receipt


def expected_reproduction_status(receipt: dict[str, Any]) -> str:
    semantic = receipt.get("semanticComparison", {}).get(
        "allSemanticRootsMatch"
    ) is True
    exact = receipt.get("exactComparison", {}).get(
        "allDesignatedBytesMatch"
    ) is True
    if semantic and exact:
        return "EXACT_BYTE_REPRODUCED"
    if semantic:
        return "SEMANTICALLY_REPRODUCED"
    return "NOT_REPRODUCED"


def verify_receipt_value(
    receipt: dict[str, Any], manifest: dict[str, Any], manifest_file_sha256: str
) -> list[str]:
    failures: list[str] = []
    if expected_hash(receipt) != receipt.get("contentHash"):
        failures.append("reproduction receipt contentHash mismatch")
    expected_semantic = sha256_bytes(
        canonical_bytes(receipt_semantic_material(receipt))
    )
    if expected_semantic != receipt.get("semanticReceiptHash"):
        failures.append("reproduction receipt semanticReceiptHash mismatch")
    expected_attestation = sha256_bytes(
        canonical_bytes(attestation_material(receipt))
    )
    if expected_attestation != receipt.get("reproducerAttestationHash"):
        failures.append("reproducer attestation hash mismatch")
    if expected_reproduction_status(receipt) != receipt.get("reproductionStatus"):
        failures.append("reproduction status disagrees with comparison results")
    manifest_hash, source = manifest_hash_binding(manifest, manifest_file_sha256)
    if receipt.get("artifactManifestHash") != manifest_hash:
        failures.append("receipt is bound to another artifact manifest hash")
    if receipt.get("artifactManifestHashSource") != source:
        failures.append("receipt manifest hash source is inconsistent")
    if receipt.get("artifactManifestFileSha256") != manifest_file_sha256:
        failures.append("receipt is bound to other manifest bytes")
    if receipt.get("artifactId") != valid_artifact_id(manifest.get("artifactId")):
        failures.append("receipt artifact identity differs from manifest")
    if receipt.get("claimPolicy") != CLAIM_POLICY:
        failures.append("receipt claim policy is not conservative")
    if receipt.get("externalAttestationStatus") != "NOT_COLLECTED":
        failures.append("receipt overstates external attestation")
    try:
        started = datetime.fromisoformat(
            receipt["executionStartedAt"].replace("Z", "+00:00")
        )
        finished = datetime.fromisoformat(
            receipt["executionFinishedAt"].replace("Z", "+00:00")
        )
        if finished < started:
            failures.append("executionFinishedAt precedes executionStartedAt")
    except (KeyError, TypeError, ValueError):
        failures.append("receipt execution timestamps are invalid")
    return sorted(set(failures))


def fallback_manifest(manifest_file_sha256: str) -> dict[str, Any]:
    return {
        "schema": MANIFEST_SCHEMA,
        "artifactId": "unresolved-reproduction-artifact",
        "contentHash": "NOT_AVAILABLE",
        "source": {"revision": "0" * 40},
        "expectedEvidence": {
            "semanticRoots": {},
            "requiredStatuses": {},
            "claimBanner": "NO EXTERNAL NOVELTY CLAIM",
            "exactByteRoot": exact_root([]),
        },
        "portabilityPolicy": {"exactBytePaths": []},
        "files": [],
    }


def load_manifest_for_receipt(
    root: Path,
) -> tuple[dict[str, Any], str, list[str]]:
    path = root / "artifact-manifest.json"
    if not path.is_file():
        file_sha = sha256_bytes(b"")
        return fallback_manifest(file_sha), file_sha, ["artifact manifest is missing"]
    raw = path.read_bytes()
    file_sha = sha256_bytes(raw)
    try:
        manifest = load_unique(path)
    except Exception as exc:  # noqa: BLE001 - retained diagnostic
        return (
            fallback_manifest(file_sha),
            file_sha,
            [f"artifact manifest is unreadable: {exc}"],
        )
    failures: list[str] = []
    try:
        schema_validate(root / "schemas/artifact-manifest.schema.json", manifest)
    except Exception as exc:  # noqa: BLE001 - retained diagnostic
        failures.append(f"artifact manifest schema validation failed: {exc}")
    return manifest, file_sha, failures


def write_receipt(path: Path, receipt: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(receipt, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)

    verify = subparsers.add_parser("verify-artifact")
    verify.add_argument("--root", type=Path, required=True)

    compare = subparsers.add_parser("compare")
    compare.add_argument("--root", type=Path, required=True)
    compare.add_argument("--observed", type=Path, required=True)
    compare.add_argument("--receipt", type=Path, required=True)
    compare.add_argument("--environment-id", required=True)
    compare.add_argument("--started-at", default="")
    compare.add_argument("--finished-at", default="")
    compare.add_argument("--container-image-id", default="NOT_AVAILABLE")
    compare.add_argument("--execution-exit-code", type=int, default=0)
    compare.add_argument("--platform", default="")
    compare.add_argument("--docker-version", default="")

    verify_receipt_parser = subparsers.add_parser("verify-receipt")
    verify_receipt_parser.add_argument("--root", type=Path, required=True)
    verify_receipt_parser.add_argument("--receipt", type=Path, required=True)
    args = parser.parse_args()

    root_input = args.root.expanduser().absolute()
    root_failures = (
        ["artifact root must not be a symlink"]
        if root_input.is_symlink() else []
    )
    root = root_input.resolve()
    manifest, manifest_file_sha, load_failures = load_manifest_for_receipt(root)
    artifact_failures = sorted(
        set(root_failures + load_failures + verify_artifact(root, manifest))
    )

    if args.command == "verify-artifact":
        if artifact_failures:
            for failure in artifact_failures:
                print(f"ERROR: {failure}")
            raise SystemExit(1)
        print(manifest["contentHash"])
        print(manifest_file_sha)
        return

    if args.command == "verify-receipt":
        receipt = load_unique(args.receipt.resolve())
        schema_validate(root / "schemas/reproduction-receipt.schema.json", receipt)
        failures = verify_receipt_value(receipt, manifest, manifest_file_sha)
        if failures:
            for failure in failures:
                print(f"ERROR: {failure}")
            raise SystemExit(1)
        print(receipt["reproductionStatus"])
        print(receipt["contentHash"])
        return

    observed_input = args.observed.expanduser().absolute()
    observed_failures = (
        ["observed output root must not be a symlink"]
        if observed_input.is_symlink() else []
    )
    observed = observed_input.resolve()
    exact = compare_exact(root, observed, manifest)
    semantic = compare_semantic(
        observed,
        manifest,
        sorted(set(artifact_failures + observed_failures)),
        max(0, args.execution_exit_code),
        root / "schemas/result-card.schema.json",
    )
    receipt = make_receipt(
        manifest,
        manifest_file_sha,
        exact,
        semantic,
        args.environment_id.strip() or "anonymous-reproducer",
        args.started_at or utc_now(),
        args.finished_at or utc_now(),
        args.container_image_id,
        max(0, args.execution_exit_code),
        args.platform or platform.platform(),
        args.docker_version or docker_version(),
    )
    write_receipt(args.receipt.resolve(), receipt)

    receipt_failures: list[str] = []
    try:
        schema_validate(
            root / "schemas/reproduction-receipt.schema.json", receipt
        )
    except Exception as exc:  # noqa: BLE001 - receipt remains retained
        receipt_failures.append(f"receipt schema validation failed: {exc}")
    receipt_failures.extend(
        verify_receipt_value(receipt, manifest, manifest_file_sha)
    )
    for failure in sorted(set(receipt_failures)):
        print(f"ERROR: {failure}", file=sys.stderr)
    print(receipt["reproductionStatus"])
    print(receipt["contentHash"])
    raise SystemExit(
        0
        if receipt["reproductionStatus"] != "NOT_REPRODUCED"
        and not receipt_failures
        else 1
    )


if __name__ == "__main__":
    main()
