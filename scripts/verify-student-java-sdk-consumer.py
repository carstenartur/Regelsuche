#!/usr/bin/env python3
"""Verify the student-facing SDK through a clean external consumer."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from typing import Callable, Mapping

SDK_MODULES = (
    "regelsuche-core",
    "regelsuche-egraph",
    "regelsuche-search",
    "regelsuche-validation",
    "regelsuche-discovery",
    "regelsuche-discovery-sdk",
)
FORBIDDEN_RUNTIME_MARKERS = (
    "regelsuche-app",
    "regelsuche-persistence",
    "regelsuche-persistence-hibernate",
    "spring-boot",
    "spring-context",
    "hibernate-core",
    "jakarta.persistence",
)
OUTPUT_RELATIVE = Path("build/reports/student-java-sdk")


def run(
        command: list[str],
        cwd: Path,
        extra_environment: Mapping[str, str] | None = None,
) -> str:
    environment = {**os.environ, "LC_ALL": "C.UTF-8"}
    if extra_environment:
        environment.update(extra_environment)
    completed = subprocess.run(
        command,
        cwd=cwd,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
        env=environment,
    )
    if completed.returncode:
        print(completed.stdout, file=sys.stderr)
        raise RuntimeError(
            f"command failed ({completed.returncode}): {' '.join(command)}"
        )
    return completed.stdout


def read_version(root: Path) -> str:
    entries = [
        line.strip().removeprefix("version=").strip()
        for line in (root / "release.properties").read_text(encoding="utf-8").splitlines()
        if line.strip().startswith("version=")
    ]
    if len(entries) != 1 or not entries[0]:
        raise RuntimeError("release.properties must declare one version")
    return entries[0]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def overlaps(left: Path, right: Path) -> bool:
    return left == right or left in right.parents or right in left.parents


def checkout_owned_output(root: Path) -> Path:
    """Return the only removable output tree and reject symlink escapes."""
    output = root / OUTPUT_RELATIVE
    for candidate in (root / "build", root / "build/reports", output):
        if candidate.is_symlink():
            raise RuntimeError(
                f"checkout-owned output path must not traverse symlinks: {candidate}"
            )
    resolved = output.resolve()
    if resolved != output:
        raise RuntimeError(
            f"checkout-owned output escaped its fixed location: {resolved}"
        )
    return output


def one_artifact(
        directory: Path,
        pattern: str,
        accept: Callable[[Path], bool] = lambda path: True,
) -> Path:
    candidates = sorted(path for path in directory.glob(pattern) if accept(path))
    if len(candidates) != 1:
        raise RuntimeError(
            f"expected one {pattern} artifact in {directory}, got "
            f"{[path.name for path in candidates]}"
        )
    return candidates[0]


def artifact_files(repository: Path, version: str) -> dict[str, Path]:
    base = repository / "de" / "regelsuche"
    artifacts: dict[str, Path] = {}
    for module in SDK_MODULES:
        directory = base / module / version
        artifacts[f"{module}:jar"] = one_artifact(
            directory,
            f"{module}-*.jar",
            lambda path: not path.name.endswith(("-sources.jar", "-javadoc.jar")),
        )
        artifacts[f"{module}:pom"] = one_artifact(directory, f"{module}-*.pom")
    sdk = base / "regelsuche-discovery-sdk" / version
    for classifier in ("sources", "javadoc"):
        artifacts[f"regelsuche-discovery-sdk:{classifier}"] = one_artifact(
            sdk,
            f"regelsuche-discovery-sdk-*-{classifier}.jar",
        )
    return artifacts


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository-root", type=Path, default=Path.cwd())
    parser.add_argument("--published-repository", type=Path, required=True)
    parser.add_argument("--gradle", default="gradle")
    arguments = parser.parse_args()

    root = arguments.repository_root.resolve()
    repository = arguments.published_repository.resolve()
    output = checkout_owned_output(root)
    source = root / "examples/external-consumers/geometric-sequence-domain-java25"
    if not repository.is_dir():
        raise RuntimeError(f"SDK repository does not exist: {repository}")
    if not source.is_dir():
        raise RuntimeError(f"external consumer example is missing: {source}")
    if overlaps(repository, output):
        raise RuntimeError("published repository and rebuilt output must be disjoint")
    if overlaps(source, output):
        raise RuntimeError("external consumer source and rebuilt output must be disjoint")
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)

    version = read_version(root)
    artifacts = artifact_files(repository, version)
    consumer = output / "external-consumer"
    shutil.copytree(source, consumer)
    consumer_environment = {
        "GRADLE_USER_HOME": str(output / "isolated-gradle-user-home"),
    }

    properties = [
        f"-PregelsucheRepository={repository}",
        f"-PregelsucheVersion={version}",
    ]
    execution = run(
        [
            arguments.gradle,
            "--no-daemon",
            "--refresh-dependencies",
            "clean",
            "test",
            "run",
            *properties,
        ],
        consumer,
        consumer_environment,
    )
    expected = (
        "provider=example-geometric-sequence-provider",
        "outcome=CONFIRMED",
        "multiplier=2",
    )
    missing = [value for value in expected if value not in execution]
    if missing:
        raise RuntimeError(f"external consumer output is incomplete: {missing}")

    dependencies = run(
        [
            arguments.gradle,
            "--no-daemon",
            "dependencies",
            "--configuration",
            "runtimeClasspath",
            *properties,
        ],
        consumer,
        consumer_environment,
    )
    lowered = dependencies.lower()
    forbidden = [marker for marker in FORBIDDEN_RUNTIME_MARKERS if marker in lowered]
    if forbidden:
        raise RuntimeError(f"forbidden runtime dependencies: {forbidden}")

    java_feature = int(
        run(["java", "-XshowSettings:properties", "-version"], root)
        .split("java.specification.version = ", 1)[1]
        .splitlines()[0]
        .strip()
    )
    if java_feature != 25:
        raise RuntimeError(f"consumer verification requires Java 25, got {java_feature}")

    ledger = {
        name: {
            "path": "repository/" + path.relative_to(repository).as_posix(),
            "bytes": path.stat().st_size,
            "sha256": sha256(path),
        }
        for name, path in sorted(artifacts.items())
    }
    report = {
        "schema": "regelsuche.student-java-sdk-consumer-verification/v1",
        "sdkVersion": version,
        "publicationMode": "CHECKOUT_OWNED_TASK_DEPENDENCIES",
        "dependencyCacheMode": "ISOLATED_EMPTY_GRADLE_USER_HOME",
        "javaFeature": java_feature,
        "externalConsumer": "geometric-sequence-domain-java25",
        "provider": "example-geometric-sequence-provider",
        "confirmedCandidate": "multiplier=2",
        "requiredOutcomes": ["CONFIRMED", "REFUTED", "BUDGET_EXHAUSTED"],
        "forbiddenRuntimeDependenciesObserved": forbidden,
        "artifacts": ledger,
        "result": "success",
    }
    (output / "consumer-report.json").write_text(
        json.dumps(report, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    markdown = (
        "# Student Java SDK consumer verification\n\n"
        f"- SDK version: `{version}`\n"
        "- Publication: checkout-owned Gradle task dependencies\n"
        "- Dependency cache: isolated empty Gradle user home\n"
        "- Java: `25`\n"
        "- External consumer: `geometric-sequence-domain-java25`\n"
        "- ServiceLoader provider: `example-geometric-sequence-provider`\n"
        "- Confirmed candidate: `multiplier=2`\n"
        "- Negative paths: `REFUTED`, `BUDGET_EXHAUSTED`\n"
        "- App/Spring/Hibernate/Persistence dependencies: none observed\n"
        "- Result: `success`\n"
    )
    (output / "consumer-report.md").write_text(markdown, encoding="utf-8")
    (output / "consumer.log").write_text(execution, encoding="utf-8")
    (output / "dependencies.log").write_text(dependencies, encoding="utf-8")
    print(markdown)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
