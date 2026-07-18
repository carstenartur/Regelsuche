#!/usr/bin/env python3
"""Build a deterministic, independently executable reproduction artifact."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tarfile
from typing import Any, Iterable


MANIFEST_SCHEMA = "regelsuche.independent-reproduction-artifact/v1"
CLAIM_POLICY = "REPRODUCTION_DOES_NOT_IMPLY_EXTERNAL_NOVELTY"
BASE_IMAGE = (
    "eclipse-temurin:21.0.11_10-jdk-noble@"
    "sha256:35685c7e23352983a48882d97cd9875f5284c228db71d1e2476e5e6c1bab1080"
)
BASE_IMAGE_INDEX_DIGEST = (
    "sha256:35685c7e23352983a48882d97cd9875f5284c228db71d1e2476e5e6c1bab1080"
)
GRADLE_DISTRIBUTION = "gradle-9.5.1-bin.zip"
GRADLE_DISTRIBUTION_SHA256 = (
    "bafc141b619ad6350fd975fc903156dd5c151998cc8b058e8c1044ab5f7b031f"
)
REVISION_RE = re.compile(r"^[0-9a-f]{40}$")
SHA_RE = re.compile(r"^sha256:[0-9a-f]{64}$")
MANAGED_OUTPUT_MARKER = ".regelsuche-independent-reproduction-output"
MANAGED_OUTPUT_MARKER_CONTENT = "regelsuche.independent-reproduction-output/v1\n"
TAG_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._/-]{0,190}$")


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


def require_sha(value: Any, name: str) -> str:
    if not isinstance(value, str) or not SHA_RE.fullmatch(value):
        raise ValueError(f"{name} must be a sha256: value")
    return value


def require_text(value: Any, name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{name} must be non-blank text")
    return value.strip()


def safe_relative(value: str) -> PurePosixPath:
    path = PurePosixPath(value)
    if path.is_absolute() or not path.parts or ".." in path.parts:
        raise ValueError(f"unsafe artifact path: {value}")
    if any(part in {"", "."} for part in path.parts):
        raise ValueError(f"non-canonical artifact path: {value}")
    return path


def remove_entry(path: Path) -> None:
    if path.is_dir() and not path.is_symlink():
        shutil.rmtree(path)
    else:
        path.unlink()


def prepare_managed_directory(directory: Path) -> None:
    if directory.is_symlink():
        raise ValueError(f"output directory must not be a symlink: {directory}")
    if directory.exists() and not directory.is_dir():
        raise ValueError(f"output path must be a directory: {directory}")
    directory.mkdir(parents=True, exist_ok=True)
    entries = sorted(directory.iterdir(), key=lambda item: item.name)
    marker = directory / MANAGED_OUTPUT_MARKER
    if entries:
        if (not marker.is_file() or marker.is_symlink()
                or marker.read_text(encoding="utf-8")
                    != MANAGED_OUTPUT_MARKER_CONTENT):
            raise ValueError(
                "refusing to clear non-empty directory without the "
                f"Regelsuche ownership marker: {directory}")
        for child in entries:
            if child != marker:
                remove_entry(child)
    marker.write_text(MANAGED_OUTPUT_MARKER_CONTENT, encoding="utf-8")
    marker.chmod(0o644)


def copy_required(source: Path, destination: Path, executable: bool = False) -> None:
    if not source.is_file() or source.is_symlink():
        raise FileNotFoundError(source)
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, destination)
    destination.chmod(0o755 if executable else 0o644)


def copy_tree(source: Path, destination: Path) -> None:
    if not source.is_dir():
        raise FileNotFoundError(source)
    for path in sorted(source.rglob("*"), key=lambda item: item.as_posix()):
        if path.is_symlink():
            raise ValueError(f"symlink is not permitted in expected evidence: {path}")
        relative = path.relative_to(source)
        target = destination / relative
        if path.is_dir():
            target.mkdir(parents=True, exist_ok=True)
        elif path.is_file():
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, target)
            target.chmod(0o644)


def run_git(repository: Path, *args: str) -> str:
    return subprocess.run(
        ["git", "-C", str(repository), *args],
        check=True,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    ).stdout.strip()


def validate_source_identity(
    repository: Path,
    revision: str,
    release_tag: str,
    release_tag_status: str,
) -> None:
    run_git(repository, "cat-file", "-e", f"{revision}^{{commit}}")
    checked_out = run_git(repository, "rev-parse", "HEAD^{commit}")
    if checked_out != revision:
        raise ValueError(
            "repository checkout does not match the requested source revision: "
            f"HEAD={checked_out}, requested={revision}"
        )
    worktree = run_git(
        repository,
        "status",
        "--porcelain=v1",
        "--untracked-files=all",
    )
    if worktree:
        preview = "\n".join(worktree.splitlines()[:20])
        raise ValueError(
            "repository worktree must be clean before freezing an artifact; "
            f"observed changes:\n{preview}"
        )
    if release_tag_status == "PUBLISHED":
        tagged = run_git(repository, "rev-parse", f"refs/tags/{release_tag}^{{commit}}")
        if tagged != revision:
            raise ValueError(
                f"published tag {release_tag!r} resolves to {tagged}, not {revision}"
            )
    elif release_tag_status == "DEVELOPMENT_REVISION":
        if not release_tag.startswith("development-"):
            raise ValueError(
                "development release labels must start with 'development-'"
            )
    else:
        raise ValueError("unsupported release tag status")


def parse_properties(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise ValueError(f"invalid property line in {path}: {raw!r}")
        key, value = line.split("=", 1)
        if key in result:
            raise ValueError(f"duplicate property {key!r} in {path}")
        result[key] = value
    return result


def validate_gradle_wrapper(repository: Path) -> dict[str, str]:
    path = repository / "gradle/wrapper/gradle-wrapper.properties"
    properties = parse_properties(path)
    expected_url = (
        "https\\://services.gradle.org/distributions/" + GRADLE_DISTRIBUTION
    )
    if properties.get("distributionUrl") != expected_url:
        raise ValueError("Gradle distribution URL differs from the frozen contract")
    if properties.get("distributionSha256Sum") != GRADLE_DISTRIBUTION_SHA256:
        raise ValueError("Gradle distribution checksum differs from the frozen contract")
    return properties


def validate_container_definition(repository: Path) -> None:
    path = repository / "reproduction/Dockerfile.reproduction"
    text = path.read_text(encoding="utf-8")
    expected = f"FROM {BASE_IMAGE} AS build"
    if text.splitlines()[0].strip() != expected:
        raise ValueError("reproduction Dockerfile does not use the frozen base digest")
    external_from = [
        line.strip()
        for line in text.splitlines()
        if line.strip().startswith("FROM ") and "FROM build " not in line
    ]
    if external_from != [expected]:
        raise ValueError(
            "reproduction Dockerfile must have exactly one digest-pinned external base"
        )


def create_source_archive(repository: Path, revision: str, output: Path) -> None:
    archive = subprocess.run(
        [
            "git",
            "-C",
            str(repository),
            "archive",
            "--format=tar",
            "--prefix=regelsuche-source/",
            revision,
        ],
        check=True,
        stdout=subprocess.PIPE,
    ).stdout
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as zipped:
            zipped.write(archive)


def normalized_tar_info(relative: PurePosixPath, path: Path) -> tarfile.TarInfo:
    info = tarfile.TarInfo(relative.as_posix())
    info.uid = 0
    info.gid = 0
    info.uname = "root"
    info.gname = "root"
    info.mtime = 0
    if path.is_dir():
        info.type = tarfile.DIRTYPE
        info.mode = 0o755
        info.size = 0
    else:
        info.type = tarfile.REGTYPE
        info.mode = 0o755 if os.access(path, os.X_OK) else 0o644
        info.size = path.stat().st_size
    return info


def create_bundle_archive(bundle: Path, output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as zipped:
            with tarfile.open(fileobj=zipped, mode="w", format=tarfile.PAX_FORMAT) as tar:
                root_name = PurePosixPath("regelsuche-independent-reproduction")
                root_info = normalized_tar_info(root_name, bundle)
                tar.addfile(root_info)
                for path in sorted(bundle.rglob("*"), key=lambda item: item.as_posix()):
                    if path.is_symlink():
                        raise ValueError(f"symlink is not permitted in bundle: {path}")
                    relative = root_name / PurePosixPath(
                        path.relative_to(bundle).as_posix()
                    )
                    info = normalized_tar_info(relative, path)
                    if path.is_dir():
                        tar.addfile(info)
                    else:
                        with path.open("rb") as handle:
                            tar.addfile(info, handle)


def reference(bundle: Path, value: str) -> dict[str, Any]:
    relative = safe_relative(value)
    path = bundle.joinpath(*relative.parts)
    if not path.is_file() or path.is_symlink():
        raise FileNotFoundError(path)
    size = path.stat().st_size
    if size <= 0:
        raise ValueError(f"artifact file must not be empty: {value}")
    return {
        "path": relative.as_posix(),
        "sha256": sha256_file(path),
        "bytes": size,
    }


def all_file_references(bundle: Path) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for path in sorted(bundle.rglob("*"), key=lambda item: item.as_posix()):
        relative = path.relative_to(bundle).as_posix()
        if not path.is_file() or relative == "artifact-manifest.json":
            continue
        result.append(reference(bundle, relative))
    return result


def exact_root(references: Iterable[dict[str, Any]]) -> str:
    material = [
        {"path": item["path"], "sha256": item["sha256"]}
        for item in sorted(references, key=lambda item: item["path"])
    ]
    return sha256_bytes(canonical_bytes(material))


def validate_result_card(card: dict[str, Any], revision: str) -> None:
    if card.get("schema") != "regelsuche.autonomous-discovery-result-card/v1":
        raise ValueError("unsupported result-card schema")
    if card.get("repositoryRevision") != revision:
        raise ValueError("result card is not bound to the requested source revision")
    if card.get("claimBanner") != "NO EXTERNAL NOVELTY CLAIM":
        raise ValueError("result card claim banner is missing")
    if card.get("researchBrief", {}).get("targetOrExpectedAnswerAccess") != "ABSENT":
        raise ValueError("formation target access is not absent")
    if card.get("qualification", {}).get("qualified") is not True:
        raise ValueError("result card is not qualified")
    if card.get("claimBoundaries", {}).get("autonomyClaimAuthorized") is not True:
        raise ValueError("autonomy claim is not authorized")
    for field in ("externalNoveltyStatus", "promotionStatus", "publicEvidenceStatus"):
        if card.get("claimBoundaries", {}).get(field) != "NOT_EVALUATED":
            raise ValueError(f"result card overstates {field}")
    if card.get("qualification", {}).get("correctnessRegressionCount") != 0:
        raise ValueError("result card retains correctness regressions")
    require_sha(card.get("contentHash"), "result-card contentHash")
    require_sha(card.get("runIdentity"), "result-card runIdentity")


def load_backend_identity(walkthrough: Path) -> dict[str, Any]:
    proof = load_unique(walkthrough / "evidence/campaign/proof-report.json")
    result = load_unique(walkthrough / "evidence/campaign/solver-result.json")
    if proof.get("solverResultHash") != result.get("contentHash"):
        raise ValueError("proof report is not bound to the retained solver result")
    if proof.get("backendId") != result.get("backendId"):
        raise ValueError("proof and solver-result backend identities differ")
    if proof.get("backendVersion") != result.get("backendVersion"):
        raise ValueError("proof and solver-result backend versions differ")
    return {
        "backendId": require_text(result.get("backendId"), "backendId"),
        "backendVersion": require_text(result.get("backendVersion"), "backendVersion"),
        "status": require_text(result.get("status"), "backend status"),
        "translationStatus": require_text(
            result.get("translationStatus"), "translation status"
        ),
        "resultHash": require_sha(result.get("contentHash"), "solver result hash"),
        "invocationHash": require_sha(
            result.get("invocationHash"), "solver invocation hash"
        ),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository-root", type=Path, required=True)
    parser.add_argument("--walkthrough-root", type=Path, required=True)
    parser.add_argument("--source-revision", required=True)
    parser.add_argument("--release-tag", required=True)
    parser.add_argument(
        "--release-tag-status",
        choices=["DEVELOPMENT_REVISION", "PUBLISHED"],
        required=True,
    )
    parser.add_argument(
        "--architecture",
        choices=["linux/amd64", "linux/arm64"],
        default="linux/amd64",
    )
    parser.add_argument("--output-directory", type=Path, required=True)
    parser.add_argument("--archive-output", type=Path, required=True)
    args = parser.parse_args()

    repository = args.repository_root.resolve()
    walkthrough = args.walkthrough_root.resolve()
    revision = args.source_revision.strip()
    release_tag = args.release_tag.strip()
    if not REVISION_RE.fullmatch(revision):
        raise SystemExit("source revision must be a lowercase 40-character commit SHA")
    if not TAG_RE.fullmatch(release_tag):
        raise SystemExit("release tag/label contains unsupported characters")

    validate_source_identity(
        repository, revision, release_tag, args.release_tag_status
    )
    wrapper = validate_gradle_wrapper(repository)
    validate_container_definition(repository)

    card = load_unique(walkthrough / "result-card.json")
    validate_result_card(card, revision)
    qualification = load_unique(
        walkthrough / "evidence/qualification/candidate-qualification-evidence.json"
    )
    backend = load_backend_identity(walkthrough)

    bundle = args.output_directory.resolve()
    archive_output = args.archive_output.resolve()
    if archive_output == bundle or bundle in archive_output.parents:
        raise SystemExit("archive output must be outside the bundle directory")
    if archive_output.is_symlink():
        raise SystemExit("archive output must not be a symlink")
    prepare_managed_directory(bundle)
    copy_tree(walkthrough, bundle / "expected")

    create_source_archive(repository, revision, bundle / "source/regelsuche-source.tar.gz")
    copy_required(
        repository / "reproduction/Dockerfile.reproduction",
        bundle / "environment/Dockerfile.reproduction",
    )
    copy_required(
        repository / "reproduction/reproduce.sh",
        bundle / "reproduce.sh",
        executable=True,
    )
    copy_required(
        repository / "scripts/verify-independent-reproduction.py",
        bundle / "scripts/verify-independent-reproduction.py",
        executable=True,
    )
    copy_required(
        repository / "scripts/verify-autonomous-discovery-walkthrough.py",
        bundle / "scripts/verify-autonomous-discovery-walkthrough.py",
        executable=True,
    )
    copy_required(
        repository
        / "docs/schemas/regelsuche-independent-reproduction-artifact-v1.schema.json",
        bundle / "schemas/artifact-manifest.schema.json",
    )
    copy_required(
        repository
        / "docs/schemas/regelsuche-independent-reproduction-receipt-v1.schema.json",
        bundle / "schemas/reproduction-receipt.schema.json",
    )
    copy_required(
        repository
        / "docs/schemas/regelsuche-autonomous-discovery-result-card-v1.schema.json",
        bundle / "schemas/result-card.schema.json",
    )
    copy_required(repository / "reproduction/README.md", bundle / "README.md")
    copy_required(repository / "LICENSE", bundle / "metadata/LICENSE")
    copy_required(repository / "CITATION.cff", bundle / "metadata/CITATION.cff")
    copy_required(repository / "codemeta.json", bundle / "metadata/codemeta.json")
    (bundle / ".dockerignore").write_text(
        "**\n!source/regelsuche-source.tar.gz\n"
        "!environment/Dockerfile.reproduction\n",
        encoding="utf-8",
    )

    expected_refs = [
        reference(bundle, path.relative_to(bundle).as_posix())
        for path in sorted(
            (bundle / "expected").rglob("*"), key=lambda item: item.as_posix()
        )
        if path.is_file()
    ]
    exact_paths = [item["path"] for item in expected_refs]
    semantic_roots = {
        "resultCardContentHash": require_sha(
            card["contentHash"], "card.contentHash"
        ),
        "runIdentity": require_sha(card["runIdentity"], "card.runIdentity"),
    }
    for item in card.get("artifacts", []):
        role = item.get("role")
        semantic = item.get("semanticHash")
        if not isinstance(role, str) or not role:
            raise ValueError("result card contains an invalid artifact role")
        if role in semantic_roots:
            raise ValueError(f"duplicate semantic root role: {role}")
        semantic_roots[role] = require_sha(
            semantic, f"artifact {role} semanticHash"
        )

    schema_paths = [
        "schemas/artifact-manifest.schema.json",
        "schemas/reproduction-receipt.schema.json",
        "schemas/result-card.schema.json",
    ]
    artifact_status = (
        "FROZEN_PUBLIC_RELEASE"
        if args.release_tag_status == "PUBLISHED"
        else "DEVELOPMENT_READY_FOR_INDEPENDENT_EXECUTION"
    )
    manifest: dict[str, Any] = {
        "schema": MANIFEST_SCHEMA,
        "artifactId": (
            "regelsuche-autonomous-discovery-reproduction/"
            f"{release_tag}/{revision}"
        ),
        "artifactStatus": artifact_status,
        "claimPolicy": CLAIM_POLICY,
        "source": {
            "repository": "carstenartur/Regelsuche",
            "revision": revision,
            "releaseTag": release_tag,
            "releaseTagStatus": args.release_tag_status,
            "archivePath": "source/regelsuche-source.tar.gz",
            "archiveSha256": sha256_file(
                bundle / "source/regelsuche-source.tar.gz"
            ),
        },
        "declaredEnvironment": {
            "javaImage": BASE_IMAGE,
            "javaImageIndexDigest": BASE_IMAGE_INDEX_DIGEST,
            "gradleDistribution": GRADLE_DISTRIBUTION,
            "gradleDistributionUrl": wrapper["distributionUrl"].replace("\\:", ":"),
            "gradleDistributionSha256": GRADLE_DISTRIBUTION_SHA256,
            "operatingSystem": "ubuntu-24.04-noble-container",
            "architecture": args.architecture,
            "containerDefinitionPath": "environment/Dockerfile.reproduction",
            "containerDefinitionSha256": sha256_file(
                bundle / "environment/Dockerfile.reproduction"
            ),
            "containerTarget": "independent-reproduction",
            "runtimeUser": "reproducer:10001",
            "evaluatedRunNetworkPolicy": "DISABLED",
            "buildNetworkPolicy": "DEPENDENCY_RESOLUTION_ONLY",
            "containerImagePolicy": "BUILD_FROM_DIGEST_PINNED_DEFINITION",
            "launcherRequirements": {
                "python": ">=3.11",
                "jsonschema": "4.25.1",
                "docker": ">=24",
                "shell": "bash",
            },
        },
        "backendIdentities": [backend],
        "inputIdentities": {
            "resultCardHash": card["contentHash"],
            "runIdentity": card["runIdentity"],
            "researchBriefHash": card["researchBrief"]["briefHash"],
            "inventoryHash": card["researchBrief"]["inventoryHash"],
            "modelHash": card["researchBrief"]["modelHash"],
            "candidateLineageHash": card["candidate"]["lineageRoot"],
            "qualificationSuiteRevision": card["pairedUtility"]["suiteRevision"],
            "qualificationSuiteHash": qualification["suiteHash"],
            "qualificationSplitHash": qualification["splitAuditHash"],
        },
        "expectedEvidence": {
            "semanticRoots": dict(sorted(semantic_roots.items())),
            "requiredStatuses": {
                "autonomyClaimAuthorized": True,
                "qualificationQualified": True,
                "targetAccess": "ABSENT",
                "externalNoveltyStatus": "NOT_EVALUATED",
                "promotionStatus": "NOT_EVALUATED",
                "publicEvidenceStatus": "NOT_EVALUATED",
                "correctnessRegressionCount": 0,
            },
            "claimBanner": "NO EXTERNAL NOVELTY CLAIM",
            "exactByteRoot": exact_root(expected_refs),
        },
        "portabilityPolicy": {
            "levels": [
                "EXACT_BYTE_REPRODUCED",
                "SEMANTICALLY_REPRODUCED",
                "NOT_REPRODUCED",
            ],
            "exactBytePaths": exact_paths,
            "semanticOnlyMetadata": [
                "receipt.executionStartedAt",
                "receipt.executionFinishedAt",
                "receipt.environmentDiagnostics",
                "receipt.containerImageId",
            ],
            "unexpectedArtifactDisposition": "RETAIN_AND_REPORT",
            "correctionDisposition": "NEW_IMMUTABLE_ARTIFACT_IDENTITY",
        },
        "commands": {
            "verifyArtifact": (
                "python3 scripts/verify-independent-reproduction.py "
                "verify-artifact --root ."
            ),
            "reproduce": (
                "./reproduce.sh --output /path/to/output "
                "--environment-id anonymous-independent-run"
            ),
            "verifyReceipt": (
                "python3 scripts/verify-independent-reproduction.py "
                "verify-receipt --root . --receipt /path/to/receipt.json"
            ),
        },
        "schemas": [reference(bundle, path) for path in schema_paths],
        "metadata": {
            "license": reference(bundle, "metadata/LICENSE"),
            "citation": reference(bundle, "metadata/CITATION.cff"),
            "codemeta": reference(bundle, "metadata/codemeta.json"),
            "instructions": reference(bundle, "README.md"),
        },
        "files": all_file_references(bundle),
        "externalAttestationStatus": "NOT_COLLECTED",
    }
    manifest["contentHash"] = sha256_bytes(canonical_bytes(manifest))
    (bundle / "artifact-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )

    create_bundle_archive(bundle, archive_output)
    print(manifest["contentHash"])
    print(sha256_file(archive_output))


if __name__ == "__main__":
    main()
