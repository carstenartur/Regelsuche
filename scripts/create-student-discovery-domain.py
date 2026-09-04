#!/usr/bin/env python3
"""Generate a standalone Java 25 Regelsuche discovery-domain starter."""

from __future__ import annotations

import argparse
import json
import re
import shutil
import stat
from pathlib import Path

SOURCE_RELATIVE = Path(
    "examples/external-consumers/geometric-sequence-domain-java25"
)
WRAPPER_FILES = (
    Path("gradlew"),
    Path("gradlew.bat"),
    Path("gradle/wrapper/gradle-wrapper.jar"),
    Path("gradle/wrapper/gradle-wrapper.properties"),
)
GRADLE_WRAPPER_VERSION = "9.7.1"
PACKAGE_SEGMENT = re.compile(r"[a-z][a-z0-9_]*\Z")
SLUG = re.compile(r"[a-z][a-z0-9-]*\Z")
JAVA_KEYWORDS = {
    "abstract", "assert", "boolean", "break", "byte", "case", "catch",
    "char", "class", "const", "continue", "default", "do", "double",
    "else", "enum", "extends", "final", "finally", "float", "for",
    "goto", "if", "implements", "import", "instanceof", "int",
    "interface", "long", "native", "new", "package", "private",
    "protected", "public", "return", "short", "static", "strictfp",
    "super", "switch", "synchronized", "this", "throw", "throws",
    "transient", "try", "void", "volatile", "while", "_",
}


def fail(message: str) -> None:
    raise SystemExit(f"starter generation failed: {message}")


def validate_package(value: str) -> str:
    parts = value.split(".")
    if not parts or any(
        not PACKAGE_SEGMENT.fullmatch(part) or part in JAVA_KEYWORDS
        for part in parts
    ):
        fail(
            "--package must contain lowercase Java identifier segments, "
            f"got {value!r}"
        )
    return value


def validate_slug(value: str, option: str) -> str:
    if not SLUG.fullmatch(value):
        fail(
            f"{option} must start with a lowercase letter and contain only "
            f"lowercase letters, digits and hyphens, got {value!r}"
        )
    return value


def overlaps(left: Path, right: Path) -> bool:
    return left == right or left in right.parents or right in left.parents


def replace_text(path: Path, replacements: tuple[tuple[str, str], ...]) -> None:
    value = path.read_text(encoding="utf-8")
    for source, target in replacements:
        value = value.replace(source, target)
    path.write_text(value, encoding="utf-8")


def copy_wrapper(repository_root: Path, output: Path) -> None:
    for relative in WRAPPER_FILES:
        source = repository_root / relative
        target = output / relative
        if source.is_symlink() or not source.is_file():
            fail(f"pinned Gradle wrapper file is missing or unsafe: {source}")
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)

    unix_launcher = output / "gradlew"
    unix_launcher.chmod(
        unix_launcher.stat().st_mode
        | stat.S_IXUSR
        | stat.S_IXGRP
        | stat.S_IXOTH
    )

    properties = (
        output / "gradle/wrapper/gradle-wrapper.properties"
    ).read_text(encoding="utf-8")
    expected_distribution = f"gradle-{GRADLE_WRAPPER_VERSION}-bin.zip"
    if expected_distribution not in properties:
        fail(
            "Gradle wrapper version does not match the generator contract: "
            f"expected {expected_distribution}"
        )
    if "distributionSha256Sum=" not in properties:
        fail("Gradle wrapper must pin the distribution SHA-256")


def generate(
    repository_root: Path,
    output: Path,
    package_name: str,
    project_name: str,
    domain_id: str,
    provider_id: str,
) -> None:
    repository_root = repository_root.resolve()
    source = (repository_root / SOURCE_RELATIVE).resolve()
    output = output.expanduser().resolve()

    if not source.is_dir():
        fail(f"verified starter source is missing: {source}")
    if output.exists() or output.is_symlink():
        fail(f"output already exists; refusing to overwrite it: {output}")
    if overlaps(source, output):
        fail("output must not overlap the verified starter source")

    output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(
        source,
        output,
        ignore=shutil.ignore_patterns("build", ".gradle", "repository"),
    )
    copy_wrapper(repository_root, output)

    package_path = Path(*package_name.split("."))
    for source_set in ("main", "test"):
        old = output / "src" / source_set / "java" / "example"
        target = output / "src" / source_set / "java" / package_path
        if not old.is_dir():
            fail(f"starter source package is missing: {old}")
        target.parent.mkdir(parents=True, exist_ok=True)
        old.rename(target)

    replacements = (
        (
            "example-geometric-sequence-provider",
            provider_id,
        ),
        (
            "example-geometric-sequence",
            domain_id,
        ),
        (
            "external-geometric-sequence",
            project_name,
        ),
        (
            "Regelsuche external-consumer example",
            "Generated Regelsuche student discovery starter",
        ),
        (
            "package example;",
            f"package {package_name};",
        ),
        (
            "example.GeometricSequenceExample",
            f"{package_name}.GeometricSequenceExample",
        ),
        (
            "example.GeometricSequenceDomainProvider",
            f"{package_name}.GeometricSequenceDomainProvider",
        ),
        (
            "rootProject.name = 'regelsuche-geometric-sequence-example'",
            f"rootProject.name = '{project_name}'",
        ),
        (
            "# Geometric sequence discovery domain",
            f"# {project_name} — Regelsuche discovery starter",
        ),
    )

    text_files = [
        output / "build.gradle",
        output / "settings.gradle",
        output / "README.md",
        *sorted((output / "src").rglob("*.java")),
    ]
    for path in text_files:
        replace_text(path, replacements)

    service = output / (
        "src/main/resources/META-INF/services/"
        "de.regelsuche.sdk.discovery.DiscoveryDomainProvider"
    )
    service.write_text(
        f"{package_name}.GeometricSequenceDomainProvider\n",
        encoding="utf-8",
    )

    manifest = {
        "schema": "regelsuche.student-discovery-starter/v1",
        "template": SOURCE_RELATIVE.as_posix(),
        "projectName": project_name,
        "package": package_name,
        "domainId": domain_id,
        "providerId": provider_id,
        "javaFeature": 25,
        "gradleWrapperVersion": GRADLE_WRAPPER_VERSION,
        "sdkArtifact": "de.regelsuche:regelsuche-discovery-sdk",
    }
    (output / "regelsuche-starter.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )

    unresolved = []
    for path in [*text_files, service]:
        value = path.read_text(encoding="utf-8")
        if "package example;" in value or "example.GeometricSequence" in value:
            unresolved.append(path.relative_to(output).as_posix())
    if unresolved:
        fail(f"unresolved starter placeholders in {unresolved}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--repository-root",
        type=Path,
        default=Path.cwd(),
        help="Regelsuche checkout containing the verified external example",
    )
    parser.add_argument(
        "--output",
        type=Path,
        required=True,
        help="new output directory; existing paths are never overwritten",
    )
    parser.add_argument(
        "--package",
        dest="package_name",
        default="org.example.discovery",
        help="lowercase Java package for the generated sources",
    )
    parser.add_argument(
        "--project-name",
        default="my-regelsuche-domain",
        help="Gradle root project name",
    )
    parser.add_argument(
        "--domain-id",
        default="my-discovery-domain",
        help="stable Regelsuche discovery-domain identifier",
    )
    parser.add_argument(
        "--provider-id",
        default="my-discovery-provider",
        help="stable ServiceLoader provider identifier",
    )
    return parser.parse_args()


def main() -> int:
    arguments = parse_args()
    package_name = validate_package(arguments.package_name)
    project_name = validate_slug(arguments.project_name, "--project-name")
    domain_id = validate_slug(arguments.domain_id, "--domain-id")
    provider_id = validate_slug(arguments.provider_id, "--provider-id")
    output = arguments.output.expanduser().resolve()

    generate(
        arguments.repository_root,
        output,
        package_name,
        project_name,
        domain_id,
        provider_id,
    )
    print(f"created={output}")
    print(f"package={package_name}")
    print(f"domainId={domain_id}")
    print(f"providerId={provider_id}")
    print(f"gradleWrapper={GRADLE_WRAPPER_VERSION}")
    print(
        "next=./gradlew clean test run "
        "-PregelsucheRepository=/path/to/sdk-repository "
        "-PregelsucheVersion=<version>"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
