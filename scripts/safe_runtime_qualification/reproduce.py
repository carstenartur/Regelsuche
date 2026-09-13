#!/usr/bin/env python3
"""Reproduce the public SAFE runtime qualification in isolated environments."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
from typing import Sequence


REVISION_PATTERN = re.compile(r"[0-9a-f]{40}")
PLATFORM = "linux/amd64"
IMAGE_REPOSITORY = "regelsuche-safe-runtime-qualification"
IMAGE_BASE_DIGEST = (
    "sha256:3eb81ed94d8c1a34422f19f8188548bdf02cae69c91d0328afdbb7abed90f617"
)
EXPECTED_DOCKER_FROM = (
    "FROM eclipse-temurin:25.0.3_9-jdk-noble@"
    + IMAGE_BASE_DIGEST
    + " AS build"
)
MANIFEST_RELATIVE = Path("config/qualification/safe-runtime-public-v1.json")
SCHEMA_RELATIVE = Path(
    "docs/schemas/regelsuche-safe-runtime-product-qualification-v1.schema.json"
)
DOCKERFILE_RELATIVE = Path("Dockerfile.safe-runtime-qualification")
INPUTS_RELATIVE = Path("build/reports/safe-runtime-product-qualification/inputs")
APP_HOME_RELATIVE = Path("app/build/install/app")
PYTHON_RELATIVE = Path("build/verification-venv/bin/python")


class ReproductionError(RuntimeError):
    """Raised when independent reproduction cannot be established."""


def require_clean_revision(repository: Path, revision: str) -> None:
    """Require an exact clean source commit before creating reproductions."""
    repository = repository.resolve()
    if not repository.is_dir() or repository.is_symlink():
        raise ReproductionError(f"repository is not a regular directory: {repository}")
    if REVISION_PATTERN.fullmatch(revision) is None:
        raise ReproductionError("revision must be a lowercase 40-character commit SHA")
    actual = capture(
        ["git", "rev-parse", "HEAD^{commit}"], cwd=repository, label="source revision"
    ).strip()
    if actual != revision:
        raise ReproductionError(
            f"repository revision differs: expected {revision}, actual {actual}"
        )
    status = capture(
        ["git", "status", "--porcelain", "--untracked-files=normal"],
        cwd=repository,
        label="source status",
    )
    if status.strip():
        raise ReproductionError("repository is dirty; reproduction requires committed inputs")


def require_executable(name: str, search_path: str | None = None) -> str:
    """Resolve one mandatory external executable."""
    resolved = shutil.which(name, path=search_path)
    if resolved is None:
        raise ReproductionError(f"required executable is missing: {name}")
    return resolved


def compare_canonical_outputs(first: Path, second: Path, container: Path) -> str:
    """Compare the complete canonical qualification output byte for byte."""
    roots = (first, second, container)
    snapshots = [canonical_snapshot(root) for root in roots]
    expected_paths = set(snapshots[0])
    for root, snapshot in zip(roots[1:], snapshots[1:]):
        if set(snapshot) != expected_paths:
            raise ReproductionError(
                f"canonical output file set differs for {root.name}"
            )
    for relative in sorted(expected_paths):
        expected = snapshots[0][relative]
        for root, snapshot in zip(roots[1:], snapshots[1:]):
            if snapshot[relative] != expected:
                raise ReproductionError(
                    f"canonical output bytes differ for {root.name}/{relative}"
                )
    digest = hashlib.sha256()
    for relative in sorted(expected_paths):
        digest.update(relative.encode("utf-8"))
        digest.update(b"\0")
        digest.update(snapshots[0][relative])
        digest.update(b"\0")
    return "sha256:" + digest.hexdigest()


def canonical_snapshot(root: Path) -> dict[str, bytes]:
    if not root.is_dir() or root.is_symlink():
        raise ReproductionError(f"missing canonical output root: {root.name}")
    qualification = root / "qualification.json"
    artifacts = root / "artifacts"
    require_regular_file(qualification, "qualification report")
    if not artifacts.is_dir() or artifacts.is_symlink():
        raise ReproductionError(f"missing canonical artifact directory: {root.name}")

    unexpected = sorted(
        path.name
        for path in root.iterdir()
        if path.name not in {"qualification.json", "artifacts", "diagnostics"}
    )
    if unexpected:
        raise ReproductionError(
            f"unexpected files outside canonical output: {unexpected}"
        )

    snapshot = {"qualification.json": qualification.read_bytes()}
    for current, directories, files in os.walk(
        artifacts, topdown=True, followlinks=False
    ):
        current_path = Path(current)
        directories.sort()
        files.sort()
        for name in directories:
            directory = current_path / name
            if directory.is_symlink() or not directory.is_dir():
                raise ReproductionError(
                    f"canonical artifact directory is not regular: {directory}"
                )
        for name in files:
            path = current_path / name
            require_regular_file(path, "canonical artifact")
            relative = path.relative_to(root).as_posix()
            snapshot[relative] = path.read_bytes()
    if len(snapshot) == 1:
        raise ReproductionError(f"canonical artifact set is empty: {root.name}")
    return snapshot


def require_regular_file(path: Path, label: str) -> Path:
    if not path.is_file() or path.is_symlink():
        raise ReproductionError(f"{label} is not a regular file: {path}")
    return path


def capture(command: Sequence[str], *, cwd: Path, label: str) -> str:
    result = subprocess.run(
        list(command),
        cwd=cwd,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
    )
    if result.returncode != 0:
        raise ReproductionError(
            f"{label} failed ({result.returncode}): {' '.join(command)}\n{result.stdout}"
        )
    return result.stdout


def run_logged(
    command: Sequence[str], *, cwd: Path, log: Path, label: str
) -> str:
    result = subprocess.run(
        list(command),
        cwd=cwd,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
    )
    log.parent.mkdir(parents=True, exist_ok=True)
    log.write_text(result.stdout, encoding="utf-8")
    if result.returncode != 0:
        raise ReproductionError(
            f"{label} failed ({result.returncode}); see {log}"
        )
    return result.stdout


def checkout_run(
    checkout: Path,
    revision: str,
    output: Path,
    diagnostics: Path,
) -> None:
    require_clean_revision(checkout, revision)
    gradlew = require_regular_file(checkout / "gradlew", "Gradle wrapper")
    gradlew.chmod(gradlew.stat().st_mode | 0o100)
    run_logged(
        [
            str(gradlew),
            "--no-daemon",
            "--no-configuration-cache",
            "--no-build-cache",
            "--max-workers=2",
            ":app:installDist",
            ":regelsuche-learning:exportSafeRuntimeProductQualificationInputs",
            "verifySafeRuntimeProductQualificationInputs",
            "prepareVerificationEnvironment",
            "--console=plain",
        ],
        cwd=checkout,
        log=diagnostics / "gradle.log",
        label="clean-checkout build",
    )
    require_clean_revision(checkout, revision)
    python = require_regular_file(
        checkout / PYTHON_RELATIVE, "checkout verification Python"
    )
    inputs = checkout / INPUTS_RELATIVE
    app_home = checkout / APP_HOME_RELATIVE
    runner = require_regular_file(
        checkout / "scripts/safe_runtime_qualification/run.py", "runtime runner"
    )
    verifier = require_regular_file(
        checkout / "scripts/safe_runtime_qualification/verify.py", "runtime verifier"
    )
    output.mkdir(parents=True)
    run_logged(
        [
            str(python),
            "-B",
            str(runner),
            "--app-home",
            str(app_home),
            "--inputs",
            str(inputs),
            "--manifest",
            str(checkout / MANIFEST_RELATIVE),
            "--revision",
            revision,
            "--output",
            str(output),
        ],
        cwd=checkout,
        log=diagnostics / "run.log",
        label="clean-checkout runtime qualification",
    )
    run_logged(
        [
            str(python),
            "-B",
            str(verifier),
            "--root",
            str(output),
            "--manifest",
            str(checkout / MANIFEST_RELATIVE),
            "--schema",
            str(checkout / SCHEMA_RELATIVE),
            "--app-home",
            str(app_home),
            "--inputs",
            str(inputs),
            "--revision",
            revision,
        ],
        cwd=checkout,
        log=diagnostics / "verify.log",
        label="clean-checkout independent verification",
    )


def container_run(
    repository: Path,
    revision: str,
    output: Path,
    diagnostics: Path,
) -> str:
    docker = require_executable("docker")
    dockerfile = require_regular_file(
        repository / DOCKERFILE_RELATIVE, "qualification Dockerfile"
    )
    first_line = dockerfile.read_text(encoding="utf-8").splitlines()[0].strip()
    if first_line != EXPECTED_DOCKER_FROM:
        raise ReproductionError("qualification Dockerfile base image digest differs")
    image = f"{IMAGE_REPOSITORY}:{revision}"
    run_logged(
        [
            docker,
            "build",
            "--pull",
            "--platform",
            PLATFORM,
            "--build-arg",
            f"REGELSUCHE_REPOSITORY_REVISION={revision}",
            "--label",
            f"org.opencontainers.image.revision={revision}",
            "-f",
            str(dockerfile),
            "-t",
            image,
            str(repository),
        ],
        cwd=repository,
        log=diagnostics / "docker-build.log",
        label="qualification container build",
    )
    image_id = capture(
        [docker, "image", "inspect", "--format", "{{.Id}}", image],
        cwd=repository,
        label="qualification image inspection",
    ).strip()
    if re.fullmatch(r"sha256:[0-9a-f]{64}", image_id) is None:
        raise ReproductionError(f"invalid qualification image ID: {image_id}")
    output.mkdir(parents=True)
    output.chmod(0o777)
    run_logged(
        [
            docker,
            "run",
            "--rm",
            "--network",
            "none",
            "--platform",
            PLATFORM,
            "-v",
            f"{output.resolve()}:/out",
            image,
        ],
        cwd=repository,
        log=diagnostics / "docker-run.log",
        label="offline qualification container run",
    )
    return image_id


def reproduce(repository: Path, revision: str, output: Path) -> dict[str, object]:
    repository = repository.resolve()
    output = output.resolve()
    require_clean_revision(repository, revision)
    require_executable("git")
    require_executable("docker")
    for relative, label in (
        (MANIFEST_RELATIVE, "qualification manifest"),
        (SCHEMA_RELATIVE, "qualification schema"),
        (DOCKERFILE_RELATIVE, "qualification Dockerfile"),
    ):
        require_regular_file(repository / relative, label)
    if output.exists() and any(output.iterdir()):
        raise ReproductionError("reproduction output directory must be empty")
    output.mkdir(parents=True, exist_ok=True)
    diagnostics = output / "diagnostics"
    diagnostics.mkdir()

    with tempfile.TemporaryDirectory(prefix="regelsuche-safe-runtime-") as temporary:
        worktree_root = Path(temporary)
        checkouts: list[Path] = []
        try:
            for label in ("checkout-a", "checkout-b"):
                checkout = worktree_root / label
                run_logged(
                    [
                        "git",
                        "worktree",
                        "add",
                        "--detach",
                        str(checkout),
                        revision,
                    ],
                    cwd=repository,
                    log=diagnostics / f"{label}-worktree.log",
                    label=f"{label} creation",
                )
                checkouts.append(checkout)
                checkout_run(
                    checkout,
                    revision,
                    output / label,
                    diagnostics / label,
                )

            image_id = container_run(
                repository,
                revision,
                output / "container",
                diagnostics / "container",
            )
            tree_hash = compare_canonical_outputs(
                output / "checkout-a",
                output / "checkout-b",
                output / "container",
            )
        finally:
            for checkout in reversed(checkouts):
                if checkout.exists():
                    run_logged(
                        ["git", "worktree", "remove", str(checkout)],
                        cwd=repository,
                        log=diagnostics / f"{checkout.name}-cleanup.log",
                        label=f"{checkout.name} cleanup",
                    )

    receipt: dict[str, object] = {
        "schema": "regelsuche.safe-runtime-product-qualification-reproduction/v1",
        "repositoryRevision": revision,
        "canonicalOutputTreeHash": tree_hash,
        "containerImageId": image_id,
        "containerBaseImageDigest": IMAGE_BASE_DIGEST,
        "containerPlatform": PLATFORM,
        "environments": ["checkout-a", "checkout-b", "container"],
    }
    (output / "reproduction.json").write_text(
        json.dumps(
            receipt,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        + "\n",
        encoding="utf-8",
    )
    return receipt


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository-root", required=True, type=Path)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        receipt = reproduce(args.repository_root, args.revision, args.output)
    except ReproductionError as error:
        raise SystemExit(f"safe runtime reproduction failed: {error}") from error
    print(
        "safe runtime reproduction: VERIFIED "
        + str(receipt["canonicalOutputTreeHash"])
    )


if __name__ == "__main__":
    main()
