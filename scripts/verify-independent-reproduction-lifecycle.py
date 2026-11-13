#!/usr/bin/env python3
"""Verify the checkout-local independent reproduction artifact lifecycle."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
from typing import Any

from independent_reproduction_environment import (
    ARTIFACT_V2,
    DESCRIPTOR_SHA256,
    DOCKERFILE_V2,
    ENVIRONMENT_MODULE,
    RECEIPT_V2,
    repository_descriptor,
)

EXPECTED_IMAGE_DIGEST = (
    "sha256:3eb81ed94d8c1a34422f19f8188548bdf02cae69c91d0328afdbb7abed90f617"
)
EXPECTED_IMAGE_FROM = (
    "FROM eclipse-temurin:25.0.3_9-jdk-noble@"
    + EXPECTED_IMAGE_DIGEST
    + " AS build"
)
EXPECTED_LAUNCHERS = {
    "python": ">=3.11",
    "jsonschema": "4.25.1",
    "docker": ">=24",
    "shell": "bash",
}
MARKER_NAME = ".regelsuche-independent-reproduction-output"
MARKER_CONTENT = "regelsuche.independent-reproduction-output/v1\n"


def fail(message: str) -> None:
    raise SystemExit(f"independent reproduction lifecycle invalid: {message}")


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def require_regular_file(path: Path, label: str) -> Path:
    require(path.exists(), f"{label} is missing: {path}")
    require(not path.is_symlink(), f"{label} must not be symbolic: {path}")
    require(path.is_file(), f"{label} must be a regular file: {path}")
    return path


def run(
    command: list[str],
    *,
    cwd: Path,
    expect_success: bool = True,
    environment: dict[str, str] | None = None,
) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        command,
        cwd=cwd,
        env=environment,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
    )
    if expect_success and result.returncode != 0:
        fail(
            f"command failed ({result.returncode}): {' '.join(command)}\n"
            + result.stdout
        )
    if not expect_success and result.returncode == 0:
        fail(f"negative command unexpectedly passed: {' '.join(command)}")
    return result


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


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return "sha256:" + digest.hexdigest()


def tree_entries(root: Path) -> list[tuple[str, str]]:
    require(root.is_dir() and not root.is_symlink(), f"invalid tree root: {root}")
    entries: list[tuple[str, str]] = []
    for current, directories, files in os.walk(root, topdown=True, followlinks=False):
        current_path = Path(current)
        directories.sort()
        files.sort()
        for name in directories:
            path = current_path / name
            require(not path.is_symlink(), f"symbolic directory is forbidden: {path}")
            require(path.is_dir(), f"unsupported directory entry: {path}")
            entries.append(("directory", path.relative_to(root).as_posix()))
        for name in files:
            path = current_path / name
            require(not path.is_symlink(), f"symbolic file is forbidden: {path}")
            require(path.is_file(), f"unsupported file entry: {path}")
            entries.append(("file", path.relative_to(root).as_posix()))
    return sorted(entries)


def require_identical_trees(first: Path, second: Path) -> None:
    first_entries = tree_entries(first)
    second_entries = tree_entries(second)
    require(first_entries == second_entries, "artifact directory trees differ")
    for kind, relative in first_entries:
        if kind == "file":
            require(
                first.joinpath(relative).read_bytes()
                == second.joinpath(relative).read_bytes(),
                f"artifact bytes differ at {relative}",
            )


def require_safe_archive(path: Path) -> list[str]:
    require(path.is_file() and not path.is_symlink(), f"missing archive: {path}")
    names: list[str] = []
    with tarfile.open(path, "r:gz") as archive:
        for member in archive.getmembers():
            name = member.name
            require(name not in names, f"duplicate archive member: {name}")
            names.append(name)
            require(
                name == "regelsuche-independent-reproduction"
                or name.startswith("regelsuche-independent-reproduction/"),
                f"archive member escapes canonical root: {name}",
            )
            parts = Path(name).parts
            require(
                not name.startswith("/") and ".." not in parts,
                f"unsafe archive member: {name}",
            )
            require(
                not member.issym() and not member.islnk(),
                f"archive link is forbidden: {name}",
            )
    require(bool(names), "archive is empty")
    return names


def verify_frozen_sources(repository: Path) -> None:
    descriptor = repository_descriptor(repository)
    wrapper = require_regular_file(
        repository / "gradle/wrapper/gradle-wrapper.properties",
        "Gradle wrapper properties",
    )
    wrapper_lines = wrapper.read_text(encoding="utf-8").splitlines()
    require(
        "distributionSha256Sum=" + descriptor["gradleDistributionSha256"] in wrapper_lines,
        "Gradle wrapper digest differs from the frozen contract",
    )

    dockerfile = require_regular_file(
        repository / DOCKERFILE_V2,
        "reproduction Dockerfile",
    )
    dockerfile_lines = dockerfile.read_text(encoding="utf-8").splitlines()
    require(
        sha256_file(dockerfile) == descriptor["containerDefinitionSha256"],
        "v2 reproduction Dockerfile bytes differ from the frozen contract",
    )
    require(bool(dockerfile_lines), "reproduction Dockerfile is empty")
    require(
        dockerfile_lines[0].strip() == EXPECTED_IMAGE_FROM,
        "reproduction Dockerfile base digest differs from the frozen contract",
    )

    launcher = require_regular_file(
        repository / "reproduction/reproduce.sh",
        "reproduction launcher",
    )
    run(["bash", "-n", str(launcher)], cwd=repository)

    for relative in (
        "scripts/build-independent-reproduction-artifact.py",
        "scripts/verify-independent-reproduction.py",
        "scripts/verify-independent-reproduction-lifecycle.py",
    ):
        source = require_regular_file(repository / relative, relative)
        compile(source.read_text(encoding="utf-8"), relative, "exec")


def verify_artifact(
    repository: Path,
    artifact: Path,
    source_revision: str,
) -> dict[str, Any]:
    run(
        [
            sys.executable,
            str(repository / "scripts/verify-independent-reproduction.py"),
            "verify-artifact",
            "--root",
            str(artifact),
        ],
        cwd=repository,
    )
    manifest = load_unique(artifact / "artifact-manifest.json")
    require(
        manifest.get("artifactStatus")
        == "DEVELOPMENT_READY_FOR_INDEPENDENT_EXECUTION",
        "artifact status is not the conservative development status",
    )
    source = manifest.get("source", {})
    require(
        source.get("revision") == source_revision,
        "artifact source revision differs from Gradle input",
    )
    require(
        source.get("releaseTagStatus") == "DEVELOPMENT_REVISION",
        "artifact release tag status is not DEVELOPMENT_REVISION",
    )
    require(
        manifest.get("externalAttestationStatus") == "NOT_COLLECTED",
        "external attestation must remain NOT_COLLECTED",
    )
    environment = manifest.get("declaredEnvironment", {})
    require(
        environment.get("evaluatedRunNetworkPolicy") == "DISABLED",
        "evaluated run network policy must be DISABLED",
    )
    require(
        environment.get("javaImageIndexDigest") == EXPECTED_IMAGE_DIGEST,
        "manifest Java image digest differs from frozen contract",
    )
    require(
        manifest.get("schema") == ARTIFACT_V2
        and environment.get("environmentDescriptorSha256") == DESCRIPTOR_SHA256
        and environment.get("gradleDistributionSha256")
        == repository_descriptor(repository)["gradleDistributionSha256"],
        "manifest Gradle digest differs from frozen contract",
    )
    require(
        environment.get("launcherRequirements") == EXPECTED_LAUNCHERS,
        "launcher requirements differ from frozen contract",
    )
    return manifest


def builder_command(
    repository: Path,
    walkthrough: Path,
    revision: str,
    release_tag: str,
    release_tag_status: str,
    architecture: str,
    output: Path,
    archive: Path,
) -> list[str]:
    return [
        sys.executable,
        str(repository / "scripts/build-independent-reproduction-artifact.py"),
        "--repository-root",
        str(repository),
        "--walkthrough-root",
        str(walkthrough),
        "--source-revision",
        revision,
        "--release-tag",
        release_tag,
        "--release-tag-status",
        release_tag_status,
        "--architecture",
        architecture,
        "--output-directory",
        str(output),
        "--archive-output",
        str(archive),
    ]


def create_unchecked_commit(repository: Path, source_revision: str) -> str:
    tree = run(
        ["git", "rev-parse", f"{source_revision}^{{tree}}"],
        cwd=repository,
    ).stdout.strip()
    environment = os.environ.copy()
    environment.update({
        "GIT_AUTHOR_NAME": "Regelsuche verification",
        "GIT_AUTHOR_EMAIL": "verification@invalid.example",
        "GIT_COMMITTER_NAME": "Regelsuche verification",
        "GIT_COMMITTER_EMAIL": "verification@invalid.example",
        "GIT_AUTHOR_DATE": "2000-01-01T00:00:00Z",
        "GIT_COMMITTER_DATE": "2000-01-01T00:00:00Z",
    })
    return run(
        ["git", "commit-tree", tree, "-p", source_revision],
        cwd=repository,
        environment=environment,
    ).stdout.strip()


def verify_source_negatives(
    repository: Path,
    artifact: Path,
    manifest: dict[str, Any],
    source_revision: str,
    temporary: Path,
) -> None:
    source = manifest["source"]
    environment = manifest["declaredEnvironment"]
    walkthrough = artifact / "expected"
    mismatch_revision = create_unchecked_commit(repository, source_revision)
    mismatch = run(
        builder_command(
            repository,
            walkthrough,
            mismatch_revision,
            "development-" + mismatch_revision,
            source["releaseTagStatus"],
            environment["architecture"],
            temporary / "mismatch",
            temporary / "mismatch.tar.gz",
        ),
        cwd=repository,
        expect_success=False,
    )
    require(
        "checkout does not match" in mismatch.stdout,
        "mismatched revision did not retain the expected diagnostic",
    )

    readme = require_regular_file(
        repository / "reproduction/README.md",
        "reproduction README",
    )
    original = readme.read_bytes()
    original_mode = stat.S_IMODE(readme.stat().st_mode)
    try:
        readme.write_bytes(original + b"\ncheckout-local-dirty-negative\n")
        dirty = run(
            builder_command(
                repository,
                walkthrough,
                source_revision,
                source["releaseTag"],
                source["releaseTagStatus"],
                environment["architecture"],
                temporary / "dirty",
                temporary / "dirty.tar.gz",
            ),
            cwd=repository,
            expect_success=False,
        )
        require(
            "worktree must be clean" in dirty.stdout,
            "dirty checkout did not retain the expected diagnostic",
        )
    finally:
        readme.write_bytes(original)
        readme.chmod(original_mode)
    status = run(
        ["git", "status", "--porcelain=v1", "--", "reproduction/README.md"],
        cwd=repository,
    ).stdout
    require(
        not status.strip(),
        "dirty-checkout negative did not restore README bytes",
    )


def reproduction_environment() -> dict[str, str]:
    environment = os.environ.copy()
    executable_directory = str(Path(sys.executable).resolve().parent)
    environment["PATH"] = (
        executable_directory + os.pathsep + environment.get("PATH", "")
    )
    return environment


def verify_packaged_launcher(
    repository: Path, archive_path: Path, manifest: dict[str, Any], temporary: Path
) -> None:
    extracted = temporary / "public-launcher"
    with tarfile.open(archive_path, "r:gz") as archive:
        archive.extractall(extracted, filter="data")
    artifact = extracted / "regelsuche-independent-reproduction"
    result = run(
        [str(artifact / "reproduce.sh"), "--verify-only"],
        cwd=repository,
        environment=reproduction_environment(),
    )
    require(
        manifest["contentHash"] in result.stdout.splitlines(),
        "public launcher did not verify the extracted manifest identity",
    )
    require(
        not (artifact / "scripts/__pycache__").exists(),
        "public launcher wrote unlisted files into the extracted artifact",
    )


def verify_marker_negatives(repository: Path, artifact: Path, temporary: Path) -> None:
    environment = reproduction_environment()
    for label, symbolic in (("wrong", False), ("symlink", True)):
        output = temporary / (label + "-marker")
        output.mkdir()
        (output / "sentinel.txt").write_text("sentinel\n", encoding="utf-8")
        marker = output / MARKER_NAME
        if symbolic:
            target = output / "marker-target.txt"
            target.write_text(MARKER_CONTENT, encoding="utf-8")
            marker.symlink_to(target.name)
        else:
            marker.write_text("wrong-marker-version\n", encoding="utf-8")
        result = run(
            [
                str(artifact / "reproduce.sh"),
                "--output",
                str(output),
                "--environment-id",
                "checkout-local-marker-negative",
            ],
            cwd=repository,
            expect_success=False,
            environment=environment,
        )
        require(
            (output / "sentinel.txt").is_file(),
            f"{label} marker negative removed unrelated sentinel",
        )
        require(
            "exact regular reproduction marker" in result.stdout,
            f"{label} marker negative lost ownership diagnostic",
        )
        if symbolic:
            require(marker.is_symlink(), "symbolic marker was replaced")


def compare_command(
    repository: Path,
    artifact: Path,
    observed: Path,
    receipt: Path,
    *,
    environment_id: str,
    started_at: str,
    finished_at: str,
    execution_exit_code: int,
    container_image_id: str = "sha256:checkout-local-non-semantic-image",
    platform: str = "checkout-local-platform",
    docker_version: str = "checkout-local-docker",
) -> list[str]:
    return [
        sys.executable,
        str(repository / "scripts/verify-independent-reproduction.py"),
        "compare",
        "--root",
        str(artifact),
        "--observed",
        str(observed),
        "--receipt",
        str(receipt),
        "--environment-id",
        environment_id,
        "--started-at",
        started_at,
        "--finished-at",
        finished_at,
        "--container-image-id",
        container_image_id,
        "--execution-exit-code",
        str(execution_exit_code),
        "--platform",
        platform,
        "--docker-version",
        docker_version,
    ]


def verify_receipt(repository: Path, artifact: Path, receipt: Path) -> dict[str, Any]:
    run(
        [
            sys.executable,
            str(repository / "scripts/verify-independent-reproduction.py"),
            "verify-receipt",
            "--root",
            str(artifact),
            "--receipt",
            str(receipt),
        ],
        cwd=repository,
    )
    return load_unique(receipt)


def verify_environment_negatives(repository: Path, artifact: Path, temporary: Path) -> None:
    negative_root = temporary / "environment-negatives"
    shutil.copytree(artifact, negative_root)
    manifest_path = negative_root / "artifact-manifest.json"
    manifest_bytes = manifest_path.read_bytes()
    command = [
        sys.executable,
        str(repository / "scripts/verify-independent-reproduction.py"),
        "verify-artifact", "--root", str(negative_root),
    ]

    manifest = json.loads(manifest_bytes)
    manifest["schema"] = "regelsuche.independent-reproduction-artifact/v3"
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    result = run(command, cwd=repository, expect_success=False)
    require("unsupported artifact manifest schema" in result.stdout, "unknown version passed")
    receipt = temporary / "unknown-version-receipt.json"
    result = run(
        compare_command(
            repository, negative_root, artifact / "expected", receipt,
            environment_id="checkout-local-unknown-version",
            started_at="2034-01-01T00:00:00Z",
            finished_at="2034-01-01T00:00:01Z",
            execution_exit_code=125,
        ),
        cwd=repository,
        expect_success=False,
    )
    require("unsupported artifact manifest schema" in result.stdout,
            "compare did not reject the unknown artifact version")
    require(not receipt.exists(), "compare created a receipt for an unknown artifact version")
    result = run(
        [*command[:2], "verify-receipt", "--root", str(negative_root),
         "--receipt", str(receipt)],
        cwd=repository,
        expect_success=False,
    )
    require("unsupported artifact manifest schema" in result.stdout,
            "receipt verification did not reject the unknown artifact version")
    manifest_path.write_bytes(manifest_bytes)

    descriptor_path = negative_root / "environment/environment.json"
    descriptor_bytes = descriptor_path.read_bytes()
    descriptor_path.write_bytes(descriptor_bytes + b"\n")
    result = run(command, cwd=repository, expect_success=False)
    require("descriptor bytes differ" in result.stdout, "descriptor mismatch passed")
    descriptor_path.write_bytes(descriptor_bytes)

    artifact_schema_path = negative_root / "schemas/artifact-manifest.schema.json"
    artifact_schema_bytes = artifact_schema_path.read_bytes()
    artifact_schema_path.write_bytes(b"{malformed schema")
    result = run(command, cwd=repository, expect_success=False)
    require(
        "artifact manifest schema validation failed: schema bytes differ from the frozen contract"
        in result.stdout,
        "changed artifact schema was interpreted before the byte-identity check",
    )
    artifact_schema_path.write_bytes(artifact_schema_bytes)

    receipt_schema_path = negative_root / "schemas/reproduction-receipt.schema.json"
    receipt_schema_path.write_bytes((repository / (
        "docs/schemas/regelsuche-independent-reproduction-receipt-v1.schema.json"
    )).read_bytes())
    result = run(command, cwd=repository, expect_success=False)
    require("artifact SHA-256 mismatch: schemas/reproduction-receipt.schema.json"
            in result.stdout, "receipt schema substitution passed")
    receipt_schema_path.write_bytes((artifact / "schemas/reproduction-receipt.schema.json").read_bytes())

    for relative, verify_command, diagnostic in (
        (
            ENVIRONMENT_MODULE, command,
            f"manifest v2 environment file is missing: {ENVIRONMENT_MODULE}",
        ),
        (
            "source/regelsuche-source.tar.gz",
            [str(negative_root / "reproduce.sh"), "--verify-only"],
            "v2 source archive is absent from the file inventory",
        ),
    ):
        original = artifact / relative
        omitted = negative_root / relative
        omitted.unlink()
        manifest = json.loads(manifest_bytes)
        manifest["files"] = [item for item in manifest["files"] if item["path"] != relative]
        del manifest["contentHash"]
        material = json.dumps(manifest, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        manifest["contentHash"] = "sha256:" + hashlib.sha256(material.encode("utf-8")).hexdigest()
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        result = run(
            verify_command, cwd=repository, expect_success=False,
            environment=reproduction_environment(),
        )
        require(diagnostic in result.stdout, f"omitted bundle file passed: {relative}")
        shutil.copy2(original, omitted)


def verify_receipt_semantics(
    repository: Path,
    artifact: Path,
    reports: Path,
    temporary: Path,
) -> dict[str, str]:
    observed = artifact / "expected"
    first_path = reports / "reproduction-receipt-first.json"
    second_path = reports / "reproduction-receipt-second-metadata.json"
    run(
        compare_command(
            repository,
            artifact,
            observed,
            first_path,
            environment_id="checkout-local-self-test",
            started_at="2029-01-01T00:00:00Z",
            finished_at="2029-01-01T00:00:01Z",
            execution_exit_code=0,
        ),
        cwd=repository,
    )
    run(
        compare_command(
            repository,
            artifact,
            observed,
            second_path,
            environment_id="checkout-local-self-test",
            started_at="2030-01-01T00:00:00Z",
            finished_at="2030-01-01T00:00:01Z",
            execution_exit_code=0,
            container_image_id="sha256:alternate-non-semantic-image",
            platform="alternate-non-semantic-platform",
            docker_version="alternate-non-semantic-docker",
        ),
        cwd=repository,
    )
    first = verify_receipt(repository, artifact, first_path)
    second = verify_receipt(repository, artifact, second_path)
    require(first["schema"] == second["schema"] == RECEIPT_V2, "receipt schema is not v2")
    require(
        first["reproductionStatus"] == "EXACT_BYTE_REPRODUCED",
        "first receipt is not exact",
    )
    require(
        second["reproductionStatus"] == "EXACT_BYTE_REPRODUCED",
        "second receipt is not exact",
    )
    require(
        first["semanticReceiptHash"] == second["semanticReceiptHash"],
        "non-semantic metadata changed semantic receipt identity",
    )
    require(
        first["reproducerAttestationHash"]
        == second["reproducerAttestationHash"],
        "non-semantic metadata changed reproducer attestation identity",
    )
    require(
        first["contentHash"] != second["contentHash"],
        "different retained metadata must change complete receipt content hash",
    )
    require(
        first["externalAttestationStatus"] == "NOT_COLLECTED",
        "self-test must not claim external attestation",
    )

    missing_root = temporary / "missing-artifact"
    shutil.copytree(artifact, missing_root)
    (missing_root / "expected/result-card.md").unlink()
    missing_receipt = reports / "reproduction-receipt-missing-input.json"
    run(
        compare_command(
            repository,
            missing_root,
            observed,
            missing_receipt,
            environment_id="checkout-local-negative",
            started_at="2031-01-01T00:00:00Z",
            finished_at="2031-01-01T00:00:01Z",
            execution_exit_code=125,
        ),
        cwd=repository,
        expect_success=False,
    )
    missing = verify_receipt(repository, missing_root, missing_receipt)
    require(
        missing["reproductionStatus"] == "NOT_REPRODUCED",
        "missing input did not retain NOT_REPRODUCED",
    )
    require(
        "missing artifact file: expected/result-card.md"
        in missing["semanticComparison"]["inputArtifactFailures"],
        "missing input receipt lost the exact failure",
    )

    corrupt_root = temporary / "corrupt-artifact"
    shutil.copytree(artifact, corrupt_root)
    (corrupt_root / "artifact-manifest.json").write_text(
        "{not-json\n",
        encoding="utf-8",
    )
    corrupt_receipt = reports / "reproduction-receipt-unreadable-manifest.json"
    run(
        compare_command(
            repository,
            corrupt_root,
            observed,
            corrupt_receipt,
            environment_id="checkout-local-negative",
            started_at="2032-01-01T00:00:00Z",
            finished_at="2032-01-01T00:00:01Z",
            execution_exit_code=125,
        ),
        cwd=repository,
        expect_success=False,
    )
    corrupt = verify_receipt(repository, corrupt_root, corrupt_receipt)
    require(corrupt["schema"] == RECEIPT_V2, "corrupt artifact lost v2 receipt identity")
    require(
        corrupt["reproductionStatus"] == "NOT_REPRODUCED",
        "unreadable manifest did not retain NOT_REPRODUCED",
    )
    require(
        corrupt["artifactManifestHashSource"] == "FILE_SHA256_FALLBACK",
        "unreadable manifest did not retain fallback file identity",
    )

    (corrupt_root / "environment/environment.json").unlink()
    missing_descriptor_receipt = reports / "reproduction-receipt-missing-descriptor.json"
    run(
        compare_command(
            repository, corrupt_root, observed, missing_descriptor_receipt,
            environment_id="checkout-local-negative",
            started_at="2032-01-01T00:00:00Z",
            finished_at="2032-01-01T00:00:01Z",
            execution_exit_code=125,
        ),
        cwd=repository,
        expect_success=False,
    )
    damaged = verify_receipt(repository, corrupt_root, missing_descriptor_receipt)
    require(
        damaged["schema"] == RECEIPT_V2 and damaged["reproductionStatus"] == "NOT_REPRODUCED",
        "missing descriptor must preserve the frozen v2 negative receipt",
    )
    require(
        any(message.startswith("invalid v2 reproduction environment descriptor:")
            for message in damaged["semanticComparison"]["inputArtifactFailures"]),
        "missing descriptor diagnostic was lost",
    )

    extra_observed = temporary / "extra-observed"
    shutil.copytree(observed, extra_observed)
    (extra_observed / "unexpected-empty-directory").mkdir()
    extra_receipt = reports / "reproduction-receipt-extra-directory.json"
    run(
        compare_command(
            repository,
            artifact,
            extra_observed,
            extra_receipt,
            environment_id="checkout-local-negative",
            started_at="2033-01-01T00:00:00Z",
            finished_at="2033-01-01T00:00:01Z",
            execution_exit_code=0,
        ),
        cwd=repository,
    )
    extra = verify_receipt(repository, artifact, extra_receipt)
    require(
        extra["reproductionStatus"] == "SEMANTICALLY_REPRODUCED",
        "extra empty directory must remain a semantic-only reproduction",
    )
    require(
        extra["exactComparison"]["unexpectedPaths"]
        == ["unexpected-empty-directory"],
        "extra directory receipt lost the exact unexpected path",
    )
    return {
        "semanticReceiptHash": first["semanticReceiptHash"],
        "reproducerAttestationHash": first["reproducerAttestationHash"],
    }


def write_reports(
    reports: Path,
    source_revision: str,
    archive_hash: str,
    receipt_hashes: dict[str, str],
) -> None:
    summary = {
        "schema": "regelsuche.independent-reproduction-lifecycle-verification/v1",
        "status": "VERIFIED_CHECKOUT_LOCAL",
        "sourceRevision": source_revision,
        "archiveSha256": archive_hash,
        "semanticReceiptHash": receipt_hashes["semanticReceiptHash"],
        "reproducerAttestationHash": receipt_hashes["reproducerAttestationHash"],
        "containerExecutionStatus": "SEPARATE_TESTCONTAINERS_CONTRACT",
        "externalAttestationStatus": "NOT_COLLECTED",
        "claimPolicy": "REPRODUCTION_DOES_NOT_IMPLY_EXTERNAL_NOVELTY",
    }
    reports.mkdir(parents=True, exist_ok=True)
    (reports / "lifecycle-verification.json").write_text(
        json.dumps(summary, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    (reports / "lifecycle-summary.md").write_text(
        "# Independent reproduction lifecycle verification\n\n"
        f"- Status: `{summary['status']}`\n"
        f"- Source revision: `{source_revision}`\n"
        f"- Archive SHA-256: `{archive_hash}`\n"
        f"- Semantic receipt: `{summary['semanticReceiptHash']}`\n"
        "- Container execution: separate checkout-local Testcontainers contract\n"
        "- External attestation: `NOT_COLLECTED`\n"
        "- Claim boundary: reproduction does not imply external mathematical novelty.\n",
        encoding="utf-8",
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository-root", type=Path, required=True)
    parser.add_argument("--artifact-a", type=Path, required=True)
    parser.add_argument("--artifact-b", type=Path, required=True)
    parser.add_argument("--archive-a", type=Path, required=True)
    parser.add_argument("--archive-b", type=Path, required=True)
    parser.add_argument("--source-revision", required=True)
    parser.add_argument("--report-directory", type=Path, required=True)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    repository = args.repository_root.resolve()
    artifact_a = args.artifact_a.resolve()
    artifact_b = args.artifact_b.resolve()
    archive_a = args.archive_a.resolve()
    archive_b = args.archive_b.resolve()
    reports = args.report_directory.resolve()

    verify_frozen_sources(repository)
    require_identical_trees(artifact_a, artifact_b)
    require(
        archive_a.read_bytes() == archive_b.read_bytes(),
        "artifact archives are not byte-identical",
    )
    require_safe_archive(archive_a)
    require_safe_archive(archive_b)
    manifest_a = verify_artifact(repository, artifact_a, args.source_revision)
    manifest_b = verify_artifact(repository, artifact_b, args.source_revision)
    require(manifest_a == manifest_b, "artifact manifests differ")

    if reports.exists():
        shutil.rmtree(reports)
    reports.mkdir(parents=True)
    with tempfile.TemporaryDirectory(prefix="regelsuche-reproduction-") as raw:
        temporary = Path(raw)
        verify_source_negatives(
            repository,
            artifact_a,
            manifest_a,
            args.source_revision,
            temporary,
        )
        verify_packaged_launcher(repository, archive_a, manifest_a, temporary)
        verify_marker_negatives(repository, artifact_a, temporary)
        verify_environment_negatives(repository, artifact_a, temporary)
        receipt_hashes = verify_receipt_semantics(
            repository,
            artifact_a,
            reports,
            temporary,
        )
    write_reports(
        reports,
        args.source_revision,
        sha256_file(archive_a),
        receipt_hashes,
    )
    print(f"verified independent reproduction lifecycle: {reports}")


if __name__ == "__main__":
    main()
