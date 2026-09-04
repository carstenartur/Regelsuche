#!/usr/bin/env python3
"""Verify the student-facing SDK through clean external consumers."""

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
GENERATOR_RELATIVE = Path("scripts/create-student-discovery-domain.py")
GENERATED_PACKAGE = "org.example.generated"
GENERATED_PROJECT = "generated-regelsuche-domain"
GENERATED_DOMAIN = "generated-geometric-sequence"
GENERATED_PROVIDER = "generated-geometric-sequence-provider"
GENERATED_GRADLE_WRAPPER = "9.7.1"


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


def execute_consumer(
    gradle: str,
    consumer: Path,
    repository: Path,
    version: str,
    gradle_user_home: Path,
) -> tuple[str, str, list[str]]:
    environment = {"GRADLE_USER_HOME": str(gradle_user_home)}
    properties = [
        f"-PregelsucheRepository={repository}",
        f"-PregelsucheVersion={version}",
    ]
    execution = run(
        [
            gradle,
            "--no-daemon",
            "--refresh-dependencies",
            "clean",
            "test",
            "run",
            *properties,
        ],
        consumer,
        environment,
    )
    dependencies = run(
        [
            gradle,
            "--no-daemon",
            "dependencies",
            "--configuration",
            "runtimeClasspath",
            *properties,
        ],
        consumer,
        environment,
    )
    lowered = dependencies.lower()
    forbidden = [
        marker for marker in FORBIDDEN_RUNTIME_MARKERS if marker in lowered
    ]
    return execution, dependencies, forbidden


def require_output(execution: str, expected: tuple[str, ...], label: str) -> None:
    missing = [value for value in expected if value not in execution]
    if missing:
        raise RuntimeError(f"{label} output is incomplete: {missing}")


def verify_generated_project_shape(starter: Path) -> dict:
    manifest_path = starter / "regelsuche-starter.json"
    if not manifest_path.is_file():
        raise RuntimeError("generated starter manifest is missing")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    expected = {
        "schema": "regelsuche.student-discovery-starter/v1",
        "projectName": GENERATED_PROJECT,
        "package": GENERATED_PACKAGE,
        "domainId": GENERATED_DOMAIN,
        "providerId": GENERATED_PROVIDER,
        "javaFeature": 25,
        "gradleWrapperVersion": GENERATED_GRADLE_WRAPPER,
        "sdkArtifact": "de.regelsuche:regelsuche-discovery-sdk",
    }
    observed = {key: manifest.get(key) for key in expected}
    if observed != expected:
        raise RuntimeError(
            f"generated starter manifest mismatch: expected {expected}, got {observed}"
        )

    required_wrapper_files = (
        starter / "gradlew",
        starter / "gradlew.bat",
        starter / "gradle/wrapper/gradle-wrapper.jar",
        starter / "gradle/wrapper/gradle-wrapper.properties",
    )
    missing_wrapper_files = [
        path.relative_to(starter).as_posix()
        for path in required_wrapper_files
        if path.is_symlink() or not path.is_file()
    ]
    if missing_wrapper_files:
        raise RuntimeError(
            f"generated starter wrapper is incomplete: {missing_wrapper_files}"
        )
    wrapper_properties = required_wrapper_files[-1].read_text(encoding="utf-8")
    if f"gradle-{GENERATED_GRADLE_WRAPPER}-bin.zip" not in wrapper_properties:
        raise RuntimeError("generated starter uses the wrong Gradle wrapper version")
    if "distributionSha256Sum=" not in wrapper_properties:
        raise RuntimeError("generated starter wrapper lacks a distribution SHA-256")

    build_contract = "\n".join(
        (starter / name).read_text(encoding="utf-8")
        for name in ("settings.gradle", "build.gradle")
    )
    forbidden_shortcuts = [
        marker for marker in ("includeBuild(", "project('", 'project("')
        if marker in build_contract
    ]
    if forbidden_shortcuts:
        raise RuntimeError(
            f"generated starter contains internal build shortcuts: {forbidden_shortcuts}"
        )
    return manifest


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
    generator = root / GENERATOR_RELATIVE
    if not repository.is_dir():
        raise RuntimeError(f"SDK repository does not exist: {repository}")
    if not source.is_dir():
        raise RuntimeError(f"external consumer example is missing: {source}")
    if not generator.is_file() or generator.is_symlink():
        raise RuntimeError(f"starter generator is missing or unsafe: {generator}")
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
    execution, dependencies, forbidden = execute_consumer(
        arguments.gradle,
        consumer,
        repository,
        version,
        output / "isolated-gradle-user-home",
    )
    require_output(
        execution,
        (
            "provider=example-geometric-sequence-provider",
            "outcome=CONFIRMED",
            "multiplier=2",
        ),
        "external consumer",
    )
    if forbidden:
        raise RuntimeError(f"forbidden runtime dependencies: {forbidden}")

    starter = output / "generated-starter"
    generator_output = run(
        [
            sys.executable,
            str(generator),
            "--repository-root",
            str(root),
            "--output",
            str(starter),
            "--package",
            GENERATED_PACKAGE,
            "--project-name",
            GENERATED_PROJECT,
            "--domain-id",
            GENERATED_DOMAIN,
            "--provider-id",
            GENERATED_PROVIDER,
        ],
        root,
    )
    generated_manifest = verify_generated_project_shape(starter)
    generated_gradle = starter / ("gradlew.bat" if os.name == "nt" else "gradlew")
    generated_execution, generated_dependencies, generated_forbidden = execute_consumer(
        str(generated_gradle),
        starter,
        repository,
        version,
        output / "generated-gradle-user-home",
    )
    require_output(
        generated_execution,
        (
            f"provider={GENERATED_PROVIDER}",
            "outcome=CONFIRMED",
            "multiplier=2",
        ),
        "generated starter",
    )
    if generated_forbidden:
        raise RuntimeError(
            f"generated starter has forbidden runtime dependencies: "
            f"{generated_forbidden}"
        )

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
        "schema": "regelsuche.student-java-sdk-consumer-verification/v2",
        "sdkVersion": version,
        "publicationMode": "CHECKOUT_OWNED_TASK_DEPENDENCIES",
        "dependencyCacheMode": "ISOLATED_EMPTY_GRADLE_USER_HOME_PER_CONSUMER",
        "javaFeature": java_feature,
        "externalConsumer": "geometric-sequence-domain-java25",
        "provider": "example-geometric-sequence-provider",
        "confirmedCandidate": "multiplier=2",
        "requiredOutcomes": ["CONFIRMED", "REFUTED", "BUDGET_EXHAUSTED"],
        "generatedStarter": generated_manifest,
        "generatedBuildTool": "PINNED_GRADLE_WRAPPER",
        "forbiddenRuntimeDependenciesObserved": sorted(
            set(forbidden + generated_forbidden)
        ),
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
        "- Dependency caches: isolated and initially empty per consumer\n"
        "- Java: `25`\n"
        "- External consumer: `geometric-sequence-domain-java25`\n"
        "- ServiceLoader provider: `example-geometric-sequence-provider`\n"
        "- Confirmed candidate: `multiplier=2`\n"
        "- Negative paths: `REFUTED`, `BUDGET_EXHAUSTED`\n"
        f"- Generated project: `{GENERATED_PROJECT}`\n"
        f"- Generated package: `{GENERATED_PACKAGE}`\n"
        f"- Generated provider: `{GENERATED_PROVIDER}`\n"
        f"- Generated Gradle wrapper: `{GENERATED_GRADLE_WRAPPER}`\n"
        "- Generated project built through its wrapper without modification: `yes`\n"
        "- App/Spring/Hibernate/Persistence dependencies: none observed\n"
        "- Result: `success`\n"
    )
    (output / "consumer-report.md").write_text(markdown, encoding="utf-8")
    (output / "consumer.log").write_text(execution, encoding="utf-8")
    (output / "dependencies.log").write_text(dependencies, encoding="utf-8")
    (output / "generator.log").write_text(generator_output, encoding="utf-8")
    (output / "generated-starter.log").write_text(
        generated_execution,
        encoding="utf-8",
    )
    (output / "generated-dependencies.log").write_text(
        generated_dependencies,
        encoding="utf-8",
    )
    print(markdown)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
