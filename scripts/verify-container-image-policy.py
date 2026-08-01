#!/usr/bin/env python3
"""Verify tracked Dockerfiles against the checkout-owned image contract."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path


POLICY_SCHEMA = "regelsuche.quality.container-image-policy/v1"
REPORT_SCHEMA = "regelsuche.quality.container-image-report/v1"
VISUAL_POLICY_SCHEMA = "regelsuche.visual-regression-policy/v1"
VISUAL_POLICY_PATH = Path(
    "app/src/e2eTest/resources/screenshots/visual-regression-policy.json"
)
FROM_PATTERN = re.compile(
    r"^\s*FROM\s+(?:--platform=\S+\s+)?(\S+)(?:\s+AS\s+(\S+))?\s*$",
    re.IGNORECASE,
)
PLAYWRIGHT_DEPENDENCY_PATTERN = re.compile(
    r"com[.]microsoft[.]playwright:playwright:([0-9]+[.][0-9]+[.][0-9]+)"
)
IGNORED_DIRECTORY_NAMES = {
    ".git",
    ".gradle",
    "build",
    "node_modules",
    "out",
    "target",
}


def fail(message: str) -> None:
    raise SystemExit(f"container image policy failed: {message}")


def load_json(path: Path, description: str) -> dict:
    if not path.is_file():
        fail(f"missing {description}: {path}")
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"cannot read {description} {path}: {error}")
    if not isinstance(value, dict):
        fail(f"{description} must be a JSON object")
    return value


def load_policy(path: Path) -> dict:
    value = load_json(path, "container image policy")
    if value.get("schema") != POLICY_SCHEMA:
        fail("unsupported policy schema")
    files = value.get("files")
    if not isinstance(files, dict) or not files:
        fail("policy has no Dockerfiles")
    return value


def tracked_dockerfiles(root: Path) -> list[str]:
    try:
        completed = subprocess.run(
            ["git", "ls-files"],
            cwd=root,
            check=True,
            capture_output=True,
            text=True,
        )
        candidates = [Path(line) for line in completed.stdout.splitlines()]
    except (OSError, subprocess.CalledProcessError):
        candidates = [
            path.relative_to(root)
            for path in root.rglob("Dockerfile*")
            if path.is_file()
            and not any(part in IGNORED_DIRECTORY_NAMES for part in path.parts)
        ]
    return sorted(
        path.as_posix()
        for path in candidates
        if path.name.startswith("Dockerfile")
        and not any(part in IGNORED_DIRECTORY_NAMES for part in path.parts)
    )


def external_images(path: Path) -> list[str]:
    aliases: set[str] = set()
    images: list[str] = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.lstrip().upper().startswith("FROM "):
            continue
        match = FROM_PATTERN.fullmatch(line)
        if match is None:
            fail(f"unsupported FROM syntax in {path}:{number}: {line}")
        reference, alias = match.groups()
        if reference not in aliases:
            images.append(reference)
        if alias:
            aliases.add(alias)
    if not images:
        fail(f"Dockerfile has no external base image: {path}")
    return images


def is_digest_or_exact_version(reference: str) -> bool:
    if re.search(r"@sha256:[0-9a-f]{64}$", reference):
        return True
    image = reference.split("@", 1)[0]
    slash = image.rfind("/")
    colon = image.rfind(":")
    if colon <= slash:
        return False
    tag = image[colon + 1 :]
    if not tag or tag.lower() == "latest":
        return False
    return re.search(r"\d+\.\d+\.\d+", tag) is not None


def verify_visual_contract(
    root: Path,
    declared: dict,
    actual_by_file: dict[str, list[str]],
) -> dict:
    violations: list[str] = []
    path = root / VISUAL_POLICY_PATH
    visual = load_json(path, "visual regression policy")
    if visual.get("schema") != VISUAL_POLICY_SCHEMA:
        violations.append("unsupported visual regression policy schema")
    environment = visual.get("environment")
    if not isinstance(environment, dict):
        violations.append("visual regression environment must be an object")
        environment = {}
    image = environment.get("containerImage")
    version = environment.get("playwrightVersion")
    if not isinstance(image, str) or not image:
        violations.append("visual regression containerImage is missing")
    if not isinstance(version, str) or not re.fullmatch(
        r"[0-9]+[.][0-9]+[.][0-9]+", version or ""
    ):
        violations.append("visual regression playwrightVersion is invalid")

    dockerfile = "Dockerfile.visual-regression"
    if isinstance(image, str) and image:
        if declared.get(dockerfile) != [image]:
            violations.append(
                "visual policy containerImage differs from container-image-policy"
            )
        if actual_by_file.get(dockerfile) != [image]:
            violations.append(
                "visual policy containerImage differs from Dockerfile.visual-regression"
            )
    if isinstance(image, str) and isinstance(version, str):
        if f":v{version}-" not in image:
            violations.append(
                "visual container image tag does not match playwrightVersion"
            )

    app_build = root / "app/build.gradle"
    dependency_versions = sorted(set(
        PLAYWRIGHT_DEPENDENCY_PATTERN.findall(
            app_build.read_text(encoding="utf-8")
        )
    )) if app_build.is_file() else []
    if not dependency_versions:
        violations.append("no Playwright Java dependency found in app/build.gradle")
    elif version not in dependency_versions or dependency_versions != [version]:
        violations.append(
            "Playwright dependency versions do not match visual policy: "
            f"policy={version}, dependencies={dependency_versions}"
        )

    return {
        "policy": VISUAL_POLICY_PATH.as_posix(),
        "containerImage": image,
        "playwrightVersion": version,
        "dependencyVersions": dependency_versions,
        "status": "PASSED" if not violations else "FAILED",
        "violations": violations,
    }


def write_report(path: Path, report: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(report, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument(
        "--policy",
        type=Path,
        default=Path("config/quality/container-image-policy.json"),
    )
    parser.add_argument(
        "--report",
        type=Path,
        default=Path("build/reports/quality/container-image-report.json"),
    )
    args = parser.parse_args()

    root = args.root.resolve()
    policy_path = args.policy if args.policy.is_absolute() else root / args.policy
    report_path = args.report if args.report.is_absolute() else root / args.report
    policy = load_policy(policy_path)
    declared = policy["files"]
    tracked = tracked_dockerfiles(root)
    violations: list[str] = []
    rows: list[dict] = []
    actual_by_file: dict[str, list[str]] = {}

    missing_from_policy = sorted(set(tracked) - set(declared))
    missing_from_checkout = sorted(set(declared) - set(tracked))
    if missing_from_policy:
        violations.append(
            "tracked Dockerfiles missing from policy: " + ", ".join(missing_from_policy)
        )
    if missing_from_checkout:
        violations.append(
            "policy Dockerfiles missing from checkout: " + ", ".join(missing_from_checkout)
        )

    for relative in sorted(set(tracked) & set(declared)):
        expected = declared[relative]
        if not isinstance(expected, list) or not expected or not all(
            isinstance(item, str) and item for item in expected
        ):
            violations.append(f"invalid image declaration for {relative}")
            continue
        actual = external_images(root / relative)
        actual_by_file[relative] = actual
        row_violations: list[str] = []
        if actual != expected:
            row_violations.append(
                f"external image sequence differs: expected {expected}, found {actual}"
            )
        for reference in actual:
            if not is_digest_or_exact_version(reference):
                row_violations.append(
                    f"floating or insufficiently versioned image reference: {reference}"
                )
        violations.extend(f"{relative}: {item}" for item in row_violations)
        rows.append(
            {
                "path": relative,
                "expectedImages": expected,
                "externalImages": actual,
                "status": "PASSED" if not row_violations else "FAILED",
                "violations": row_violations,
            }
        )

    visual_contract = verify_visual_contract(root, declared, actual_by_file)
    violations.extend(
        "visual regression: " + item
        for item in visual_contract["violations"]
    )

    report = {
        "schema": REPORT_SCHEMA,
        "policy": str(policy_path.relative_to(root)),
        "status": "PASSED" if not violations else "FAILED",
        "trackedDockerfiles": tracked,
        "files": rows,
        "visualRegressionContract": visual_contract,
        "violations": violations,
    }
    write_report(report_path, report)

    print(f"containerImagePolicyStatus={report['status']}")
    print(f"containerImagePolicyFiles={len(rows)}")
    print(f"containerImagePolicyReport={report_path}")
    if violations:
        for violation in violations:
            print(f"containerImagePolicyViolation={violation}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
