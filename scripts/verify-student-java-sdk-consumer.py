#!/usr/bin/env python3
"""Build and verify the Regelsuche student-facing SDK from a clean consumer boundary."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from typing import Callable, Iterable

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


def run(command: list[str], cwd: Path) -> str:
    completed = subprocess.run(
        command,
        cwd=cwd,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
        env={**os.environ, "LC_ALL": "C.UTF-8"},
    )
    if completed.returncode != 0:
        print(completed.stdout, file=sys.stderr)
        raise RuntimeError(
            f"command failed with exit code {completed.returncode}: "
            + " ".join(command)
        )
    return completed.stdout


def read_version(root: Path) -> str:
    entries = []
    for line in (root / "release.properties").read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if stripped.startswith("version="):
            entries.append(stripped.removeprefix("version=").strip())
    if len(entries) != 1 or not entries[0]:
        raise RuntimeError("release.properties must declare exactly one version")
    return entries[0]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def one_artifact(
        directory: Path,
        pattern: str,
        accept: Callable[[Path], bool] = lambda path: True
) -> Path:
    candidates = sorted(path for path in directory.glob(pattern) if accept(path))
    if len(candidates) != 1:
        names = [path.name for path in candidates]
        raise RuntimeError(
            f"expected one artifact matching {pattern} in {directory}, got {names}"
        )
    return candidates[0]


def artifact_files(repository: Path, version: str) -> dict[str, Path]:
    base = repository / "de" / "regelsuche"
    required: dict[str, Path] = {}
    for module in SDK_MODULES:
        directory = base / module / version
        jar = one_artifact(
            directory,
            f"{module}-*.jar",
            lambda path: not path.name.endswith("-sources.jar")
                and not path.name.endswith("-javadoc.jar"),
        )
        pom = one_artifact(directory, f"{module}-*.pom")
        required[f"{module}:jar"] = jar
        required[f"{module}:pom"] = pom
    sdk_dir = base / "regelsuche-discovery-sdk" / version
    for classifier in ("sources", "javadoc"):
        path = one_artifact(
            sdk_dir,
            f"regelsuche-discovery-sdk-*-{classifier}.jar",
        )
        required[f"regelsuche-discovery-sdk:{classifier}"] = path
    return required


def assert_no_forbidden_dependencies(report: str) -> None:
    lowered = report.lower()
    forbidden = [marker for marker in FORBIDDEN_RUNTIME_MARKERS if marker in lowered]
    if forbidden:
        raise RuntimeError(
            "external consumer pulled forbidden application/infrastructure dependencies: "
            + ", ".join(forbidden)
        )


def publication_tasks() -> Iterable[str]:
    for module in SDK_MODULES:
        yield f":{module}:publishMavenJavaPublicationToStudentSdkRepository"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository-root", type=Path, default=Path.cwd())
    parser.add_argument("--gradle", default="gradle")
    parser.add_argument("--output", type=Path)
    arguments = parser.parse_args()

    root = arguments.repository_root.resolve()
    version = read_version(root)
    output = (arguments.output or root / "build" / "reports" / "student-java-sdk").resolve()
    local_repository = output / "repository"
    consumer_checkout = output / "external-consumer"
    if output.exists():
        shutil.rmtree(output)
    local_repository.mkdir(parents=True)

    publish_command = [
        arguments.gradle,
        "--no-daemon",
        "--no-configuration-cache",
        f"-PstudentSdkRepository={local_repository}",
        *publication_tasks(),
    ]
    publish_output = run(publish_command, root)
    artifacts = artifact_files(local_repository, version)

    source_example = root / "examples" / "external-consumers" / "geometric-sequence-domain-java25"
    if not source_example.is_dir():
        raise RuntimeError(f"missing external consumer example: {source_example}")
    shutil.copytree(source_example, consumer_checkout)

    consumer_properties = [
        f"-PregelsucheRepository={local_repository}",
        f"-PregelsucheVersion={version}",
    ]
    test_output = run(
        [arguments.gradle, "--no-daemon", "clean", "test", "run", *consumer_properties],
        consumer_checkout,
    )
    if "provider=example-geometric-sequence-provider" not in test_output:
        raise RuntimeError("ServiceLoader provider was not reported by the external example")
    if "outcome=CONFIRMED" not in test_output:
        raise RuntimeError("external example did not produce a confirmed run")
    if "multiplier=2" not in test_output:
        raise RuntimeError("external example did not recover multiplier 2")

    dependency_output = run(
        [
            arguments.gradle,
            "--no-daemon",
            "dependencies",
            "--configuration",
            "runtimeClasspath",
            *consumer_properties,
        ],
        consumer_checkout,
    )
    assert_no_forbidden_dependencies(dependency_output)

    artifact_ledger = {
        name: {
            "path": str(path.relative_to(output)),
            "bytes": path.stat().st_size,
            "sha256": sha256(path),
        }
        for name, path in sorted(artifacts.items())
    }
    report = {
        "schema": "regelsuche.student-java-sdk-consumer-verification/v1",
        "sdkVersion": version,
        "javaFeature": int(
            run(["java", "-XshowSettings:properties", "-version"], root)
            .split("java.specification.version = ", 1)[1]
            .splitlines()[0]
            .strip()
        ),
        "externalConsumer": "geometric-sequence-domain-java25",
        "provider": "example-geometric-sequence-provider",
        "confirmedCandidate": "multiplier=2",
        "requiredOutcomes": ["CONFIRMED", "REFUTED", "BUDGET_EXHAUSTED"],
        "forbiddenRuntimeDependencies": list(FORBIDDEN_RUNTIME_MARKERS),
        "forbiddenRuntimeDependenciesObserved": [],
        "artifacts": artifact_ledger,
        "result": "success",
    }
    if report["javaFeature"] != 25:
        raise RuntimeError(f"consumer verification requires Java 25, got {report['javaFeature']}")

    output.mkdir(parents=True, exist_ok=True)
    (output / "consumer-report.json").write_text(
        json.dumps(report, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    (output / "consumer-report.md").write_text(
        "# Student Java SDK consumer verification\n\n"
        f"- SDK version: `{version}`\n"
        "- Java: `25`\n"
        "- External consumer: `geometric-sequence-domain-java25`\n"
        "- ServiceLoader provider: `example-geometric-sequence-provider`\n"
        "- Confirmed candidate: `multiplier=2`\n"
        "- Negative paths: `REFUTED`, `BUDGET_EXHAUSTED`\n"
        "- App/Spring/Hibernate/Persistence dependencies: none observed\n"
        "- Result: `success`\n",
        encoding="utf-8",
    )
    (output / "publish.log").write_text(publish_output, encoding="utf-8")
    (output / "consumer.log").write_text(test_output, encoding="utf-8")
    (output / "dependencies.log").write_text(dependency_output, encoding="utf-8")
    print((output / "consumer-report.md").read_text(encoding="utf-8"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
