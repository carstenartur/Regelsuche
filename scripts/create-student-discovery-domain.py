#!/usr/bin/env python3
"""Generate a standalone Java 25 Regelsuche discovery-domain starter."""

from __future__ import annotations

import argparse
import json
import re
import shutil
import stat
import tempfile
from pathlib import Path

SOURCE_RELATIVE = Path("examples/external-consumers/geometric-sequence-domain-java25")
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
    "true", "false", "null",
}


def fail(message: str) -> None:
    raise SystemExit(f"starter generation failed: {message}")


def validate_package(value: str) -> str:
    parts = value.split(".")
    if not parts or any(
        not PACKAGE_SEGMENT.fullmatch(part) or part in JAVA_KEYWORDS
        for part in parts
    ):
        fail(f"--package must contain lowercase Java identifier segments, got {value!r}")
    return value


def validate_slug(value: str, option: str) -> str:
    if not SLUG.fullmatch(value):
        fail(f"{option} must start with a lowercase letter and contain only "
             f"lowercase letters, digits and hyphens, got {value!r}")
    return value


def overlaps(left: Path, right: Path) -> bool:
    return left == right or left in right.parents or right in left.parents


def replace_text(path: Path, replacements: tuple[tuple[str, str], ...]) -> None:
    """Replace original template tokens once; never reinterpret user-supplied IDs."""
    mapping = dict(replacements)
    pattern = re.compile("|".join(
        re.escape(token) for token in sorted(mapping, key=len, reverse=True)
    ))
    value = pattern.sub(lambda match: mapping[match.group(0)],
                        path.read_text(encoding="utf-8"))
    path.write_text(value, encoding="utf-8")


def validate_wrapper(repository_root: Path) -> None:
    for relative in WRAPPER_FILES:
        source = repository_root / relative
        if source.is_symlink() or not source.is_file():
            fail(f"pinned Gradle wrapper file is missing or unsafe: {source}")
    properties = (repository_root / WRAPPER_FILES[-1]).read_text(encoding="utf-8")
    parsed: dict[str, str] = {}
    for line in properties.splitlines():
        if not line.strip() or line.lstrip().startswith(("#", "!")):
            continue
        key, separator, value = line.partition("=")
        key = key.strip()
        if key not in ("distributionUrl", "distributionSha256Sum"):
            continue
        if not separator or key in parsed:
            fail(f"Gradle wrapper property must occur exactly once: {key}")
        parsed[key] = value.strip()
    expected = ("https://services.gradle.org/distributions/"
                f"gradle-{GRADLE_WRAPPER_VERSION}-bin.zip")
    if parsed.get("distributionUrl", "").replace(r"\:", ":") != expected:
        fail(f"Gradle wrapper distribution must be {expected}")
    if not re.fullmatch(r"[0-9a-f]{64}", parsed.get("distributionSha256Sum", "")):
        fail("Gradle wrapper must pin a lowercase 64-digit distribution SHA-256")


def copy_wrapper(repository_root: Path, output: Path) -> None:
    for relative in WRAPPER_FILES:
        target = output / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(repository_root / relative, target)
    unix_launcher = output / "gradlew"
    unix_launcher.chmod(unix_launcher.stat().st_mode
                        | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)


def relocate_package(output: Path, source_set: str, package_name: str) -> None:
    java_root = output / "src" / source_set / "java"
    old = java_root / "example"
    target = java_root.joinpath(*package_name.split("."))
    if old == target:
        return
    # Move out first so that example.child does not rename a directory into itself.
    with tempfile.TemporaryDirectory(prefix=".starter-package-", dir=java_root) as stage:
        staged = Path(stage) / "package"
        old.rename(staged)
        target.parent.mkdir(parents=True, exist_ok=True)
        staged.rename(target)


def generate(
    repository_root: Path,
    output: Path,
    package_name: str,
    project_name: str,
    domain_id: str,
    provider_id: str,
) -> None:
    # Enforce the same contract for CLI and imported callers, before creating output.
    validate_package(package_name)
    validate_slug(project_name, "--project-name")
    validate_slug(domain_id, "--domain-id")
    validate_slug(provider_id, "--provider-id")
    repository_root = repository_root.resolve()
    source = repository_root / SOURCE_RELATIVE
    output = output.expanduser().absolute()
    if output.exists() or output.is_symlink():
        fail(f"output already exists; refusing to overwrite it: {output}")
    output = output.resolve()
    if source.is_symlink() or not source.is_dir():
        fail(f"verified starter source is missing or unsafe: {source}")
    if overlaps(source.resolve(), output):
        fail("output must not overlap the verified starter source")
    for path in source.rglob("*"):
        if path.is_symlink():
            fail(f"starter source must not contain symlinks: {path}")
    required = [source / name for name in ("build.gradle", "settings.gradle", "README.md")]
    for path in required:
        if not path.is_file():
            fail(f"starter source file is missing: {path}")
    for source_set in ("main", "test"):
        old = source / "src" / source_set / "java" / "example"
        if not old.is_dir() or not list(old.rglob("*.java")):
            fail(f"starter source package is missing or empty: {old}")
    validate_wrapper(repository_root)

    output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(source, output,
                    ignore=shutil.ignore_patterns("build", ".gradle", "repository"))
    copy_wrapper(repository_root, output)
    for source_set in ("main", "test"):
        relocate_package(output, source_set, package_name)

    replacements = (
        ("example-geometric-sequence-provider", provider_id),
        ("example-geometric-sequence", domain_id),
        ("external-geometric-sequence", project_name),
        ("Regelsuche external-consumer example", "Generated Regelsuche student discovery starter"),
        ("package example;", f"package {package_name};"),
        ("example.GeometricSequenceExample", f"{package_name}.GeometricSequenceExample"),
        ("example.GeometricSequenceDomainProvider", f"{package_name}.GeometricSequenceDomainProvider"),
        ("rootProject.name = 'regelsuche-geometric-sequence-example'",
         f"rootProject.name = '{project_name}'"),
        ("# Geometric sequence discovery domain", f"# {project_name} — Regelsuche discovery starter"),
        ("\ngradle clean test run", "\n./gradlew clean test run"),
    )
    text_files = [output / "build.gradle", output / "settings.gradle",
                  output / "README.md", *sorted((output / "src").rglob("*.java"))]
    for path in text_files:
        replace_text(path, replacements)
    service = output / ("src/main/resources/META-INF/services/"
                        "de.regelsuche.sdk.discovery.DiscoveryDomainProvider")
    service.parent.mkdir(parents=True, exist_ok=True)
    service.write_text(f"{package_name}.GeometricSequenceDomainProvider\n", encoding="utf-8")
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
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    # The original package or original ID may be an intentional user choice.
    for path in (output / "src").rglob("*.java"):
        expected = f"package {package_name};"
        if expected not in path.read_text(encoding="utf-8"):
            fail(f"generated Java source lacks {expected!r}: {path.relative_to(output)}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository-root", type=Path, default=Path.cwd(),
                        help="Regelsuche checkout containing the verified external example")
    parser.add_argument("--output", type=Path, required=True,
                        help="new output directory; existing paths are never overwritten")
    parser.add_argument("--package", dest="package_name", default="org.example.discovery",
                        help="lowercase Java package for the generated sources")
    parser.add_argument("--project-name", default="my-regelsuche-domain",
                        help="Gradle root project name")
    parser.add_argument("--domain-id", default="my-discovery-domain",
                        help="stable Regelsuche discovery-domain identifier")
    parser.add_argument("--provider-id", default="my-discovery-provider",
                        help="stable ServiceLoader provider identifier")
    return parser.parse_args()


def main() -> int:
    arguments = parse_args()
    generate(arguments.repository_root, arguments.output, arguments.package_name,
             arguments.project_name, arguments.domain_id, arguments.provider_id)
    print(f"created={arguments.output.expanduser().resolve()}")
    print(f"package={arguments.package_name}")
    print(f"domainId={arguments.domain_id}")
    print(f"providerId={arguments.provider_id}")
    print(f"gradleWrapper={GRADLE_WRAPPER_VERSION}")
    print("next=./gradlew clean test run "
          "-PregelsucheRepository=/path/to/sdk-repository -PregelsucheVersion=<version>")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
